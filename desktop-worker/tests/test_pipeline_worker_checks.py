from __future__ import annotations

import logging
import os
import threading
from pathlib import Path

import numpy as np
import pytest

from hushcast_worker import chapters, pipeline
from hushcast_worker.models import JobState, WordDto
from hushcast_worker.pipeline import (
    FASTER_WHISPER_PINS,
    SPOT_CHECK_CLIP_S,
    LocalMlBackend,
    _MonotonicProgress,
    _TranscribeProgress,
    clip_token_overlap,
    load_faster_whisper_model_dir,
    resolve_faster_whisper_source,
    spot_check_publisher_timings,
)


@pytest.fixture
def backend(tmp_path: Path, monkeypatch) -> LocalMlBackend:
    monkeypatch.setenv("TORCH_HOME", str(tmp_path / "torch"))  # ForcedAligner only setdefault()s it
    monkeypatch.setenv("HUSHCAST_LOW_MEM", "0")
    return LocalMlBackend(models_dir=tmp_path / "models")


# --- 1. embedding batch fallback ---------------------------------------------------------------


class _FakeLlama:
    def __init__(self, batch_ok: bool = True) -> None:
        self.batch_ok = batch_ok
        self.batch_calls = 0
        self.single_calls = 0

    @staticmethod
    def _vec(text: str) -> list[float]:
        v = np.zeros(8, dtype=np.float32)
        v[len(text) % 8] = 1.0
        v[0] += 0.5
        return v.tolist()

    def embed(self, inp):
        if isinstance(inp, list):
            self.batch_calls += 1
            if not self.batch_ok:
                raise RuntimeError("llama_decode returned -1")
            return [self._vec(t) for t in inp]
        self.single_calls += 1
        return self._vec(inp)


