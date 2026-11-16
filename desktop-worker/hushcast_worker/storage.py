from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Optional

from .models import DesktopAnalysisBundle, DesktopReadyEpisode


class WorkerCache:
    """Content-addressed disk cache keyed by fileSha256."""

    def __init__(self, root_dir: Path) -> None:
        self.root_dir = root_dir
        self.episodes_dir = root_dir / "episodes"
        self.tmp_dir = root_dir / "tmp"
        self.index_path = root_dir / "index.json"
        self.episodes_dir.mkdir(parents=True, exist_ok=True)
        self.tmp_dir.mkdir(parents=True, exist_ok=True)
        self._index: dict[str, dict] = self._load_index()

    def _load_index(self) -> dict[str, dict]:
        if not self.index_path.exists():
            return {"by_url": {}, "by_sha": {}}
        try:
            data = json.loads(self.index_path.read_text(encoding="utf-8"))
            data.setdefault("by_url", {})
            data.setdefault("by_sha", {})
            return data
        except Exception:
            return {"by_url": {}, "by_sha": {}}

    def _save_index(self) -> None:
        tmp = self.index_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._index, indent=2), encoding="utf-8")
        tmp.replace(self.index_path)

    def audio_path(self, sha256: str) -> Path:
        return self.episodes_dir / f"{sha256}.audio"

    def bundle_path(self, sha256: str) -> Path:
        return self.episodes_dir / f"{sha256}.bundle.json"

    def has_complete(self, sha256: str) -> bool:
        return self.audio_path(sha256).exists() and self.bundle_path(sha256).exists()

    def lookup_by_url(self, audio_url: str) -> Optional[str]:
        sha = self._index["by_url"].get(audio_url)
        if sha and self.has_complete(sha):
            return sha
        return None

    def get_bundle(self, sha256: str) -> Optional[DesktopAnalysisBundle]:
        path = self.bundle_path(sha256)
        if not path.exists():
            return None
        return DesktopAnalysisBundle.model_validate_json(path.read_text(encoding="utf-8"))

    def save_episode(
        self,
        *,
        sha256: str,
        audio_tmp_path: Path,
        bundle: DesktopAnalysisBundle,
        audio_url: str,
        title: str,
        content_type: str = "audio/mpeg",
    ) -> None:
        dest_audio = self.audio_path(sha256)
        if not dest_audio.exists():
            audio_tmp_path.replace(dest_audio)
        else:
            audio_tmp_path.unlink(missing_ok=True)

        dest_bundle = self.bundle_path(sha256)
        dest_bundle.write_text(bundle.model_dump_json(indent=2), encoding="utf-8")

        self._index["by_url"][audio_url] = sha256
        self._index["by_sha"][sha256] = {
            "fileSha256": sha256,
            "episodeId": bundle.episodeId,
            "title": title,
            "audioUrl": audio_url,
            "contentType": content_type,
            "durationSec": bundle.durationSec,
            "audioBytes": dest_audio.stat().st_size,
            "wordCount": len(bundle.wordIndexBlob.words),
            "adCount": len(bundle.adSegments),
            "createdAtMs": int(time.time() * 1000),
        }
        self._save_index()

    def get_content_type(self, sha256: str) -> str:
        meta = self._index["by_sha"].get(sha256, {})
        return meta.get("contentType", "audio/mpeg")

    def list_ready(self) -> list[DesktopReadyEpisode]:
        out: list[DesktopReadyEpisode] = []
        for sha, meta in self._index["by_sha"].items():
            if not self.has_complete(sha):
                continue
            out.append(
                DesktopReadyEpisode(
                    fileSha256=sha,
                    episodeId=meta.get("episodeId", ""),
                    title=meta.get("title", ""),
                    durationSec=float(meta.get("durationSec", 0.0)),
                    audioBytes=int(meta.get("audioBytes", 0)),
                    wordCount=int(meta.get("wordCount", 0)),
                    adCount=int(meta.get("adCount", 0)),
                    createdAtMs=int(meta.get("createdAtMs", 0)),
                )
            )
        out.sort(key=lambda e: e.createdAtMs, reverse=True)
        return out
