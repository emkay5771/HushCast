from __future__ import annotations

import asyncio
import socket
import uuid
from pathlib import Path
from typing import Awaitable, Callable, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import FileResponse

from . import __version__
from .models import (
    DesktopAnalysisBundle,
    DesktopHealthResponse,
    DesktopJobRequest,
    DesktopJobResponse,
    DesktopReadyEpisode,
    DesktopSearchRequest,
    DesktopSearchResponse,
    JobState,
)
from .pipeline import (
    AnalysisBackend,
    detect_accelerator,
    download_audio_streaming_sha256,
)
from .storage import WorkerCache

AudioDownloader = Callable[
    [str, Path, Optional[Callable[[int, int], None]]], Awaitable[tuple[str, str]]
]


def create_app(
    *,
    cache: WorkerCache,
    backend: AnalysisBackend,
    token: Optional[str] = None,
    downloader: AudioDownloader = download_audio_streaming_sha256,
) -> FastAPI:
    app = FastAPI(title="HushCast Desktop Worker", version=__version__)
    jobs: dict[str, DesktopJobResponse] = {}
    url_to_job_id: dict[str, str] = {}

    def verify_auth(
        authorization: Optional[str] = Header(default=None),
        x_hushcast_token: Optional[str] = Header(default=None),
    ) -> None:
        if not token:
            return
        bearer = None
        if authorization and authorization.lower().startswith("bearer "):
            bearer = authorization[7:].strip()
        provided = x_hushcast_token or bearer
        if provided != token:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or missing HushCast pairing token",
            )

    @app.get("/v1/health", response_model=DesktopHealthResponse)
    async def health() -> DesktopHealthResponse:
        whisper_name = backend.whisper_model_name() if hasattr(backend, "whisper_model_name") else ""
        chat_ready = backend.chat_model_ready() if hasattr(backend, "chat_model_ready") else False
        return DesktopHealthResponse(
            status="ok",
            version=__version__,
            hostname=socket.gethostname(),
            accelerator=detect_accelerator(),
            modelsReady=backend.models_ready(),
            whisperModel=whisper_name,
            chatModelReady=chat_ready,
            cachedEpisodes=len(cache.list_ready()),
        )

    @app.post(
        "/v1/jobs",
        response_model=DesktopJobResponse,
        dependencies=[Depends(verify_auth)],
    )
    async def submit_job(req: DesktopJobRequest) -> DesktopJobResponse:
        # 1. Fast-path: already analyzed & cached for this audioUrl
        existing_sha = cache.lookup_by_url(req.audioUrl)
        if existing_sha:
            job_id = str(uuid.uuid4())
            resp = DesktopJobResponse(
                jobId=job_id,
                episodeId=req.episodeId,
                state=JobState.READY,
                progressPercent=100,
                stageLabel="Cached on desktop",
                fileSha256=existing_sha,
            )
            jobs[job_id] = resp
            return resp

        # 2. Reuse in-flight job for same URL if still running
        in_flight_id = url_to_job_id.get(req.audioUrl)
        if in_flight_id and in_flight_id in jobs:
            existing_job = jobs[in_flight_id]
            if existing_job.state not in (JobState.READY, JobState.FAILED):
                return existing_job

        job_id = str(uuid.uuid4())
        job = DesktopJobResponse(
            jobId=job_id,
            episodeId=req.episodeId,
            state=JobState.QUEUED,
            progressPercent=0,
            stageLabel="Queued on desktop",
        )
        jobs[job_id] = job
        url_to_job_id[req.audioUrl] = job_id

        asyncio.create_task(_run_job(job_id, req))
        return job

    async def _run_job(job_id: str, req: DesktopJobRequest) -> None:
        tmp_audio = cache.tmp_dir / f"{job_id}.part"
        try:
            jobs[job_id] = jobs[job_id].model_copy(
                update={
                    "state": JobState.DOWNLOADING,
                    "progressPercent": 5,
                    "stageLabel": "Downloading episode on desktop",
                }
            )

            def on_dl_bytes(received: int, total: int) -> None:
                if total > 0:
                    pct = min(24, 5 + int((received * 19) / total))
                    jobs[job_id] = jobs[job_id].model_copy(
                        update={
                            "state": JobState.DOWNLOADING,
                            "progressPercent": pct,
                            "stageLabel": f"Downloading ({int(received * 100 / total)}%)",
                        }
                    )

            sha256, content_type = await downloader(req.audioUrl, tmp_audio, on_dl_bytes)

            # Check if another URL already produced this exact SHA-256
            if cache.has_complete(sha256):
                tmp_audio.unlink(missing_ok=True)
                jobs[job_id] = jobs[job_id].model_copy(
                    update={
                        "state": JobState.READY,
                        "progressPercent": 100,
                        "stageLabel": "Ready (matched existing SHA-256)",
                        "fileSha256": sha256,
                    }
                )
                return

            def on_ml_progress(state: JobState, pct: int, stage: str) -> None:
                jobs[job_id] = jobs[job_id].model_copy(
                    update={
                        "state": state,
                        "progressPercent": pct,
                        "stageLabel": stage,
                        "fileSha256": sha256,
                    }
                )

            if hasattr(backend, "analyze_with_embeddings"):
                bundle, embeddings = await asyncio.to_thread(
                    backend.analyze_with_embeddings,
                    audio_path=tmp_audio,
                    file_sha256=sha256,
                    episode_id=req.episodeId,
                    feed_transcript_raw=req.feedTranscriptRaw,
                    feed_transcript_mime=req.feedTranscriptMime,
                    on_progress=on_ml_progress,
                )
            else:
                bundle = await asyncio.to_thread(
                    backend.analyze,
                    audio_path=tmp_audio,
                    file_sha256=sha256,
                    episode_id=req.episodeId,
                    feed_transcript_raw=req.feedTranscriptRaw,
                    feed_transcript_mime=req.feedTranscriptMime,
                    on_progress=on_ml_progress,
                )
                embeddings = None

            cache.save_episode(
                sha256=sha256,
                audio_tmp_path=tmp_audio,
                bundle=bundle,
                audio_url=req.audioUrl,
                title=req.title,
                content_type=content_type,
                embeddings=embeddings,
            )

            jobs[job_id] = jobs[job_id].model_copy(
                update={
                    "state": JobState.READY,
                    "progressPercent": 100,
                    "stageLabel": "Ready",
                    "fileSha256": sha256,
                }
            )
        except Exception as exc:
            tmp_audio.unlink(missing_ok=True)
            jobs[job_id] = jobs[job_id].model_copy(
                update={
                    "state": JobState.FAILED,
                    "stageLabel": "Failed",
                    "errorMessage": f"{type(exc).__name__}: {exc}",
                }
            )

    @app.get(
        "/v1/jobs/{job_id}",
        response_model=DesktopJobResponse,
        dependencies=[Depends(verify_auth)],
    )
    async def get_job(job_id: str) -> DesktopJobResponse:
        job = jobs.get(job_id)
        if not job:
            raise HTTPException(status_code=404, detail="Job not found")
        return job

    @app.get(
        "/v1/episodes",
        response_model=list[DesktopReadyEpisode],
        dependencies=[Depends(verify_auth)],
    )
    async def list_episodes() -> list[DesktopReadyEpisode]:
        return cache.list_ready()

    @app.get(
        "/v1/episodes/{sha256}/bundle",
        response_model=DesktopAnalysisBundle,
        dependencies=[Depends(verify_auth)],
    )
    async def get_bundle(sha256: str) -> DesktopAnalysisBundle:
        bundle = cache.get_bundle(sha256)
        if not bundle:
            raise HTTPException(status_code=404, detail="Bundle not found")
        return bundle

    @app.get(
        "/v1/episodes/{sha256}/audio",
        dependencies=[Depends(verify_auth)],
    )
    async def get_audio(sha256: str, request: Request) -> FileResponse:
        audio = cache.audio_path(sha256)
        if not audio.exists():
            raise HTTPException(status_code=404, detail="Audio not found")
        return FileResponse(
            path=audio,
            media_type=cache.get_content_type(sha256),
            headers={"X-HushCast-Sha256": sha256},
        )

    @app.post(
        "/v1/search",
        response_model=DesktopSearchResponse,
        dependencies=[Depends(verify_auth)],
    )
    async def semantic_search(req: DesktopSearchRequest) -> DesktopSearchResponse:
        q = req.query.strip()
        if not q:
            return DesktopSearchResponse(query=req.query, hits=[])
        if not hasattr(backend, "embed_query"):
            raise HTTPException(
                status_code=status.HTTP_501_NOT_IMPLEMENTED,
                detail="Backend does not support semantic search",
            )
        query_vec = await asyncio.to_thread(backend.embed_query, q)
        hits = cache.semantic_search(query_vec, top_k=req.topK)
        return DesktopSearchResponse(query=req.query, hits=hits)

    return app
