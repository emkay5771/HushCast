"""Modal serverless GPU deployment for HushCast Desktop/Cloud Worker.

Architecture (V1 / B10 / V2):
- CPU-only `@modal.asgi_app` serves API, long-polling, search, and cached bundle/audio downloads.
- `@app.cls(gpu="T4")` loads ML models once in `@modal.enter` and runs jobs via `.spawn()`.
- Job state is shared via `modal.Dict("hushcast-jobs")` so polling never keeps the GPU warm
  and jobs survive client disconnects or web container scale-downs.

Quickstart:
    pip install modal
    modal setup
    modal secret create hushcast-token HUSHCAST_WORKER_TOKEN="your-secret-token"
    modal run modal_app.py::setup_models
    modal deploy modal_app.py
"""

import hashlib
import logging
import os
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Optional

import modal

DATA_DIR = Path("/data")
MODELS_DIR = DATA_DIR / "models"
CACHE_DIR = DATA_DIR / "cache"
BUNDLED_AD_TAGGER = Path("/opt/hushcast/ad_tagger.onnx")

WHISPER_URL = (
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    "ggml-large-v3-turbo.bin"
)
QWEN_URL = (
    "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/c80565f1b529c2b2f58f48c0a6e1b2f30f22c2a3/"
    "Qwen3-Embedding-0.6B-Q8_0.gguf"
)
# W7: Smaller 3B instruct model (~2 GB vs 4.7 GB) with JSON mode
CHAT_URL = (
    "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/"
    "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
)

app = modal.App("hushcast-worker")
volume = modal.Volume.from_name("hushcast-data", create_if_missing=True)
job_dict = modal.Dict.from_name("hushcast-jobs", create_if_missing=True)

_AD_TAGGER_URL = (
    "https://raw.githubusercontent.com/emkay5771/HushCast/main/"
    "model/ad_tagger.onnx"
)
_LOCAL_AD_TAGGER_CANDIDATES = [
    Path(__file__).resolve().parent.parent / "model/ad_tagger.onnx",
    Path(__file__).resolve().parent.parent / "core/automute-engine/src/main/assets/ad_tagger.onnx",
    Path(__file__).resolve().parent / "ad_tagger.onnx",
    Path.home() / ".hushcast-worker/models/ad_tagger.onnx",
]
_LOCAL_AD_TAGGER = next((p for p in _LOCAL_AD_TAGGER_CANDIDATES if p.exists()), None)

# V19: Lightweight CPU image for fast API/polling/audio serving cold starts
_cpu_image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install(
        "fastapi==0.115.12",
        "uvicorn[standard]==0.34.2",
        "httpx==0.28.1",
        "pydantic==2.11.3",
        "zeroconf==0.146.5",
        "numpy==2.2.5",
        "onnxruntime==1.20.1",
        "qrcode==8.0",
    )
    .add_local_python_source("hushcast_worker")
)

# V14 / V19 / W3 / W11: Heavy CUDA builds ordered first, app source last
_gpu_base_image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.4.1-devel-ubuntu22.04", add_python="3.11"
    )
    .apt_install("ffmpeg", "cmake", "build-essential", "git", "curl")
    .env({
        "GGML_CUDA": "1",
        "CMAKE_ARGS": "-DGGML_CUDA=1 -DCMAKE_CUDA_ARCHITECTURES=75;89 -DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "CMAKE_POLICY_VERSION_MINIMUM": "3.5",
        "TORCH_HOME": "/data/models/torch",
        "HUSHCAST_ASR_ENGINE": "faster-whisper",
    })
    .pip_install(
        "pywhispercpp==1.5.1",
        extra_options="--no-binary pywhispercpp",
    )
    .pip_install(
        "llama-cpp-python==0.3.19",
        extra_options="--extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124",
    )
    .pip_install(
        "torch==2.5.1",
        "torchaudio==2.5.1",
        extra_options="--index-url https://download.pytorch.org/whl/cu124",
    )
    .pip_install(
        "faster-whisper==1.2.1",
        "fastapi==0.115.12",
        "uvicorn[standard]==0.34.2",
        "httpx==0.28.1",
        "pydantic==2.11.3",
        "zeroconf==0.146.5",
        "numpy==2.2.5",
        "onnxruntime-gpu==1.20.2",
        "av==14.2.0",
        "qrcode==8.0",
    )
    .run_commands(
        "find /usr/local/lib/python3.11/site-packages -name 'libwhisper*.so*' -o -name 'libggml*.so*' | xargs -r -n1 dirname | sort -u > /etc/ld.so.conf.d/hushcast-cuda.conf",
        "ldconfig",
        "mkdir -p /tmp/cuda-stubs && ln -sf /usr/local/cuda/lib64/stubs/libcuda.so /tmp/cuda-stubs/libcuda.so.1 && LD_LIBRARY_PATH=/tmp/cuda-stubs:${LD_LIBRARY_PATH:-} python3 -c 'import av, numpy, onnxruntime, llama_cpp, torch, torchaudio, faster_whisper; from pywhispercpp.model import Model; print(\"ML imports OK\")' && rm -rf /tmp/cuda-stubs",
    )
)
if _LOCAL_AD_TAGGER is not None:
    _gpu_base_image = _gpu_base_image.add_local_file(
        str(_LOCAL_AD_TAGGER),
        remote_path=str(BUNDLED_AD_TAGGER),
    )

