from __future__ import annotations

import threading
from types import SimpleNamespace

import pytest

from hushcast_worker.ad_tagger import split_sentences
from hushcast_worker.models import PIPELINE_VERSION, WordDto
from hushcast_worker.transcript_repair import (
    PROMPT_SENTENCE,
    build_initial_prompt,
    find_hard_cuts,
    repair_hard_cuts,
    strip_prompt_leak,
)


def _w(start: float, end: float, text: str) -> WordDto:
    return WordDto(start=start, end=end, text=text)


def _contiguous(n: int, total_s: float, t0: float = 0.0, label: str = "word") -> list[WordDto]:
    """faster-whisper style: every word ends exactly where the next one starts (zero gaps)."""
    step = total_s / n
    return [_w(t0 + i * step, t0 + (i + 1) * step, f" {label}{i}") for i in range(n)]


def _seq(t0: float, step: float, texts: list[str]) -> list[WordDto]:
    return [_w(round(t0 + i * step, 3), round(t0 + (i + 1) * step, 3), " " + t) for i, t in enumerate(texts)]


def _joined(words) -> str:
    return "".join(w.text if w.text.startswith(" ") else " " + w.text for w in words).strip()


# --- (f) pipeline version -------------------------------------------------------------------


def test_pipeline_version_is_6() -> None:
    assert PIPELINE_VERSION == 6


# --- (a)/(b) sentence splitter ------------------------------------------------------------------


def test_zero_gap_unpunctuated_words_no_one_word_lines() -> None:
    words = _contiguous(90, 30.0)
    segs = split_sentences(words)
    assert 2 <= len(segs) <= 3
    assert all(s.end - s.start >= 3.0 for s in segs)
    assert all(s.nWords >= 6 for s in segs)
    assert sum(s.nWords for s in segs) == 90
    assert all(s.end - s.start <= 15.0 + 1e-6 for s in segs)


def test_zero_gap_long_stretch_pieces_stay_near_max_len() -> None:
    # Uneven word lengths, still contiguous, two minutes with no punctuation.
    words: list[WordDto] = []
    t = 0.0
    for i in range(300):
        d = 0.2 + 0.25 * ((i * 7) % 5) / 4.0
        words.append(_w(round(t, 3), round(t + d, 3), f" w{i}"))
        t += d
    segs = split_sentences(words)
    assert sum(s.nWords for s in segs) == 300
    for s in segs[:-1]:
        assert 3.0 <= s.end - s.start <= 15.0 + 1e-6
        assert s.nWords >= 6


def test_fallback_prefers_real_pause_of_at_least_150ms() -> None:
    first = _seq(0.0, 0.4, [f"a{i}" for i in range(20)])  # 0.0 - 8.0
    second = _seq(8.3, 0.4, [f"b{i}" for i in range(25)])  # 0.3 s pause, then 8.3 - 18.3
    segs = split_sentences(first + second)
    assert segs[0].nWords == 20
    assert segs[0].text.startswith("a0") and segs[0].text.endswith("a19")


def test_fallback_ignores_sub_threshold_gap_and_short_pieces() -> None:
    # 0.1 s gap (< 0.15 s) after word 19, and a big gap after word 3 (piece would be < 6 words).
    words = _seq(0.0, 0.4, [f"a{i}" for i in range(4)])  # 0.0 - 1.6
    words += _seq(2.6, 0.4, [f"b{i}" for i in range(16)])  # 1 s pause; 2.6 - 9.0
    words += _seq(9.1, 0.4, [f"c{i}" for i in range(30)])  # 0.1 s pause; 9.1 - 21.1
    segs = split_sentences(words)
    # Largest usable gap is 0.1 s, below the threshold: cut at the last boundary within 15 s.
    assert segs[0].nWords == 4 + 16 + 14  # c13 ends at 14.7 s, c14 would end at 15.1 s
    assert all(s.nWords >= 6 and s.end - s.start >= 3.0 for s in segs)


