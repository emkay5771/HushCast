from __future__ import annotations

import hashlib
import os
import sys
import types
from pathlib import Path

from fastapi.testclient import TestClient

from hushcast_worker.models import (
    DesktopAnalysisBundle,
    WordDto,
    WordIndexBlobDto,
)
from hushcast_worker.storage import WorkerCache


def _import_modal_app_with_stub():
    """Import modal_app, stubbing the `modal` package if not installed in local venv."""
    if "modal_app" in sys.modules:
        return sys.modules["modal_app"]

    if "modal" not in sys.modules:
        modal_stub = types.ModuleType("modal")

        class _FakeImage:
            @classmethod
            def debian_slim(cls, **kwargs):
                return cls()

            @classmethod
            def from_registry(cls, *args, **kwargs):
                return cls()

            def pip_install(self, *args, **kwargs):
                return self

            def apt_install(self, *args, **kwargs):
                return self

            def env(self, *args, **kwargs):
                return self

            def run_commands(self, *args, **kwargs):
                return self

            def add_local_python_source(self, *args, **kwargs):
                return self

            def add_local_file(self, *args, **kwargs):
                return self

        class _FakeApp:
            def __init__(self, name):
                self.name = name

            def function(self, *args, **kwargs):
                return lambda fn: fn

            def cls(self, *args, **kwargs):
                return lambda cls_obj: cls_obj

            def local_entrypoint(self, *args, **kwargs):
                return lambda fn: fn

        class _FakeVolume:
            @classmethod
            def from_name(cls, *args, **kwargs):
                return cls()

            def reload(self):
                pass

            def commit(self):
                pass

        class _FakeDict(dict):
            @classmethod
            def from_name(cls, *args, **kwargs):
                return cls()

        class _FakeSecret:
            @classmethod
            def from_name(cls, *args, **kwargs):
                return cls()

        modal_stub.Image = _FakeImage
        modal_stub.App = _FakeApp
        modal_stub.Volume = _FakeVolume
        modal_stub.Dict = _FakeDict
        modal_stub.Secret = _FakeSecret
        modal_stub.asgi_app = lambda *a, **kw: (lambda fn: fn)
        modal_stub.concurrent = lambda *a, **kw: (lambda fn: fn)
        modal_stub.enter = lambda *a, **kw: (lambda fn: fn)
        modal_stub.exit = lambda *a, **kw: (lambda fn: fn)
        modal_stub.method = lambda *a, **kw: (lambda fn: fn)
        modal_stub.Period = lambda *a, **kw: None
        sys.modules["modal"] = modal_stub

    worker_root = str(Path(__file__).resolve().parent.parent)
    if worker_root not in sys.path:
        sys.path.insert(0, worker_root)
    import modal_app  # type: ignore
    return modal_app


