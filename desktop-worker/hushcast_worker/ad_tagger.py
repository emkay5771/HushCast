from __future__ import annotations

import math
import re
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_EVEN
from typing import Optional, Sequence

import numpy as np

from .models import AdSegmentDto, SegmentDto

CLASSES = ("content", "sponsor_ad", "house_promo", "membership_pitch", "boilerplate")
MIN_SEGMENTS = 2
MAX_GAP = 2
CAND_MIN_P = 0.25
CAND_MIN_SEGMENTS = 2
HIGH_CONFIDENCE_THRESHOLD = 0.80

FEATURE_COUNT = 28
_WIN = 50
_VALID = -190.0
_BASS_BANDS = 4
_N_BANDS = 16


@dataclass
class AudioFrames:
    """100 ms acoustic frames matching Kotlin FrameExtractor output."""

    n: int
    stereo: bool
    lk: np.ndarray
    rms: np.ndarray
    rms10max: np.ndarray
    rms10min: np.ndarray
    flatness: np.ndarray
    side: np.ndarray
    zcr: np.ndarray
    cpp: np.ndarray
    f0: np.ndarray
    centroid: np.ndarray
    flux: np.ndarray
    bands: np.ndarray  # shape (n, 16) or flat (n * 16,)


def extract_audio_frames(pcm_16k: np.ndarray, sr: int = 16000) -> AudioFrames:
    """Extracts 100 ms acoustic frames from 16 kHz mono float32 PCM for SegmentFeatures."""
    hop = max(1, sr // 10)  # 1600 samples = 100 ms
    n = len(pcm_16k) // hop
    if n <= 0:
        empty = np.zeros(0, dtype=np.float32)
        return AudioFrames(
            n=0,
            stereo=False,
            lk=empty,
            rms=empty,
            rms10max=empty,
            rms10min=empty,
            flatness=empty,
            side=empty,
            zcr=empty,
            cpp=empty,
            f0=empty,
            centroid=empty,
            flux=empty,
            bands=np.zeros((0, _N_BANDS), dtype=np.float32),
        )

    frames_2d = pcm_16k[: n * hop].reshape(n, hop).astype(np.float32)
    eps = 1e-12

    # Frame RMS and sub-frame (10 ms) min/max RMS in dB
    rms_lin = np.sqrt(np.mean(frames_2d * frames_2d, axis=1) + eps)
    rms_db = (20.0 * np.log10(rms_lin)).astype(np.float32)
    sub_hop = max(1, hop // 10)
    sub_2d = frames_2d[:, : sub_hop * 10].reshape(n, 10, sub_hop)
    sub_rms_db = 20.0 * np.log10(np.sqrt(np.mean(sub_2d * sub_2d, axis=2) + eps))
    rms10max = np.max(sub_rms_db, axis=1).astype(np.float32)
    rms10min = np.min(sub_rms_db, axis=1).astype(np.float32)
    # Approximate short-term loudness (LKFS-like) from RMS dB
    lk = (rms_db - 0.691).astype(np.float32)

    # Zero-crossing rate
    signs = frames_2d >= 0.0
    zcr = (np.sum(signs[:, 1:] != signs[:, :-1], axis=1) / float(max(1, hop - 1))).astype(np.float32)

    # Spectral features via rFFT
    win = np.hanning(hop).astype(np.float32)
    spec = np.abs(np.fft.rfft(frames_2d * win[None, :], axis=1)) + eps  # (n, hop//2 + 1)
    power = spec * spec
    freqs = np.fft.rfftfreq(hop, d=1.0 / sr).astype(np.float32)

    # Spectral flatness (geometric mean / arithmetic mean of power)
    geom_mean = np.exp(np.mean(np.log(power), axis=1))
    arith_mean = np.mean(power, axis=1) + eps
    flatness = np.clip(geom_mean / arith_mean, 0.0, 1.0).astype(np.float32)

    # Spectral centroid (Hz)
    mag_sum = np.sum(spec, axis=1) + eps
    centroid = (np.sum(spec * freqs[None, :], axis=1) / mag_sum).astype(np.float32)

    # Spectral flux (normalized L2 diff between consecutive frames)
    norm_spec = spec / mag_sum[:, None]
    flux = np.zeros(n, dtype=np.float32)
    if n > 1:
        diff = norm_spec[1:] - norm_spec[:-1]
        flux[1:] = np.sqrt(np.sum(diff * diff, axis=1)).astype(np.float32)

    # 16 log-spaced frequency bands in dB
    band_edges = np.geomspace(40.0, sr / 2.0, _N_BANDS + 1)
    bands = np.full((n, _N_BANDS), -120.0, dtype=np.float32)
    for b in range(_N_BANDS):
        mask = (freqs >= band_edges[b]) & (freqs < band_edges[b + 1])
        if np.any(mask):
            b_pow = np.mean(power[:, mask], axis=1)
            bands[:, b] = (10.0 * np.log10(b_pow + eps)).astype(np.float32)

    # Cepstral peak prominence (CPP) & F0 over 70..350 Hz
    log_pow = np.log(power + eps)
    ceps = np.fft.irfft(log_pow, axis=1)
    min_lag = max(1, sr // 350)
    max_lag = min(ceps.shape[1] - 1, sr // 70)
    cpp = np.zeros(n, dtype=np.float32)
    f0 = np.zeros(n, dtype=np.float32)
    if max_lag > min_lag:
        sub_ceps = ceps[:, min_lag : max_lag + 1]
        peak_rel = np.argmax(sub_ceps, axis=1)
        peak_val = sub_ceps[np.arange(n), peak_rel]
        baseline = np.mean(sub_ceps, axis=1)
        cpp = np.maximum(0.0, (peak_val - baseline) * 20.0).astype(np.float32)
        f0 = (sr / (min_lag + peak_rel).astype(np.float32)).astype(np.float32)

    side = np.zeros(n, dtype=np.float32)
    return AudioFrames(
        n=n,
        stereo=False,
        lk=lk,
        rms=rms_db,
        rms10max=rms10max,
        rms10min=rms10min,
        flatness=flatness,
        side=side,
        zcr=zcr,
        cpp=cpp,
        f0=f0,
        centroid=centroid,
        flux=flux,
        bands=bands,
    )


def _round_half_even(x: float, digits: int) -> float:
    return float(Decimal(str(x)).quantize(Decimal(10) ** -digits, rounding=ROUND_HALF_EVEN))


def _valid(arr: np.ndarray, start: int, stop: int) -> np.ndarray:
    sub = arr[start:stop].astype(np.float64)
    return sub[sub > _VALID]


def _edge(lk: np.ndarray, idx: int) -> float:
    n = len(lk)
    pre = _valid(lk, min(max(0, idx - 10), n), min(idx, n))
    post = _valid(lk, min(idx, n), min(idx + 10, n))
    if len(pre) >= 3 and len(post) >= 3:
        return abs(float(np.mean(post)) - float(np.mean(pre)))
    return 0.0


def compute_segment_features(
    frames: Optional[AudioFrames],
    segments: Sequence[SegmentDto],
    duration_sec: float = 0.0,
) -> np.ndarray:
    """
    Computes the 28 per-segment features in exact parity with
    `SegmentFeatures.compute` in core/automute-engine.
    Returns float32 array of shape (len(segments), 28).
    """
    n = len(segments)
    out = np.zeros((n, FEATURE_COUNT), dtype=np.float32)
    if n == 0:
        return out

    have = frames is not None and frames.n > 0
    nfr = frames.n if frames is not None else 0
    dur = segments[-1].end if segments[-1].end > 0 else max(duration_sec, 1.0)

    if have:
        assert frames is not None
        bands_2d = frames.bands.reshape(nfr, _N_BANDS).astype(np.float64)
        lk_valid = _valid(frames.lk, 0, nfr)
        lk_med = float(np.median(lk_valid)) if len(lk_valid) > 0 else -70.0
        if len(lk_valid) > 10:
            lra = float(np.percentile(lk_valid, 95.0) - np.percentile(lk_valid, 10.0))
        else:
            lra = 0.0
        act = frames.rms[:nfr] > -80.0
        n_act = int(np.count_nonzero(act))
        side_c = np.maximum(frames.side[:nfr].astype(np.float64), -120.0)
        side_med = float(np.median(side_c[act])) if (frames.stereo and n_act > 10) else None

        clamped_bands = np.maximum(bands_2d, -120.0)
        band_means = np.mean(clamped_bands, axis=1, keepdims=True)
        prof = clamped_bands - band_means
        lin_bands = np.power(10.0, np.minimum(clamped_bands, 0.0) / 10.0)
        bass = np.sum(lin_bands[:, :_BASS_BANDS], axis=1) / (np.sum(lin_bands, axis=1) + 1e-12)

        if n_act > 10:
            prof_med = np.median(prof[act], axis=0)
            bass_med = float(np.median(bass[act]))
        else:
            prof_med = np.zeros(_N_BANDS, dtype=np.float64)
            bass_med = 0.0
    else:
        lk_med = -70.0
        lra = 0.0
        act = None
        side_c = None
        side_med = None
        prof = None
        prof_med = None
        bass = None
        bass_med = 0.0

    prev_lk: Optional[float] = None
    for i, s in enumerate(segments):
        row = np.zeros(FEATURE_COUNT, dtype=np.float64)
        a = float(s.start)
        e = float(s.end)

        if have:
            assert frames is not None
            f0i = max(0, int(a * 10.0))
            f1i = max(int(a * 10.0) + 1, int(e * 10.0))
            stop = min(f1i, nfr)
            has_range = stop > f0i

            lk = _valid(frames.lk, f0i, stop) if has_range else np.empty(0, dtype=np.float64)
            lk_mean = float(np.mean(lk)) if len(lk) > 0 else -100.0
            row[0] = lk_mean
            row[1] = float(np.std(lk)) if len(lk) > 0 else 0.0
            row[2] = float(np.max(frames.rms10max[f0i:stop])) if has_range else -100.0
            row[3] = float(np.min(frames.rms10min[f0i:stop])) if has_range else -100.0

            for col_idx, arr in (
                (4, frames.flatness),
                (5, frames.side),
                (6, frames.zcr),
                (7, frames.cpp),
                (9, frames.centroid),
            ):
                vals = _valid(arr, f0i, stop) if has_range else np.empty(0, dtype=np.float64)
                row[col_idx] = float(np.mean(vals)) if len(vals) > 0 else 0.0

            if has_range:
                f0_mask = frames.cpp[f0i:stop] > 1.0
                f0v = frames.f0[f0i:stop][f0_mask].astype(np.float64)
                row[8] = float(np.std(f0v)) if len(f0v) > 3 else 0.0

            gb_start = min(max(0, f0i - 30), nfr)
            gb_end = min(f0i, nfr)
            row[10] = float(np.count_nonzero(frames.rms[gb_start:gb_end] < -60.0)) / 10.0

            ga_end = min(stop + 30, nfr)
            row[11] = float(np.count_nonzero(frames.rms[stop:ga_end] < -60.0)) / 10.0

            row[12] = (lk_mean - prev_lk) if prev_lk is not None else 0.0
            prev_lk = lk_mean

            if len(lk) > 0:
                row[18] = lk_mean - lk_med

            c0 = (f0i + min(f1i, nfr)) // 2
            w0 = max(0, c0 - _WIN)
            w1 = min(nfr, c0 + _WIN)
            wlk = _valid(frames.lk, w0, w1) if w1 > w0 else np.empty(0, dtype=np.float64)
            if len(wlk) > 10:
                row[19] = float(np.median(wlk)) - lk_med
                row[20] = float(np.percentile(wlk, 95.0) - np.percentile(wlk, 10.0)) - lra

            if side_med is not None and side_c is not None and w1 > w0:
                row[21] = float(np.median(side_c[w0:w1])) - side_med

            fx = _valid(frames.flux, f0i, stop) if has_range else np.empty(0, dtype=np.float64)
            row[22] = float(np.mean(fx)) if len(fx) > 0 else -90.0

            if has_range:
                row[23] = float(np.count_nonzero(frames.cpp[f0i:stop] > 1.0)) / float(stop - f0i)
                assert act is not None and prof is not None and prof_med is not None and bass is not None
                active_slice = act[f0i:stop]
                if int(np.count_nonzero(active_slice)) >= 3:
                    band_meds = np.median(prof[f0i:stop][active_slice], axis=0)
                    row[24] = float(np.mean(np.abs(band_meds - prof_med)))
                    row[25] = float(np.median(bass[f0i:stop][active_slice])) - bass_med

            row[26] = _edge(frames.lk, f0i)
            row[27] = _edge(frames.lk, min(stop, nfr))

        row[14] = a / max(dur, 1.0)
        row[15] = e - a
        row[16] = float(s.nWords)
        prev_spk = segments[i - 1].speaker if i > 0 else s.speaker
        row[17] = 1.0 if (s.speaker is not None and prev_spk is not None and s.speaker != prev_spk) else 0.0

        out[i] = row.astype(np.float32)

    return out


def decoder_labels(probs: np.ndarray) -> list[int]:
    return [int(np.argmax(probs[i])) for i in range(len(probs))]


def decoder_runs(labels: Sequence[int]) -> list[tuple[int, int, int]]:
    out: list[tuple[int, int, int]] = []
    n = len(labels)
    i = 0
    while i < n:
        c = labels[i]
        if c == 0:
            i += 1
            continue
        j = i
        while j + 1 < n and (
            labels[j + 1] == c
            or (
                j + 1 + MAX_GAP < n
                and labels[j + 1] == 0
                and any(labels[j + 1 + step] == c for step in range(1, MAX_GAP + 1))
            )
        ):
            j += 1
        if j - i + 1 >= MIN_SEGMENTS:
            out.append((i, j, c))
        i = j + 1
    return out


def extract_sponsor_name(text: str) -> Optional[str]:
    patterns = [
        r"(?:support for (?:the|this) show comes from|sponsored by|brought to you by|thanks to|this episode is brought to you by)\s+([A-Z][A-Za-z0-9&'-]+(?:\s+[A-Z][A-Za-z0-9&'-]+){0,2})",
        r"(?:learn more at|visit|head to|go to|check out)\s+([A-Za-z0-9-]+)\.(?:com|io|co|org|net|ai)\b",
    ]
    for pat in patterns:
        m = re.search(pat, text, flags=re.IGNORECASE)
        if m:
            candidate = m.group(1).strip(" .,:;!?\"'")
            if 2 <= len(candidate) <= 28:
                if candidate.islower():
                    candidate = candidate.capitalize()
                return candidate
    return None


_CLASS_LABELS = {
    "sponsor_ad": "Sponsor / Ad",
    "house_promo": "House Promo",
    "membership_pitch": "Membership Pitch",
    "boilerplate": "Intro / Outro",
}


def decode_ad_segments(probs: np.ndarray, segments: Sequence[SegmentDto]) -> list[AdSegmentDto]:
    """
    Exact port of `Decoder.findSpans` + F6 filtering (only `sponsor_ad` can be
    high-confidence / auto-skipped; other non-content classes are marked only).
    """
    if len(segments) == 0 or len(probs) == 0:
        return []

    labels = decoder_labels(probs)
    runs = decoder_runs(labels)

    out: list[AdSegmentDto] = []
    for i, j, cls_idx in runs:
        cls_name = CLASSES[cls_idx]
        avg_p = _round_half_even(float(np.mean(probs[i : j + 1, cls_idx])), 3)
        start_s = _round_half_even(float(segments[i].start), 2)
        end_s = _round_half_even(float(segments[j].end), 2)

        is_sponsor = cls_name == "sponsor_ad"
        is_high = bool(is_sponsor and avg_p >= HIGH_CONFIDENCE_THRESHOLD)

        combined_text = " ".join(s.text for s in segments[i : j + 1])
        sponsor = extract_sponsor_name(combined_text) if is_sponsor else None
        base_label = _CLASS_LABELS.get(cls_name, "Sponsor / Ad")
        label = f"Sponsor: {sponsor}" if sponsor else base_label

        out.append(
            AdSegmentDto(
                startMs=int(round(start_s * 1000)),
                endMs=int(round(end_s * 1000)),
                confidence=avg_p,
                isHighConfidence=is_high,
                label=label,
                adClass=cls_name,
            )
        )
    return out