def test_punctuation_still_splits_normally() -> None:
    words = [
        _w(0.0, 0.3, "Dr."),
        _w(0.3, 0.6, " Smith"),
        _w(0.6, 0.9, " spoke."),
        _w(0.9, 1.2, " Yes."),
        _w(1.2, 1.5, " Really?"),
        _w(1.5, 1.8, " Okay"),
        _w(1.8, 2.1, " then!"),
    ]
    segs = split_sentences(words)
    assert [s.text for s in segs] == ["Dr. Smith spoke.", "Yes.", "Really?", "Okay then!"]
    assert segs[1].nWords == 1  # short sentences are fine; only the fallback has minimums


# --- (d) prompt ----------------------------------------------------------------------------------


def test_build_initial_prompt() -> None:
    assert build_initial_prompt(None, None) == PROMPT_SENTENCE
    assert build_initial_prompt("Acquired", "NVIDIA Part III") == f"Acquired: NVIDIA Part III. {PROMPT_SENTENCE}"
    assert build_initial_prompt("", "Why now?") == f"Why now? {PROMPT_SENTENCE}"
    # Episode title that already names the show isn't doubled.
    assert build_initial_prompt("Acquired", "Acquired: NVIDIA") == f"Acquired: NVIDIA. {PROMPT_SENTENCE}"
    long = build_initial_prompt("P" * 500, "word " * 200)
    assert len(long) < 260  # ~60 tokens, far under the 224-token prompt cap
    assert "\n" not in build_initial_prompt("Show\nName", "Ep\t1")
    assert build_initial_prompt("Show", "Folge 1", language="de") == "Show: Folge 1."
    assert build_initial_prompt(None, None, language="de") is None


def test_prompt_leak_window_is_dropped() -> None:
    prompt = build_initial_prompt("Acquired", "NVIDIA Part III")
    leak = _seq(0.0, 0.3, ["Hello,", "and", "welcome", "back", "to", "the", "show."])
    kept, dropped = strip_prompt_leak(leak, prompt)
    assert kept == [] and dropped == 7

    full_leak = _seq(0.0, 0.3, ["Acquired:", "NVIDIA", "Part", "III.", "Hello,", "and", "welcome", "back", "to", "the", "show."])
    kept, dropped = strip_prompt_leak(full_leak, prompt)
    assert kept == [] and dropped == 11

    leak_then_speech = leak + _seq(2.1, 0.3, ["Today", "we", "talk", "chips."])
    kept, dropped = strip_prompt_leak(leak_then_speech, prompt)
    assert dropped == 7 and _joined(kept) == "Today we talk chips."

    real = _seq(0.0, 0.3, ["Hello", "everyone,", "welcome", "back."])
    kept, dropped = strip_prompt_leak(real, prompt)
    assert dropped == 0 and len(kept) == 4


# --- (c) hard-cut repair -------------------------------------------------------------------------


def _cut_fixture():
    # Window A ends exactly where window B starts (30.0 s): a VAD hard cut. The word "models"
    # was lost on the cut and Whisper closed window A with a period.
    window_a = _seq(20.0, 0.4, ["Earlier", "we", "talked", "about", "chips."])  # 20.0 - 22.0
    window_a += _seq(27.2, 0.4, ["so", "we", "saw", "the", "use", "of", "sophisticated."])  # - 30.0
    window_b = _seq(30.0, 0.4, ["really", "significant", "chunk", "of", "NVIDIA's", "profit", "share."])
    return [window_a, window_b]


