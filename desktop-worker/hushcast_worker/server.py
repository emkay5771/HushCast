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
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Awaitable, Callable, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, Response

from . import __version__
from .audio import download_audio_streaming_sha256
from .models import (
    PIPELINE_VERSION,
    DesktopAnalysisBundle,
    DesktopHealthResponse,
    DesktopJobRequest,
    DesktopJobResponse,
    DesktopReadyEpisode,
    DesktopSearchRequest,
    DesktopSearchResponse,
    JobStage,
    JobState,
)
from .pipeline import AnalysisBackend, detect_accelerator
from .storage import WorkerCache, normalize_sha256

logger = logging.getLogger("hushcast_worker.server")

AudioDownloader = Callable[
    [str, Path, Optional[Callable[[int, int], None]]], Awaitable[tuple[str, str]]
]

_CTRL_RE = re.compile(r"[\x00-\x1f\x7f-\x9f]|\x1b\[[0-9;]*[A-Za-z]")


def _infer_stage(state: JobState, label: str) -> JobStage:
    low = (label or "").lower()
    if state == JobState.QUEUED:
        return JobStage.QUEUED
    if state == JobState.DOWNLOADING:
        return JobStage.DOWNLOADING
    if state == JobState.TRANSCRIBING:
        if "decoding" in low:
            return JobStage.DECODING
        if "align" in low:
            return JobStage.ALIGNING
        if "speaker" in low:
            return JobStage.SPEAKER_ID
        return JobStage.TRANSCRIBING
    if state == JobState.EMBEDDING:
        return JobStage.EMBEDDING
    if state == JobState.TAGGING:
        if "chapter" in low or "summary" in low or "llm" in low:
            return JobStage.SUMMARIZING
        return JobStage.TAGGING
    if state == JobState.READY:
        return JobStage.READY
    if state == JobState.CANCELLED:
        return JobStage.CANCELLED
    return JobStage.FAILED


def _classify_error(exc: Exception) -> tuple[str, bool]:
    msg = str(exc).lower()
    name = type(exc).__name__
    if "404" in msg or "403" in msg or "401" in msg or "400" in msg or "invalid" in msg or "ssrf" in msg:
        return ("PERMANENT_FETCH_ERROR", False)
    if "filenotfound" in name.lower() or "missing model" in msg:
        return ("MISSING_MODELS", False)
    if "timeout" in name.lower() or "timeout" in msg or "connect" in msg or "network" in msg or "502" in msg or "503" in msg:
        return ("TRANSIENT_NETWORK_ERROR", True)
    return (name.upper(), True)


def _sanitize_log_text(text: str, max_len: int = 80) -> str:
    cleaned = _CTRL_RE.sub("", text or "").strip()
    if len(cleaned) > max_len:
        return cleaned[: max_len - 3] + "..."
    return cleaned


def _validate_sha256(sha256: str) -> str:
    clean = normalize_sha256(sha256)
    if clean is None:
        raise HTTPException(status_code=400, detail="Invalid sha256 parameter")
    return clean


