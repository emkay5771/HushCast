from __future__ import annotations

import asyncio
import shutil
import socket
import sys
import time
import uuid
from datetime import datetime
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


class _JobConsoleLogger:
    """Single-line, non-wrapping CLI progress bar & stage logger."""

    _SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

    def __init__(self, job_id: str, title: str) -> None:
        self.short_id = job_id[:8]
        self.label = (title or "Episode").strip()
        if len(self.label) > 24:
            self.label = self.label[:21] + "..."
        self.is_tty = hasattr(sys.stdout, "isatty") and sys.stdout.isatty()
        self.last_state: Optional[JobState] = None
        self.last_milestone_msg: str = ""
        self.last_draw_ts: float = 0.0
        self.spin_idx: int = 0
        self.t_start = time.monotonic()

    @staticmethod
    def _ts() -> str:
        return datetime.now().strftime("%H:%M:%S")

    @staticmethod
    def _bar(pct: int, width: int = 20) -> str:
        clamped = max(0, min(100, pct))
        filled = int(round(width * clamped / 100.0))
        return "█" * filled + "░" * (width - filled)

    @staticmethod
    def _fit_line(text: str) -> str:
        cols = shutil.get_terminal_size((100, 24)).columns
        max_len = max(40, cols - 1)
        if len(text) <= max_len:
            return text
        return text[: max_len - 3] + "..."

    def update(self, state: JobState, pct: int, stage: str) -> None:
        bar = self._bar(pct)
        state_changed = state != self.last_state
        milestone = state_changed or any(
            kw in stage
            for kw in ("Transcribed ", "Detected ", "Found ", "Generated ")
        )
        if milestone and stage != self.last_milestone_msg:
            if self.is_tty:
                sys.stdout.write("\r\033[K")
            line = f"[{self._ts()}] [{self.short_id}] {self.label} • {stage}"
            print(line, flush=True)
            self.last_state = state
            self.last_milestone_msg = stage

        if not self.is_tty:
            return

        now = time.monotonic()
        if not state_changed and pct < 100 and (now - self.last_draw_ts) < 0.12:
            return
        self.last_draw_ts = now
        spin = self._SPINNER[self.spin_idx % len(self._SPINNER)]
        self.spin_idx += 1
        raw = f"{spin} [{self.short_id}] {state.value:<12} [{bar}] {pct:3d}% | {stage}"
        sys.stdout.write("\r\033[K" + self._fit_line(raw))
        sys.stdout.flush()

    def finish(self, summary_line: str) -> None:
        elapsed = time.monotonic() - self.t_start
        if self.is_tty:
            sys.stdout.write("\r\033[K")
        print(f"[{self._ts()}] [{self.short_id}] ✅ {self.label} • Done in {elapsed:.1f}s | {summary_line}", flush=True)

    def fail(self, error: str) -> None:
        elapsed = time.monotonic() - self.t_start
        if self.is_tty:
            sys.stdout.write("\r\033[K")
        print(f"[{self._ts()}] [{self.short_id}] ❌ {self.label} • Failed after {elapsed:.1f}s | {error}", flush=True)


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
        if not req.force:
            # 1. Fast-path: already analyzed & cached for this audioUrl (with current pipeline version)
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
                print(
                    f"[{_JobConsoleLogger._ts()}] [{job_id[:8]}] ⚡ Cache hit for '{req.title or req.episodeId}' (sha={existing_sha[:12]})",
                    flush=True,
                )
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
            stageLabel="Reprocessing on desktop" if req.force else "Queued on desktop",
        )
        jobs[job_id] = job
        url_to_job_id[req.audioUrl] = job_id

        asyncio.create_task(_run_job(job_id, req))
        return job

    async def _run_job(job_id: str, req: DesktopJobRequest) -> None:
        tmp_audio = cache.tmp_dir / f"{job_id}.part"
        cli_log = _JobConsoleLogger(job_id, req.title or req.episodeId)
        try:
            cli_log.update(JobState.DOWNLOADING, 5, "Downloading episode audio…")
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
                    dl_pct = int(received * 100 / total)
                    mb_rec = received / (1024 * 1024)
                    mb_tot = total / (1024 * 1024)
                    cli_log.update(JobState.DOWNLOADING, pct, f"Downloading {mb_rec:.1f}/{mb_tot:.1f} MB ({dl_pct}%)")
                    jobs[job_id] = jobs[job_id].model_copy(
                        update={
                            "state": JobState.DOWNLOADING,
                            "progressPercent": pct,
                            "stageLabel": f"Downloading ({dl_pct}%)",
                        }
                    )

            sha256, content_type = await downloader(req.audioUrl, tmp_audio, on_dl_bytes)

            # Check if another URL already produced this exact SHA-256 with current pipeline
            if not req.force and cache.has_complete(sha256, require_current_pipeline=True):
                tmp_audio.unlink(missing_ok=True)
                cli_log.finish(f"Matched cached SHA-256 ({sha256[:12]})")
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
                cli_log.update(state, pct, stage)
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

            dur_m = int(bundle.durationSec // 60)
            dur_s = int(bundle.durationSec % 60)
            cli_log.finish(
                f"{dur_m}m{dur_s:02d}s audio • {len(bundle.wordIndexBlob.segments)} segments • "
                f"{len(bundle.adSegments)} ads • {len(bundle.chapters)} chapters"
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
            cli_log.fail(f"{type(exc).__name__}: {exc}")
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

    @app.delete(
        "/v1/cache",
        dependencies=[Depends(verify_auth)],
    )
    async def clear_cache(audio_only: bool = False) -> dict[str, int]:
        return cache.clear(audio_only=audio_only)

    return app