def test_repair_hard_cuts_restores_dropped_word() -> None:
    windows = _cut_fixture()
    assert find_hard_cuts(windows) == [30.0]
    truth = _seq(27.2, 0.4, ["so", "we", "saw", "the", "use", "of"])  # 27.2 - 29.6
    truth += _seq(29.6, 0.3, ["sophisticated", "models,"])  # 29.6 - 30.2
    truth += _seq(30.2, 0.4, ["really", "significant", "chunk", "of", "NVIDIA's", "profit", "share."])
    calls: list[tuple[float, float, str]] = []

    def fake_decode(start: float, end: float, prompt: str):
        calls.append((start, end, prompt))
        # Times relative to the window start, as the real decoder returns them.
        return [
            _w(w.start - start, w.end - start, w.text)
            for w in truth
            if start <= w.start and w.end <= end
        ]

    flat, n = repair_hard_cuts(windows, fake_decode, audio_duration_s=600.0)
    assert n == 1
    assert calls == [(25.0, 35.0, "Earlier we talked about chips.")]
    assert _joined(flat) == (
        "Earlier we talked about chips. so we saw the use of sophisticated models, "
        "really significant chunk of NVIDIA's profit share."
    )
    assert [w.start for w in flat] == sorted(w.start for w in flat)
    # Input is not mutated.
    assert windows[0][-1].text == " sophisticated."

    # Sentences now run across the cut.
    segs = split_sentences(flat)
    assert segs[-1].text.startswith("so we saw the use of sophisticated models,")
    assert segs[-1].text.endswith("profit share.")


def test_repair_hard_cuts_seams_do_not_double_or_drop_words() -> None:
    windows = _cut_fixture()

    def fake_decode(start: float, end: float, prompt: str):
        # The re-decode times words ~0.2-0.25 s earlier than the batched pass. "saw" (orig mid
        # 28.2, inside the +-2 s span) lands at mid 27.98, just outside it: must not be lost.
        # "profit" (orig mid 32.2, outside, kept) lands at mid 31.95, inside: must not double.
        words = [
            _w(27.2, 27.6, " so"),
            _w(27.6, 27.78, " we"),
            _w(27.78, 28.18, " saw"),
            _w(28.18, 28.5, " the"),
            _w(28.5, 28.8, " use"),
            _w(28.8, 29.1, " of"),
            _w(29.1, 29.45, " sophisticated"),
            _w(29.45, 29.75, " models"),
        ]
        words += _seq(29.75, 0.4, ["really", "significant", "chunk", "of", "NVIDIA's", "profit", "share."])
        return [_w(w.start - start, w.end - start, w.text) for w in words]

    flat, n = repair_hard_cuts(windows, fake_decode)
    assert n == 1
    text = _joined(flat)
    assert text.endswith("so we saw the use of sophisticated models really significant chunk of NVIDIA's profit share.")
    assert text.count(" saw ") == 1 and text.count("profit") == 1


def test_repair_strips_boundary_period_when_decode_returns_nothing() -> None:
    windows = _cut_fixture()
    flat, n = repair_hard_cuts(windows, lambda s, e, p: [])
    assert n == 0
    assert "sophisticated really significant" in _joined(flat)
    assert "chips. so" in _joined(flat)  # a real pause, not a hard cut: untouched


def test_repair_keeps_period_before_capital_and_on_initialisms() -> None:
    a = _seq(0.0, 0.5, ["we", "went", "to", "the", "U.S."])
    b = _seq(2.5, 0.5, ["economy", "is", "big."])
    c = _seq(4.0, 0.5, ["That", "was", "it."])
    flat, _ = repair_hard_cuts([a, b, c], lambda s, e, p: [])
    assert _joined(flat) == "we went to the U.S. economy is big. That was it."


def test_repair_rejects_implausible_redecode_and_survives_errors() -> None:
    windows = _cut_fixture()

    def looping(start, end, prompt):
        return [_w(28.0 + i * 0.05 - start, 28.05 + i * 0.05 - start, " the") for i in range(80)]

    flat, n = repair_hard_cuts(windows, looping)
    assert n == 0 and _joined(flat).count(" the ") == 1

    def broken(start, end, prompt):
        raise ValueError("decoder blew up")

    flat, n = repair_hard_cuts(windows, broken)
    assert n == 0 and "sophisticated really" in _joined(flat)

    def dropped_words(start, end, prompt):
        # Re-decode lost several words compared to the original span: must be rejected.
        words = _seq(28.0, 0.5, ["we", "saw", "sophisticated", "really", "significant"])
        return [_w(w.start - start, w.end - start, w.text) for w in words]

    flat, n = repair_hard_cuts(windows, dropped_words)
    assert n == 0
    assert "so we saw the use of sophisticated really significant chunk of NVIDIA's profit share." in _joined(flat)