def test_embedding_uses_batch_path(backend: LocalMlBackend, caplog) -> None:
    llm = _FakeLlama(batch_ok=True)
    backend._embedding_llm = llm
    texts = [f"sentence number {i}" for i in range(40)]
    with caplog.at_level(logging.WARNING, logger="hushcast_worker.pipeline"):
        emb = backend._embed_segments(texts)
    assert emb.shape == (40, 8)
    assert llm.batch_calls == 3  # 16 + 16 + 8
    assert llm.single_calls == 0
    assert not [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert np.allclose(np.linalg.norm(emb, axis=1), 1.0)


def test_embedding_fallback_warns_once_per_episode(backend: LocalMlBackend, caplog) -> None:
    llm = _FakeLlama(batch_ok=False)
    backend._embedding_llm = llm
    texts = [f"sentence number {i}" for i in range(40)]
    with caplog.at_level(logging.DEBUG, logger="hushcast_worker.pipeline"):
        emb = backend._embed_segments(texts)
    assert emb.shape == (40, 8)
    assert llm.batch_calls == 3
    assert llm.single_calls == 40
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1
    msg = warnings[0].getMessage()
    assert "3/3 batch" in msg
    assert "RuntimeError: llama_decode returned -1" in msg
    # Same vectors as the batch path would give.
    good = _FakeLlama(batch_ok=True)
    backend._embedding_llm = good
    assert np.array_equal(backend._embed_segments(texts), emb)


# --- 2. faster-whisper pin ---------------------------------------------------------------------


def test_faster_whisper_names_resolve_to_pinned_repo_and_commit(tmp_path: Path) -> None:
    repo, rev = FASTER_WHISPER_PINS["large-v3-turbo"]
    assert repo == "dropbox-dash/faster-whisper-large-v3-turbo"
    assert len(rev) == 40 and all(c in "0123456789abcdef" for c in rev)
    for name in ("large-v3-turbo", "turbo", " large-v3-turbo ", "", "mobiuslabsgmbh/faster-whisper-large-v3-turbo"):
        assert resolve_faster_whisper_source(name) == (repo, rev)
    assert resolve_faster_whisper_source("turbo", "abc123") == (repo, "abc123")
    assert resolve_faster_whisper_source("large-v3") == ("large-v3", None)
    assert resolve_faster_whisper_source(str(tmp_path)) == (str(tmp_path), None)


_ALL_FILES = ("model.bin", "config.json", "tokenizer.json", "preprocessor_config.json", "vocabulary.json")


def _snapshot(root: Path, repo: str, rev: str, files=_ALL_FILES) -> Path:
    d = root / f"models--{repo.replace('/', '--')}" / "snapshots" / rev
    d.mkdir(parents=True)
    for f in files:
        (d / f).write_bytes(b"x")
    return d


def _fake_download(root: Path, calls: list):
    def _dl(repo, *, local_files_only, cache_dir, revision):
        calls.append((repo, local_files_only, revision))
        d = Path(cache_dir) / f"models--{repo.replace('/', '--')}" / "snapshots" / revision
        if local_files_only:
            if not d.is_dir():
                raise FileNotFoundError("LocalEntryNotFoundError")
            return str(d)
        d.mkdir(parents=True, exist_ok=True)
        for f in _ALL_FILES:
            (d / f).write_bytes(b"downloaded")
        return str(d)

    return _dl


def test_faster_whisper_cached_model_loads_without_network(tmp_path: Path) -> None:
    repo, rev = FASTER_WHISPER_PINS["large-v3-turbo"]
    snap = _snapshot(tmp_path, repo, rev)
    calls: list = []
    got = load_faster_whisper_model_dir(repo, rev, str(tmp_path), download_fn=_fake_download(tmp_path, calls))
    assert got == str(snap)
    assert calls == [(repo, True, rev)]


def test_faster_whisper_uses_legacy_cache_folder(tmp_path: Path) -> None:
    repo, rev = FASTER_WHISPER_PINS["large-v3-turbo"]
    snap = _snapshot(tmp_path, "mobiuslabsgmbh/faster-whisper-large-v3-turbo", rev)
    calls: list = []
    got = load_faster_whisper_model_dir(repo, rev, str(tmp_path), download_fn=_fake_download(tmp_path, calls))
    assert got == str(snap)
    assert all(local for _, local, _ in calls)


def test_faster_whisper_downloads_pinned_revision_when_missing_or_incomplete(tmp_path: Path) -> None:
    repo, rev = FASTER_WHISPER_PINS["large-v3-turbo"]
    _snapshot(tmp_path, repo, rev, files=("config.json", "model.bin"))  # interrupted: no tokenizer
    calls: list = []
    got = load_faster_whisper_model_dir(repo, rev, str(tmp_path), download_fn=_fake_download(tmp_path, calls))
    assert calls[-1] == (repo, False, rev)
    assert os.path.isfile(os.path.join(got, "model.bin"))


# --- 3a. publisher word timing spot checks -----------------------------------------------------

_VOCAB = (
    "today we are talking about the new budget and what it means for families across the country "
    "our guest has spent twenty years studying housing policy and she has some surprising answers"
).split()


def _timeline(duration: float, t0: float = 0.0, step: float = 0.4) -> list[WordDto]:
    out = []
    t = t0
    i = 0
    while t < duration - 0.5:
        out.append(WordDto(start=round(t, 3), end=round(t + 0.3, 3), text=f"{_VOCAB[i % len(_VOCAB)]}{i // len(_VOCAB)}"))
        t += step
        i += 1
    return out


def _asr_from(truth: list[WordDto], calls: list | None = None):
    def _clip(s: float, e: float) -> list[WordDto]:
        if calls is not None:
            calls.append((s, e))
        return [w for w in truth if s <= w.start < e]

    return _clip


def test_matching_publisher_timings_are_accepted() -> None:
    truth = _timeline(1800.0)
    calls: list = []
    ok, results = spot_check_publisher_timings(truth, 1800.0, _asr_from(truth, calls))
    assert ok
    assert len(results) == 3
    assert [round(s) for s, _ in calls] == [350, 890, 1430]  # ~20/50/80%, 20 s each
    assert all(e - s == SPOT_CHECK_CLIP_S for s, e in calls)
    assert all(ov == 1.0 for _, ov, _, _ in results)


def test_small_offset_within_tolerance_is_accepted() -> None:
    publisher = _timeline(1800.0)
    audio = [WordDto(start=w.start + 0.8, end=w.end + 0.8, text=w.text) for w in publisher]
    ok, _ = spot_check_publisher_timings(publisher, 1800.8, _asr_from(audio))
    assert ok


def test_dynamic_ad_insertion_is_rejected() -> None:
    # The publisher transcript has no ads; the served audio has a 60 s ad inserted at 25%.
    publisher = _timeline(1800.0)
    audio = [w if w.start < 450 else WordDto(start=w.start + 60, end=w.end + 60, text=w.text) for w in publisher]
    calls: list = []
    ok, results = spot_check_publisher_timings(publisher, 1860.0, _asr_from(audio, calls))
    assert not ok
    assert results[0][1] >= 0.6  # the clip before the ad still matches
    assert results[-1][1] < 0.6
    assert len(calls) == 2  # stops at the first failing clip


def test_offset_beyond_tolerance_is_rejected() -> None:
    publisher = _timeline(1800.0)
    audio = [WordDto(start=w.start + 3.0, end=w.end + 3.0, text=w.text) for w in publisher]
    ok, _ = spot_check_publisher_timings(publisher, 1803.0, _asr_from(audio))
    assert not ok


def test_music_clip_slides_to_speech() -> None:
    truth = [w for w in _timeline(1800.0) if not (880 <= w.start < 915)]  # music bed at the 50% clip
    calls: list = []
    ok, results = spot_check_publisher_timings(truth, 1800.0, _asr_from(truth, calls))
    assert ok
    assert round(results[1][0]) == 910  # moved one clip later
    assert len(calls) == 4


def test_clip_without_speech_anywhere_rejects() -> None:
    ok, _ = spot_check_publisher_timings(_timeline(600.0), 600.0, lambda s, e: [])
    assert not ok


def test_clip_overlap_is_fuzzy_and_counts_missing_words() -> None:
    pub = [WordDto(start=i * 0.5, end=i * 0.5 + 0.3, text=t) for i, t in enumerate(["Hello,", "neighbourhood", "friends", "today"])]
    asr = [WordDto(start=i * 0.5, end=i * 0.5 + 0.3, text=t) for i, t in enumerate(["hello", "neighborhood", "friends"])]
    ov, n_asr, n_pub = clip_token_overlap(asr, pub, 0.0, 2.0)
    assert (n_asr, n_pub) == (3, 4)
    assert ov == pytest.approx(3 / 4)


def test_publisher_timings_decision_is_logged(backend: LocalMlBackend, monkeypatch, caplog) -> None:
    truth = _timeline(900.0)
    monkeypatch.setattr(backend, "_make_clip_transcriber", lambda pcm, lang, cancel=None: _asr_from(truth))
    reports: list = []
    pcm = np.zeros(16000, dtype=np.float32)
    with caplog.at_level(logging.INFO, logger="hushcast_worker.pipeline"):
        ok = backend._publisher_timings_ok(truth, pcm, 900.0, "en", lambda *a: reports.append(a), None)
    assert ok
    assert any("Publisher word timings accepted" in r.getMessage() for r in caplog.records)
    assert reports and all(state == JobState.TRANSCRIBING for state, _, _ in reports)

    shifted = [WordDto(start=w.start + 30, end=w.end + 30, text=w.text) for w in truth]
    monkeypatch.setattr(backend, "_make_clip_transcriber", lambda pcm, lang, cancel=None: _asr_from(shifted))
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="hushcast_worker.pipeline"):
        assert not backend._publisher_timings_ok(truth, pcm, 930.0, "en", lambda *a: None, None)
    assert any("rejected, using Whisper" in r.getMessage() for r in caplog.records)