_TERMINAL_STATES = (JobState.READY, JobState.FAILED, JobState.CANCELLED)


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
    deployment_kind: Optional[str] = None,
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

    # V24: job-state files are written on one background thread, in order, so neither the
    # event loop nor the download callbacks wait on disk. Shutdown drains the queue.
    persist_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="hushcast-persist")

    def _in_background(fn, *args) -> None:
        try:
            persist_executor.submit(fn, *args)
        except RuntimeError:  # executor already shut down (app stopping): write inline
            fn(*args)

    @asynccontextmanager
    async def _lifespan(_app: FastAPI):
        try:
            yield
        finally:
            await asyncio.to_thread(persist_executor.shutdown, wait=True)

    app = FastAPI(
        title="HushCast Desktop Worker",
        version=__version__,
        docs_url=None if public_deployment else "/docs",
        redoc_url=None if public_deployment else "/redoc",
        openapi_url=None if public_deployment else "/openapi.json",
        lifespan=_lifespan,
    )
    app.add_middleware(GZipMiddleware, minimum_size=1024)

    warmup_fn = getattr(backend, "warmup", None)
    if callable(warmup_fn) and backend.models_ready() and not getattr(backend, "models_loaded", lambda: False)():
        threading.Thread(target=warmup_fn, daemon=True, name="hushcast-warmup").start()

    jobs: dict[str, DesktopJobResponse] = cache.load_jobs()
    for _jid, _job in list(jobs.items()):
        if _job.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
            jobs[_jid] = _job.model_copy(
                update={
                    "state": JobState.FAILED,
                    "stage": JobStage.FAILED,
                    "stageLabel": "Worker restarted",
                    "errorMessage": "Worker restarted before job completed; please retry",
                    "errorCode": "WORKER_RESTARTED",
                    "retryable": True,
                }
            )
            cache.save_job(jobs[_jid])
    url_to_job_id: dict[str, str] = {}
    episode_to_job_id: dict[str, str] = {}
    last_job_save_ts: dict[str, float] = {}
    job_tasks: dict[str, asyncio.Task] = {}
    job_cancel_events: dict[str, threading.Event] = {}
    job_change_events: dict[str, asyncio.Event] = {}
    gpu_queue_order: list[str] = []
    active_gpu_job_id: Optional[str] = None
    jobs_in_thread: set[str] = set()
    background_tasks: set[asyncio.Task] = set()
    fetch_semaphore = asyncio.Semaphore(2)
    gpu_semaphore = asyncio.Semaphore(1)
    loop_ref: list[Optional[asyncio.AbstractEventLoop]] = [None]
    caffeinate_proc: list[object] = [None]
    # V17: audio the phone asked us to delete while a running job still needs it, and the
    # audio each job downloaded (known before the job's fileSha256 is published).
    pending_audio_deletes: set[str] = set()
    job_audio_sha: dict[str, str] = {}

    def _sha_in_use(sha: str) -> bool:
        for jid, j in list(jobs.items()):
            if j.state not in _TERMINAL_STATES and (j.fileSha256 == sha or job_audio_sha.get(jid) == sha):
                return True
        return False

    def _maybe_start_caffeinate() -> None:
        if sys.platform != "darwin" or caffeinate_proc[0] is not None:
            return
        try:
            import os as _os
            import subprocess
            caffeinate_proc[0] = subprocess.Popen(
                ["caffeinate", "-i", "-w", str(_os.getpid())],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            pass

    def _maybe_stop_caffeinate() -> None:
        proc = caffeinate_proc[0]
        if proc is None:
            return
        active = any(
            j.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED)
            for j in jobs.values()
        )
        if not active:
            try:
                proc.terminate()
            except Exception:
                pass
            caffeinate_proc[0] = None

    class _JobCancelledError(Exception):
        pass

    def _notify_job(job_id: str) -> None:
        ev = job_change_events.get(job_id)
        lp = loop_ref[0]
        if ev is not None:
            if lp is not None and lp.is_running():
                lp.call_soon_threadsafe(ev.set)
            else:
                ev.set()

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
        if "stage" not in updates:
            updates["stage"] = _infer_stage(new_state, updates.get("stageLabel", current.stageLabel))
        updates["rev"] = current.rev + 1
        updated = current.model_copy(update=updates)
        jobs[job_id] = updated
        _notify_job(job_id)
        now = time.monotonic()
        is_terminal = new_state in _TERMINAL_STATES
        if is_terminal or new_state != current.state or (now - last_job_save_ts.get(job_id, 0.0)) >= 5.0:
            last_job_save_ts[job_id] = now
            _in_background(cache.save_job, updated)
        if is_terminal:
            _maybe_stop_caffeinate()
            sha = updated.fileSha256 or job_audio_sha.get(job_id)
            if sha and sha in pending_audio_deletes and not _sha_in_use(sha):
                pending_audio_deletes.discard(sha)
                _in_background(cache.delete_audio, sha)

    def _cancel_in_flight(jid: Optional[str]) -> None:
        if not jid or jid not in jobs:
            return
        existing = jobs[jid]
        if existing.state in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
            return
        if jid in job_cancel_events:
            job_cancel_events[jid].set()
        _update_job(jid, state=JobState.CANCELLED, stage=JobStage.CANCELLED, stageLabel="Cancelled")
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

    def _backend_health() -> Optional[dict]:
        # Runs in a worker thread: models_ready() imports the ML libraries the first time,
        # detect_accelerator() may import torch, and list_ready() stats every cached file.
        if not backend.models_ready():
            return None
        cache_bytes, disk_free = cache.cache_stats()
        return {
            "accelerator": detect_accelerator(),
            "gpuName": getattr(backend, "gpu_name", lambda: None)(),
            "modelsLoaded": bool(getattr(backend, "models_loaded", lambda: False)()),
            "whisperModel": backend.whisper_model_name(),
            "chatModelReady": backend.chat_model_ready(),
            "cachedEpisodes": len(cache.list_ready()),
            "cacheBytes": cache_bytes,
            "diskFreeBytes": disk_free,
        }

    @app.get("/v1/health", response_model=DesktopHealthResponse)
    async def health() -> DesktopHealthResponse:
        info = await asyncio.to_thread(_backend_health)
        if info is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Worker models or ML dependencies are not ready. Run 'hushcast-worker setup'.",
            )
        active_title = None
        if active_gpu_job_id and active_gpu_job_id in jobs:
            active_title = jobs[active_gpu_job_id].episodeId
        return DesktopHealthResponse(
            status="ok",
            version=__version__,
            apiVersion=2,
            pipelineVersion=PIPELINE_VERSION,
            hostname="" if public_deployment else socket.gethostname(),
            deployment=deployment_kind or ("modal" if public_deployment else "local"),
            modelsReady=True,
            queueDepth=len(gpu_queue_order),
            queuePosition=len(gpu_queue_order) + (1 if active_gpu_job_id else 0),
            coldStart=not info["modelsLoaded"],
            activeJob=active_title,
            **info,
        )

    @app.post(
        "/v1/jobs",
        response_model=DesktopJobResponse,
        response_model_exclude_none=True,
        dependencies=[Depends(verify_auth)],
    )
    async def submit_job(req: DesktopJobRequest) -> DesktopJobResponse:
        loop_ref[0] = asyncio.get_running_loop()
        if not await asyncio.to_thread(backend.models_ready):
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
                    stage=JobStage.READY,
                    progressPercent=100,
                    stageLabel="Cached on desktop",
                    fileSha256=existing_sha,
                )
                jobs[job_id] = resp
                _in_background(cache.save_job, resp)
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
        gpu_queue_order.append(job_id)
        q_pos = max(0, len(gpu_queue_order) - (1 if active_gpu_job_id else 0))
        job = DesktopJobResponse(
            jobId=job_id,
            episodeId=req.episodeId,
            state=JobState.QUEUED,
            stage=JobStage.QUEUED,
            progressPercent=0,
            stageLabel="Reprocessing on desktop" if req.force else "Queued on desktop",
            queuePosition=q_pos,
        )
        jobs[job_id] = job
        last_job_save_ts[job_id] = time.monotonic()
        _in_background(cache.save_job, job)
        url_to_job_id[req.audioUrl] = job_id
        if req.episodeId:
            episode_to_job_id[req.episodeId] = job_id

        cancel_event = threading.Event()
        job_cancel_events[job_id] = cancel_event
        job_change_events[job_id] = asyncio.Event()
        _maybe_start_caffeinate()

        task = asyncio.create_task(_run_job(job_id, req, cancel_event))
        job_tasks[job_id] = task
        background_tasks.add(task)

        def _cleanup(t: asyncio.Task) -> None:
            background_tasks.discard(t)
            job_tasks.pop(job_id, None)
            job_cancel_events.pop(job_id, None)
            job_change_events.pop(job_id, None)
            job_audio_sha.pop(job_id, None)
            jobs_in_thread.discard(job_id)
            if job_id in gpu_queue_order:
                gpu_queue_order.remove(job_id)

        task.add_done_callback(_cleanup)
        return job

    def _call_backend_analyze(
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        req: DesktopJobRequest,
        on_progress,
        cancel_event: threading.Event,
    ):
        import inspect

        fn = backend.analyze_with_embeddings
        sig = inspect.signature(fn)
        kwargs: dict[str, object] = {
            "audio_path": audio_path,
            "file_sha256": file_sha256,
            "episode_id": episode_id,
            "feed_transcript_raw": req.feedTranscriptRaw,
            "feed_transcript_mime": req.feedTranscriptMime,
            "on_progress": on_progress,
        }
        if "cancel_event" in sig.parameters:
            kwargs["cancel_event"] = cancel_event
        if "language" in sig.parameters:
            kwargs["language"] = req.language
        if "feed_transcript_url" in sig.parameters:
            kwargs["feed_transcript_url"] = req.feedTranscriptUrl
        if "feed_chapters_url" in sig.parameters:
            kwargs["feed_chapters_url"] = req.feedChaptersUrl
        if "feed_chapters_type" in sig.parameters:
            kwargs["feed_chapters_type"] = req.feedChaptersType
        if "episode_title" in sig.parameters:
            kwargs["episode_title"] = req.title or None
        if "podcast_title" in sig.parameters:
            kwargs["podcast_title"] = req.podcastTitle
        if "podcast_origin" in sig.parameters:
            kwargs["podcast_origin"] = req.podcastOrigin
        if "fingerprint_store" in sig.parameters:
            kwargs["fingerprint_store"] = cache.fingerprints
        return fn(**kwargs)

    async def _run_job(job_id: str, req: DesktopJobRequest, cancel_event: threading.Event) -> None:
        nonlocal active_gpu_job_id
        tmp_audio = cache.tmp_dir / f"{job_id}.part"
        cli_log = _JobConsoleLogger(job_id, req.title or req.episodeId)
        try:
            stage_req = (req.reprocessStage or "").strip().upper()
            if req.force and stage_req in ("SUMMARY_CHAPTERS", "ALIGN_WORDS", "ADS_AND_SUMMARY") and hasattr(backend, "reprocess_bundle"):
                async with gpu_semaphore:
                    if cancel_event.is_set() or (jobs.get(job_id) and jobs[job_id].state == JobState.CANCELLED):
                        return
                    active_gpu_job_id = job_id
                    if job_id in gpu_queue_order:
                        gpu_queue_order.remove(job_id)
                    try:
                        existing_sha = cache.lookup_by_url(req.audioUrl, require_audio=False)
                        existing_bundle = (
                            await asyncio.to_thread(cache.get_bundle, existing_sha) if existing_sha else None
                        )
                        audio_ok = (stage_req == "SUMMARY_CHAPTERS") or (existing_sha is not None and cache.has_audio(existing_sha))
                        if existing_sha and existing_bundle is not None and audio_ok:
                            cached_audio = cache.audio_path(existing_sha) if cache.has_audio(existing_sha) else None
                            existing_emb = await asyncio.to_thread(cache.load_embeddings, existing_sha)

                            def on_reprocess_progress(state: JobState, pct: int, stage_lbl: str) -> None:
                                if cancel_event.is_set():
                                    raise _JobCancelledError()
                                cli_log.update(state, pct, stage_lbl)
                                _update_job(
                                    job_id,
                                    state=state,
                                    progressPercent=pct,
                                    stageLabel=stage_lbl,
                                    fileSha256=existing_sha,
                                    queuePosition=0,
                                )

                            jobs_in_thread.add(job_id)
                            try:
                                import inspect
                                rep_kwargs: dict[str, object] = {
                                    "stage": stage_req,
                                    "existing_bundle": existing_bundle,
                                    "audio_path": cached_audio,
                                    "existing_embeddings": existing_emb,
                                    "on_progress": on_reprocess_progress,
                                }
                                if "cancel_event" in inspect.signature(backend.reprocess_bundle).parameters:
                                    rep_kwargs["cancel_event"] = cancel_event
                                bundle, new_emb = await asyncio.to_thread(backend.reprocess_bundle, **rep_kwargs)
                            finally:
                                jobs_in_thread.discard(job_id)

                            if cancel_event.is_set():
                                return

                            await asyncio.to_thread(
                                cache.save_reprocessed,
                                sha256=existing_sha,
                                bundle=bundle,
                                embeddings=new_emb,
                            )
                            cli_log.finish(f"Reprocessed ({stage_req})")
                            _update_job(
                                job_id,
                                state=JobState.READY,
                                stage=JobStage.READY,
                                progressPercent=100,
                                stageLabel=f"Ready ({stage_req})",
                                fileSha256=existing_sha,
                                queuePosition=0,
                            )
                            if on_state_change:
                                await asyncio.to_thread(on_state_change)
                            return
                    finally:
                        if active_gpu_job_id == job_id:
                            active_gpu_job_id = None

            async with fetch_semaphore:
                if cancel_event.is_set() or (jobs.get(job_id) and jobs[job_id].state == JobState.CANCELLED):
                    return

                cli_log.update(JobState.DOWNLOADING, 5, "Downloading episode audio…")
                _update_job(
                    job_id,
                    state=JobState.DOWNLOADING,
                    stage=JobStage.DOWNLOADING,
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
                            stage=JobStage.DOWNLOADING,
                            progressPercent=pct,
                            stageProgress=dl_pct,
                            stageLabel=f"Downloading ({dl_pct}%)",
                        )

                sha256, content_type = await active_downloader(req.audioUrl, tmp_audio, on_dl_bytes)
                if cancel_event.is_set():
                    tmp_audio.unlink(missing_ok=True)
                    return
                # Claim the audio before staging, so a DELETE of it waits for this job (V17).
                job_audio_sha[job_id] = sha256

                # Stage audio early (V5) and fix B11 (keep freshly downloaded audio if missing on cache hit)
                staged_audio = await asyncio.to_thread(
                    cache.stage_audio,
                    sha256=sha256,
                    audio_tmp_path=tmp_audio,
                    content_type=content_type,
                    audio_url=req.audioUrl,
                    title=req.title,
                    episode_id=req.episodeId,
                )

                if not req.force and cache.has_bundle(sha256, require_current_pipeline=True):
                    if job_id in gpu_queue_order:
                        gpu_queue_order.remove(job_id)
                    cli_log.finish(f"Matched cached SHA-256 ({sha256[:12]})")
                    _update_job(
                        job_id,
                        state=JobState.READY,
                        stage=JobStage.READY,
                        progressPercent=100,
                        stageLabel="Ready (matched existing SHA-256)",
                        fileSha256=sha256,
                        queuePosition=0,
                    )
                    if on_state_change:
                        await asyncio.to_thread(on_state_change)
                    return

                _update_job(
                    job_id,
                    fileSha256=sha256,
                    stageLabel="Waiting for GPU worker",
                )

            async with gpu_semaphore:
                if cancel_event.is_set() or (jobs.get(job_id) and jobs[job_id].state == JobState.CANCELLED):
                    return
                active_gpu_job_id = job_id
                if job_id in gpu_queue_order:
                    gpu_queue_order.remove(job_id)
                try:
                    ml_start_ts = time.monotonic()

                    def on_ml_progress(state: JobState, pct: int, stage: str) -> None:
                        if cancel_event.is_set():
                            raise _JobCancelledError()
                        cli_log.update(state, pct, stage)
                        elapsed = time.monotonic() - ml_start_ts
                        eta_val: Optional[int] = None
                        if 5 <= pct < 100 and elapsed >= 1.5:
                            eta_val = max(1, int(round(elapsed * (100 - pct) / pct)))
                        _update_job(
                            job_id,
                            state=state,
                            progressPercent=pct,
                            stageLabel=stage,
                            fileSha256=sha256,
                            queuePosition=0,
                            etaSec=eta_val,
                        )

                    jobs_in_thread.add(job_id)
                    try:
                        bundle, embeddings = await asyncio.to_thread(
                            _call_backend_analyze,
                            audio_path=staged_audio,
                            file_sha256=sha256,
                            episode_id=req.episodeId,
                            req=req,
                            on_progress=on_ml_progress,
                            cancel_event=cancel_event,
                        )
                    finally:
                        jobs_in_thread.discard(job_id)

                    if cancel_event.is_set():
                        return

                    await asyncio.to_thread(
                        cache.save_episode,
                        sha256=sha256,
                        audio_tmp_path=None,
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
                        stage=JobStage.READY,
                        progressPercent=100,
                        stageLabel="Ready",
                        fileSha256=sha256,
                        queuePosition=0,
                        etaSec=0,
                    )
                    if on_state_change:
                        await asyncio.to_thread(on_state_change)
                finally:
                    if active_gpu_job_id == job_id:
                        active_gpu_job_id = None
        except _JobCancelledError:
            tmp_audio.unlink(missing_ok=True)
            _update_job(job_id, state=JobState.CANCELLED, stage=JobStage.CANCELLED, stageLabel="Cancelled")
        except asyncio.CancelledError:
            tmp_audio.unlink(missing_ok=True)
            _update_job(job_id, state=JobState.CANCELLED, stage=JobStage.CANCELLED, stageLabel="Cancelled")
            raise
        except Exception as exc:
            tmp_audio.unlink(missing_ok=True)
            logger.exception("Job %s failed", job_id)
            cli_log.fail(f"{type(exc).__name__}: {exc}")
            err_code, is_retryable = _classify_error(exc)
            _update_job(
                job_id,
                state=JobState.FAILED,
                stage=JobStage.FAILED,
                stageLabel="Failed",
                errorMessage=f"{type(exc).__name__}: {exc}",
                errorCode=err_code,
                retryable=is_retryable,
            )

    @app.get(
        "/v1/jobs/{job_id}",
        response_model=DesktopJobResponse,
        response_model_exclude_none=True,
        dependencies=[Depends(verify_auth)],
    )
    async def get_job(
        job_id: str,
        waitMs: int = Query(default=0, ge=0, le=30000),
        sinceRev: int = Query(default=0, ge=0),
    ) -> DesktopJobResponse:
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
                stage=JobStage.FAILED,
                stageLabel="Worker task stopped",
                errorMessage="Worker task is no longer running; please retry",
                errorCode="WORKER_TASK_STOPPED",
                retryable=True,
            )
            return jobs[job_id]

        if (
            waitMs > 0
            and job.rev <= sinceRev
            and job.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED)
        ):
            ev = job_change_events.get(job_id)
            if ev is not None:
                ev.clear()
                job = jobs.get(job_id)
                if job and job.rev <= sinceRev and job.state not in (JobState.READY, JobState.FAILED, JobState.CANCELLED):
                    try:
                        await asyncio.wait_for(ev.wait(), timeout=waitMs / 1000.0)
                    except asyncio.TimeoutError:
                        pass
                    job = jobs.get(job_id) or job

        if job_id in gpu_queue_order:
            q_pos = gpu_queue_order.index(job_id) + (1 if active_gpu_job_id else 0)
            if job.queuePosition != q_pos:
                job = job.model_copy(update={"queuePosition": q_pos})
        return job

    @app.delete(
        "/v1/jobs/{job_id}",
        response_model=DesktopJobResponse,
        response_model_exclude_none=True,
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
        _update_job(job_id, state=JobState.CANCELLED, stage=JobStage.CANCELLED, stageLabel="Cancelled")
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
        return await asyncio.to_thread(cache.list_ready)

    @app.get(
        "/v1/episodes/{sha256}/bundle",
        dependencies=[Depends(verify_auth)],
    )
    async def get_bundle(sha256: str) -> FileResponse:
        clean_sha = _validate_sha256(sha256)
        bundle_path = cache.bundle_path(clean_sha)
        if not bundle_path.exists():
            raise HTTPException(status_code=404, detail="Bundle not found")
        etag = f'"{clean_sha}-v{PIPELINE_VERSION}-{bundle_path.stat().st_mtime_ns}"'
        return FileResponse(
            path=bundle_path,
            media_type="application/json",
            headers={"ETag": etag},
        )

    @app.get(
        "/v1/episodes/{sha256}/audio",
        dependencies=[Depends(verify_auth)],
    )
    async def get_audio(sha256: str, request: Request) -> FileResponse:
        clean_sha = _validate_sha256(sha256)
        audio = cache.audio_path(clean_sha)
        if not audio.exists():
            raise HTTPException(status_code=404, detail="Audio not found")
        cache.touch_audio(clean_sha)
        return FileResponse(
            path=audio,
            media_type=cache.get_content_type(clean_sha),
            headers={
                "X-HushCast-Sha256": clean_sha,
                "ETag": f'"{clean_sha}"',
            },
        )

    @app.delete(
        "/v1/episodes/{sha256}/audio",
        status_code=status.HTTP_204_NO_CONTENT,
        response_class=Response,
        dependencies=[Depends(verify_auth)],
    )
    async def delete_audio(sha256: str) -> Response:
        """V17: the phone verified its own copy, so drop ours. The bundle stays.

        204 whether the file was deleted or was already gone. If a running job still uses
        the audio, it is deleted when that job finishes.
        """
        clean_sha = _validate_sha256(sha256)
        if _sha_in_use(clean_sha):
            pending_audio_deletes.add(clean_sha)
            logger.info("Audio %s is in use by a running job; deleting it when the job ends", clean_sha[:12])
        else:
            removed = await asyncio.to_thread(cache.delete_audio, clean_sha)
            if removed and on_state_change:
                await asyncio.to_thread(on_state_change)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

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
        res = await asyncio.to_thread(cache.clear, audio_only=audio_only, active_job_ids=active_ids)
        if on_state_change:
            await asyncio.to_thread(on_state_change)
        return res

    return app
