from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Optional

from .models import (
    PIPELINE_VERSION,
    DesktopAnalysisBundle,
    DesktopJobResponse,
    DesktopReadyEpisode,
    DesktopSearchHit,
    JobState,
)


class WorkerCache:
    """Content-addressed disk cache keyed by fileSha256, plus persisted job states."""

    def __init__(self, root_dir: Path, max_cache_bytes: Optional[int] = None) -> None:
        self.root_dir = root_dir
        self.max_cache_bytes = max_cache_bytes
        self.episodes_dir = root_dir / "episodes"
        self.tmp_dir = root_dir / "tmp"
        self.jobs_dir = root_dir / "jobs"
        self.index_path = root_dir / "index.json"
        self.episodes_dir.mkdir(parents=True, exist_ok=True)
        self.tmp_dir.mkdir(parents=True, exist_ok=True)
        self.jobs_dir.mkdir(parents=True, exist_ok=True)
        self._index_mtime: Optional[float] = None
        self._index: dict[str, dict] = self._load_index()
        # sha -> (embeddings_matrix, episodeId, list[(startSec, endSec, text)])
        self._search_cache: dict[str, tuple] = {}

    def _load_index(self) -> dict[str, dict]:
        if not self.index_path.exists():
            self._index_mtime = None
            return {"by_url": {}, "by_sha": {}}
        try:
            self._index_mtime = self.index_path.stat().st_mtime
            data = json.loads(self.index_path.read_text(encoding="utf-8"))
            data.setdefault("by_url", {})
            data.setdefault("by_sha", {})
            return data
        except Exception:
            return {"by_url": {}, "by_sha": {}}

    def reload_index_if_changed(self) -> None:
        try:
            mtime = self.index_path.stat().st_mtime
        except OSError:
            return
        if mtime != self._index_mtime:
            self._index = self._load_index()

    def _save_index(self) -> None:
        tmp = self.index_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._index, separators=(",", ":")), encoding="utf-8")
        os.replace(tmp, self.index_path)
        try:
            self._index_mtime = self.index_path.stat().st_mtime
        except OSError:
            pass

    def audio_path(self, sha256: str) -> Path:
        return self.episodes_dir / f"{sha256}.audio"

    def bundle_path(self, sha256: str) -> Path:
        return self.episodes_dir / f"{sha256}.bundle.json"

    def embeddings_path(self, sha256: str) -> Path:
        return self.episodes_dir / f"{sha256}.emb.npy"

    def has_audio(self, sha256: str) -> bool:
        return self.audio_path(sha256).exists()

    def has_bundle(self, sha256: str, *, require_current_pipeline: bool = False) -> bool:
        if not self.bundle_path(sha256).exists():
            return False
        if require_current_pipeline:
            meta = self._index["by_sha"].get(sha256, {})
            if int(meta.get("pipelineVersion", 1)) < PIPELINE_VERSION:
                return False
        return True

    def has_complete(
        self,
        sha256: str,
        *,
        require_current_pipeline: bool = False,
        require_audio: bool = False,
    ) -> bool:
        if not self.has_bundle(sha256, require_current_pipeline=require_current_pipeline):
            return False
        if require_audio and not self.has_audio(sha256):
            return False
        return True

    def lookup_by_url(self, audio_url: str, *, require_audio: bool = True) -> Optional[str]:
        sha = self._index["by_url"].get(audio_url)
        if sha and self.has_complete(sha, require_current_pipeline=True, require_audio=require_audio):
            return sha
        return None

    def lookup_by_episode_id(self, episode_id: str, audio_url: Optional[str] = "") -> Optional[str]:
        if audio_url:
            sha = self._index["by_url"].get(audio_url)
            if sha and self.has_bundle(sha):
                return sha
        best_sha: Optional[str] = None
        best_ts: float = -1.0
        for sha, meta in self._index["by_sha"].items():
            if meta.get("episodeId") == episode_id and self.has_bundle(sha):
                ts = float(meta.get("createdAtMs") or 0)
                if ts <= 0:
                    try:
                        ts = self.bundle_path(sha).stat().st_mtime * 1000.0
                    except OSError:
                        ts = 0.0
                if ts > best_ts:
                    best_ts = ts
                    best_sha = sha
        return best_sha

    def get_bundle(self, sha256: str) -> Optional[DesktopAnalysisBundle]:
        path = self.bundle_path(sha256)
        if not path.exists():
            return None
        return DesktopAnalysisBundle.model_validate_json(path.read_text(encoding="utf-8"))

    def stage_audio(
        self,
        *,
        sha256: str,
        audio_tmp_path: Path,
        content_type: str = "audio/mpeg",
        audio_url: str = "",
        title: str = "",
        episode_id: str = "",
    ) -> Path:
        """Moves downloaded audio to its content-addressed path early so Range GETs can start."""
        dest_audio = self.audio_path(sha256)
        if not dest_audio.exists() and audio_tmp_path.exists():
            os.replace(audio_tmp_path, dest_audio)
        elif audio_tmp_path.exists():
            audio_tmp_path.unlink(missing_ok=True)

        now_ms = int(time.time() * 1000)
        meta = self._index["by_sha"].get(sha256)
        if meta is not None:
            meta["hasAudio"] = dest_audio.exists()
            meta["audioBytes"] = dest_audio.stat().st_size if dest_audio.exists() else 0
            meta["contentType"] = content_type or meta.get("contentType", "audio/mpeg")
            meta["lastAccessedAtMs"] = now_ms
            if audio_url:
                self._index["by_url"][audio_url] = sha256
            self._save_index()
        return dest_audio

    def touch_audio(self, sha256: str) -> None:
        meta = self._index["by_sha"].get(sha256)
        if meta is not None:
            meta["lastAccessedAtMs"] = int(time.time() * 1000)

    def evict_audio_if_needed(self, protected_shas: Optional[set[str]] = None) -> int:
        if not self.max_cache_bytes or self.max_cache_bytes <= 0:
            return 0
        protected = protected_shas or set()
        audio_files: list[tuple[int, str, Path, int]] = []
        total_bytes = 0
        for sha, meta in self._index["by_sha"].items():
            p = self.audio_path(sha)
            if p.exists():
                sz = p.stat().st_size
                total_bytes += sz
                last_acc = int(meta.get("lastAccessedAtMs") or meta.get("createdAtMs") or 0)
                audio_files.append((last_acc, sha, p, sz))
        if total_bytes <= self.max_cache_bytes:
            return 0
        audio_files.sort(key=lambda item: item[0])
        removed = 0
        for _, sha, p, sz in audio_files:
            if total_bytes <= self.max_cache_bytes:
                break
            if sha in protected:
                continue
            p.unlink(missing_ok=True)
            total_bytes -= sz
            removed += 1
            meta = self._index["by_sha"].get(sha)
            if meta is not None:
                meta["hasAudio"] = False
                meta["audioBytes"] = 0
        if removed:
            self._save_index()
        return removed

    def cache_stats(self) -> tuple[int, int]:
        total = 0
        for d in (self.episodes_dir, self.jobs_dir):
            if d.exists():
                for entry in d.iterdir():
                    if entry.is_file():
                        try:
                            total += entry.stat().st_size
                        except OSError:
                            pass
        try:
            import shutil
            free = shutil.disk_usage(self.root_dir).free
        except OSError:
            free = 0
        return total, free

    def save_episode(
        self,
        *,
        sha256: str,
        audio_tmp_path: Optional[Path],
        bundle: DesktopAnalysisBundle,
        audio_url: str,
        title: str,
        content_type: str = "audio/mpeg",
        embeddings=None,
    ) -> None:
        dest_audio = self.audio_path(sha256)
        if audio_tmp_path is not None:
            if not dest_audio.exists() and audio_tmp_path.exists():
                os.replace(audio_tmp_path, dest_audio)
            elif audio_tmp_path.exists():
                audio_tmp_path.unlink(missing_ok=True)

        dest_bundle = self.bundle_path(sha256)
        tmp_bundle = dest_bundle.with_suffix(".bundle.tmp")
        tmp_bundle.write_text(bundle.model_dump_json(), encoding="utf-8")
        os.replace(tmp_bundle, dest_bundle)

        if embeddings is not None:
            import numpy as np

            emb_path = self.embeddings_path(sha256)
            tmp_emb = emb_path.with_suffix(".emb.tmp.npy")
            np.save(tmp_emb, np.asarray(embeddings, dtype=np.float32))
            os.replace(tmp_emb, emb_path)

        now_ms = int(time.time() * 1000)
        self._search_cache.pop(sha256, None)
        self._index["by_url"][audio_url] = sha256
        self._index["by_sha"][sha256] = {
            "fileSha256": sha256,
            "episodeId": bundle.episodeId,
            "title": title,
            "audioUrl": audio_url,
            "contentType": content_type,
            "durationSec": bundle.durationSec,
            "audioBytes": dest_audio.stat().st_size if dest_audio.exists() else 0,
            "hasAudio": dest_audio.exists(),
            "wordCount": len(bundle.wordIndexBlob.words),
            "adCount": len(bundle.adSegments),
            "pipelineVersion": bundle.pipelineVersion,
            "createdAtMs": now_ms,
            "lastAccessedAtMs": now_ms,
        }
        self._save_index()
        self.evict_audio_if_needed(protected_shas={sha256})

    def save_reprocessed(
        self,
        *,
        sha256: str,
        bundle: DesktopAnalysisBundle,
        embeddings=None,
    ) -> None:
        dest_bundle = self.bundle_path(sha256)
        tmp_bundle = dest_bundle.with_suffix(".bundle.tmp")
        tmp_bundle.write_text(bundle.model_dump_json(), encoding="utf-8")
        os.replace(tmp_bundle, dest_bundle)

        if embeddings is not None:
            import numpy as np

            emb_path = self.embeddings_path(sha256)
            tmp_emb = emb_path.with_suffix(".emb.tmp.npy")
            np.save(tmp_emb, np.asarray(embeddings, dtype=np.float32))
            os.replace(tmp_emb, emb_path)

        self._search_cache.pop(sha256, None)
        meta = self._index["by_sha"].get(sha256)
        if meta is not None:
            meta["wordCount"] = len(bundle.wordIndexBlob.words)
            meta["adCount"] = len(bundle.adSegments)
            meta["pipelineVersion"] = bundle.pipelineVersion
            meta["durationSec"] = bundle.durationSec
            self._save_index()

    def semantic_search(self, query_vec, top_k: int = 15) -> list[DesktopSearchHit]:
        import numpy as np

        q = np.asarray(query_vec, dtype=np.float32).reshape(-1)
        q_norm = float(np.linalg.norm(q))
        if q_norm > 1e-12:
            q = q / q_norm

        hits: list[DesktopSearchHit] = []
        for sha, meta in list(self._index["by_sha"].items()):
            cached = self._search_cache.get(sha)
            if cached is None:
                emb_file = self.embeddings_path(sha)
                bundle_file = self.bundle_path(sha)
                if not emb_file.exists() or not bundle_file.exists():
                    continue
                mat = np.load(emb_file)
                if mat.ndim != 2 or mat.shape[0] == 0:
                    continue
                bundle = self.get_bundle(sha)
                if bundle is None or not bundle.wordIndexBlob.segments:
                    continue
                seg_entries = [
                    (int(round(seg.start * 1000)), int(round(seg.end * 1000)), seg.text.strip())
                    for seg in bundle.wordIndexBlob.segments
                ]
                ep_id = bundle.episodeId
                self._search_cache[sha] = (mat, ep_id, seg_entries)
            else:
                mat, ep_id, seg_entries = cached

            scores = mat @ q
            n = min(len(scores), len(seg_entries))
            top_indices = np.argsort(scores[:n])[::-1][: min(5, n)]
            ep_title = meta.get("title", "") or ep_id
            for idx in top_indices:
                start_ms, end_ms, seg_text = seg_entries[int(idx)]
                hits.append(
                    DesktopSearchHit(
                        fileSha256=sha,
                        episodeId=ep_id,
                        episodeTitle=ep_title,
                        startMs=start_ms,
                        endMs=end_ms,
                        text=seg_text,
                        score=round(float(scores[int(idx)]), 4),
                    )
                )

        hits.sort(key=lambda h: h.score, reverse=True)
        return hits[:top_k]

    def get_content_type(self, sha256: str) -> str:
        meta = self._index["by_sha"].get(sha256, {})
        return meta.get("contentType", "audio/mpeg")

    def list_ready(self) -> list[DesktopReadyEpisode]:
        out: list[DesktopReadyEpisode] = []
        for sha, meta in self._index["by_sha"].items():
            if not self.has_bundle(sha):
                continue
            audio_p = self.audio_path(sha)
            audio_bytes = audio_p.stat().st_size if audio_p.exists() else 0
            out.append(
                DesktopReadyEpisode(
                    fileSha256=sha,
                    episodeId=meta.get("episodeId", ""),
                    title=meta.get("title", ""),
                    durationSec=float(meta.get("durationSec", 0.0)),
                    audioBytes=audio_bytes,
                    wordCount=int(meta.get("wordCount", 0)),
                    adCount=int(meta.get("adCount", 0)),
                    createdAtMs=int(meta.get("createdAtMs", 0)),
                )
            )
        out.sort(key=lambda e: e.createdAtMs, reverse=True)
        return out

    def save_job(self, job: DesktopJobResponse) -> None:
        path = self.jobs_dir / f"{job.jobId}.json"
        tmp = path.with_suffix(".tmp")
        payload = {
            "job": job.model_dump(mode="json"),
            "updatedAt": time.time(),
        }
        tmp.write_text(json.dumps(payload), encoding="utf-8")
        os.replace(tmp, path)

    def load_jobs(self, *, ttl_seconds: float = 86400.0) -> dict[str, DesktopJobResponse]:
        out: dict[str, DesktopJobResponse] = {}
        now = time.time()
        if not self.jobs_dir.exists():
            return out
        for entry in self.jobs_dir.iterdir():
            if not entry.is_file() or entry.suffix != ".json":
                continue
            try:
                data = json.loads(entry.read_text(encoding="utf-8"))
                updated_at = float(data.get("updatedAt", 0.0))
                job = DesktopJobResponse.model_validate(data["job"])
                if job.state in (JobState.READY, JobState.FAILED, JobState.CANCELLED) and (now - updated_at) > ttl_seconds:
                    entry.unlink(missing_ok=True)
                    continue
                out[job.jobId] = job
            except Exception:
                entry.unlink(missing_ok=True)
        return out

    def clear(
        self,
        *,
        audio_only: bool = False,
        active_job_ids: Optional[set[str]] = None,
    ) -> dict[str, int]:
        removed_files = 0
        freed_bytes = 0
        active_prefixes = {f"{jid}." for jid in (active_job_ids or set())}

        for directory in (self.episodes_dir, self.tmp_dir):
            if not directory.exists():
                continue
            for entry in directory.iterdir():
                if not entry.is_file():
                    continue
                if audio_only and directory == self.episodes_dir and entry.suffix != ".audio":
                    continue
                if directory == self.tmp_dir and any(entry.name.startswith(p) for p in active_prefixes):
                    continue
                try:
                    size = entry.stat().st_size
                except OSError:
                    size = 0
                entry.unlink(missing_ok=True)
                removed_files += 1
                freed_bytes += size

        if audio_only:
            for meta in self._index["by_sha"].values():
                meta["hasAudio"] = False
                meta["audioBytes"] = 0
            self._save_index()
        else:
            self._search_cache.clear()
            self._index = {"by_url": {}, "by_sha": {}}
            self._save_index()

        return {"removedFiles": removed_files, "freedBytes": freed_bytes}