def test_publisher_check_error_falls_back_to_whisper(backend: LocalMlBackend, monkeypatch) -> None:
    def _broken(pcm, lang, cancel=None):
        raise RuntimeError("model load failed")

    monkeypatch.setattr(backend, "_make_clip_transcriber", _broken)
    pcm = np.zeros(16000, dtype=np.float32)
    assert not backend._publisher_timings_ok(_timeline(600.0), pcm, 600.0, "en", lambda *a: None, None)

    cancel = threading.Event()
    cancel.set()
    with pytest.raises(RuntimeError, match="cancelled"):
        backend._publisher_timings_ok(_timeline(600.0), pcm, 600.0, "en", lambda *a: None, cancel)


# --- 5. Mac progress ---------------------------------------------------------------------------


def test_monotonic_progress_never_decreases() -> None:
    seen: list = []
    prog = _MonotonicProgress(lambda st, pct, msg: seen.append((pct, msg)))
    for pct in (25, 44, 30, 31, 60, 59, 61):
        prog(JobState.TRANSCRIBING, pct, f"m{pct}")
    assert [p for p, _ in seen] == [25, 44, 44, 44, 60, 60, 61]
    assert [m for _, m in seen][2] == "m30"  # messages still get through


def test_timer_only_until_first_segment_callback() -> None:
    now = [0.0]
    seen: list = []
    tp = _TranscribeProgress(
        _MonotonicProgress(lambda st, pct, msg: seen.append(pct)),
        duration_sec=600.0,
        est_sec=20.0,
        model_name="ggml-large-v3-turbo.bin",
        clock=lambda: now[0],
    )
    now[0] = 10.0
    assert tp.tick()  # 30 + 28 * 0.5 = 44: the estimate runs ahead of real progress
    assert seen == [44]
    now[0] = 10.5
    tp.segment(60.0)  # real progress: 10% -> 32
    now[0] = 11.0
    assert not tp.tick()  # the timer stops once segments arrive
    now[0] = 12.0
    tp.segment(300.0)  # 50% -> 44
    now[0] = 13.0
    tp.segment(480.0)  # 80% -> 53
    assert seen == [44, 44, 44, 53]
    assert seen == sorted(seen)


