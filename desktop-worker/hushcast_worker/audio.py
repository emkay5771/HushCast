from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import logging
import shutil
import socket
import subprocess
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
MAX_DECODED_SECONDS = 6 * 3600  # 6 hours
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


def _decode_ffmpeg_f32le(
    audio_path: Path,
    *,
    sample_rate: int,
    channels: int,
    max_seconds: float,
) -> Optional[np.ndarray]:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return None
    max_bytes = int(max_seconds * sample_rate * channels * 4)
    cmd = [
        ffmpeg,
        "-nostdin",
        "-v",
        "error",
        "-i",
        str(audio_path),
        "-vn",
        "-ac",
        str(channels),
        "-ar",
        str(sample_rate),
        "-f",
        "f32le",
        "pipe:1",
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=300, check=False)
        if proc.returncode != 0 or not proc.stdout:
            return None
        if len(proc.stdout) > max_bytes:
            raise ValueError(f"Decoded audio exceeds {int(max_seconds // 3600)}-hour limit")
        usable = len(proc.stdout) - (len(proc.stdout) % (4 * channels))
        arr = np.frombuffer(proc.stdout[:usable], dtype=np.float32).copy()
        if channels > 1:
            arr = arr.reshape(-1, channels)
        return arr
    except ValueError:
        raise
    except Exception:
        logger.debug("ffmpeg pipe decode failed; falling back to PyAV", exc_info=True)
        return None


def decode_audio_16k_mono(
    audio_path: Path,
    *,
    max_seconds: float = MAX_DECODED_SECONDS,
) -> tuple[np.ndarray, float]:
    """Decodes audio_path to 16 kHz mono float32 PCM (ffmpeg pipe with PyAV fallback)."""
    pcm_ff = _decode_ffmpeg_f32le(audio_path, sample_rate=16000, channels=1, max_seconds=max_seconds)
    if pcm_ff is not None:
        return pcm_ff, float(len(pcm_ff) / 16000.0)

    import av

    max_samples = int(max_seconds * 16000)
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

        if est_samples > max_samples + 16000:
            raise ValueError(f"Audio duration exceeds {int(max_seconds // 3600)}-hour limit")

        resampler = av.AudioResampler(format="flt", layout="mono", rate=16000)
        if est_samples > 0:
            buf = np.empty(est_samples, dtype=np.float32)
            pos = 0

            def _append(arr: np.ndarray) -> None:
                nonlocal buf, pos
                n = len(arr)
                if n == 0:
                    return
                if pos + n > max_samples:
                    raise ValueError(f"Decoded audio exceeds {int(max_seconds // 3600)}-hour limit")
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
            total_samples = 0
            for frame in container.decode(audio=0):
                for resampled in resampler.resample(frame):
                    arr = resampled.to_ndarray().reshape(-1)
                    total_samples += len(arr)
                    if total_samples > max_samples:
                        raise ValueError(f"Decoded audio exceeds {int(max_seconds // 3600)}-hour limit")
                    chunks.append(arr)
            for resampled in resampler.resample(None):
                arr = resampled.to_ndarray().reshape(-1)
                total_samples += len(arr)
                if total_samples > max_samples:
                    raise ValueError(f"Decoded audio exceeds {int(max_seconds // 3600)}-hour limit")
                chunks.append(arr)
            pcm = np.concatenate(chunks).astype(np.float32) if chunks else np.zeros(0, dtype=np.float32)
    finally:
        container.close()

    return pcm, float(len(pcm) / 16000.0)


# W8: decoded audio is resampled in blocks of about this many seconds instead of one codec frame
# (~26 ms for MP3) at a time, which cut the Python overhead of the decode loop.
RESAMPLE_BLOCK_SEC = 20.0


def _iter_decoded_blocks(container, block_sec: float = RESAMPLE_BLOCK_SEC):
    """
    Yields the decoded audio of `container` as a few large `av.AudioFrame`s (about `block_sec`
    each) built from consecutive codec frames with numpy, in order. swresample is a streaming
    filter, so resampling the blocks gives the same samples as resampling every codec frame.
    A change of format, layout or rate starts a new block. If a block can't be rebuilt (an
    unusual layout), the original frames are yielded one by one from then on.
    """
    from fractions import Fraction

    import av

    pending: list = []
    pending_n = 0
    key = None
    fed = 0
    batching = True

    def _flush():
        nonlocal pending, pending_n, fed, batching
        frames, n = pending, pending_n
        pending, pending_n = [], 0
        if batching:
            try:
                arrays = [f.to_ndarray() for f in frames]
                arr = arrays[0] if len(arrays) == 1 else np.concatenate(arrays, axis=1)
                big = av.AudioFrame.from_ndarray(arr, format=key[0], layout=key[1])
                big.sample_rate = key[2]
                big.time_base = Fraction(1, key[2])
                big.pts = fed
                fed += n
                return [big]
            except Exception:
                logger.debug("Could not batch decoded frames; resampling frame by frame", exc_info=True)
                batching = False
        return frames

    for frame in container.decode(audio=0):
        fk = (frame.format.name, frame.layout.name, int(frame.sample_rate))
        if pending and fk != key:
            yield from _flush()
        key = fk
        pending.append(frame)
        pending_n += int(frame.samples)
        if not batching or pending_n >= block_sec * fk[2]:
            yield from _flush()
    if pending:
        yield from _flush()


