from __future__ import annotations

import hashlib
import time
from pathlib import Path

from fastapi.testclient import TestClient

import numpy as np

from hushcast_worker.models import (
    AdSegmentDto,
    DesktopAnalysisBundle,
    DesktopChapterDto,
    JobState,
    SegmentDto,
    WordDto,
    WordIndexBlobDto,
)
from hushcast_worker.server import create_app
from hushcast_worker.storage import WorkerCache


class FakeBackend:
    def models_ready(self) -> bool:
        return True

    def whisper_model_name(self) -> str:
        return "ggml-large-v3-turbo.bin"

    def chat_model_ready(self) -> bool:
        return False

    def embed_query(self, text: str) -> list[float]:
        if "gpu" in text.lower() or "metal" in text.lower():
            return [1.0, 0.0, 0.0, 0.0]
        return [0.0, 1.0, 0.0, 0.0]

    def analyze_with_embeddings(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw,
        feed_transcript_mime: str,
        on_progress,
    ):
        on_progress(JobState.TRANSCRIBING, 40, "Transcribing")
        on_progress(JobState.TAGGING, 90, "Tagging")
        bundle = DesktopAnalysisBundle(
            fileSha256=file_sha256,
            episodeId=episode_id,
            durationSec=12.5,
            wordIndexBlob=WordIndexBlobDto(
                words=[
                    WordDto(start=0.5, end=0.9, text="hello"),
                    WordDto(start=1.0, end=1.4, text="world"),
                ],
                segments=[
                    SegmentDto(start=0.5, end=1.4, text="hello world", nWords=2, speaker=0),
                    SegmentDto(start=2.0, end=3.5, text="Metal GPU acceleration is fast", nWords=5, speaker=1),
                ],
            ),
            adSegments=[
                AdSegmentDto(
                    startMs=5000,
                    endMs=10000,
                    confidence=0.91,
                    isHighConfidence=True,
                    label="Sponsor / Ad",
                )
            ],
            summary="A quick test about Metal GPU acceleration.",
            chapters=[DesktopChapterDto(startMs=500, title="Intro & Metal GPU")],
            whisperModel="ggml-large-v3-turbo.bin",
            analysisDurationMs=42,
            workerAccelerator="metal",
        )
        embs = np.array(
            [
                [0.0, 1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0, 0.0],
            ],
            dtype=np.float32,
        )
        return bundle, embs

    def analyze(self, **kwargs) -> DesktopAnalysisBundle:
        bundle, _ = self.analyze_with_embeddings(**kwargs)
        return bundle


