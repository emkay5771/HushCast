from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import pytest

from hushcast_worker.forced_align import (
    MIN_WORD_SCORE,
    ForcedAligner,
    count_alignable_words,
)
from hushcast_worker.models import SegmentDto, WordDto
from hushcast_worker.pipeline import FEED_ALIGN_MIN_FRACTION, feed_alignment_accepted

SR = 16000


class _FakeEmission:
    """Stands in for a torch [frames, tokens] emission: 50 frames per second."""

    def __init__(self, n_samples: int) -> None:
        self.n_frames = max(1, n_samples // 320)

    def size(self, dim: int) -> int:
        assert dim == 0
        return self.n_frames


def _fake_aligner(scores: dict[str, float], words: list[WordDto], frames_per_s: float = 50.0):
    """Aligns each word to its current time (relative to a window that starts at chunk_s)."""
    state = {"chunk_s": 0.0}

    def _align(emission, tokens):
        out = []
        for tok in tokens:
            w = next(w for w in words if w.text == tok)
            # Spans in emission frames, shifted 0.05 s later than the word's current start.
            s = int(round((w.start + 0.05 - state["chunk_s"]) * frames_per_s))
            e = s + 10
            out.append([SimpleNamespace(start=s, end=e, score=scores[tok])])
        return out

    return _align, state


def _loaded_fake_aligner(words: list[WordDto], scores: dict[str, float]):
    fa = ForcedAligner()
    fa._available = True
    fa._loaded = True
    fa._model = object()
    fa._tokenizer = lambda transcript: list(transcript)
    align, state = _fake_aligner(scores, words)
    fa._aligner = align
    batches: list[int] = []

    def _emissions(chunks):
        batches.append(len(chunks))
        return [_FakeEmission(len(c)) for c in chunks]

    fa._emissions = _emissions
    return fa, state, batches


def test_score_gate_is_a_probability_threshold() -> None:
    words = [
        WordDto(start=1.0, end=1.3, text="good"),
        WordDto(start=1.5, end=1.8, text="bad"),
        WordDto(start=2.0, end=2.3, text="edge"),
        WordDto(start=2.5, end=2.8, text="2026"),  # not alignable
    ]
    scores = {"good": 0.95, "bad": 0.2, "edge": MIN_WORD_SCORE}
    fa, state, _ = _loaded_fake_aligner(words, scores)
    state["chunk_s"] = 1.0 - 0.15  # the window starts pad_sec before the first word
    pcm = np.zeros(SR * 5, dtype=np.float32)
    segs = [SegmentDto(start=1.0, end=2.8, text="x", nWords=4)]

    n = fa.align_words(pcm, segs, words)

    assert n == 2
    assert words[0].start == pytest.approx(1.05, abs=0.02)  # moved
    assert (words[1].start, words[1].end) == (1.5, 1.8)  # 0.2 < 0.30: kept
    assert words[2].start == pytest.approx(2.05, abs=0.02)  # exactly 0.30 is accepted
    assert (words[3].start, words[3].end) == (2.5, 2.8)
    assert MIN_WORD_SCORE == 0.30


def test_old_negative_threshold_would_have_accepted_everything() -> None:
    # MMS_FA scores are in 0..1, so the old `> -2.5` gate let a 0.01 word through.
    words = [WordDto(start=1.0, end=1.3, text="noise")]
    fa, state, _ = _loaded_fake_aligner(words, {"noise": 0.01})
    state["chunk_s"] = 0.85
    pcm = np.zeros(SR * 3, dtype=np.float32)
    assert fa.align_words(pcm, [SegmentDto(start=1.0, end=1.3, text="noise", nWords=1)], words) == 0
    assert fa.align_words(pcm, [SegmentDto(start=1.0, end=1.3, text="noise", nWords=1)], words, min_score=-2.5) == 1


def test_count_alignable_words_skips_numbers_and_symbols() -> None:
    words = [WordDto(start=0, end=1, text=t) for t in ("Hello", "2026", "—", "café", "don't", "$50")]
    assert count_alignable_words(words) == 3


@pytest.mark.parametrize(
    "n_aligned,n_alignable,n_words,ok",
    [
        (90, 100, 104, True),
        (89, 100, 104, False),  # below 90%
        (100, 100, 100, True),
        (0, 0, 10, False),
        (45, 45, 100, False),  # most words not alignable at all
        (900, 1000, 1000, True),
    ],
)
def test_feed_alignment_gate(n_aligned: int, n_alignable: int, n_words: int, ok: bool) -> None:
    assert FEED_ALIGN_MIN_FRACTION == 0.90
    assert feed_alignment_accepted(n_aligned, n_alignable, n_words) is ok


def _many_windows() -> list[WordDto]:
    # 12 windows of ~3 s separated by 3 s pauses (a pause > 2.5 s starts a new window).
    out = []
    t = 0.5
    k = 0
    for _w in range(12):
        for _i in range(6):
            out.append(WordDto(start=round(t, 3), end=round(t + 0.3, 3), text=f"word{chr(97 + k % 26)}{chr(97 + k // 26)}"))
            t += 0.5
            k += 1
        t += 3.0
    return out


def test_windows_are_batched_on_cuda_with_identical_results(monkeypatch) -> None:
    monkeypatch.delenv("HUSHCAST_FA_BATCH", raising=False)
    pcm = np.zeros(SR * 80, dtype=np.float32)
    segs = [SegmentDto(start=0.0, end=80.0, text="x", nWords=1)]

    def _run(device: str, batchable: bool):
        words = _many_windows()
        scores = {w.text: 0.9 for w in words}
        fa, state, batches = _loaded_fake_aligner(words, scores)
        fa._device = device
        fa._batchable = batchable

        # The fake aligner needs each window's start: derive it from the tokens it is given.
        def _align(emission, tokens):
            first = next(w for w in words if w.text == tokens[0])
            state["chunk_s"] = max(0.0, first.start - 0.15)
            return orig(emission, tokens)

        orig = fa._aligner
        fa._aligner = _align
        n = fa.align_words(pcm, segs, words)
        return n, [(w.start, w.end) for w in words], batches

    n_seq, times_seq, batches_seq = _run("cpu", True)
    n_bat, times_bat, batches_bat = _run("cuda", True)
    assert batches_seq == [1] * 12
    assert batches_bat == [8, 4]
    assert n_seq == n_bat == 72
    assert times_seq == times_bat


def test_batch_size_policy(monkeypatch) -> None:
    fa = ForcedAligner()
    fa._batchable = True
    monkeypatch.delenv("HUSHCAST_FA_BATCH", raising=False)
    fa._device = "cuda"
    assert fa._batch_size() == 8
    for dev in ("mps", "cpu"):
        fa._device = dev
        assert fa._batch_size() == 1
    monkeypatch.setenv("HUSHCAST_FA_BATCH", "3")
    assert fa._batch_size() == 3
    fa._batchable = False
    assert fa._batch_size() == 1


def test_failed_batch_falls_back_to_one_window_at_a_time(monkeypatch) -> None:
    monkeypatch.delenv("HUSHCAST_FA_BATCH", raising=False)
    words = _many_windows()[:12]  # two windows
    fa, state, batches = _loaded_fake_aligner(words, {w.text: 0.9 for w in words})
    fa._device = "cuda"
    fa._batchable = True
    ok_emissions = fa._emissions

    def _emissions(chunks):
        if len(chunks) > 1:
            raise RuntimeError("CUDA out of memory")
        return ok_emissions(chunks)

    fa._emissions = _emissions
    orig = fa._aligner

    def _align(emission, tokens):
        first = next(w for w in words if w.text == tokens[0])
        state["chunk_s"] = max(0.0, first.start - 0.15)
        return orig(emission, tokens)

    fa._aligner = _align
    n = fa.align_words(np.zeros(SR * 20, dtype=np.float32), [SegmentDto(start=0, end=20, text="x", nWords=1)], words)
    assert n == 12
    assert fa._batchable is False


def test_batched_emissions_match_one_at_a_time() -> None:
    """Real wav2vec2 graph (MMS_FA's layer_norm extractor mode, tiny random weights)."""
    torch = pytest.importorskip("torch")
    torchaudio = pytest.importorskip("torchaudio")
    from torchaudio.pipelines._wav2vec2.utils import _Wav2Vec2Model

    from hushcast_worker.forced_align import _is_batchable

    def _tiny(extractor_mode: str):
        torch.manual_seed(0)
        inner = torchaudio.models.wav2vec2_model(
            extractor_mode=extractor_mode,
            extractor_conv_layer_config=[(32, 10, 5), (32, 3, 2), (32, 3, 2), (32, 3, 2), (32, 3, 2), (32, 2, 2), (32, 2, 2)],
            extractor_conv_bias=True,
            encoder_embed_dim=64,
            encoder_projection_dropout=0.0,
            encoder_pos_conv_kernel=16,
            encoder_pos_conv_groups=4,
            encoder_num_layers=2,
            encoder_num_heads=4,
            encoder_attention_dropout=0.0,
            encoder_ff_interm_features=128,
            encoder_ff_interm_dropout=0.0,
            encoder_dropout=0.0,
            encoder_layer_norm_first=True,
            encoder_layer_drop=0.0,
            aux_num_out=28,
        )
        return _Wav2Vec2Model(inner, normalize_waveform=True, apply_log_softmax=True, append_star=False).eval()

    model = _tiny("layer_norm")
    assert _is_batchable(model)
    assert not _is_batchable(_tiny("group_norm"))  # GroupNorm normalizes over time: unsafe to pad

    fa = ForcedAligner()
    fa._model = model
    fa._device = "cpu"
    fa._batchable = True
    rng = np.random.default_rng(1)
    chunks = [(rng.standard_normal(n) * 0.1).astype(np.float32) for n in (SR * 6 + 123, SR * 2 + 17, SR * 4, 3000)]
    one = [fa._emissions([c])[0] for c in chunks]
    batched = fa._emissions(chunks)
    for a, b in zip(one, batched):
        assert tuple(a.shape) == tuple(b.shape)
        assert float((a - b).abs().max()) < 1e-4