gpu_image = _gpu_base_image.add_local_python_source("hushcast_worker")


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _ensure_models() -> None:
    import httpx

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    # V19: Compare SHA-256 so updated ad_tagger.onnx reaches production Volume
    ad_dest = MODELS_DIR / "ad_tagger.onnx"
    if BUNDLED_AD_TAGGER.exists():
        if not ad_dest.exists() or _sha256_file(BUNDLED_AD_TAGGER) != _sha256_file(ad_dest):
            shutil.copy2(BUNDLED_AD_TAGGER, ad_dest)
            print(f"Updated {ad_dest} from bundled asset")

    downloads = [
        (MODELS_DIR / "ggml-large-v3-turbo.bin", WHISPER_URL),
        (MODELS_DIR / "Qwen3-Embedding-0.6B-Q8_0.gguf", QWEN_URL),
        (MODELS_DIR / "Qwen2.5-3B-Instruct-Q4_K_M.gguf", CHAT_URL),
    ]
    if not ad_dest.exists():
        downloads.append((ad_dest, _AD_TAGGER_URL))
    for dest, url in downloads:
        if dest.exists() and dest.stat().st_size > 0:
            continue
        tmp = dest.with_suffix(".part")
        print(f"Downloading {dest.name} from {url} ...")
        with httpx.stream("GET", url, follow_redirects=True, timeout=600.0) as resp:
            resp.raise_for_status()
            with tmp.open("wb") as f:
                for chunk in resp.iter_bytes(chunk_size=1024 * 1024):
                    f.write(chunk)
        os.replace(tmp, dest)
        print(f"Saved {dest} ({dest.stat().st_size / (1024 * 1024):.1f} MB)")

    # V7: Pre-fetch MMS_FA weights into TORCH_HOME so first job doesn't download them
    try:
        from torchaudio.pipelines import MMS_FA

        MMS_FA.get_model(with_star=False)
    except Exception as exc:
        print(f"MMS_FA pre-fetch skipped: {exc}")


@app.function(
    image=gpu_image,
    volumes={str(DATA_DIR): volume},
    timeout=1800,
)
def setup_models() -> None:
    """One-time model download into the persistent Modal Volume."""
    _ensure_models()
    volume.commit()
    print("All HushCast models are ready in Modal Volume 'hushcast-data'.")


