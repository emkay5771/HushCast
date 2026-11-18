from __future__ import annotations

import sys
from types import SimpleNamespace

from hushcast_worker.pipeline import (
    _extract_whisper_words_and_segments,
    _resolve_dtw_aheads_preset,
)


def test_resolve_dtw_aheads_preset_matches_model_names() -> None:
    assert _resolve_dtw_aheads_preset("ggml-large-v3-turbo.bin", pw_mod=SimpleNamespace()) == 14
    assert _resolve_dtw_aheads_preset("ggml-large-v3.bin", pw_mod=SimpleNamespace()) == 13
    assert _resolve_dtw_aheads_preset("ggml-medium.en.bin", pw_mod=SimpleNamespace()) == 9
    assert _resolve_dtw_aheads_preset("ggml-base.en-q5_1.bin", pw_mod=SimpleNamespace()) == 5
    assert _resolve_dtw_aheads_preset("ggml-tiny.en.bin", pw_mod=SimpleNamespace()) == 3
    assert _resolve_dtw_aheads_preset("unknown-model.bin", pw_mod=SimpleNamespace()) is None

    fake_pw = SimpleNamespace(WHISPER_AHEADS_LARGE_V3_TURBO="TURBO_ENUM")
    assert _resolve_dtw_aheads_preset("ggml-large-v3-turbo.bin", pw_mod=fake_pw) == "TURBO_ENUM"


def test_extract_whisper_words_prefers_t_dtw(monkeypatch) -> None:
    tokens = [
        (" who", SimpleNamespace(t0=30338, t1=30410, t_dtw=30510)),
        (" else", SimpleNamespace(t0=30410, t1=30500, t_dtw=30535)),
        (" fuck", SimpleNamespace(t0=30500, t1=30540, t_dtw=30580)),
        ("ing", SimpleNamespace(t0=30540, t1=30550, t_dtw=30602)),
        ("?", SimpleNamespace(t0=30550, t1=30800, t_dtw=-1)),
    ]

    fake_pw = SimpleNamespace(
        whisper_full_n_segments=lambda ctx: 1,
        whisper_full_get_segment_text=lambda ctx, s: "who else fucking?",
        whisper_full_get_segment_t0=lambda ctx, s: 30338,
        whisper_full_get_segment_t1=lambda ctx, s: 30652,
        whisper_full_n_tokens=lambda ctx, s: len(tokens),
        whisper_full_get_token_text=lambda ctx, s, t: tokens[t][0],
        whisper_full_get_token_data=lambda ctx, s, t: tokens[t][1],
    )
    monkeypatch.setitem(sys.modules, "_pywhispercpp", fake_pw)

    words, segments = _extract_whisper_words_and_segments(SimpleNamespace(_ctx=object()), [])
    assert len(segments) == 1
    assert [w.text for w in words] == ["who", "else", "fucking?"]
    fucking = words[2]
    assert fucking.start == 305.8
    assert fucking.end == 306.17
