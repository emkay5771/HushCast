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