@app.cls(
    image=gpu_image,
    gpu="T4",
    cpu=4.0,
    memory=16384,
    volumes={str(DATA_DIR): volume},
    secrets=[modal.Secret.from_name("hushcast-token")],
    max_containers=1,
    scaledown_window=120,
    timeout=900,
)
class GpuWorker:
    """Dedicated T4 GPU worker that warms up once per container and runs spawned jobs."""

    @modal.enter()
    def enter(self) -> None:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        )
        _ensure_models()
        from hushcast_worker.pipeline import LocalMlBackend
        from hushcast_worker.storage import WorkerCache

        self.cache = WorkerCache(CACHE_DIR)
        self.backend = LocalMlBackend(MODELS_DIR)
        self.backend.warmup()

    @modal.method()
    def health_info(self) -> dict[str, Any]:
        return {
            "modelsReady": self.backend.models_ready(),
            "modelsLoaded": self.backend.models_loaded(),
            "whisperModel": self.backend.whisper_model_name(),
            "chatModelReady": self.backend.chat_model_ready(),
        }

    @modal.method()
    def embed_query(self, text: str) -> list[float]:
        return self.backend.embed_query(text)

    @modal.method()
    def run_job(self, job_id: str, req_dict: dict[str, Any]) -> None:
        import asyncio
        from hushcast_worker.audio import download_audio_streaming_sha256
        from hushcast_worker.models import DesktopJobRequest, JobStage, JobState, PIPELINE_VERSION
        from hushcast_worker.server import _classify_error, _infer_stage

        req = DesktopJobRequest(**req_dict)
        cancel_event = threading.Event()

        def _update_state(**updates: Any) -> None:
            cur = job_dict.get(job_id) or {}
            cur.update(updates)
            cur["rev"] = int(cur.get("rev", 0)) + 1
            job_dict[job_id] = cur
            if job_dict.get(f"cancel:{job_id}"):
                cancel_event.set()

        def _on_progress(state: JobState, pct: int, label: str) -> None:
            if job_dict.get(f"cancel:{job_id}"):
                cancel_event.set()
                raise RuntimeError("Job cancelled")
            stage = _infer_stage(state, label)
            _update_state(
                state=state.value,
                progressPercent=pct,
                stageLabel=label,
                stage=stage.value,
                stageProgress=pct,
            )

        try:
            if req.reprocessStage:
                sha = self.cache.lookup_by_episode_id(req.episodeId, req.audioUrl)
                if sha is None:
                    volume.reload()
                    self.cache._index = self.cache._load_index()
                    sha = self.cache.lookup_by_episode_id(req.episodeId, req.audioUrl)
                existing = self.cache.get_bundle(sha) if sha else None
                if existing is None:
                    raise RuntimeError("No cached transcript found for reprocessing")
                audio_path = self.cache.audio_path(existing.fileSha256)
                if not audio_path.exists():
                    audio_path = None
                existing_emb = self.cache.load_embeddings(existing.fileSha256)
                bundle, emb_2d = self.backend.reprocess_bundle(
                    stage=req.reprocessStage,
                    existing_bundle=existing,
                    audio_path=audio_path,
                    existing_embeddings=existing_emb,
                    on_progress=_on_progress,
                    cancel_event=cancel_event,
                )
                self.cache.save_reprocessed(
                    sha256=bundle.fileSha256,
                    bundle=bundle,
                    embeddings=emb_2d,
                )
                volume.commit()
                _update_state(
                    state=JobState.READY.value,
                    progressPercent=100,
                    stageLabel="Ready",
                    stage=JobStage.READY.value,
                    stageProgress=100,
                    fileSha256=bundle.fileSha256,
                    etaSec=0,
                )
                return

            _on_progress(JobState.DOWNLOADING, 5, "Downloading episode audio")
            tmp_path = self.cache.root_dir / f"dl_{job_id}_{uuid.uuid4().hex[:8]}.part"

            def _dl_cb(rec: int, tot: int) -> None:
                if job_dict.get(f"cancel:{job_id}"):
                    cancel_event.set()
                    raise RuntimeError("Job cancelled")
                if tot > 0:
                    pct = 5 + int(15 * min(1.0, rec / tot))
                    _update_state(
                        state=JobState.DOWNLOADING.value,
                        progressPercent=pct,
                        stageLabel=f"Downloading audio ({rec // (1024 * 1024)} MB)",
                        stage=JobStage.DOWNLOADING.value,
                        stageProgress=pct,
                    )

            sha256, content_type = asyncio.run(
                download_audio_streaming_sha256(req.audioUrl, tmp_path, on_bytes=_dl_cb, allow_lan_feeds=False)
            )
            audio_path = self.cache.stage_audio(
                sha256=sha256,
                audio_tmp_path=tmp_path,
                content_type=content_type,
                audio_url=req.audioUrl,
                title=req.title,
                episode_id=req.episodeId,
            )
            _update_state(fileSha256=sha256)

            existing = None if req.force else self.cache.get_bundle(sha256)
            if existing is not None and existing.pipelineVersion >= PIPELINE_VERSION:
                volume.commit()
                _update_state(
                    state=JobState.READY.value,
                    progressPercent=100,
                    stageLabel="Ready (cached)",
                    stage=JobStage.READY.value,
                    stageProgress=100,
                    fileSha256=sha256,
                    etaSec=0,
                )
                return

            bundle, emb_2d = self.backend.analyze_with_embeddings(
                audio_path=audio_path,
                file_sha256=sha256,
                episode_id=req.episodeId,
                feed_transcript_raw=req.feedTranscriptRaw,
                feed_transcript_mime=req.feedTranscriptMime,
                on_progress=_on_progress,
                language=req.language,
                feed_transcript_url=req.feedTranscriptUrl,
                feed_chapters_url=req.feedChaptersUrl,
                feed_chapters_type=req.feedChaptersType,
                cancel_event=cancel_event,
            )
            self.cache.save_episode(
                sha256=sha256,
                audio_tmp_path=None,
                bundle=bundle,
                audio_url=req.audioUrl,
                title=req.title or req.episodeId,
                content_type=content_type,
                embeddings=emb_2d,
            )
            volume.commit()
            _update_state(
                state=JobState.READY.value,
                progressPercent=100,
                stageLabel="Ready",
                stage=JobStage.READY.value,
                stageProgress=100,
                fileSha256=sha256,
                etaSec=0,
            )
        except Exception as exc:
            if cancel_event.is_set() or "cancelled" in str(exc).lower():
                _update_state(
                    state=JobState.CANCELLED.value,
                    stageLabel="Cancelled",
                    stage=JobStage.CANCELLED.value,
                )
            else:
                code, retryable = _classify_error(exc)
                _update_state(
                    state=JobState.FAILED.value,
                    stageLabel="Failed",
                    stage=JobStage.FAILED.value,
                    errorMessage=str(exc),
                    errorCode=code,
                    retryable=retryable,
                )


