from __future__ import annotations

import asyncio
import hmac
import logging
import re
import shutil
import socket
import sys
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Awaitable, Callable, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import FileResponse

from . import __version__
from .audio import download_audio_streaming_sha256
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
from .pipeline import AnalysisBackend, detect_accelerator
from .storage import WorkerCache

logger = logging.getLogger("hushcast_worker.server")

AudioDownloader = Callable[
    [str, Path, Optional[Callable[[int, int], None]]], Awaitable[tuple[str, str]]
]

_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_CTRL_RE = re.compile(r"[\x00-\x1f\x7f-\x9f]|\x1b\[[0-9;]*[A-Za-z]")


def _sanitize_log_text(text: str, max_len: int = 80) -> str:
    cleaned = _CTRL_RE.sub("", text or "").strip()
    if len(cleaned) > max_len:
        return cleaned[: max_len - 3] + "..."
    return cleaned


def _validate_sha256(sha256: str) -> str:
    if not _SHA256_RE.match(sha256):
        raise HTTPException(status_code=400, detail="Invalid sha256 parameter")
    return sha256.lower()


class _JobConsoleLogger:
    """Single-line, non-wrapping CLI progress bar & stage logger."""

    _SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

    def __init__(self, job_id: str, title: str) -> None:
        self.short_id = job_id[:8]
        self.label = _sanitize_log_text(title or "Episode", max_len=24)
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
            logger.info("[%s] %s • %s", self.short_id, self.label, stage)
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
        logger.info("[%s] Done in %.1fs | %s | %s", self.short_id, elapsed, self.label, summary_line)

    def fail(self, error: str) -> None:
        elapsed = time.monotonic() - self.t_start
        if self.is_tty:
            sys.stdout.write("\r\033[K")
        logger.error("[%s] Failed after %.1fs | %s | %s", self.short_id, elapsed, self.label, error)


