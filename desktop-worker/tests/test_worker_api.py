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


def test_auth_required_unless_insecure_no_auth(tmp_path: Path) -> None:
    import pytest

    cache = WorkerCache(tmp_path / "cache")
    with pytest.raises(ValueError, match="Worker token is required"):
        create_app(cache=cache, backend=FakeBackend(), token=None)


def test_models_not_ready_returns_503(tmp_path: Path) -> None:
    class UnreadyBackend(FakeBackend):
        def models_ready(self) -> bool:
            return False

    cache = WorkerCache(tmp_path / "cache")
    app = create_app(cache=cache, backend=UnreadyBackend(), token="t")
    with TestClient(app) as client:
        assert client.get("/v1/health").status_code == 503
        res = client.post(
            "/v1/jobs",
            headers={"Authorization": "Bearer t"},
            json={"episodeId": "ep1", "audioUrl": "https://example.com/1.mp3"},
        )
        assert res.status_code == 503


def test_ssrf_validation_blocks_loopback_metadata_and_private_lan() -> None:
    import pytest
    from hushcast_worker.audio import validate_fetch_url

    for bad in (
        "file:///etc/passwd",
        "http://127.0.0.1:8080/a.mp3",
        "http://localhost/a.mp3",
        "http://169.254.169.254/latest/meta-data/",
        "http://metadata.google.internal/",
    ):
        with pytest.raises(ValueError):
            validate_fetch_url(bad, allow_lan_feeds=False)

    with pytest.raises(ValueError):
        validate_fetch_url("http://192.168.1.10/a.mp3", allow_lan_feeds=False)
    assert validate_fetch_url("http://192.168.1.10/a.mp3", allow_lan_feeds=True).startswith("http://192.168.1.10")


def test_sha256_validation_and_public_hostname(tmp_path: Path) -> None:
    cache = WorkerCache(tmp_path / "cache")
    app = create_app(cache=cache, backend=FakeBackend(), token="t", public_deployment=True)
    with TestClient(app) as client:
        h = client.get("/v1/health")
        assert h.status_code == 200
        assert h.json()["hostname"] == ""

        bad = client.get("/v1/episodes/not-a-sha/bundle", headers={"Authorization": "Bearer t"})
        assert bad.status_code == 400


def test_cancel_job_and_job_persistence(tmp_path: Path) -> None:
    import asyncio

    async def slow_downloader(url: str, dest: Path, on_bytes):
        await asyncio.sleep(2.0)
        dest.write_bytes(b"abc")
        return hashlib.sha256(b"abc").hexdigest(), "audio/mpeg"

    cache_dir = tmp_path / "cache"
    cache = WorkerCache(cache_dir)
    app = create_app(cache=cache, backend=FakeBackend(), token="t", downloader=slow_downloader)
    headers = {"Authorization": "Bearer t"}
    with TestClient(app) as client:
        res = client.post(
            "/v1/jobs",
            headers=headers,
            json={"episodeId": "ep1", "audioUrl": "https://example.com/slow.mp3"},
        )
        jid = res.json()["jobId"]
        cancelled = client.delete(f"/v1/jobs/{jid}", headers=headers)
        assert cancelled.status_code == 200
        assert cancelled.json()["state"] == "CANCELLED"

    # Reload from disk
    reloaded = WorkerCache(cache_dir).load_jobs()
    assert jid in reloaded
    assert reloaded[jid].state == JobState.CANCELLED


def test_ad_tagger_decoder_and_pitch_parity() -> None:
    from hushcast_worker.ad_tagger import compute_segment_features, decode_ad_segments
    from hushcast_worker.audio import estimate_pitch_hz

    segs = [
        SegmentDto(start=0.0, end=2.0, text="Welcome back.", nWords=2, speaker=0),
        SegmentDto(start=2.0, end=5.0, text="This episode is brought to you by Acme.", nWords=8, speaker=0),
        SegmentDto(start=5.0, end=8.0, text="Visit acme.com today.", nWords=3, speaker=0),
        SegmentDto(start=8.0, end=11.0, text="Support the show as a member.", nWords=6, speaker=1),
        SegmentDto(start=11.0, end=14.0, text="Join our membership today.", nWords=4, speaker=1),
    ]
    feats = compute_segment_features(None, segs, duration_sec=14.0)
    assert feats.shape == (5, 28)
    assert np.isclose(feats[1, 14], 2.0 / 14.0)
    assert np.isclose(feats[1, 15], 3.0)
    assert feats[1, 16] == 8.0
    assert feats[3, 17] == 1.0  # speaker change

    probs = np.array(
        [
            [0.95, 0.02, 0.01, 0.01, 0.01],
            [0.05, 0.90, 0.02, 0.02, 0.01],
            [0.05, 0.85, 0.05, 0.03, 0.02],
            [0.05, 0.02, 0.02, 0.88, 0.03],
            [0.05, 0.02, 0.02, 0.90, 0.01],
        ],
        dtype=np.float32,
    )
    ads = decode_ad_segments(probs, segs)
    assert len(ads) == 2
    assert ads[0].adClass == "sponsor_ad"
    assert ads[0].isHighConfidence is True
    assert ads[0].label == "Sponsor: Acme"
    # F6: membership_pitch is marked only (not high-confidence auto-skip)
    assert ads[1].adClass == "membership_pitch"
    assert ads[1].isHighConfidence is False

    # Pitch test on 150 Hz sine wave
    t = np.arange(16000, dtype=np.float32) / 16000.0
    sine = np.sin(2.0 * np.pi * 150.0 * t)
    f0 = estimate_pitch_hz(sine, sr=16000)
    assert 145.0 <= f0 <= 155.0