def test_repair_hard_cuts_multi_word_seam_drift_preserves_all_words() -> None:
    windows = _cut_fixture()

    def fake_decode(start: float, end: float, prompt: str):
        # Shift 2 words ("so", "we") earlier than lo=28.0 and 2 words ("NVIDIA's", "profit")
        # past hi=32.0 relative to the batched timestamps.
        words = _seq(27.0, 0.3, ["so", "we", "saw", "the", "use", "of", "sophisticated", "models"])
        words += _seq(29.8, 0.5, ["really", "significant", "chunk", "of", "NVIDIA's", "profit", "share."])
        return [_w(w.start - start, w.end - start, w.text) for w in words]

    flat, n = repair_hard_cuts(windows, fake_decode)
    assert n == 1
    assert _joined(flat) == (
        "Earlier we talked about chips. so we saw the use of sophisticated models "
        "really significant chunk of NVIDIA's profit share."
    )


def test_repair_respects_cancel_event() -> None:
    ev = threading.Event()
    ev.set()
    with pytest.raises(RuntimeError, match="cancelled"):
        repair_hard_cuts(_cut_fixture(), lambda s, e, p: [], cancel_event=ev)


# --- faster-whisper path end to end with fakes ----------------------------------------------------


def _fw_seg(words: list[WordDto]):
    fw_words = [SimpleNamespace(word=w.text, start=w.start, end=w.end) for w in words]
    return SimpleNamespace(
        start=words[0].start,
        end=words[-1].end,
        text="".join(w.text for w in words),
        words=fw_words,
    )


def test_faster_whisper_path_prompt_leak_repair_and_flat_split() -> None:
    from hushcast_worker.pipeline import _transcribe_faster_whisper

    prompt = build_initial_prompt("Acquired", "NVIDIA Part III", "en")
    leak = _seq(1.0, 0.3, ["Hello,", "and", "welcome", "back", "to", "the", "show."])
    a, b = _cut_fixture()
    batched_calls: list[dict] = []
    redecode_calls: list[dict] = []

    class _Model:
        def transcribe(self, audio, **kwargs):
            redecode_calls.append({"n": len(audio), **kwargs})
            # The whole 25-35 s window, timed relative to its start.
            words = _seq(2.2, 0.4, ["so", "we", "saw", "the", "use", "of"])
            words += _seq(4.6, 0.3, ["sophisticated", "models"])
            words += _seq(5.2, 0.4, ["really", "significant", "chunk", "of", "NVIDIA's", "profit", "share."])
            return iter([_fw_seg(words)]), None

    class _Pipe:
        model = _Model()

        def transcribe(self, audio, **kwargs):
            batched_calls.append(kwargs)
            return iter([_fw_seg(leak), _fw_seg(a), _fw_seg(b)]), None

    pcm = [0.0] * (16000 * 60)
    words, segments = _transcribe_faster_whisper(
        _Pipe(), pcm, lang="en", duration_sec=60.0, initial_prompt=prompt
    )
    assert batched_calls[0]["initial_prompt"] == prompt
    assert batched_calls[0]["word_timestamps"] is True
    assert len(redecode_calls) == 1
    rc = redecode_calls[0]
    assert rc["n"] == 16000 * 10
    assert rc["vad_filter"] is False and rc["condition_on_previous_text"] is False
    assert rc["temperature"] == 0.0 and rc["word_timestamps"] is True
    assert rc["initial_prompt"] == "Earlier we talked about chips."

    texts = [w.text for w in words]
    assert "Hello," not in texts and "welcome" not in texts
    assert texts[-9:] == ["sophisticated", "models", "really", "significant", "chunk", "of", "NVIDIA's", "profit", "share."]
    assert [s.text for s in segments] == [
        "Earlier we talked about chips.",
        "so we saw the use of sophisticated models really significant chunk of NVIDIA's profit share.",
    ]
    # words and segments describe the same word list.
    assert sum(s.nWords for s in segments) == len(words)
    assert segments[0].start == words[0].start and segments[-1].end == words[-1].end