def test_job_lifecycle_auth_and_sha256_serving(tmp_path: Path) -> None:
    payload = b"FAKE_MP3_BYTES_FOR_HUSHCAST_TEST"
    expected_sha = hashlib.sha256(payload).hexdigest()

    async def fake_downloader(url: str, dest: Path, on_bytes):
        dest.write_bytes(payload)
        if on_bytes:
            on_bytes(len(payload), len(payload))
        return expected_sha, "audio/mpeg"

    cache = WorkerCache(tmp_path / "cache")
    app = create_app(
        cache=cache,
        backend=FakeBackend(),
        token="secret-token",
        downloader=fake_downloader,
    )

    with TestClient(app) as client:
        # Health is open
        h = client.get("/v1/health")
        assert h.status_code == 200
        assert h.json()["modelsReady"] is True
        assert h.json()["whisperModel"] == "ggml-large-v3-turbo.bin"
        assert h.json()["chatModelReady"] is False

        # Protected endpoints reject missing token
        unauth = client.post(
            "/v1/jobs",
            json={"episodeId": "ep1", "audioUrl": "https://example.com/1.mp3"},
        )
        assert unauth.status_code == 401

        headers = {"Authorization": "Bearer secret-token"}
        res = client.post(
            "/v1/jobs",
            headers=headers,
            json={
                "episodeId": "ep1",
                "audioUrl": "https://example.com/1.mp3",
                "title": "Test Ep",
            },
        )
        assert res.status_code == 200
        job_id = res.json()["jobId"]

        # Poll until READY
        job_data = None
        for _ in range(30):
            poll = client.get(f"/v1/jobs/{job_id}", headers=headers)
            assert poll.status_code == 200
            job_data = poll.json()
            if job_data["state"] == "READY":
                break
            time.sleep(0.02)

        assert job_data is not None
        assert job_data["state"] == "READY"
        assert job_data["fileSha256"] == expected_sha

        # Fetch bundle
        bundle_res = client.get(
            f"/v1/episodes/{expected_sha}/bundle", headers=headers
        )
        assert bundle_res.status_code == 200
        bundle = bundle_res.json()
        assert bundle["fileSha256"] == expected_sha
        assert bundle["whisperModel"] == "ggml-large-v3-turbo.bin"
        assert bundle["summary"] == "A quick test about Metal GPU acceleration."
        assert bundle["chapters"][0]["title"] == "Intro & Metal GPU"
        assert bundle["wordIndexBlob"]["segments"][0]["speaker"] == 0
        assert bundle["wordIndexBlob"]["segments"][1]["speaker"] == 1
        assert len(bundle["wordIndexBlob"]["words"]) == 2
        assert bundle["adSegments"][0]["isHighConfidence"] is True

        # Fetch exact audio bytes + SHA header
        audio_res = client.get(
            f"/v1/episodes/{expected_sha}/audio", headers=headers
        )
        assert audio_res.status_code == 200
        assert audio_res.content == payload
        assert audio_res.headers["X-HushCast-Sha256"] == expected_sha

        # Semantic search across cached episodes
        search_res = client.post(
            "/v1/search",
            headers=headers,
            json={"query": "Metal GPU", "topK": 5},
        )
        assert search_res.status_code == 200
        hits = search_res.json()["hits"]
        assert len(hits) >= 1
        assert hits[0]["text"] == "Metal GPU acceleration is fast"
        assert hits[0]["episodeTitle"] == "Test Ep"
        assert hits[0]["score"] > 0.9

        # List ready episodes
        eps = client.get("/v1/episodes", headers=headers)
        assert eps.status_code == 200
        assert len(eps.json()) == 1
        assert eps.json()[0]["fileSha256"] == expected_sha

        # Re-submitting without force hits cache immediately
        cached_res = client.post(
            "/v1/jobs",
            headers=headers,
            json={
                "episodeId": "ep1",
                "audioUrl": "https://example.com/1.mp3",
                "title": "Test Ep",
            },
        )
        assert cached_res.status_code == 200
        assert cached_res.json()["state"] == "READY"
        assert cached_res.json()["stageLabel"] == "Cached on desktop"

        # Re-submitting with force=True bypasses cache and runs a fresh job
        forced_res = client.post(
            "/v1/jobs",
            headers=headers,
            json={
                "episodeId": "ep1",
                "audioUrl": "https://example.com/1.mp3",
                "title": "Test Ep",
                "force": True,
            },
        )
        assert forced_res.status_code == 200
        forced_id = forced_res.json()["jobId"]
        assert forced_res.json()["stageLabel"] == "Reprocessing on desktop"
        forced_data = None
        for _ in range(30):
            poll = client.get(f"/v1/jobs/{forced_id}", headers=headers)
            assert poll.status_code == 200
            forced_data = poll.json()
            if forced_data["state"] == "READY":
                break
            time.sleep(0.02)
        assert forced_data is not None
        assert forced_data["state"] == "READY"
        assert forced_data["stageLabel"] == "Ready"

        # Clear audio-only cache (keeps bundle/embeddings, removes .audio)
        clear_audio = client.delete("/v1/cache?audio_only=true", headers=headers)
        assert clear_audio.status_code == 200
        assert clear_audio.json()["removedFiles"] == 1
        assert clear_audio.json()["freedBytes"] == len(payload)
        assert client.get(f"/v1/episodes/{expected_sha}/bundle", headers=headers).status_code == 200
        assert client.get(f"/v1/episodes/{expected_sha}/audio", headers=headers).status_code == 404

        # Clear full cache
        clear_all = client.delete("/v1/cache", headers=headers)
        assert clear_all.status_code == 200
        assert clear_all.json()["removedFiles"] >= 1
        assert client.get("/v1/episodes", headers=headers).json() == []

