from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import logging
import socket
import time
from pathlib import Path
from typing import Callable, Optional
from urllib.parse import urljoin, urlparse

import httpx
import numpy as np

from .models import SegmentDto

logger = logging.getLogger("hushcast_worker.audio")

MAX_DOWNLOAD_BYTES = 1024 * 1024 * 1024  # 1 GB
MAX_DOWNLOAD_SECONDS = 600.0
MAX_REDIRECTS = 10

_BLOCKED_HOSTNAMES = {
    "localhost",
    "localhost.localdomain",
    "metadata.google.internal",
    "metadata.goog",
}


def _is_ip_blocked(addr: ipaddress.IPv4Address | ipaddress.IPv6Address, *, allow_lan_feeds: bool) -> bool:
    if addr.is_loopback or addr.is_link_local or addr.is_multicast or addr.is_unspecified:
        return True
    # Cloud metadata IPv4 (169.254.169.254 is link-local, already covered, but be explicit)
    if str(addr) in ("169.254.169.254", "fd00:ec2::254"):
        return True
    if not allow_lan_feeds and addr.is_private:
        return True
    return False


def validate_fetch_url(url: str, *, allow_lan_feeds: bool = False) -> str:
    """
    Validates that `url` uses http/https and resolves only to allowed IP addresses.
    Always blocks loopback, link-local, multicast, unspecified, and cloud metadata.
    Blocks RFC1918/ULA private LAN ranges unless `allow_lan_feeds=True`.
    """
    parsed = urlparse(url.strip())
    if parsed.scheme.lower() not in ("http", "https"):
        raise ValueError(f"Unsupported URL scheme: {parsed.scheme!r}")
    host = parsed.hostname
    if not host:
        raise ValueError("URL has no hostname")

    low_host = host.lower().rstrip(".")
    if low_host in _BLOCKED_HOSTNAMES:
        raise ValueError(f"Blocked host: {host}")

    # Direct IP literal check first
    try:
        ip_obj = ipaddress.ip_address(low_host)
        if _is_ip_blocked(ip_obj, allow_lan_feeds=allow_lan_feeds):
            raise ValueError(f"Blocked IP address: {ip_obj}")
        return url
    except ValueError as exc:
        if "Blocked" in str(exc):
            raise

    try:
        infos = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError(f"Failed to resolve host {host!r}: {exc}") from exc

    if not infos:
        raise ValueError(f"No addresses resolved for {host!r}")

    for info in infos:
        ip_str = info[4][0]
        # Strip IPv6 zone index if present
        ip_str = ip_str.split("%")[0]
        ip_obj = ipaddress.ip_address(ip_str)
        if _is_ip_blocked(ip_obj, allow_lan_feeds=allow_lan_feeds):
            raise ValueError(f"Blocked resolved address {ip_obj} for host {host!r}")

    return url


