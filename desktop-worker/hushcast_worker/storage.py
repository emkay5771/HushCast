from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import time
from pathlib import Path
from typing import Callable, Optional

from .fingerprint import FingerprintStore
from .models import (
    PIPELINE_VERSION,
    DesktopAnalysisBundle,
    DesktopJobResponse,
    DesktopReadyEpisode,
    DesktopSearchHit,
    JobState,
)

_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")


def normalize_sha256(value: str) -> Optional[str]:
    """Returns the lowercase hex digest, or None when `value` isn't a SHA-256 hex string."""
    if not isinstance(value, str) or not _SHA256_RE.match(value):
        return None
    return value.lower()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


class ModelVerifier:
    """Remembers which model files already passed their SHA-256 check (V19 / V21).

    Hashing ~6 GB of models on every Modal cold start would add many seconds, so a file
    is hashed once and then trusted while its size is unchanged. Downloads always land
    through a `.part` file plus an atomic rename, so a same-size corrupt file is not a
    realistic failure. The manifest lives at `<root>/.verified.json`.
    """

    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.manifest_path = self.root / ".verified.json"
        self._lock = threading.Lock()
        try:
            data = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        self._data: dict[str, dict] = data if isinstance(data, dict) else {}

    def _key(self, path: Path) -> str:
        # abspath, not resolve(): a Hugging Face cache snapshot may hold symlinks into blobs/.
        abs_path = os.path.abspath(path)
        root = os.path.abspath(self.root)
        rel = os.path.relpath(abs_path, root)
        return abs_path if rel.startswith("..") else rel

    def is_verified(self, path: Path, sha256: str) -> bool:
        try:
            size = Path(path).stat().st_size
        except OSError:
            return False
        rec = self._data.get(self._key(path))
        return (
            isinstance(rec, dict)
            and rec.get("sha256") == sha256.lower()
            and rec.get("size") == size
        )

    def verify(self, path: Path, sha256: str) -> bool:
        """True when `path` holds the file with this digest (hashing it if not checked before)."""
        path = Path(path)
        if not path.is_file():
            return False
        if self.is_verified(path, sha256):
            return True
        if sha256_file(path) != sha256.lower():
            return False
        self.mark(path, sha256)
        return True

    def mark(self, path: Path, sha256: str) -> None:
        path = Path(path)
        with self._lock:
            self._data[self._key(path)] = {
                "sha256": sha256.lower(),
                "size": path.stat().st_size,
                "verifiedAt": int(time.time()),
            }
            try:
                self.root.mkdir(parents=True, exist_ok=True)
                tmp = self.manifest_path.with_name(self.manifest_path.name + ".tmp")
                tmp.write_text(json.dumps(self._data, indent=1, sort_keys=True), encoding="utf-8")
                os.replace(tmp, self.manifest_path)
            except OSError:
                pass


def download_verified(
    url: str,
    dest: Path,
    *,
    sha256: str,
    size: Optional[int] = None,
    verifier: Optional[ModelVerifier] = None,
    timeout: float = 600.0,
    on_progress: Optional[Callable[[int, int], None]] = None,
) -> bool:
    """Makes sure `dest` holds the file with this SHA-256, downloading it when needed.

    Returns True when it downloaded the file and False when a verified copy was already
    there. A partial `<dest>.part` is resumed with a Range request and hashed while it
    streams. A file that fails the check never lands at `dest`: this raises ValueError.
    """
    import httpx

    dest = Path(dest)
    expected = sha256.lower()
    dest.parent.mkdir(parents=True, exist_ok=True)

    if dest.exists():
        size_ok = size is None or dest.stat().st_size == size
        if size_ok and (verifier.verify(dest, expected) if verifier else sha256_file(dest) == expected):
            return False
        dest.unlink(missing_ok=True)

    part = dest.with_name(dest.name + ".part")
    for attempt in range(2):
        offset = part.stat().st_size if part.exists() else 0
        if size is not None and offset > size:
            part.unlink(missing_ok=True)
            offset = 0
        h = hashlib.sha256()
        if offset:
            with part.open("rb") as f:
                for chunk in iter(lambda: f.read(1024 * 1024), b""):
                    h.update(chunk)
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        with httpx.stream("GET", url, headers=headers, follow_redirects=True, timeout=timeout) as resp:
            if resp.status_code != 416:  # 416: the partial file is already complete
                resp.raise_for_status()
                if offset and resp.status_code != 206:
                    offset = 0  # the server ignored the Range header; start over
                    h = hashlib.sha256()
                total = int(resp.headers.get("content-length") or 0) + offset
                if size is not None:
                    total = size
                received = offset
                with part.open("ab" if offset else "wb") as f:
                    for chunk in resp.iter_bytes(chunk_size=1024 * 1024):
                        f.write(chunk)
                        h.update(chunk)
                        received += len(chunk)
                        if on_progress is not None:
                            on_progress(received, total)
        actual = h.hexdigest()
        part_size = part.stat().st_size if part.exists() else -1
        if actual == expected and (size is None or part_size == size):
            os.replace(part, dest)
            if verifier is not None:
                verifier.mark(dest, expected)
            return True
        part.unlink(missing_ok=True)
        if attempt == 0 and offset:
            continue  # a stale partial file from an older revision: retry once from zero
        raise ValueError(
            f"Checksum mismatch for {dest.name}: expected sha256 {expected}"
            + (f" ({size} bytes)" if size is not None else "")
            + f", got {actual} ({part_size} bytes)"
        )
    return False  # pragma: no cover - the loop always returns or raises