@app.function(
    image=_cpu_image,
    cpu=1.0,
    memory=1024,
    volumes={str(DATA_DIR): volume},
    secrets=[modal.Secret.from_name("hushcast-token")],
    max_containers=2,
    scaledown_window=120,
    timeout=300,
)
@modal.concurrent(max_inputs=20)
@modal.asgi_app()
def fastapi_app():
    """Lightweight CPU-only FastAPI app for health, polling, search, and cached bundle/audio serving."""
    import asyncio
    from fastapi import Body, Depends, FastAPI, Header, HTTPException, Query, Request
    from fastapi.middleware.gzip import GZipMiddleware
    from fastapi.responses import FileResponse, Response
    from hushcast_worker import __version__
    from hushcast_worker.models import (
        PIPELINE_VERSION,
        DesktopHealthResponse,
        DesktopJobRequest,
        DesktopJobResponse,
        JobStage,
        JobState,
    )
    from hushcast_worker.storage import WorkerCache

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    token = (os.environ.get("HUSHCAST_WORKER_TOKEN") or "").strip()
    if not token:
        raise RuntimeError(
            "HUSHCAST_WORKER_TOKEN secret is empty or missing. "
            "Run: modal secret create hushcast-token HUSHCAST_WORKER_TOKEN=<token>"
        )

    cache = WorkerCache(CACHE_DIR)
    web = FastAPI(
        title="HushCast Cloud Worker",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    web.add_middleware(GZipMiddleware, minimum_size=1024)

    def _require_auth(authorization: Optional[str] = Header(default=None)) -> None:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Missing bearer token")
        if authorization.removeprefix("Bearer ").strip() != token:
            raise HTTPException(status_code=401, detail="Invalid bearer token")

    @web.get("/v1/health", response_model=DesktopHealthResponse)
    def health() -> DesktopHealthResponse:
        cache_bytes, disk_free_bytes = cache.cache_stats()
        return DesktopHealthResponse(
            version=__version__,
            apiVersion=2,
            pipelineVersion=PIPELINE_VERSION,
            hostname="modal-cloud",
            accelerator="cuda",
            deployment="modal",
            gpuName="NVIDIA T4",
            modelsReady=True,
            modelsLoaded=True,
            whisperModel="ggml-large-v3-turbo.bin",
            chatModelReady=True,
            cachedEpisodes=len(cache.list_ready()),
            queueDepth=0,
            cacheBytes=cache_bytes,
            diskFreeBytes=disk_free_bytes,
        )

    @web.post("/v1/jobs", response_model=DesktopJobResponse, response_model_exclude_none=True)
    def submit_job(req: DesktopJobRequest = Body(...), _: None = Depends(_require_auth)) -> DesktopJobResponse:
        if req.reprocessStage:
            sha = cache.lookup_by_episode_id(req.episodeId, req.audioUrl)
            if sha is None:
                volume.reload()
                cache._index = cache._load_index()
                sha = cache.lookup_by_episode_id(req.episodeId, req.audioUrl)
            if sha is None:
                raise HTTPException(status_code=404, detail="No cached transcript found for episode")

        job_id = uuid.uuid4().hex[:16]
        initial = {
            "jobId": job_id,
            "episodeId": req.episodeId,
            "state": JobState.QUEUED.value,
            "progressPercent": 0,
            "stageLabel": "Queued for T4 GPU",
            "stage": JobStage.QUEUED.value,
            "stageProgress": 0,
            "rev": 1,
        }
        job_dict[job_id] = initial
        GpuWorker().run_job.spawn(job_id, req.model_dump())
        return DesktopJobResponse(**initial)

    @web.get("/v1/jobs/{job_id}", response_model=DesktopJobResponse, response_model_exclude_none=True)
    async def get_job(
        job_id: str,
        waitMs: int = Query(default=0, ge=0, le=30000),
        sinceRev: int = Query(default=-1),
        _: None = Depends(_require_auth),
    ) -> DesktopJobResponse:
        deadline = time.monotonic() + (waitMs / 1000.0)
        while True:
            data = job_dict.get(job_id)
            if data is None:
                raise HTTPException(status_code=404, detail="Job not found")
            state = data.get("state")
            rev = int(data.get("rev", 0))
            if waitMs <= 0 or state in ("READY", "FAILED", "CANCELLED") or rev > sinceRev or time.monotonic() >= deadline:
                return DesktopJobResponse(**data)
            await asyncio.sleep(0.35)

    @web.delete("/v1/jobs/{job_id}", response_model=DesktopJobResponse, response_model_exclude_none=True)
    def cancel_job(job_id: str, _: None = Depends(_require_auth)) -> DesktopJobResponse:
        data = job_dict.get(job_id)
        if data is None:
            raise HTTPException(status_code=404, detail="Job not found")
        job_dict[f"cancel:{job_id}"] = True
        if data.get("state") not in ("READY", "FAILED", "CANCELLED"):
            data["state"] = JobState.CANCELLED.value
            data["stage"] = JobStage.CANCELLED.value
            data["stageLabel"] = "Cancelled"
            data["rev"] = int(data.get("rev", 0)) + 1
            job_dict[job_id] = data
        return DesktopJobResponse(**data)

    @web.get("/v1/episodes")
    def list_episodes(_: None = Depends(_require_auth)):
        volume.reload()
        return cache.list_ready()

    @web.get("/v1/episodes/{key}/bundle")
    def get_bundle(key: str, _: None = Depends(_require_auth)):
        path = cache.bundle_path(key)
        if not path.exists():
            volume.reload()
        if not path.exists():
            raise HTTPException(status_code=404, detail="Bundle not found")
        return FileResponse(path, media_type="application/json", headers={"ETag": f'"{key}"'})

    @web.get("/v1/episodes/{sha256}/audio")
    def get_audio(sha256: str, request: Request, _: None = Depends(_require_auth)):
        path = cache.audio_path(sha256)
        if not path.exists():
            volume.reload()
        if not path.exists():
            raise HTTPException(status_code=404, detail="Audio not cached")
        cache.touch_audio(sha256)
        return FileResponse(path, media_type="audio/mpeg", headers={"ETag": f'"{sha256}"'})

    @web.get("/v1/search")
    def search(q: str = Query(min_length=2), limit: int = Query(default=20, ge=1, le=100), _: None = Depends(_require_auth)):
        volume.reload()
        q_vec = GpuWorker().embed_query.remote(q)
        return cache.semantic_search(q_vec, top_k=limit)

    return web


@app.local_entrypoint()
def pair_qr(url: str, token: str = "") -> None:
    """Print a scannable hushcast://pair QR code for your deployed Modal URL."""
    from hushcast_worker.pairing import print_pairing_qr

    print("HushCast Modal Worker")
    print(f"  URL:           {url.strip()}")
    if token:
        print(f"  Pairing Token: {token}")
    print_pairing_qr(url, token)