def decode_audio_streams(
    audio_path: Path,
    *,
    max_seconds: float = MAX_DECODED_SECONDS,
    block_sec: float = RESAMPLE_BLOCK_SEC,
) -> tuple[np.ndarray, np.ndarray, Optional[np.ndarray], float]:
    """
    Single-pass decode producing both:
      - 16 kHz mono float32 PCM (for Whisper & MMS_FA)
      - 24 kHz mid = (L+R)/2 and optional side = (L-R)/2 float32 PCM (for FrameExtractor A3)
    Returns (pcm_16k, pcm_24k_mid, pcm_24k_side_or_none, duration_sec).
    """
    import av

    max_16k = int(max_seconds * 16000)
    max_24k = int(max_seconds * 24000)
    limit_msg = f"Decoded audio exceeds {int(max_seconds // 3600)}-hour limit"
    container = av.open(str(audio_path))
    try:
        audio_stream = container.streams.audio[0] if container.streams.audio else None
        is_stereo = False
        if audio_stream is not None:
            try:
                is_stereo = int(audio_stream.channels) >= 2
            except Exception:
                is_stereo = False

        resampler_16k = av.AudioResampler(format="flt", layout="mono", rate=16000)
        resampler_24k = av.AudioResampler(
            format="flt",
            layout="stereo" if is_stereo else "mono",
            rate=24000,
        )

        chunks_16k: list[np.ndarray] = []
        chunks_24k_mid: list[np.ndarray] = []
        chunks_24k_side: list[np.ndarray] = []
        total_16k = 0
        total_24k = 0

        def _handle_16k(resampled) -> None:
            nonlocal total_16k
            a16 = resampled.to_ndarray().reshape(-1)
            total_16k += len(a16)
            if total_16k > max_16k:
                raise ValueError(limit_msg)
            chunks_16k.append(a16)

        def _handle_24k(resampled) -> None:
            nonlocal total_24k
            arr = resampled.to_ndarray()
            # PyAV 'flt' planar vs packed: (channels, samples) or (1, samples*channels)
            if arr.ndim == 2 and arr.shape[0] >= 2:
                left, right = arr[0], arr[1]
            elif is_stereo and arr.ndim == 2 and arr.shape[0] == 1 and arr.shape[1] >= 2:
                lr = arr.reshape(-1, 2)
                left, right = lr[:, 0], lr[:, 1]
            else:
                flat = arr.reshape(-1)
                total_24k += len(flat)
                if total_24k > max_24k:
                    raise ValueError(limit_msg)
                chunks_24k_mid.append(flat)
                return
            mid = (left + right) * np.float32(0.5)
            side = (left - right) * np.float32(0.5)
            total_24k += len(mid)
            if total_24k > max_24k:
                raise ValueError(limit_msg)
            chunks_24k_mid.append(mid)
            chunks_24k_side.append(side)

        for block in _iter_decoded_blocks(container, block_sec):
            for r16 in resampler_16k.resample(block):
                _handle_16k(r16)
            for r24 in resampler_24k.resample(block):
                _handle_24k(r24)
        for r16 in resampler_16k.resample(None):
            _handle_16k(r16)
        for r24 in resampler_24k.resample(None):
            _handle_24k(r24)

        pcm_16k = np.concatenate(chunks_16k).astype(np.float32, copy=False) if chunks_16k else np.zeros(0, dtype=np.float32)
        pcm_24k_mid = (
            np.concatenate(chunks_24k_mid).astype(np.float32, copy=False)
            if chunks_24k_mid
            else np.zeros(0, dtype=np.float32)
        )
        pcm_24k_side = (
            np.concatenate(chunks_24k_side).astype(np.float32, copy=False)
            if (is_stereo and chunks_24k_side and len(chunks_24k_side) == len(chunks_24k_mid))
            else None
        )
    finally:
        container.close()

    return pcm_16k, pcm_24k_mid, pcm_24k_side, float(len(pcm_16k) / 16000.0)


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