class WorkerCache:
    """Content-addressed disk cache keyed by fileSha256, plus persisted job states.

    V24: the Mac server calls this from the event loop and from worker threads, so index
    reads and writes go through one re-entrant lock. Large file writes (bundles,
    embeddings) happen outside it, so a short index read never waits on them.
    """

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
        self._lock = threading.RLock()
        self._index_mtime: Optional[float] = None
        self._index: dict[str, dict] = self._load_index()
        # sha -> (embeddings_matrix, episodeId, list[(startSec, endSec, text)])
        self._search_cache: dict[str, tuple] = {}
        self.fingerprints = FingerprintStore(self.root_dir)

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
            with self._lock:
                self._index = self._load_index()

    def force_reload_index(self) -> None:
        """Re-reads index.json even if its mtime looks unchanged (e.g. after a Volume reload)."""
        with self._lock:
            self._index = self._load_index()

    def _save_index(self) -> None:
        with self._lock:
            tmp = self.index_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._index, separators=(",", ":")), encoding="utf-8")
            os.replace(tmp, self.index_path)
            try:
                self._index_mtime = self.index_path.stat().st_mtime
            except OSError:
                pass

    def _sha_items(self) -> list[tuple[str, dict]]:
        with self._lock:
            return list(self._index["by_sha"].items())

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
            with self._lock:
                meta = self._index["by_sha"].get(sha256, {})
                pipeline_version = int(meta.get("pipelineVersion", 1))
            if pipeline_version < PIPELINE_VERSION:
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
        with self._lock:
            sha = self._index["by_url"].get(audio_url)
        if sha and self.has_complete(sha, require_current_pipeline=True, require_audio=require_audio):
            return sha
        return None

    def lookup_by_episode_id(self, episode_id: str, audio_url: Optional[str] = "") -> Optional[str]:
        if audio_url:
            with self._lock:
                sha = self._index["by_url"].get(audio_url)
            if sha and self.has_bundle(sha):
                return sha
        best_sha: Optional[str] = None
        best_ts: float = -1.0
        for sha, meta in self._sha_items():
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

    def load_embeddings(self, sha256: str):
        """Returns the stored segment embeddings matrix, or None when missing or unreadable."""
        path = self.embeddings_path(sha256)
        if not path.exists():
            return None
        try:
            import numpy as np

            return np.load(path, allow_pickle=False)
        except Exception:
            return None

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
        with self._lock:
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
        with self._lock:
            meta = self._index["by_sha"].get(sha256)
            if meta is not None:
                meta["lastAccessedAtMs"] = int(time.time() * 1000)

    def delete_audio(self, sha256: str, *, update_index: bool = True) -> bool:
        """V17: deletes the cached audio for `sha256` but keeps its bundle and embeddings.

        Returns True when a file was removed and False when there was none. Set
        `update_index=False` where another process also writes index.json (Modal's GPU
        container): `hasAudio` is informational, and every reader checks the file itself.
        """
        removed = False
        try:
            self.audio_path(sha256).unlink()
            removed = True
        except FileNotFoundError:
            pass
        if update_index:
            with self._lock:
                meta = self._index["by_sha"].get(sha256)
                if meta is not None and (meta.get("hasAudio") or meta.get("audioBytes")):
                    meta["hasAudio"] = False
                    meta["audioBytes"] = 0
                    self._save_index()
        return removed

    def evict_audio_if_needed(self, protected_shas: Optional[set[str]] = None) -> int:
        if not self.max_cache_bytes or self.max_cache_bytes <= 0:
            return 0
        protected = protected_shas or set()
        audio_files: list[tuple[int, str, Path, int]] = []
        total_bytes = 0
        for sha, meta in self._sha_items():
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
        with self._lock:
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

    def _write_bundle_and_embeddings(self, sha256: str, bundle: DesktopAnalysisBundle, embeddings) -> None:
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

        self._write_bundle_and_embeddings(sha256, bundle, embeddings)

        now_ms = int(time.time() * 1000)
        with self._lock:
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
        self._write_bundle_and_embeddings(sha256, bundle, embeddings)

        with self._lock:
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
        for sha, meta in self._sha_items():
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
        with self._lock:
            meta = self._index["by_sha"].get(sha256, {})
            return meta.get("contentType", "audio/mpeg")

    def list_ready(self) -> list[DesktopReadyEpisode]:
        out: list[DesktopReadyEpisode] = []
        for sha, meta in self._sha_items():
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

        with self._lock:
            if audio_only:
                for meta in self._index["by_sha"].values():
                    meta["hasAudio"] = False
                    meta["audioBytes"] = 0
                self._save_index()
            else:
                self._search_cache.clear()
                self._index = {"by_url": {}, "by_sha": {}}
                self._save_index()
                self.fingerprints.clear()

        return {"removedFiles": removed_files, "freedBytes": freed_bytes}
