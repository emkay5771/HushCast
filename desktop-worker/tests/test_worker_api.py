from __future__ import annotations

import hashlib
import time
from pathlib import Path

from fastapi.testclient import TestClient

from hushcast_worker.models import (
    AdSegmentDto,
    DesktopAnalysisBundle,
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

    def analyze(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw,
        feed_transcript_mime: str,
        on_progress,
    ) -> DesktopAnalysisBundle:
        on_progress(JobState.TRANSCRIBING, 40, "Transcribing")
        on_progress(JobState.TAGGING, 90, "Tagging")
        return DesktopAnalysisBundle(
            fileSha256=file_sha256,
            episodeId=episode_id,
            durationSec=12.5,
            wordIndexBlob=WordIndexBlobDto(
                words=[
                    WordDto(start=0.5, end=0.9, text="hello"),
                    WordDto(start=1.0, end=1.4, text="world"),
                ],
                segments=[
                    SegmentDto(start=0.5, end=1.4, text="hello world", nWords=2)
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
            analysisDurationMs=42,
            workerAccelerator="metal",
        )


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
        assert len(bundle["wordIndexBlob"]["words"]) == 2
        assert bundle["adSegments"][0]["isHighConfidence"] is True

        # Fetch exact audio bytes + SHA header
        audio_res = client.get(
            f"/v1/episodes/{expected_sha}/audio", headers=headers
        )
        assert audio_res.status_code == 200
        assert audio_res.content == payload
        assert audio_res.headers["X-HushCast-Sha256"] == expected_sha

        # List ready episodes
        eps = client.get("/v1/episodes", headers=headers)
        assert eps.status_code == 200
        assert len(eps.json()) == 1
        assert eps.json()[0]["fileSha256"] == expected_sha
