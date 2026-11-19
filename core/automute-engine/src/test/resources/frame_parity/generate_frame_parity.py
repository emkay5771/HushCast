"""Generates frame_parity.json: the worker's AudioFrames on a fixed synthetic signal.

FrameExtractorParityTest (Kotlin) feeds the same 24 kHz mid/side samples to FrameExtractor and
compares every column.  Re-generate only when the Python extractor changes on purpose:

    cd desktop-worker && ~/.local/bin/uv run --frozen --extra dev python \
        ../core/automute-engine/src/test/resources/frame_parity/generate_frame_parity.py

The signal is 3 s of 44.1 kHz stereo:
  * left:  a linear chirp 80 Hz -> 9 kHz (0.30) + seeded white noise (0.02)
  * right: the same chirp delayed by 0.7 ms (0.25) + independent seeded noise (0.02)
  * 1.2-1.7 s: both channels add a 140 Hz band-limited sawtooth (a voiced, harmonic stretch)
  * 2.2-2.5 s: both channels drop to noise only at -60 dB (a near-silent stretch)
It is resampled to 24 kHz with a Kaiser-windowed sinc (numpy only; the worker's PyAV resampler
is not under test), split into mid = (L+R)/2 and side = (L-R)/2, and quantised to int16 so the
input can be stored compactly and decoded bit-identically on both sides (x = q / 32768f).
"""
from __future__ import annotations

import base64
import gzip
import json
import math
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, os.getcwd())  # run from desktop-worker/
from hushcast_worker.ad_tagger import SR, extract_audio_frames  # noqa: E402

SRC_SR = 44100
DUR_S = 3.0
SEED = 20261118


def _chirp(t: np.ndarray, f0: float, f1: float, dur: float) -> np.ndarray:
    k = (f1 - f0) / dur
    return np.sin(2.0 * np.pi * (f0 * t + 0.5 * k * t * t))


def _band_limited_saw(t: np.ndarray, f: float, nyquist: float) -> np.ndarray:
    out = np.zeros_like(t)
    h = 1
    while h * f < nyquist * 0.9:
        out += ((-1.0) ** (h + 1)) * np.sin(2.0 * np.pi * h * f * t) / h
        h += 1
    return out * (2.0 / np.pi)


def _source() -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(SEED)
    n = int(SRC_SR * DUR_S)
    t = np.arange(n, dtype=np.float64) / SRC_SR
    left = 0.30 * _chirp(t, 80.0, 9000.0, DUR_S) + 0.02 * rng.standard_normal(n)
    right = 0.25 * _chirp(t - 0.0007, 80.0, 9000.0, DUR_S) + 0.02 * rng.standard_normal(n)
    voiced = (t >= 1.2) & (t < 1.7)
    saw = 0.20 * _band_limited_saw(t, 140.0, SRC_SR / 2.0)
    left[voiced] += saw[voiced]
    right[voiced] += saw[voiced]
    quiet = (t >= 2.2) & (t < 2.5)
    left[quiet] = 0.001 * rng.standard_normal(int(quiet.sum()))
    right[quiet] = 0.001 * rng.standard_normal(int(quiet.sum()))
    return left, right


def _resample(x: np.ndarray, src: int, dst: int, half_taps: int = 32, beta: float = 8.6) -> np.ndarray:
    n_out = int(math.floor(len(x) * dst / src))
    cutoff = 0.5 * min(src, dst) / src * 0.94  # cycles per input sample
    pos = np.arange(n_out, dtype=np.float64) * (src / dst)
    base = np.floor(pos).astype(np.int64)
    out = np.zeros(n_out, dtype=np.float64)
    for j in range(-half_taps + 1, half_taps + 1):
        idx = base + j
        d = pos - idx
        # Continuous Kaiser window evaluated at d (|d| < half_taps).
        r = np.clip(d / half_taps, -1.0, 1.0)
        win = np.i0(beta * np.sqrt(1.0 - r * r)) / np.i0(beta)
        h = 2.0 * cutoff * np.sinc(2.0 * cutoff * d) * win
        valid = (idx >= 0) & (idx < len(x))
        out[valid] += x[idx[valid]] * h[valid]
    return out


def _q16(x: np.ndarray) -> np.ndarray:
    return np.clip(np.round(x * 32768.0), -32768, 32767).astype(np.int16)


def main() -> None:
    left, right = _source()
    l24 = _resample(left, SRC_SR, SR).astype(np.float32)
    r24 = _resample(right, SRC_SR, SR).astype(np.float32)
    mid = (l24 + r24) * np.float32(0.5)
    side = (l24 - r24) * np.float32(0.5)
    mid_q = _q16(mid)
    side_q = _q16(side)
    mid_f = mid_q.astype(np.float32) / np.float32(32768.0)
    side_f = side_q.astype(np.float32) / np.float32(32768.0)

    frames = extract_audio_frames(mid_f, sr=SR, side_pcm=side_f)

    interleaved = np.empty(2 * len(mid_q), dtype="<i2")
    interleaved[0::2] = mid_q
    interleaved[1::2] = side_q
    packed = base64.b64encode(gzip.compress(interleaved.tobytes(), compresslevel=9, mtime=0)).decode("ascii")

    def col(a: np.ndarray) -> list[float]:
        return [float(v) for v in np.asarray(a, dtype=np.float32)]

    doc = {
        "about": "AudioFrames from desktop-worker/hushcast_worker/ad_tagger.py on a synthetic signal; see generate_frame_parity.py",
        "numpy": np.__version__,
        "sr": SR,
        "samples": int(len(mid_q)),
        "input": "gzip+base64 of little-endian int16 interleaved (mid, side); sample = q / 32768f",
        "input_data": packed,
        "frames": {
            "n": int(frames.n),
            "stereo": bool(frames.stereo),
            "lk": col(frames.lk),
            "rms": col(frames.rms),
            "rms10max": col(frames.rms10max),
            "rms10min": col(frames.rms10min),
            "zcr": col(frames.zcr),
            "centroid": col(frames.centroid),
            "flatness": col(frames.flatness),
            "flux": col(frames.flux),
            "f0": col(frames.f0),
            "cpp": col(frames.cpp),
            "side": col(frames.side),
            "bands": [col(row) for row in frames.bands],
        },
    }
    out = Path(__file__).resolve().parent / "frame_parity.json"
    out.write_text(json.dumps(doc, separators=(",", ":")) + "\n")
    print(f"wrote {out} ({out.stat().st_size} bytes, {frames.n} frames)")


if __name__ == "__main__":
    main()
