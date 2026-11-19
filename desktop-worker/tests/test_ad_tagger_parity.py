from __future__ import annotations

import numpy as np
import pytest

from hushcast_worker import ad_tagger
from hushcast_worker.ad_tagger import (
    HOP,
    PAD_LEFT,
    PAD_RIGHT,
    _apply_k_weight_fft,
    _K_WEIGHT_IR,
    _KWeightStream,
    _kotlin_mean,
    _round_half_even,
    decode_ad_segments,
    extract_audio_frames,
)
from hushcast_worker.models import SegmentDto

# --- rounding parity with E/engine/Decoder.kt ---------------------------------------------------

# (x, digits, Kotlin result). Expected values printed by the JVM itself:
# BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble(), as in `Decoder.round`.
KOTLIN_ROUND_CASES = [
    (0.005, 2, 0.01),  # binary value is just above the tie
    (0.015, 2, 0.01),  # just below the tie: the old str()-based rounding gave 0.02
    (0.025, 2, 0.03),
    (0.125, 2, 0.12),  # exact binary tie -> even
    (0.375, 2, 0.38),  # exact binary tie -> even
    (1.005, 2, 1.0),
    (2.675, 2, 2.67),
    (12.335, 2, 12.34),
    (12.345, 2, 12.35),
    (1234.565, 2, 1234.57),
    (0.0625, 3, 0.062),  # exact tie -> even
    (0.1875, 3, 0.188),  # exact tie -> even
    (2.5, 0, 2.0),
    (3.5, 0, 4.0),
    (7.0049999999, 2, 7.0),
]


@pytest.mark.parametrize("x,digits,expected", KOTLIN_ROUND_CASES)
def test_round_half_even_matches_kotlin_bigdecimal(x: float, digits: int, expected: float) -> None:
    assert _round_half_even(x, digits) == expected


def test_round_half_even_accepts_numpy_floats() -> None:
    assert _round_half_even(np.float32(0.125), 2) == 0.12
    assert _round_half_even(np.float64(0.015), 2) == 0.01


def test_span_edges_round_like_kotlin() -> None:
    # A 0.015 s start is a str() tie but not a binary one: Kotlin puts the span at 10 ms.
    segs = [
        SegmentDto(start=0.015, end=2.0, text="a", nWords=1),
        SegmentDto(start=2.0, end=4.125, text="b", nWords=1),
        SegmentDto(start=4.125, end=6.0, text="c", nWords=1),
    ]
    probs = np.array(
        [[0.1, 0.9, 0.0, 0.0, 0.0], [0.1, 0.9, 0.0, 0.0, 0.0], [0.9, 0.1, 0.0, 0.0, 0.0]],
        dtype=np.float32,
    )
    spans = decode_ad_segments(probs, segs)
    assert len(spans) == 1
    assert spans[0].startMs == 10
    assert spans[0].endMs == 4120  # 4.125 is an exact tie -> 4.12


def test_kotlin_mean_sums_float_probabilities_in_order() -> None:
    vals = np.array([0.1, 0.2, 0.7, 0.33333334, 0.9999999], dtype=np.float32)
    expected = 0.0
    for v in vals.tolist():  # float32 -> exact double, summed left to right like the Kotlin loop
        expected += v
    expected /= len(vals)
    assert _kotlin_mean(vals) == expected


# --- V18: streamed K-weighting -----------------------------------------------------------------


def _old_apply_k_weight_fft(signal: np.ndarray) -> np.ndarray:
    """The 1.0.30 full-length implementation, kept here as the reference."""
    n = len(signal)
    ir_len = len(_K_WEIGHT_IR)
    block_size = 16384
    step = block_size - ir_len + 1
    h_spec = np.fft.rfft(_K_WEIGHT_IR, n=block_size)
    out = np.empty(n, dtype=np.float64)
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


def test_k_weight_stream_is_bit_identical_to_full_filter() -> None:
    rng = np.random.default_rng(3)
    sig = (rng.standard_normal(100_003) * 0.2).astype(np.float32)
    ref = _old_apply_k_weight_fft(sig)

    stream = _KWeightStream(sig)
    parts = []
    for n in (1024, 5, 12289, 30000, 7, 40000):
        parts.append(stream.take(n))
    parts.append(stream.take(len(sig)))  # the rest
    got = np.concatenate(parts)
    assert got.dtype == np.float64
    assert len(got) == len(sig)
    assert np.array_equal(got, ref)
    assert np.array_equal(_apply_k_weight_fft(sig), ref)


def test_extract_audio_frames_loudness_unchanged_and_chunk_independent() -> None:
    rng = np.random.default_rng(7)
    sr = ad_tagger.SR
    t = np.arange(int(sr * 75)) / sr
    mid = (0.3 * np.sin(2 * np.pi * 440 * t) * (1 + np.sin(2 * np.pi * 0.1 * t)) + 0.05 * rng.standard_normal(len(t)))
    mid = mid.astype(np.float32)
    side = (0.02 * rng.standard_normal(len(t))).astype(np.float32)

    small = extract_audio_frames(mid, side_pcm=side, chunk_minutes=0.1)  # 60-frame chunks
    big = extract_audio_frames(mid, side_pcm=side)  # one chunk

    # Reference loudness from the old full-length K-weighted copy.
    padded = np.concatenate([np.zeros(PAD_LEFT, np.float32), mid, np.zeros(PAD_RIGHT + HOP, np.float32)])
    kw = _old_apply_k_weight_fft(padded)
    n = big.n
    kw_2d = kw[PAD_LEFT : PAD_LEFT + n * HOP].reshape(n, HOP)
    ref_lk = ad_tagger._db10(np.sum(kw_2d * kw_2d, axis=1) / float(HOP)).astype(np.float32)

    assert np.array_equal(big.lk, ref_lk)
    for name in ("lk", "rms", "rms10max", "rms10min", "flatness", "side", "zcr", "cpp", "f0", "centroid", "flux", "bands"):
        np.testing.assert_allclose(getattr(small, name), getattr(big, name), rtol=1e-6, atol=1e-4, err_msg=name)
