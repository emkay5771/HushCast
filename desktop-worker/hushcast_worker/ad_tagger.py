from __future__ import annotations

import math
import re
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_EVEN
from typing import Optional, Sequence

import numpy as np

from .models import AdSegmentDto, SegmentDto, WordDto

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

# Exact constants from core/automute-engine/.../FrameExtractor.kt
SR = 24000
HOP = 2400
SUB = 240
NFFT = 2048
SHOP = 1200
PAD_LEFT = NFFT // 2
PAD_RIGHT = NFFT
DB_FLOOR = -200.0
BAND_EDGES = (
    0, 60, 120, 250, 400, 630, 800, 1000, 1250, 1600,
    2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12000,
)
_N_BANDS = len(BAND_EDGES) - 1  # 18
_N_BINS = NFFT // 2 + 1  # 1025
_Q_LO = max(1, int(SR / 400.0))  # 60
_Q_HI = min(NFFT // 2, int(SR / 60.0) + 1)  # 401

_ABBREVIATIONS = frozenset({
    "mr.", "mrs.", "ms.", "dr.", "st.", "jr.", "sr.", "vs.", "etc.", "inc.",
    "co.", "no.", "u.s.", "u.k.", "e.g.", "i.e.", "a.m.", "p.m.", "mt.", "ft.", "approx.",
})
_SENTENCE_END_RE = re.compile(r"[.?!][\"')\]]*$")
MAX_SENTENCE_SEGMENT_S = 15.0


def is_sentence_end(word: str) -> bool:
    w = word.strip().lower()
    return bool(_SENTENCE_END_RE.search(w)) and w not in _ABBREVIATIONS


def split_sentences(words: Sequence[WordDto], max_len: float = MAX_SENTENCE_SEGMENT_S) -> list[SegmentDto]:
    """
    Exact port of `SentenceSplitter.split` in core/automute-engine.
    Call once per Whisper segment (don't join words across segments).
    """
    out: list[SegmentDto] = []
    cur: list[WordDto] = []

    def _flush(upto: Optional[int] = None) -> None:
        nonlocal cur
        n = len(cur) if upto is None else upto
        take = cur[:n]
        cur = cur[n:]
        if take:
            text = "".join(w.text for w in take).strip()
            out.append(
                SegmentDto(
                    start=round(float(take[0].start), 3),
                    end=round(float(take[-1].end), 3),
                    text=text,
                    nWords=len(take),
                )
            )

    for w in words:
        cur.append(w)
        if is_sentence_end(w.text):
            _flush()
        elif (cur[-1].end - cur[0].start) > max_len and len(cur) > 2:
            best = 0
            for k in range(1, len(cur) - 1):
                if (cur[k + 1].start - cur[k].end) > (cur[best + 1].start - cur[best].end):
                    best = k
            _flush(best + 1)
    _flush()
    return out


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
    bands: np.ndarray  # shape (n, 18)


def _k_weighting_sos(sr: float = float(SR)) -> list[list[float]]:
    """ITU-R BS.1770 K-weighting as two biquads [b0, b1, b2, a1, a2], rounded through float32."""
    f0 = 1681.974450955533
    gain_db = 3.999843853973347
    q = 0.7071752369554196
    k = math.tan(math.pi * f0 / sr)
    vh = math.pow(10.0, gain_db / 20.0)
    vb = math.pow(vh, 0.4996667741545416)
    a0 = 1.0 + k / q + k * k
    shelf = [
        (vh + vb * k / q + k * k) / a0,
        2.0 * (k * k - vh) / a0,
        (vh - vb * k / q + k * k) / a0,
        2.0 * (k * k - 1.0) / a0,
        (1.0 - k / q + k * k) / a0,
    ]
    f0b = 38.13547087602444
    qb = 0.5003270373238773
    kb = math.tan(math.pi * f0b / sr)
    a0b = 1.0 + kb / qb + kb * kb
    hpf = [
        1.0,
        -2.0,
        1.0,
        2.0 * (kb * kb - 1.0) / a0b,
        (1.0 - kb / qb + kb * kb) / a0b,
    ]
    return [[float(np.float32(v)) for v in c] for c in (shelf, hpf)]


def _k_weighting_impulse(length: int = 4096) -> np.ndarray:
    """Computes the exact impulse response of the cascaded K-weighting biquads."""
    sos = _k_weighting_sos(float(SR))
    z = [0.0, 0.0, 0.0, 0.0]
    h = np.empty(length, dtype=np.float64)
    for i in range(length):
        v = 1.0 if i == 0 else 0.0
        for s in range(2):
            c = sos[s]
            y = c[0] * v + z[2 * s]
            z[2 * s] = c[1] * v - c[3] * y + z[2 * s + 1]
            z[2 * s + 1] = c[2] * v - c[4] * y
            v = y
        h[i] = v
    return h


_K_WEIGHT_IR = _k_weighting_impulse(4096)
_WIN_HANN = np.array(
    [float(np.float32(0.5 - 0.5 * math.cos(2.0 * math.pi * i / (NFFT - 1)))) for i in range(NFFT)],
    dtype=np.float64,
)
_FREQS = np.array([float(np.float32(i * float(SR) / NFFT)) for i in range(_N_BINS)], dtype=np.float64)
_SCALE = 1.0 / (float(NFFT) * NFFT * float(np.sum(_WIN_HANN * _WIN_HANN)) / NFFT)
_BIN_NORM = np.full(_N_BINS, 2.0 * _SCALE, dtype=np.float64)
_BIN_NORM[0] = _SCALE
_BIN_NORM[-1] = _SCALE
_BAND_MASKS = [
    (_FREQS >= BAND_EDGES[b]) & (_FREQS < BAND_EDGES[b + 1])
    for b in range(_N_BANDS)
]


def _db10(p: np.ndarray | float) -> np.ndarray | float:
    if isinstance(p, np.ndarray):
        out = np.full(p.shape, DB_FLOOR, dtype=np.float64)
        pos = p > 0.0
        out[pos] = np.maximum(10.0 * np.log10(p[pos]), DB_FLOOR)
        return out
    return max(10.0 * math.log10(p), DB_FLOOR) if p > 0.0 else DB_FLOOR


def _apply_k_weight_fft(signal: np.ndarray) -> np.ndarray:
    """Fast FFT convolution with the 4096-tap K-weighting impulse response."""
    n = len(signal)
    if n == 0:
        return np.zeros(0, dtype=np.float64)
    ir_len = len(_K_WEIGHT_IR)
    block_size = 16384
    step = block_size - ir_len + 1
    h_spec = np.fft.rfft(_K_WEIGHT_IR, n=block_size)
    out = np.empty(n, dtype=np.float64)
    # Overlap-add with ir_len - 1 overlap
    tail = np.zeros(ir_len - 1, dtype=np.float64)
    for pos in range(0, n, step):
        seg = signal[pos : pos + step].astype(np.float64)
        conv = np.fft.irfft(np.fft.rfft(seg, n=block_size) * h_spec, n=block_size)
        valid_len = len(seg)
        conv[: len(tail)] += tail
        out[pos : pos + valid_len] = conv[:valid_len]
        if valid_len + ir_len - 1 <= block_size:
            tail = conv[valid_len : valid_len + ir_len - 1].copy()
        else:
            tail = np.zeros(ir_len - 1, dtype=np.float64)
    return out


def extract_audio_frames(
    pcm_mid: np.ndarray,
    sr: int = SR,
    side_pcm: Optional[np.ndarray] = None,
    *,
    chunk_minutes: float = 10.0,
) -> AudioFrames:
    """
    Extracts 100 ms acoustic frames in exact parity with `FrameExtractor.kt`.
    Accepts 24 kHz mid = (L+R)/2 and optional 24 kHz side = (L-R)/2.
    Processes in 10-minute chunks (V18) to bound peak memory on long episodes.
    """
    if len(pcm_mid) == 0:
        empty = np.zeros(0, dtype=np.float32)
        return AudioFrames(
            n=0,
            stereo=side_pcm is not None,
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

    mid = pcm_mid.astype(np.float32, copy=False)
    have_side = side_pcm is not None and len(side_pcm) == len(mid)
    side_arr = side_pcm.astype(np.float32, copy=False) if have_side else None

    if sr != SR:
        new_len = max(1, int(round(len(mid) * (float(SR) / float(sr)))))
        x_old = np.linspace(0.0, 1.0, len(mid), endpoint=False)
        x_new = np.linspace(0.0, 1.0, new_len, endpoint=False)
        mid = np.interp(x_new, x_old, mid).astype(np.float32)
        if side_arr is not None:
            side_arr = np.interp(x_new, x_old, side_arr).astype(np.float32)

    # Pad left by PAD_LEFT and right by PAD_RIGHT + HOP (matching FrameExtractor.kt)
    pad_r = PAD_RIGHT + HOP
    padded_mid = np.concatenate([
        np.zeros(PAD_LEFT, dtype=np.float32),
        mid,
        np.zeros(pad_r, dtype=np.float32),
    ])
    padded_side = (
        np.concatenate([
            np.zeros(PAD_LEFT, dtype=np.float32),
            side_arr,
            np.zeros(pad_r, dtype=np.float32),
        ])
        if side_arr is not None
        else None
    )

    # K-weight filter the entire padded mid signal in overlap-add blocks
    kw_padded = _apply_k_weight_fft(padded_mid)

    total_frames = (len(padded_mid) - PAD_LEFT - PAD_RIGHT) // HOP
    if total_frames <= 0:
        empty = np.zeros(0, dtype=np.float32)
        return AudioFrames(
            n=0,
            stereo=have_side,
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

    lk_out = np.empty(total_frames, dtype=np.float32)
    rms_out = np.empty(total_frames, dtype=np.float32)
    rms10max_out = np.empty(total_frames, dtype=np.float32)
    rms10min_out = np.empty(total_frames, dtype=np.float32)
    zcr_out = np.empty(total_frames, dtype=np.float32)
    centroid_out = np.empty(total_frames, dtype=np.float32)
    flatness_out = np.empty(total_frames, dtype=np.float32)
    flux_out = np.empty(total_frames, dtype=np.float32)
    f0_out = np.empty(total_frames, dtype=np.float32)
    cpp_out = np.empty(total_frames, dtype=np.float32)
    side_out = np.full(total_frames, DB_FLOOR, dtype=np.float32)
    bands_out = np.empty((total_frames, _N_BANDS), dtype=np.float32)

    # Process frames in 10-minute chunks (6000 frames) to bound peak FFT matrix memory (V18)
    chunk_frames = max(1, int(chunk_minutes * 600))
    n_sub = HOP // SUB  # 10
    sub_per_frame = HOP // SHOP  # 2
    prev_mag: Optional[np.ndarray] = None

    for f0_idx in range(0, total_frames, chunk_frames):
        f1_idx = min(total_frames, f0_idx + chunk_frames)
        nf = f1_idx - f0_idx

        s_start = PAD_LEFT + f0_idx * HOP
        s_end = PAD_LEFT + f1_idx * HOP
        mid_2d = padded_mid[s_start:s_end].astype(np.float64).reshape(nf, HOP)
        kw_2d = kw_padded[s_start:s_end].reshape(nf, HOP)

        # Loudness & RMS
        lk_ms = np.sum(kw_2d * kw_2d, axis=1) / float(HOP)
        lk_out[f0_idx:f1_idx] = _db10(lk_ms).astype(np.float32)

        sub_2d = mid_2d.reshape(nf, n_sub, SUB)
        sub_ms = np.mean(sub_2d * sub_2d, axis=2)
        rms_ms = np.mean(sub_ms, axis=1)
        rms_out[f0_idx:f1_idx] = _db10(rms_ms).astype(np.float32)
        rms10max_out[f0_idx:f1_idx] = _db10(np.max(sub_ms, axis=1)).astype(np.float32)
        rms10min_out[f0_idx:f1_idx] = _db10(np.min(sub_ms, axis=1)).astype(np.float32)

        # ZCR (matching Kotlin FrameExtractor: sign-bit comparison across t=1..HOP-1)
        neg = mid_2d < 0.0
        crossings = np.sum(neg[:, 1:] != neg[:, :-1], axis=1)
        zcr_out[f0_idx:f1_idx] = (crossings / float(HOP - 1)).astype(np.float32)

        # Side channel energy
        if padded_side is not None:
            side_2d = padded_side[s_start:s_end].astype(np.float64).reshape(nf, HOP)
            side_ms = np.mean(side_2d * side_2d, axis=1)
            side_out[f0_idx:f1_idx] = _db10(side_ms).astype(np.float32)

        # Spectral features: 2 sub-windows per 100ms frame
        # Window k starts at (f * 2 + k) * SHOP + SHOP // 2
        n_win = nf * sub_per_frame
        w_starts = (np.arange(f0_idx * sub_per_frame, f1_idx * sub_per_frame) * SHOP) + (SHOP // 2)
        idx_mat = w_starts[:, None] + np.arange(NFFT)[None, :]
        win_frames = padded_mid[idx_mat].astype(np.float64) * _WIN_HANN[None, :]

        spec = np.fft.rfft(win_frames, n=NFFT, axis=1)
        power = (spec.real * spec.real + spec.imag * spec.imag) * _BIN_NORM[None, :] + 1e-20
        total_p = np.sum(power, axis=1)
        weighted_p = np.sum(power * _FREQS[None, :], axis=1)
        log_p = np.log(power)
        log_sum = np.sum(log_p, axis=1)

        win_centroid = weighted_p / total_p
        win_flatness = np.exp(log_sum / float(_N_BINS)) / (total_p / float(_N_BINS))

        mag = np.sqrt(power)
        win_flux = np.zeros(n_win, dtype=np.float64)
        if prev_mag is not None:
            win_flux[0] = float(np.sum(np.maximum(mag[0] - prev_mag, 0.0)))
        if n_win > 1:
            win_flux[1:] = np.sum(np.maximum(mag[1:] - mag[:-1], 0.0), axis=1)
        prev_mag = mag[-1].copy()

        # Real cepstrum: irfft of real symmetric log_p
        ceps = np.fft.irfft(log_p, n=NFFT, axis=1)
        cep_win = ceps[:, _Q_LO:_Q_HI]
        peak_rel = np.argmax(cep_win, axis=1)
        peak_val = cep_win[np.arange(n_win), peak_rel]
        med_val = np.median(cep_win, axis=1)
        win_cpp = (peak_val - med_val) * (10.0 / math.log(10.0))
        win_f0 = float(SR) / (peak_rel + _Q_LO).astype(np.float64)

        # Average over the 2 sub-windows per frame
        centroid_out[f0_idx:f1_idx] = np.mean(win_centroid.reshape(nf, sub_per_frame), axis=1).astype(np.float32)
        flatness_out[f0_idx:f1_idx] = _db10(np.mean(win_flatness.reshape(nf, sub_per_frame), axis=1)).astype(np.float32)
        flux_mean = np.mean(win_flux.reshape(nf, sub_per_frame), axis=1)
        flux_out[f0_idx:f1_idx] = _db10(flux_mean * flux_mean).astype(np.float32)

        cpp_2 = win_cpp.reshape(nf, sub_per_frame)
        f0_2 = win_f0.reshape(nf, sub_per_frame)
        best_k = np.argmax(cpp_2, axis=1)
        cpp_out[f0_idx:f1_idx] = cpp_2[np.arange(nf), best_k].astype(np.float32)
        f0_out[f0_idx:f1_idx] = f0_2[np.arange(nf), best_k].astype(np.float32)

        # 18 fixed frequency bands
        for b, mask in enumerate(_BAND_MASKS):
            if np.any(mask):
                b_sum = np.sum(power[:, mask], axis=1)
                b_mean = np.mean(b_sum.reshape(nf, sub_per_frame), axis=1)
                bands_out[f0_idx:f1_idx, b] = _db10(b_mean).astype(np.float32)
            else:
                bands_out[f0_idx:f1_idx, b] = DB_FLOOR

    return AudioFrames(
        n=total_frames,
        stereo=have_side,
        lk=lk_out,
        rms=rms_out,
        rms10max=rms10max_out,
        rms10min=rms10min_out,
        flatness=flatness_out,
        side=side_out,
        zcr=zcr_out,
        cpp=cpp_out,
        f0=f0_out,
        centroid=centroid_out,
        flux=flux_out,
        bands=bands_out,
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


def find_ad_candidates(
    probs: np.ndarray,
    segments: Sequence[SegmentDto],
) -> list[AdSegmentDto]:
    """
    Exact port of `Decoder.findCandidates` (A3):
    finds suggested sponsor-ad candidates outside confirmed spans where
    sponsor_ad probability >= CAND_MIN_P for >= CAND_MIN_SEGMENTS.
    """
    n = len(probs)
    if n == 0 or len(segments) == 0:
        return []
    runs = decoder_runs(decoder_labels(probs))
    inside = [False] * n
    for i, j, _ in runs:
        for r in range(i, j + 1):
            inside[r] = True

    hot = [not inside[i] and float(probs[i, 1]) >= CAND_MIN_P for i in range(n)]
    out: list[AdSegmentDto] = []
    i = 0
    while i < n:
        if not hot[i]:
            i += 1
            continue
        j = i
        while j + 1 < n and (hot[j + 1] or (j + 2 < n and hot[j + 2] and not inside[j + 1])):
            j += 1
        if j - i + 1 >= CAND_MIN_SEGMENTS:
            avg_p = _round_half_even(float(np.mean(probs[i : j + 1, 1])), 3)
            start_s = _round_half_even(float(segments[i].start), 2)
            end_s = _round_half_even(float(segments[j].end), 2)
            out.append(
                AdSegmentDto(
                    startMs=int(round(start_s * 1000)),
                    endMs=int(round(end_s * 1000)),
                    confidence=avg_p,
                    isHighConfidence=False,
                    label="Suggested Ad",
                    adClass="sponsor_ad",
                )
            )
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
    out.extend(find_ad_candidates(probs, segments))
    out.sort(key=lambda a: a.startMs)
    return out