def test_progress_never_goes_backwards_with_timer_and_callback_threads() -> None:
    seen: list = []
    lock = threading.Lock()

    def _record(st, pct, msg):
        with lock:
            seen.append(pct)

    prog = _MonotonicProgress(_record)
    tp = _TranscribeProgress(prog, duration_sec=3600.0, est_sec=0.05, model_name="m")
    stop = threading.Event()

    def _timer():
        while not stop.is_set():
            if not tp.tick():
                return

    th = threading.Thread(target=_timer)
    th.start()
    for i in range(1, 201):
        tp._last_segment_emit = None  # skip the 0.75 s throttle for the test
        tp.segment(i * 18.0)
    stop.set()
    th.join(timeout=5)
    assert seen == sorted(seen)
    assert seen[-1] == 59


# --- 4. low-memory chat model ------------------------------------------------------------------


class _FakeChat:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def test_chat_model_freed_in_low_memory_mode(backend: LocalMlBackend, monkeypatch) -> None:
    fake = _FakeChat()
    monkeypatch.setattr(chapters, "_CACHED_CHAT_LLM", ("/m/chat.gguf", 1, fake))
    backend._low_mem = False
    backend._release_chat_model_if_low_mem()
    assert chapters._CACHED_CHAT_LLM is not None and not fake.closed

    backend._low_mem = True
    backend._release_chat_model_if_low_mem()
    assert chapters._CACHED_CHAT_LLM is None
    assert fake.closed
    assert chapters.release_chat_llm() is False  # nothing left to free


def test_low_memory_embedding_model_is_closed(backend: LocalMlBackend) -> None:
    class _ClosableLlama(_FakeLlama):
        closed = False

        def close(self) -> None:
            self.closed = True

    llm = _ClosableLlama()
    backend._embedding_llm = llm
    backend._low_mem = True
    backend._embed_segments(["a b c", "d e f"])
    assert backend._embedding_llm is None
    assert llm.closed


def test_pipeline_exports() -> None:
    # Names other modules and tests rely on.
    for name in ("spot_check_publisher_timings", "feed_alignment_accepted", "load_faster_whisper_model_dir"):
        assert hasattr(pipeline, name)


def test_faster_whisper_accepts_older_snapshot_with_identical_weights(tmp_path: Path) -> None:
    repo, rev = FASTER_WHISPER_PINS["large-v3-turbo"]
    old = "0c94664816ec82be77b20e824c8e8675995b0029"
    snap = _snapshot(tmp_path, "mobiuslabsgmbh/faster-whisper-large-v3-turbo", old)
    calls: list = []
    got = load_faster_whisper_model_dir(repo, rev, str(tmp_path), download_fn=_fake_download(tmp_path, calls))
    assert got == str(snap)
    assert all(local for _, local, _ in calls)