def create_app(
    *,
    cache: WorkerCache,
    backend: AnalysisBackend,
    token: Optional[str] = None,
    allow_insecure_no_auth: bool = False,
    allow_lan_feeds: bool = False,
    public_deployment: bool = False,
    downloader: Optional[AudioDownloader] = None,
    on_state_change: Optional[Callable[[], None]] = None,
) -> FastAPI:
    if not token and not allow_insecure_no_auth:
        raise ValueError(
            "Worker token is required. Set HUSHCAST_WORKER_TOKEN or pass allow_insecure_no_auth=True."
        )

    if downloader is None:
        async def _default_downloader(url: str, dest: Path, on_bytes):
            return await download_audio_streaming_sha256(
                url, dest, on_bytes, allow_lan_feeds=allow_lan_feeds
            )
        active_downloader = _default_downloader
    else:
        active_downloader = downloader

    app = FastAPI(title="HushCast Desktop Worker", version=__version__)
    jobs: dict[str, DesktopJobResponse] = cache.load_jobs()
    for _jid, _job in list(jobs.items()):
        if _job.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
            jobs[_jid] = _job.model_copy(
                update={
                    "state": JobState.FAILED,
                    "stageLabel": "Worker restarted",
                    "errorMessage": "Worker restarted before job completed; please retry",
                }
            )
            cache.save_job(jobs[_jid])
    url_to_job_id: dict[str, str] = {}
    episode_to_job_id: dict[str, str] = {}
    last_job_save_ts: dict[str, float] = {}
    job_tasks: dict[str, asyncio.Task] = {}
    job_cancel_events: dict[str, threading.Event] = {}
    jobs_in_thread: set[str] = set()
    background_tasks: set[asyncio.Task] = set()
    job_semaphore = asyncio.Semaphore(1)

    class _JobCancelledError(Exception):
        pass

    def _update_job(job_id: str, **updates) -> None:
        if job_id not in jobs:
            return
        current = jobs[job_id]
        if current.state == JobState.CANCELLED and updates.get("state") != JobState.CANCELLED:
            return
        new_state = updates.get("state", current.state)
        if "progressPercent" in updates and new_state not in (
            JobState.READY,
            JobState.FAILED,
            JobState.CANCELLED,
        ):
            updates["progressPercent"] = max(current.progressPercent, int(updates["progressPercent"]))
        updated = current.model_copy(update=updates)
        jobs[job_id] = updated
        now = time.monotonic()
        is_terminal = new_state in (JobState.READY, JobState.FAILED, JobState.CANCELLED)
        if is_terminal or new_state != current.state or (now - last_job_save_ts.get(job_id, 0.0)) >= 5.0:
            last_job_save_ts[job_id] = now
            cache.save_job(updated)

    def _cancel_in_flight(jid: Optional[str]) -> None:
        if not jid or jid not in jobs:
            return
        existing = jobs[jid]
        if existing.state in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
            return
        if jid in job_cancel_events:
            job_cancel_events[jid].set()
        _update_job(jid, state=JobState.CANCELLED, stageLabel="Superseded by new request")
        task = job_tasks.get(jid)
        if task and not task.done() and jid not in jobs_in_thread:
            task.cancel()

    def verify_auth(
        authorization: Optional[str] = Header(default=None),
        x_hushcast_token: Optional[str] = Header(default=None),
    ) -> None:
        if not token:
            return
        bearer = None
        if authorization and authorization.lower().startswith("bearer "):
            bearer = authorization[7:].strip()
        provided = x_hushcast_token or bearer or ""
        if not hmac.compare_digest(provided.encode("utf-8"), token.encode("utf-8")):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or missing HushCast pairing token",
            )

    @app.get("/v1/health", response_model=DesktopHealthResponse)
    async def health() -> DesktopHealthResponse:
        ready = backend.models_ready()
        if not ready:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Worker models or ML dependencies are not ready. Run 'hushcast-worker setup'.",
            )
        return DesktopHealthResponse(
            status="ok",
            version=__version__,
            hostname="" if public_deployment else socket.gethostname(),
            accelerator=detect_accelerator(),
            modelsReady=ready,
            whisperModel=backend.whisper_model_name(),
            chatModelReady=backend.chat_model_ready(),
            cachedEpisodes=len(cache.list_ready()),
        )

    @app.post(
        "/v1/jobs",
        response_model=DesktopJobResponse,
        dependencies=[Depends(verify_auth)],
    )
    async def submit_job(req: DesktopJobRequest) -> DesktopJobResponse:
        if not backend.models_ready():
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Worker models or ML dependencies are not ready.",
            )

        if not req.force:
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
                cache.save_job(resp)
                logger.info(
                    "[%s] Cache hit for '%s' (sha=%s)",
                    job_id[:8],
                    _sanitize_log_text(req.title or req.episodeId),
                    existing_sha[:12],
                )
                return resp

            for in_flight_id in (
                url_to_job_id.get(req.audioUrl),
                episode_to_job_id.get(req.episodeId) if req.episodeId else None,
            ):
                if in_flight_id and in_flight_id in jobs:
                    existing_job = jobs[in_flight_id]
                    if existing_job.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
                        return existing_job
        else:
            _cancel_in_flight(url_to_job_id.get(req.audioUrl))
            if req.episodeId:
                _cancel_in_flight(episode_to_job_id.get(req.episodeId))
        job_id = str(uuid.uuid4())
        job = DesktopJobResponse(
            jobId=job_id,
            episodeId=req.episodeId,
            state=JobState.QUEUED,
            progressPercent=0,
            stageLabel="Reprocessing on desktop" if req.force else "Queued on desktop",
        )
        jobs[job_id] = job
        last_job_save_ts[job_id] = time.monotonic()
        cache.save_job(job)
        url_to_job_id[req.audioUrl] = job_id
        if req.episodeId:
            episode_to_job_id[req.episodeId] = job_id

        cancel_event = threading.Event()
        job_cancel_events[job_id] = cancel_event

        task = asyncio.create_task(_run_job(job_id, req, cancel_event))
        job_tasks[job_id] = task
        background_tasks.add(task)

        def _cleanup(t: asyncio.Task) -> None:
            background_tasks.discard(t)
            job_tasks.pop(job_id, None)
            job_cancel_events.pop(job_id, None)
            jobs_in_thread.discard(job_id)

        task.add_done_callback(_cleanup)
        return job

    async def _run_job(job_id: str, req: DesktopJobRequest, cancel_event: threading.Event) -> None:
        tmp_audio = cache.tmp_dir / f"{job_id}.part"
        cli_log = _JobConsoleLogger(job_id, req.title or req.episodeId)
        try:
            async with job_semaphore:
                if cancel_event.is_set() or (jobs.get(job_id) and jobs[job_id].state == JobState.CANCELLED):
                    return
                cli_log.update(JobState.DOWNLOADING, 5, "Downloading episode audio…")
                _update_job(
                    job_id,
                    state=JobState.DOWNLOADING,
                    progressPercent=5,
                    stageLabel="Downloading episode on desktop",
                )

                def on_dl_bytes(received: int, total: int) -> None:
                    if cancel_event.is_set():
                        raise _JobCancelledError()
                    if total > 0:
                        pct = min(24, 5 + int((received * 19) / total))
                        dl_pct = int(received * 100 / total)
                        mb_rec = received / (1024 * 1024)
                        mb_tot = total / (1024 * 1024)
                        cli_log.update(JobState.DOWNLOADING, pct, f"Downloading {mb_rec:.1f}/{mb_tot:.1f} MB ({dl_pct}%)")
                        _update_job(
                            job_id,
                            state=JobState.DOWNLOADING,
                            progressPercent=pct,
                            stageLabel=f"Downloading ({dl_pct}%)",
                        )

                sha256, content_type = await active_downloader(req.audioUrl, tmp_audio, on_dl_bytes)

                if not req.force and cache.has_bundle(sha256, require_current_pipeline=True):
                    tmp_audio.unlink(missing_ok=True)
                    cli_log.finish(f"Matched cached SHA-256 ({sha256[:12]})")
                    _update_job(
                        job_id,
                        state=JobState.READY,
                        progressPercent=100,
                        stageLabel="Ready (matched existing SHA-256)",
                        fileSha256=sha256,
                    )
                    if on_state_change:
                        on_state_change()
                    return

                def on_ml_progress(state: JobState, pct: int, stage: str) -> None:
                    if cancel_event.is_set():
                        raise _JobCancelledError()
                    cli_log.update(state, pct, stage)
                    _update_job(
                        job_id,
                        state=state,
                        progressPercent=pct,
                        stageLabel=stage,
                        fileSha256=sha256,
                    )

                jobs_in_thread.add(job_id)
                try:
                    bundle, embeddings = await asyncio.to_thread(
                        backend.analyze_with_embeddings,
                        audio_path=tmp_audio,
                        file_sha256=sha256,
                        episode_id=req.episodeId,
                        feed_transcript_raw=req.feedTranscriptRaw,
                        feed_transcript_mime=req.feedTranscriptMime,
                        on_progress=on_ml_progress,
                    )
                finally:
                    jobs_in_thread.discard(job_id)

                if cancel_event.is_set():
                    tmp_audio.unlink(missing_ok=True)
                    return

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
                _update_job(
                    job_id,
                    state=JobState.READY,
                    progressPercent=100,
                    stageLabel="Ready",
                    fileSha256=sha256,
                )
                if on_state_change:
                    on_state_change()
        except _JobCancelledError:
            tmp_audio.unlink(missing_ok=True)
            _update_job(job_id, state=JobState.CANCELLED, stageLabel="Cancelled")
        except asyncio.CancelledError:
            tmp_audio.unlink(missing_ok=True)
            _update_job(job_id, state=JobState.CANCELLED, stageLabel="Cancelled")
            raise
        except Exception as exc:
            tmp_audio.unlink(missing_ok=True)
            logger.exception("Job %s failed", job_id)
            cli_log.fail(f"{type(exc).__name__}: {exc}")
            _update_job(
                job_id,
                state=JobState.FAILED,
                stageLabel="Failed",
                errorMessage=f"{type(exc).__name__}: {exc}",
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
        if (
            job.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED)
            and job_id not in job_tasks
        ):
            _update_job(
                job_id,
                state=JobState.FAILED,
                stageLabel="Worker task stopped",
                errorMessage="Worker task is no longer running; please retry",
            )
            return jobs[job_id]
        return job

    @app.delete(
        "/v1/jobs/{job_id}",
        response_model=DesktopJobResponse,
        dependencies=[Depends(verify_auth)],
    )
    async def cancel_job(job_id: str) -> DesktopJobResponse:
        job = jobs.get(job_id)
        if not job:
            raise HTTPException(status_code=404, detail="Job not found")
        if job.state in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
            return job
        if job_id in job_cancel_events:
            job_cancel_events[job_id].set()
        _update_job(job_id, state=JobState.CANCELLED, stageLabel="Cancelled")
        task = job_tasks.get(job_id)
        if task and not task.done() and job_id not in jobs_in_thread:
            task.cancel()
        return jobs[job_id]

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
        clean_sha = _validate_sha256(sha256)
        bundle = cache.get_bundle(clean_sha)
        if not bundle:
            raise HTTPException(status_code=404, detail="Bundle not found")
        return bundle

    @app.get(
        "/v1/episodes/{sha256}/audio",
        dependencies=[Depends(verify_auth)],
    )
    async def get_audio(sha256: str, request: Request) -> FileResponse:
        clean_sha = _validate_sha256(sha256)
        audio = cache.audio_path(clean_sha)
        if not audio.exists():
            raise HTTPException(status_code=404, detail="Audio not found")
        return FileResponse(
            path=audio,
            media_type=cache.get_content_type(clean_sha),
            headers={"X-HushCast-Sha256": clean_sha},
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
        query_vec = await asyncio.to_thread(backend.embed_query, q)
        hits = await asyncio.to_thread(cache.semantic_search, query_vec, req.topK)
        return DesktopSearchResponse(query=req.query, hits=hits)

    @app.delete(
        "/v1/cache",
        dependencies=[Depends(verify_auth)],
    )
    async def clear_cache(audio_only: bool = False) -> dict[str, int]:
        active_ids = {
            jid for jid, j in jobs.items()
            if j.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED)
        }
        res = cache.clear(audio_only=audio_only, active_job_ids=active_ids)
        if on_state_change:
            on_state_change()
        return res

    return app