async def download_audio_streaming_sha256(
    audio_url: str,
    dest_path: Path,
    on_bytes: Optional[Callable[[int, int], None]] = None,
    *,
    allow_lan_feeds: bool = False,
    max_bytes: int = MAX_DOWNLOAD_BYTES,
    max_seconds: float = MAX_DOWNLOAD_SECONDS,
) -> tuple[str, str]:
    """Downloads audio_url to dest_path while computing SHA-256 on the fly with SSRF & size checks."""
    hasher = hashlib.sha256()
    content_type = "audio/mpeg"
    deadline = time.monotonic() + max_seconds
    current_url = validate_fetch_url(audio_url, allow_lan_feeds=allow_lan_feeds)

    async with httpx.AsyncClient(follow_redirects=False, timeout=120.0) as client:
        for _ in range(MAX_REDIRECTS + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Audio download exceeded wall-clock limit")

            async with client.stream("GET", current_url) as resp:
                if resp.status_code in (301, 302, 303, 307, 308):
                    loc = resp.headers.get("location")
                    if not loc:
                        resp.raise_for_status()
                    next_url = urljoin(current_url, loc)
                    current_url = validate_fetch_url(next_url, allow_lan_feeds=allow_lan_feeds)
                    continue

                resp.raise_for_status()
                content_type = resp.headers.get("content-type", "audio/mpeg").split(";")[0].strip()
                total = int(resp.headers.get("content-length") or 0)
                if total > max_bytes:
                    raise ValueError(f"Audio size {total} bytes exceeds limit of {max_bytes} bytes")

                received = 0
                with dest_path.open("wb") as f:
                    async for chunk in resp.aiter_bytes(chunk_size=65536):
                        if time.monotonic() > deadline:
                            raise TimeoutError("Audio download exceeded wall-clock limit")
                        received += len(chunk)
                        if received > max_bytes:
                            raise ValueError(f"Audio download exceeded limit of {max_bytes} bytes")
                        f.write(chunk)
                        hasher.update(chunk)
                        if on_bytes:
                            on_bytes(received, total)
                return hasher.hexdigest(), content_type

        raise ValueError(f"Too many redirects (>{MAX_REDIRECTS})")


def decode_audio_16k_mono(audio_path: Path) -> tuple[np.ndarray, float]:
    """Decodes audio_path to 16 kHz mono float32 PCM using a preallocated buffer when duration is known."""
    import av

    container = av.open(str(audio_path))
    try:
        audio_stream = container.streams.audio[0] if container.streams.audio else None
        est_samples = 0
        if container.duration and container.duration > 0:
            est_samples = int((container.duration / 1_000_000.0) * 16000) + 16000
        elif audio_stream and audio_stream.duration and audio_stream.time_base:
            dur_s = float(audio_stream.duration * audio_stream.time_base)
            if dur_s > 0:
                est_samples = int(dur_s * 16000) + 16000

        resampler = av.AudioResampler(format="flt", layout="mono", rate=16000)
        if est_samples > 0:
            buf = np.empty(est_samples, dtype=np.float32)
            pos = 0

            def _append(arr: np.ndarray) -> None:
                nonlocal buf, pos
                n = len(arr)
                if n == 0:
                    return
                if pos + n > len(buf):
                    new_cap = max(len(buf) * 2, pos + n + 16000)
                    new_buf = np.empty(new_cap, dtype=np.float32)
                    new_buf[:pos] = buf[:pos]
                    buf = new_buf
                buf[pos : pos + n] = arr
                pos += n

            for frame in container.decode(audio=0):
                for resampled in resampler.resample(frame):
                    _append(resampled.to_ndarray().reshape(-1))
            for resampled in resampler.resample(None):
                _append(resampled.to_ndarray().reshape(-1))
            pcm = buf[:pos].copy() if pos < len(buf) * 0.8 else buf[:pos]
        else:
            chunks = []
            for frame in container.decode(audio=0):
                for resampled in resampler.resample(frame):
                    chunks.append(resampled.to_ndarray().reshape(-1))
            for resampled in resampler.resample(None):
                chunks.append(resampled.to_ndarray().reshape(-1))
            pcm = np.concatenate(chunks).astype(np.float32) if chunks else np.zeros(0, dtype=np.float32)
    finally:
        container.close()

    return pcm, float(len(pcm) / 16000.0)


def estimate_pitch_hz(chunk: np.ndarray, sr: int = 16000) -> float:
    """Fast FFT-based autocorrelation pitch estimator over 70..350 Hz."""
    if len(chunk) < sr // 10:
        return 0.0
    x = chunk.astype(np.float64) - float(np.mean(chunk))
    min_lag = sr // 350
    max_lag = sr // 70
    if len(x) <= max_lag + 16:
        return 0.0

    # Zero-padded FFT autocorrelation computes all lags in O(N log N)
    n = len(x)
    n_fft = 1 << (2 * n - 1).bit_length()
    spec = np.fft.rfft(x, n=n_fft)
    corr = np.fft.irfft(spec * np.conj(spec), n=n_fft)[: max_lag + 1]
    if corr[0] <= 1e-8:
        return 0.0
    sub = corr[min_lag : max_lag + 1]
    peak_idx = int(np.argmax(sub))
    if sub[peak_idx] < 0.25 * corr[0]:
        return 0.0
    return float(sr / (min_lag + peak_idx))


def assign_speakers(pcm_16k: np.ndarray, segments: list[SegmentDto]) -> None:
    """
    Acoustic speaker diarization using MFCCs + fundamental frequency (F0) +
    cosine K-means with silhouette-based speaker count selection.
    """
    sr = 16000
    n_segs = len(segments)
    if n_segs == 0 or len(pcm_16k) < sr // 10:
        return

    n_fft = 512
    n_mels = 24
    n_mfcc = 12
    freqs = np.fft.rfftfreq(n_fft, d=1.0 / sr)
    mel_low = 2595.0 * np.log10(1.0 + 80.0 / 700.0)
    mel_high = 2595.0 * np.log10(1.0 + 7500.0 / 700.0)
    mel_pts = np.linspace(mel_low, mel_high, n_mels + 2)
    hz_pts = 700.0 * (10.0 ** (mel_pts / 2595.0) - 1.0)
    bins = np.floor((n_fft + 1) * hz_pts / sr).astype(int)
    fb = np.zeros((n_mels, len(freqs)), dtype=np.float32)
    for m in range(1, n_mels + 1):
        f_l, f_c, f_r = bins[m - 1], bins[m], bins[m + 1]
        if f_c > f_l:
            fb[m - 1, f_l:f_c] = (np.arange(f_l, f_c) - f_l) / float(f_c - f_l)
        if f_r > f_c:
            fb[m - 1, f_c:f_r] = (f_r - np.arange(f_c, f_r)) / float(f_r - f_c)
    dct_idx = np.arange(n_mfcc)[:, None]
    mel_idx = np.arange(n_mels)[None, :]
    dct_mat = np.cos(np.pi * dct_idx * (mel_idx + 0.5) / n_mels).astype(np.float32)
    hann = np.hanning(n_fft).astype(np.float32)

    feats: list[Optional[np.ndarray]] = []
    durs: list[float] = []

    for s in segments:
        dur = max(0.0, s.end - s.start)
        durs.append(dur)
        i0 = max(0, int(s.start * sr))
        i1 = min(len(pcm_16k), int(s.end * sr))
        chunk = pcm_16k[i0:i1]
        if len(chunk) < n_fft:
            feats.append(None)
            continue
        rms = float(np.sqrt(np.mean(chunk * chunk)))
        if rms < 1e-4:
            feats.append(None)
            continue

        if len(chunk) > 4 * sr:
            mid = len(chunk) // 2
            chunk = chunk[mid - 2 * sr : mid + 2 * sr]

        step = 256
        frames = [
            chunk[pos : pos + n_fft] * hann
            for pos in range(0, len(chunk) - n_fft + 1, step)
        ]
        if not frames:
            feats.append(None)
            continue
        frame_mat = np.stack(frames, axis=0)
        pow_spec = np.abs(np.fft.rfft(frame_mat, n=n_fft)) ** 2
        mel_e = np.log(np.maximum(pow_spec @ fb.T, 1e-9))
        mfccs = mel_e @ dct_mat.T
        mfcc_mean = np.mean(mfccs, axis=0)

        f0 = estimate_pitch_hz(chunk[: min(len(chunk), sr)], sr)
        f0_log = np.log2(f0 / 150.0) if f0 > 0 else 0.0
        vec = np.concatenate([mfcc_mean[1:n_mfcc], [f0_log * 2.5]]).astype(np.float32)
        feats.append(vec)

    valid_idx = [i for i, v in enumerate(feats) if v is not None]
    if not valid_idx:
        for s in segments:
            s.speaker = 0
        return

    mat = np.stack([feats[i] for i in valid_idx], axis=0)
    mean = np.mean(mat, axis=0)
    std = np.std(mat, axis=0) + 1e-6
    mat_norm = (mat - mean) / std
    mat_unit = mat_norm / (np.linalg.norm(mat_norm, axis=1, keepdims=True) + 1e-12)

    train_mask = np.array([durs[i] >= 0.8 for i in valid_idx], dtype=bool)
    train_vecs = mat_unit[train_mask] if np.sum(train_mask) >= 6 else mat_unit

    def _run_kmeans(vecs: np.ndarray, k: int, iters: int = 18):
        centroids = [vecs[0].copy()]
        for _ in range(1, k):
            sims = np.max(vecs @ np.stack(centroids, axis=0).T, axis=1)
            centroids.append(vecs[int(np.argmin(sims))].copy())
        c_mat = np.stack(centroids, axis=0)
        labels = np.zeros(len(vecs), dtype=int)
        for _ in range(iters):
            sims = vecs @ c_mat.T
            new_labels = np.argmax(sims, axis=1)
            if np.array_equal(new_labels, labels):
                break
            labels = new_labels
            for c_i in range(k):
                members = vecs[labels == c_i]
                if len(members) > 0:
                    c = np.mean(members, axis=0)
                    c /= float(np.linalg.norm(c)) + 1e-12
                    c_mat[c_i] = c
        return c_mat, labels

    best_centroids = None
    best_sil = 0.14
    max_k = min(4, max(1, len(train_vecs) // 4))
    for k in range(2, max_k + 1):
        c_mat, tr_labels = _run_kmeans(train_vecs, k)
        counts = np.bincount(tr_labels, minlength=k)
        if np.min(counts) < max(2, int(0.06 * len(train_vecs))):
            continue
        sims = train_vecs @ c_mat.T
        a_sim = sims[np.arange(len(train_vecs)), tr_labels]
        masked_sims = sims.copy()
        masked_sims[np.arange(len(train_vecs)), tr_labels] = -2.0
        b_sim = np.max(masked_sims, axis=1)
        sil = float(np.mean(a_sim - b_sim))
        if sil > best_sil:
            best_sil = sil
            best_centroids = c_mat

    if best_centroids is None:
        for s in segments:
            s.speaker = 0
        return

    all_labels = np.argmax(mat_unit @ best_centroids.T, axis=1)
    for row_i, seg_i in enumerate(valid_idx):
        segments[seg_i].speaker = int(all_labels[row_i])

    last_spk = 0
    for s in segments:
        if s.speaker is None:
            s.speaker = last_spk
        else:
            last_spk = s.speaker

    for i in range(1, n_segs - 1):
        dur = segments[i].end - segments[i].start
        if (
            dur < 1.8
            and segments[i - 1].speaker == segments[i + 1].speaker
            and segments[i].speaker != segments[i - 1].speaker
        ):
            segments[i].speaker = segments[i - 1].speaker

    remap: dict[int, int] = {}
    for s in segments:
        if s.speaker is not None:
            if s.speaker not in remap:
                remap[s.speaker] = len(remap)
            s.speaker = remap[s.speaker]