def test_modal_fastapi_post_jobs_and_reprocess_lookup(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    cache = WorkerCache(tmp_path / "cache")

    spawned: list[dict] = []

    class _FakeGpuWorker:
        class _RunJob:
            def spawn(self, job_id: str, req_dict: dict):
                spawned.append({"job_id": job_id, "req": req_dict})

        run_job = _RunJob()

    monkeypatch.setattr(modal_app, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(modal_app, "volume", modal_app.volume)
    monkeypatch.setattr(modal_app, "job_dict", {})
    monkeypatch.setattr(modal_app, "GpuWorker", _FakeGpuWorker)
    monkeypatch.setenv("HUSHCAST_WORKER_TOKEN", "test-token")

    headers = {"Authorization": "Bearer test-token"}
    app = modal_app.fastapi_app()
    with TestClient(app) as client:
        # Submit with explicit nulls like the Android client sends
        resp = client.post(
            "/v1/jobs",
            headers=headers,
            json={
                "episodeId": "ep-modal-1",
                "audioUrl": "https://example.com/ep.mp3",
                "episodeTitle": "Modal Test",
                "feedChaptersType": None,
                "feedTranscriptMime": None,
            },
        )
        assert resp.status_code == 200, resp.text
        data = resp.json()
        assert data["state"] == "QUEUED"
        # response_model_exclude_none=True omits null optional fields
        assert "stageProgress" in data  # initial sets stageProgress=0
        assert "retryable" not in data
        assert "queuePosition" not in data
        assert len(spawned) == 1

        # Poll job from job_dict
        job_id = data["jobId"]
        poll = client.get(f"/v1/jobs/{job_id}", headers=headers)
        assert poll.status_code == 200
        assert poll.json()["jobId"] == job_id

        # Save a bundle under sha256 and verify lookup_by_episode_id works for reprocessStage
        sha = hashlib.sha256(b"audio").hexdigest()
        bundle = DesktopAnalysisBundle(
            fileSha256=sha,
            episodeId="ep-modal-1",
            durationSec=10.0,
            wordIndexBlob=WordIndexBlobDto(words=[WordDto(start=0.0, end=0.5, text="hi")], segments=[]),
            adSegments=[],
            whisperModel="ggml-large-v3-turbo.bin",
            analysisDurationMs=10,
            workerAccelerator="cuda",
        )
        cache.save_episode(
            sha256=sha,
            audio_tmp_path=None,
            bundle=bundle,
            audio_url="https://example.com/ep.mp3",
            title="Modal Test",
            content_type="audio/mpeg",
        )
        assert cache.lookup_by_episode_id("ep-modal-1", None) == sha

        reproc = client.post(
            "/v1/jobs",
            headers=headers,
            json={
                "episodeId": "ep-modal-1",
                "audioUrl": "https://example.com/ep.mp3",
                "reprocessStage": "SUMMARY_CHAPTERS",
            },
        )
        assert reproc.status_code == 200, reproc.text
        assert len(spawned) == 2


class _CountingDict(dict):
    def __init__(self) -> None:
        super().__init__()
        self.gets = 0
        self.puts = 0

    def get(self, key, default=None):
        self.gets += 1
        return super().get(key, default)

    def __setitem__(self, key, value) -> None:
        self.puts += 1
        super().__setitem__(key, value)


def test_modal_download_throttles_dict_calls_57mb(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    counting_dict = _CountingDict()
    cache = WorkerCache(tmp_path / "cache")

    class _FakeBackend:
        def analyze_with_embeddings(self, **kwargs):
            bundle = DesktopAnalysisBundle(
                fileSha256=kwargs["file_sha256"],
                episodeId=kwargs["episode_id"],
                durationSec=3600.0,
                wordIndexBlob=WordIndexBlobDto(words=[WordDto(start=0.0, end=0.5, text="hi")], segments=[]),
                adSegments=[],
                whisperModel="ggml-large-v3-turbo.bin",
                analysisDurationMs=10,
                workerAccelerator="cuda",
            )
            return bundle, None

    worker = modal_app.GpuWorker()
    worker.cache = cache
    worker.backend = _FakeBackend()

    async def _fake_download(audio_url, dest_path, on_bytes=None, **kwargs):
        total = 57 * 1024 * 1024
        chunk = 65536
        dest_path.write_bytes(b"fake")
        for rec in range(chunk, total + 1, chunk):
            if on_bytes:
                on_bytes(min(rec, total), total)
        return hashlib.sha256(b"57mb-audio").hexdigest(), "audio/mpeg"

    import hushcast_worker.audio as audio_mod

    monkeypatch.setattr(audio_mod, "download_audio_streaming_sha256", _fake_download)
    monkeypatch.setattr(modal_app, "job_dict", counting_dict)

    worker.run_job(
        "job-57mb",
        {
            "episodeId": "ep-57mb",
            "audioUrl": "https://example.com/57mb.mp3",
            "title": "Long Episode",
        },
    )

    assert counting_dict["job-57mb"]["state"] == "READY"
    assert (counting_dict.gets + counting_dict.puts) < 200


def test_modal_stale_heartbeat_reports_failed_retryable(tmp_path: Path, monkeypatch) -> None:
    import time

    modal_app = _import_modal_app_with_stub()
    fake_dict = {
        "stale-1": {
            "jobId": "stale-1",
            "episodeId": "ep-stale",
            "state": "TRANSCRIBING",
            "stage": "TRANSCRIBING",
            "progressPercent": 42,
            "stageLabel": "Transcribing",
            "rev": 3,
            "createdAt": time.time() - 500.0,
            "heartbeatAt": time.time() - 200.0,
        }
    }
    monkeypatch.setattr(modal_app, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(modal_app, "job_dict", fake_dict)
    monkeypatch.setenv("HUSHCAST_WORKER_TOKEN", "test-token")

    app = modal_app.fastapi_app()
    with TestClient(app) as client:
        resp = client.get("/v1/jobs/stale-1", headers={"Authorization": "Bearer test-token"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["state"] == "FAILED"
        assert body["retryable"] is True
        assert body["errorCode"] == "WORKER_STALE"


def test_lookup_by_episode_id_returns_newest_bundle(tmp_path: Path) -> None:
    cache = WorkerCache(tmp_path / "cache")
    sha_old = hashlib.sha256(b"old-audio").hexdigest()
    sha_new = hashlib.sha256(b"new-audio").hexdigest()
    for sha, url in ((sha_old, "https://example.com/old.mp3"), (sha_new, "https://example.com/new.mp3")):
        bundle = DesktopAnalysisBundle(
            fileSha256=sha,
            episodeId="same-ep",
            durationSec=10.0,
            wordIndexBlob=WordIndexBlobDto(words=[WordDto(start=0.0, end=0.5, text="hi")], segments=[]),
            adSegments=[],
            whisperModel="ggml-large-v3-turbo.bin",
            analysisDurationMs=10,
            workerAccelerator="cuda",
        )
        cache.save_episode(
            sha256=sha,
            audio_tmp_path=None,
            bundle=bundle,
            audio_url=url,
            title="Ep",
            content_type="audio/mpeg",
        )
    cache._index["by_sha"][sha_old]["createdAtMs"] = 1000
    cache._index["by_sha"][sha_new]["createdAtMs"] = 5000
    assert cache.lookup_by_episode_id("same-ep", "") == sha_new



class _RecordingDict(dict):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.get_keys: list[str] = []
        self.writes: list[tuple[str, dict]] = []

    def get(self, key, default=None):
        self.get_keys.append(key)
        return super().get(key, default)

    def __setitem__(self, key, value) -> None:
        self.writes.append((key, dict(value) if isinstance(value, dict) else value))
        super().__setitem__(key, value)


def test_run_job_keeps_web_call_id_and_passes_titles(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    cache = WorkerCache(tmp_path / "cache")
    seen: dict = {}

    class _FakeBackend:
        def analyze_with_embeddings(self, **kwargs):
            seen.update(kwargs)
            bundle = DesktopAnalysisBundle(
                fileSha256=kwargs["file_sha256"],
                episodeId=kwargs["episode_id"],
                durationSec=60.0,
                wordIndexBlob=WordIndexBlobDto(words=[WordDto(start=0.0, end=0.5, text="hi")], segments=[]),
                adSegments=[],
                whisperModel="ggml-large-v3-turbo.bin",
                analysisDurationMs=10,
                workerAccelerator="cuda",
            )
            return bundle, None

    async def _fake_download(audio_url, dest_path, on_bytes=None, **kwargs):
        dest_path.write_bytes(b"fake")
        return hashlib.sha256(b"call-id-audio").hexdigest(), "audio/mpeg"

    import hushcast_worker.audio as audio_mod

    web_entry = {
        "jobId": "job-call",
        "episodeId": "ep-call",
        "state": "QUEUED",
        "stage": "QUEUED",
        "progressPercent": 0,
        "stageLabel": "Queued for T4 GPU",
        "rev": 1,
        "createdAt": 1234.5,
        "heartbeatAt": 1234.5,
        "callId": "fc-123",
    }
    jd = _RecordingDict({"job-call": web_entry})
    monkeypatch.setattr(audio_mod, "download_audio_streaming_sha256", _fake_download)
    monkeypatch.setattr(modal_app, "job_dict", jd)

    worker = modal_app.GpuWorker()
    worker.cache = cache
    worker.backend = _FakeBackend()
    worker.run_job(
        "job-call",
        {
            "episodeId": "ep-call",
            "audioUrl": "https://example.com/call.mp3",
            "title": "NVIDIA Part III",
            "podcastTitle": "Acquired",
        },
    )

    final = jd["job-call"]
    assert final["state"] == "READY"
    assert final["callId"] == "fc-123"
    assert final["createdAt"] == 1234.5
    job_writes = [v for k, v in jd.writes if k == "job-call"]
    assert job_writes and all(v.get("callId") == "fc-123" for v in job_writes)
    assert jd.get_keys.count("job-call") == 1
    assert seen["episode_title"] == "NVIDIA Part III"
    assert seen["podcast_title"] == "Acquired"


def test_submit_and_cancel_use_aio_when_available(tmp_path: Path, monkeypatch) -> None:
    from types import SimpleNamespace

    modal_app = _import_modal_app_with_stub()
    events: list[str] = []
    cancelled: list[tuple[str, str]] = []

    class _Spawn:
        def __call__(self, job_id, req_dict):
            events.append("sync-spawn")
            return SimpleNamespace(object_id="fc-sync")

        async def aio(self, job_id, req_dict):
            events.append("aio-spawn")
            return SimpleNamespace(object_id="fc-aio")

    class _FakeGpuWorker:
        class _RunJob:
            spawn = _Spawn()

        run_job = _RunJob()

    class _Cancel:
        def __init__(self, cid: str) -> None:
            self.cid = cid

        def __call__(self) -> None:
            cancelled.append(("sync", self.cid))

        async def aio(self) -> None:
            cancelled.append(("aio", self.cid))

    class _FakeFunctionCall:
        def __init__(self, cid: str) -> None:
            self.cancel = _Cancel(cid)

        @classmethod
        def from_id(cls, cid: str):
            return cls(cid)

    monkeypatch.setattr(modal_app, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(modal_app, "job_dict", {})
    monkeypatch.setattr(modal_app, "GpuWorker", _FakeGpuWorker)
    monkeypatch.setattr(modal_app.modal, "FunctionCall", _FakeFunctionCall, raising=False)
    monkeypatch.setenv("HUSHCAST_WORKER_TOKEN", "test-token")

    headers = {"Authorization": "Bearer test-token"}
    with TestClient(modal_app.fastapi_app()) as client:
        resp = client.post(
            "/v1/jobs",
            headers=headers,
            json={"episodeId": "ep-aio", "audioUrl": "https://example.com/aio.mp3", "podcastTitle": "Show"},
        )
        assert resp.status_code == 200, resp.text
        job_id = resp.json()["jobId"]
        assert events == ["aio-spawn"]
        assert modal_app.job_dict[job_id]["callId"] == "fc-aio"

        resp = client.delete(f"/v1/jobs/{job_id}", headers=headers)
        assert resp.status_code == 200, resp.text
        assert resp.json()["state"] == "CANCELLED"
        assert cancelled == [("aio", "fc-aio")]


# --------------------------------------------------------------------------------------------
# 1.0.31 worker robustness: 3a (stale queue / late start), 3b (health), 3c (download progress),
# V17 (DELETE audio), V19 (pinned models, health schema), W11 (image pins vs uv.lock).
# --------------------------------------------------------------------------------------------

import re
import threading
import time
from types import SimpleNamespace

import pytest

from hushcast_worker.models import health_schema_problems

AUTH = {"Authorization": "Bearer test-token"}


def _bundle(sha: str, episode_id: str = "ep") -> DesktopAnalysisBundle:
    return DesktopAnalysisBundle(
        fileSha256=sha,
        episodeId=episode_id,
        durationSec=60.0,
        wordIndexBlob=WordIndexBlobDto(words=[WordDto(start=0.0, end=0.5, text="hi")], segments=[]),
        adSegments=[],
        whisperModel="faster-whisper large-v3-turbo",
        analysisDurationMs=10,
        workerAccelerator="cuda",
    )


class _ThreadDict(dict):
    """Records every Dict call with the thread that made it."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.ops: list[tuple[str, str, str, object]] = []  # (op, key, thread name, value copy)
        self.lock = threading.Lock()

    def get(self, key, default=None):
        with self.lock:
            self.ops.append(("get", key, threading.current_thread().name, None))
        return super().get(key, default)

    def __setitem__(self, key, value) -> None:
        with self.lock:
            self.ops.append(("set", key, threading.current_thread().name, dict(value) if isinstance(value, dict) else value))
        super().__setitem__(key, value)

    def sets(self, key):
        with self.lock:
            return [(thread, value) for op, k, thread, value in self.ops if op == "set" and k == key]


class _ReadyBackend:
    def __init__(self) -> None:
        self.calls = 0

    def analyze_with_embeddings(self, **kwargs):
        self.calls += 1
        return _bundle(kwargs["file_sha256"], kwargs["episode_id"]), None


def _worker(modal_app, tmp_path: Path, backend=None):
    worker = modal_app.GpuWorker()
    worker.cache = WorkerCache(tmp_path / "cache")
    worker.backend = backend or _ReadyBackend()
    return worker


def _web_client(modal_app, tmp_path: Path, monkeypatch, job_dict, gpu_worker=None):
    monkeypatch.setattr(modal_app, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(modal_app, "job_dict", job_dict)
    if gpu_worker is not None:
        monkeypatch.setattr(modal_app, "GpuWorker", gpu_worker)
    monkeypatch.setenv("HUSHCAST_WORKER_TOKEN", "test-token")
    return TestClient(modal_app.fastapi_app())


# ---- 3a ---------------------------------------------------------------------------------------

def test_queued_job_is_not_stale_for_20_minutes(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    assert modal_app.QUEUED_STALE_SEC == 1200.0
    now = time.time()
    base = {"episodeId": "ep", "state": "QUEUED", "stage": "QUEUED", "progressPercent": 0, "stageLabel": "Queued", "rev": 1}
    jd = {
        "waiting-10m": {**base, "jobId": "waiting-10m", "createdAt": now - 600, "heartbeatAt": now - 600},
        "waiting-22m": {**base, "jobId": "waiting-22m", "createdAt": now - 1320, "heartbeatAt": now - 1320},
    }
    with _web_client(modal_app, tmp_path, monkeypatch, jd) as client:
        assert client.get("/v1/jobs/waiting-10m", headers=AUTH).json()["state"] == "QUEUED"
        stale = client.get("/v1/jobs/waiting-22m", headers=AUTH).json()
        assert stale["state"] == "FAILED"
        assert stale["errorCode"] == "WORKER_STALE"


@pytest.mark.parametrize(
    "entry_state, cancel_key",
    [("FAILED", False), ("CANCELLED", False), ("QUEUED", True)],
)
def test_run_job_returns_at_once_when_given_up_or_cancelled(tmp_path: Path, monkeypatch, entry_state, cancel_key) -> None:
    modal_app = _import_modal_app_with_stub()
    import hushcast_worker.audio as audio_mod

    async def _must_not_download(*args, **kwargs):
        raise AssertionError("a given-up job must not download")

    entries = {
        "late": {"jobId": "late", "episodeId": "ep", "state": entry_state, "rev": 4, "createdAt": 1.0, "heartbeatAt": 1.0},
    }
    if cancel_key:
        entries["cancel:late"] = True
    jd = _ThreadDict(entries)
    monkeypatch.setattr(audio_mod, "download_audio_streaming_sha256", _must_not_download)
    monkeypatch.setattr(modal_app, "job_dict", jd)
    backend = _ReadyBackend()
    worker = _worker(modal_app, tmp_path, backend)

    worker.run_job("late", {"episodeId": "ep", "audioUrl": "https://example.com/late.mp3"})

    assert backend.calls == 0
    assert [op for op in jd.ops if op[0] == "set"] == []  # nothing written: the web tier's state stands
    assert jd["late"]["state"] == entry_state


# ---- 3c ---------------------------------------------------------------------------------------

def test_download_progress_flushed_by_heartbeat_not_event_loop(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    import hushcast_worker.audio as audio_mod

    monkeypatch.setattr(modal_app, "HEARTBEAT_DOWNLOAD_SEC", 0.01)
    monkeypatch.setattr(modal_app, "HEARTBEAT_SEC", 0.01)
    jd = _ThreadDict()
    monkeypatch.setattr(modal_app, "job_dict", jd)
    loop_threads: set[str] = set()
    ops_from_callback: list[int] = []

    async def _fake_download(audio_url, dest_path, on_bytes=None, **kwargs):
        me = threading.current_thread().name
        loop_threads.add(me)
        total = 100 * 1024 * 1024
        for i in range(1, 11):
            before = sum(1 for op in jd.ops if op[2] == me)
            on_bytes(i * total // 10, total)
            ops_from_callback.append(sum(1 for op in jd.ops if op[2] == me) - before)
            time.sleep(0.04)  # the download "takes time"; the heartbeat thread runs meanwhile
        dest_path.write_bytes(b"audio")
        return hashlib.sha256(b"heartbeat-audio").hexdigest(), "audio/mpeg"

    monkeypatch.setattr(audio_mod, "download_audio_streaming_sha256", _fake_download)
    worker = _worker(modal_app, tmp_path)
    worker.run_job("job-3c", {"episodeId": "ep-3c", "audioUrl": "https://example.com/3c.mp3"})

    assert ops_from_callback and all(n == 0 for n in ops_from_callback)  # callback never touches the Dict
    writes = jd.sets("job-3c")
    # Writes made while the download ran (fileSha256 is set right after it finishes).
    progress_writes = [
        (t, v) for t, v in writes
        if v["state"] == "DOWNLOADING" and v["progressPercent"] > 5 and "fileSha256" not in v
    ]
    assert progress_writes, "heartbeat should publish download progress"
    assert all(t.startswith("hb-") for t, _ in progress_writes)
    assert not any(t in loop_threads for t, _ in progress_writes)
    # rev moves whenever progress moved by >= 1%, never backwards
    revs = [v["rev"] for _, v in writes]
    assert revs == sorted(revs)
    seen_pcts = sorted({v["progressPercent"] for _, v in progress_writes})
    assert len(seen_pcts) >= 3
    for (_, prev), (_, cur) in zip(progress_writes, progress_writes[1:]):
        if abs(cur["progressPercent"] - prev["progressPercent"]) >= 1:
            assert cur["rev"] > prev["rev"]
    assert jd["job-3c"]["state"] == "READY"


# ---- 3b ---------------------------------------------------------------------------------------

def test_heartbeat_keeps_gpu_health_fresh_during_a_job(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    import hushcast_worker.audio as audio_mod

    monkeypatch.setattr(modal_app, "HEARTBEAT_DOWNLOAD_SEC", 0.01)
    monkeypatch.setattr(modal_app, "GPU_HEALTH_REFRESH_SEC", 0.02)
    jd = _ThreadDict()
    monkeypatch.setattr(modal_app, "job_dict", jd)

    async def _slow_download(audio_url, dest_path, on_bytes=None, **kwargs):
        time.sleep(0.25)
        dest_path.write_bytes(b"audio")
        return hashlib.sha256(b"health-audio").hexdigest(), "audio/mpeg"

    monkeypatch.setattr(audio_mod, "download_audio_streaming_sha256", _slow_download)
    worker = _worker(modal_app, tmp_path)
    worker._gpu_health_base = {"modelsReady": True, "modelsLoaded": True, "whisperModel": "faster-whisper large-v3-turbo", "chatModelReady": True}
    worker.run_job("job-hb", {"episodeId": "ep-hb", "audioUrl": "https://example.com/hb.mp3"})

    health_writes = [v for _, v in jd.sets("__gpu_health__")]
    refreshed = [v for t, v in jd.sets("__gpu_health__") if t.startswith("hb-")]
    assert refreshed, "the heartbeat thread should refresh __gpu_health__"
    assert all(v["activeJob"] == "ep-hb" and v["modelsReady"] is True for v in refreshed)
    stamps = [v["updatedAt"] for v in health_writes]
    assert stamps == sorted(stamps)
    assert health_writes[-1]["activeJob"] is None and health_writes[-1]["warm"] is True


class _StatsGpuWorker:
    """Stands in for modal's GpuWorker class handle on the web tier."""

    stats = SimpleNamespace(backlog=2, num_total_runners=1, num_running_inputs=1, input_headroom=0)

    class _RunJob:
        def spawn(self, job_id, req_dict):
            return SimpleNamespace(object_id="fc-x")

        def get_current_stats(self):
            return _StatsGpuWorker.stats

    run_job = _RunJob()


def test_health_reports_gpu_record_and_queue_stats(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    record = {
        "modelsReady": False,
        "modelsLoaded": True,
        "whisperModel": "faster-whisper large-v3-turbo",
        "chatModelReady": True,
        "updatedAt": time.time(),
        "warm": True,
        "activeJob": "ep-running",
    }
    with _web_client(modal_app, tmp_path, monkeypatch, {"__gpu_health__": record}, _StatsGpuWorker) as client:
        body = client.get("/v1/health").json()
    assert health_schema_problems(body, expect_deployment="modal") == []
    assert body["modelsReady"] is False  # from the GPU record, no longer hard-coded
    assert body["whisperModel"] == "faster-whisper large-v3-turbo"
    assert body["queueDepth"] == 2
    assert body["queuePosition"] == 3  # 2 waiting + 1 running
    assert body["coldStart"] is False
    assert body["modelsLoaded"] is True
    assert body["chatModelReady"] is True
    assert body["activeJob"] == "ep-running"


def test_health_without_stats_or_fresh_record_is_cold(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()

    class _NoStatsGpuWorker:
        class _RunJob:
            pass

        run_job = _RunJob()

    models_dir = tmp_path / "models"
    monkeypatch.setattr(modal_app, "MODELS_DIR", models_dir)
    stale = {"modelsReady": True, "modelsLoaded": True, "updatedAt": time.time() - 1000, "activeJob": "old"}
    with _web_client(modal_app, tmp_path, monkeypatch, {"__gpu_health__": stale}, _NoStatsGpuWorker) as client:
        body = client.get("/v1/health").json()
    assert body["queueDepth"] == 0 and body["queuePosition"] == 0
    assert body["coldStart"] is True
    assert body["modelsLoaded"] is False
    assert body["activeJob"] is None
    assert body["whisperModel"].startswith("faster-whisper")

    # No GPU record at all: modelsReady falls back to what's on the Volume.
    with _web_client(modal_app, tmp_path, monkeypatch, {}, _NoStatsGpuWorker) as client:
        assert client.get("/v1/health").json()["modelsReady"] is False
        for spec in [f for f in modal_app.MODEL_FILES if f.required] + [modal_app.AD_TAGGER_FILE]:
            (models_dir / spec.path).parent.mkdir(parents=True, exist_ok=True)
            (models_dir / spec.path).write_bytes(b"x")
        assert client.get("/v1/health").json()["modelsReady"] is True


def test_gpu_health_view_uses_exit_marker_and_aio_stats() -> None:
    import asyncio

    modal_app = _import_modal_app_with_stub()
    now = time.time()
    # @modal.exit wrote warm=False: cold even though the record is recent.
    view = modal_app._gpu_health_view({"updatedAt": now, "warm": False, "modelsLoaded": True}, None, now)
    assert view["coldStart"] is True and view["modelsLoaded"] is False and view["modelsReady"] is None

    calls: list[str] = []

    class _Stats:
        def __call__(self):
            calls.append("sync")

        async def aio(self):
            calls.append("aio")
            return SimpleNamespace(backlog=0, num_total_runners=0, num_running_inputs=0)

    class _AioGpuWorker:
        class _RunJob:
            get_current_stats = _Stats()

        run_job = _RunJob()

    orig = modal_app.GpuWorker
    modal_app.GpuWorker = _AioGpuWorker
    try:
        stats = asyncio.run(modal_app._gpu_queue_stats())
    finally:
        modal_app.GpuWorker = orig
    assert calls == ["aio"]
    assert stats == modal_app.GpuQueueStats(backlog=0, runners=0, running=0)
    assert modal_app._gpu_health_view({}, stats, now)["coldStart"] is True


# ---- V17 --------------------------------------------------------------------------------------

def test_modal_delete_audio_keeps_bundle(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    commits: list[str] = []

    class _Volume:
        def reload(self):
            commits.append("reload")

        def commit(self):
            commits.append("commit")

    monkeypatch.setattr(modal_app, "volume", _Volume())
    cache = WorkerCache(tmp_path / "cache")
    sha = hashlib.sha256(b"modal-delete").hexdigest()
    cache.audio_path(sha).write_bytes(b"AUDIO")
    cache.save_episode(sha256=sha, audio_tmp_path=None, bundle=_bundle(sha), audio_url="https://example.com/d.mp3", title="D")

    with _web_client(modal_app, tmp_path, monkeypatch, {}) as client:
        assert client.delete(f"/v1/episodes/{sha}/audio").status_code == 401
        assert client.delete(f"/v1/episodes/{sha}/audio", headers={"Authorization": "Bearer nope"}).status_code == 401

        res = client.delete(f"/v1/episodes/{sha}/audio", headers=AUTH)
        assert res.status_code == 204 and res.content == b""
        assert not cache.audio_path(sha).exists()
        assert commits == ["commit"]
        assert client.get(f"/v1/episodes/{sha}/bundle", headers=AUTH).status_code == 200
        assert client.get(f"/v1/episodes/{sha}/audio", headers=AUTH).status_code == 404

        # Already gone: still 204 (after one Volume reload to be sure), never 500.
        commits.clear()
        again = client.delete(f"/v1/episodes/{sha.upper()}/audio", headers=AUTH)
        assert again.status_code == 204
        assert commits == ["reload"]
        assert client.delete("/v1/episodes/not-a-sha/audio", headers=AUTH).status_code == 400
    assert cache.get_bundle(sha) is not None


# ---- V19 --------------------------------------------------------------------------------------

def test_model_downloads_are_pinned_with_sha256() -> None:
    modal_app = _import_modal_app_with_stub()
    specs = list(modal_app.MODEL_FILES) + [modal_app.AD_TAGGER_FILE]
    assert len({s.path for s in specs}) == len(specs)
    for spec in specs:
        assert re.fullmatch(r"[0-9a-f]{64}", spec.sha256), spec
        assert spec.size > 0
        assert "/resolve/main/" not in spec.url and "/main/" not in spec.url, spec.url
        if "huggingface.co" in spec.url:
            assert re.search(r"/resolve/[0-9a-f]{40}/", spec.url), spec.url
    assert "/v1.0.30-hushcast/" in modal_app.AD_TAGGER_FILE.url
    fw = [s for s in specs if s.path.startswith(str(modal_app.FASTER_WHISPER_SNAPSHOT))]
    assert {Path(s.path).name for s in fw} == {"config.json", "preprocessor_config.json", "model.bin", "tokenizer.json", "vocabulary.json"}


def test_ensure_models_pins_hf_cache_and_goes_offline(tmp_path: Path, monkeypatch) -> None:
    modal_app = _import_modal_app_with_stub()
    import hushcast_worker.storage as storage_mod

    requested: list[str] = []

    def _fake_download(url, dest, *, sha256, size=None, verifier=None, **kwargs):
        requested.append(url)
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(b"model")
        return True

    models_dir = tmp_path / "models"
    monkeypatch.setattr(modal_app, "MODELS_DIR", models_dir)
    monkeypatch.setattr(modal_app, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(modal_app, "BUNDLED_AD_TAGGER", tmp_path / "no-bundle.onnx")
    monkeypatch.setattr(storage_mod, "download_verified", _fake_download)

    status = modal_app._ensure_models()
    assert status == {"changed": True, "failed": []}
    assert set(requested) == {s.url for s in modal_app.MODEL_FILES} | {modal_app.AD_TAGGER_FILE.url}
    ref = models_dir / modal_app.FASTER_WHISPER_CACHE / "refs" / "main"
    assert ref.read_text() == modal_app.FASTER_WHISPER_REVISION

    # Second cold start: nothing to download, nothing to commit.
    monkeypatch.setattr(storage_mod, "download_verified", lambda *a, **k: False)
    assert modal_app._ensure_models() == {"changed": False, "failed": []}

    # An optional model (MMS_FA) may fail; a required one may not.
    def _fail_mms(url, dest, **kwargs):
        if url == modal_app.MMS_FA_URL:
            raise ValueError("Checksum mismatch")
        return False

    monkeypatch.setattr(storage_mod, "download_verified", _fail_mms)
    assert modal_app._ensure_models()["failed"] == ["torch/hub/checkpoints/model.pt"]

    def _fail_whisper(url, dest, **kwargs):
        if url == modal_app.WHISPER_URL:
            raise ValueError("Checksum mismatch")
        return False

    monkeypatch.setattr(storage_mod, "download_verified", _fail_whisper)
    with pytest.raises(RuntimeError, match="ggml-large-v3-turbo.bin"):
        modal_app._ensure_models()

    for key in ("HUSHCAST_FASTER_WHISPER_MODEL", "HUSHCAST_FASTER_WHISPER_REVISION", "HF_HUB_OFFLINE"):
        monkeypatch.delenv(key, raising=False)
    assert modal_app._use_local_faster_whisper() is True
    snapshot = str(models_dir / modal_app.FASTER_WHISPER_SNAPSHOT)
    assert os.environ["HUSHCAST_FASTER_WHISPER_MODEL"] == snapshot
    assert os.environ["HUSHCAST_FASTER_WHISPER_REVISION"] == modal_app.FASTER_WHISPER_REVISION
    assert os.environ["HF_HUB_OFFLINE"] == "1"
    assert modal_app._asr_model_label() == "faster-whisper large-v3-turbo"

    # A user-chosen model keeps the Hub online.
    monkeypatch.setenv("HUSHCAST_FASTER_WHISPER_MODEL", "distil-large-v3")
    monkeypatch.delenv("HF_HUB_OFFLINE")
    assert modal_app._use_local_faster_whisper() is False
    assert "HF_HUB_OFFLINE" not in os.environ
    assert modal_app._asr_model_label() == "faster-whisper distil-large-v3"


# ---- W11 --------------------------------------------------------------------------------------

def _lock_versions_for_py311() -> dict[str, str]:
    tomllib = pytest.importorskip("tomllib")
    lock = tomllib.loads((Path(__file__).resolve().parent.parent / "uv.lock").read_text(encoding="utf-8"))
    by_name: dict[str, list[dict]] = {}
    for pkg in lock["package"]:
        by_name.setdefault(pkg["name"], []).append(pkg)
    out: dict[str, str] = {}
    for name, entries in by_name.items():
        if len(entries) == 1:
            out[name] = entries[0]["version"]
            continue
        for pkg in entries:
            if "python_full_version == '3.11.*'" in pkg.get("resolution-markers", []):
                out[name] = pkg["version"]
    return out


def test_image_pins_match_uv_lock() -> None:
    modal_app = _import_modal_app_with_stub()
    locked = _lock_versions_for_py311()
    images = {
        "cpu": modal_app._pin_map(modal_app.CPU_IMAGE_PINS),
        "gpu": {
            **modal_app._pin_map(modal_app.GPU_IMAGE_PINS),
            "llama-cpp-python": modal_app.LLAMA_CPP_PYTHON_VERSION,
            "pywhispercpp": modal_app.PYWHISPERCPP_VERSION,
        },
    }
    checked = 0
    for image, pins in images.items():
        for name, version in pins.items():
            if name in locked:
                checked += 1
                assert version == locked[name], f"{image} image pins {name}=={version}, uv.lock (py3.11) has {locked[name]}"
    # Everything in the CPU image is a locked project dependency.
    assert set(images["cpu"]) <= set(locked)
    assert images["gpu"]["llama-cpp-python"] == locked["llama-cpp-python"]
    assert checked >= 18
    assert f'llama_cpp.__version__ == "{modal_app.LLAMA_CPP_PYTHON_VERSION}"' in modal_app._GPU_IMPORT_CHECK
