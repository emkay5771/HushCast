from __future__ import annotations

import hashlib
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

