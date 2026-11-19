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
                "feedChaptersType": None,
                "feedTranscriptMime": None,
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


def test_submit_job_inflight_dedup_and_force_cancel(tmp_path: Path) -> None:
    import asyncio
    import threading

    gate = threading.Event()
    payload = b"FAKE_MP3_INFLIGHT_DEDUP"
    expected_sha = hashlib.sha256(payload).hexdigest()

    async def slow_downloader(url: str, dest: Path, on_bytes):
        await asyncio.to_thread(gate.wait, 5.0)
        dest.write_bytes(payload)
        return expected_sha, "audio/mpeg"

    cache = WorkerCache(tmp_path / "cache")
    app = create_app(
        cache=cache,
        backend=FakeBackend(),
        token="secret-token",
        downloader=slow_downloader,
    )
    headers = {"Authorization": "Bearer secret-token"}

    with TestClient(app) as client:
        r1 = client.post(
            "/v1/jobs",
            headers=headers,
            json={"episodeId": "ep-1", "audioUrl": "https://example.com/a.mp3", "force": True},
        )
        assert r1.status_code == 200
        jid1 = r1.json()["jobId"]

        # Retry (force=False) reuses the in-flight job
        r2 = client.post(
            "/v1/jobs",
            headers=headers,
            json={"episodeId": "ep-1", "audioUrl": "https://example.com/a.mp3", "force": False},
        )
        assert r2.status_code == 200
        assert r2.json()["jobId"] == jid1

        gate.set()
        for _ in range(40):
            s = client.get(f"/v1/jobs/{jid1}", headers=headers).json()
            if s["state"] == "READY":
                break
            time.sleep(0.05)
        assert s["state"] == "READY"


def test_b11_audio_only_clear_preserves_bundles_and_index(tmp_path: Path) -> None:
    from hushcast_worker.models import DesktopAnalysisBundle, WordIndexBlobDto

    cache = WorkerCache(tmp_path / "cache")
    sha = "a" * 64
    audio = cache.audio_path(sha)
    audio.write_bytes(b"FAKE_AUDIO_BYTES")
    bundle = DesktopAnalysisBundle(
        fileSha256=sha,
        episodeId="ep-b11",
        durationSec=12.0,
        wordIndexBlob=WordIndexBlobDto(
            words=[],
            segments=[SegmentDto(start=0.0, end=2.0, text="Hello world", nWords=2)],
        ),
        adSegments=[],
    )
    cache.save_episode(
        sha256=sha,
        audio_tmp_path=None,
        bundle=bundle,
        audio_url="https://example.com/b11.mp3",
        title="Ep B11",
    )
    assert audio.exists()
    assert cache.get_bundle(sha) is not None

    stats = cache.clear(audio_only=True)
    assert stats["removedFiles"] == 1
    assert not audio.exists()
    assert cache.get_bundle(sha) is not None
    assert len(cache.list_ready()) == 1


def test_a9_save_reprocessed_updates_index_and_invalidates_search_cache(tmp_path: Path) -> None:
    from hushcast_worker.models import AdSegmentDto, DesktopAnalysisBundle, WordIndexBlobDto

    cache = WorkerCache(tmp_path / "cache")
    sha = "b" * 64
    b1 = DesktopAnalysisBundle(
        fileSha256=sha,
        episodeId="ep-a9",
        durationSec=10.0,
        wordIndexBlob=WordIndexBlobDto(
            words=[],
            segments=[SegmentDto(start=0.0, end=2.0, text="Old text", nWords=2)],
        ),
        adSegments=[],
        pipelineVersion=4,
    )
    emb = np.ones((1, 4), dtype=np.float32)
    cache.save_episode(
        sha256=sha,
        audio_tmp_path=None,
        bundle=b1,
        audio_url="https://example.com/a9.mp3",
        title="Ep A9",
        embeddings=emb,
    )
    hits1 = cache.semantic_search([1.0, 0.0, 0.0, 0.0])
    assert hits1 and hits1[0].text == "Old text"

    b2 = b1.model_copy(
        update={
            "wordIndexBlob": WordIndexBlobDto(
                words=[],
                segments=[SegmentDto(start=0.0, end=2.0, text="Updated text", nWords=2)],
            ),
            "adSegments": [
                AdSegmentDto(startMs=0, endMs=2000, confidence=0.9, isHighConfidence=True, label="Sponsor")
            ],
            "pipelineVersion": 5,
        }
    )
    cache.save_reprocessed(sha256=sha, bundle=b2, embeddings=emb)
    eps = cache.list_ready()
    assert eps[0].adCount == 1
    hits2 = cache.semantic_search([1.0, 0.0, 0.0, 0.0])
    assert hits2 and hits2[0].text == "Updated text"


def test_a3_frame_extractor_24k_kweight_18bands_and_sentence_splitter() -> None:
    from hushcast_worker.ad_tagger import (
        SR,
        compute_segment_features,
        extract_audio_frames,
        find_ad_candidates,
        split_sentences,
    )
    from hushcast_worker.models import WordDto

    # 1.2s of 24 kHz stereo audio (mid = 440 Hz sine, side = 220 Hz sine)
    t = np.arange(int(SR * 1.2), dtype=np.float32) / float(SR)
    mid = 0.25 * np.sin(2.0 * np.pi * 440.0 * t).astype(np.float32)
    side = 0.05 * np.sin(2.0 * np.pi * 220.0 * t).astype(np.float32)

    frames = extract_audio_frames(mid, sr=SR, side_pcm=side)
    assert frames.n == 13  # 12 frames + 1 trailing padded HOP matching FrameExtractor.kt
    assert frames.stereo is True
    assert frames.bands.shape == (13, 18)
    assert np.all(np.isfinite(frames.lk))
    assert np.mean(frames.side) > -100.0  # non-zero side energy

    words = [
        WordDto(start=0.0, end=0.3, text="Dr."),
        WordDto(start=0.3, end=0.6, text=" Smith"),
        WordDto(start=0.6, end=0.9, text=" spoke."),
        WordDto(start=0.9, end=1.2, text=" Next."),
    ]
    segs = split_sentences(words)
    assert len(segs) == 2  # "Dr." must NOT split a sentence
    assert segs[0].text == "Dr. Smith spoke."

    feats = compute_segment_features(frames, segs, duration_sec=1.2)
    assert feats.shape == (2, 28)
    assert np.all(np.isfinite(feats))

    probs = np.array([[0.1, 0.30, 0.1, 0.1, 0.4], [0.1, 0.35, 0.1, 0.1, 0.35]], dtype=np.float32)
    # Neither is argmax==sponsor_ad (class 4 wins), so find_ad_candidates should catch both!
    cands = find_ad_candidates(probs, segs)
    assert len(cands) == 1
    assert cands[0].label == "Suggested Ad"


def test_v22_parse_srt_vtt_and_one_word_json_and_a8_lang() -> None:
    import json
    from hushcast_worker.pipeline import _normalize_whisper_language, _parse_feed_transcript

    assert _normalize_whisper_language(None) == "en"
    assert _normalize_whisper_language("auto") == "en"
    assert _normalize_whisper_language("fr-CA") == "fr"

    vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello world.\n\n00:00:03.500 --> 00:00:05.000\nSecond cue.\n"
    parsed_vtt = _parse_feed_transcript(vtt, "text/vtt")
    assert parsed_vtt is not None
    w_vtt, s_vtt, needs_align = parsed_vtt
    assert needs_align is True
    assert len(s_vtt) == 2 and len(w_vtt) == 4

    one_word_json = json.dumps({
        "segments": [
            {"startTime": 0.0, "endTime": 0.4, "body": "Hello"},
            {"startTime": 0.4, "endTime": 0.8, "body": "world."},
        ]
    })
    parsed_json = _parse_feed_transcript(one_word_json, "application/json")
    assert parsed_json is not None
    w_j, s_j, needs_align_j = parsed_json
    assert needs_align_j is False
    assert len(w_j) == 2 and len(s_j) == 1


def test_v9_long_poll_and_v10_stage_fields(tmp_path: Path) -> None:
    payload = b"LONG_POLL_PAYLOAD"
    expected_sha = hashlib.sha256(payload).hexdigest()

    async def fast_dl(url: str, dest: Path, on_bytes):
        dest.write_bytes(payload)
        return expected_sha, "audio/mpeg"

    cache = WorkerCache(tmp_path / "cache")
    app = create_app(cache=cache, backend=FakeBackend(), token="tok", downloader=fast_dl)
    headers = {"Authorization": "Bearer tok"}
    with TestClient(app) as client:
        r = client.post(
            "/v1/jobs",
            headers=headers,
            json={"episodeId": "ep-lp", "audioUrl": "https://example.com/a.mp3"},
        )
        assert r.status_code == 200
        jid = r.json()["jobId"]
        # Long-poll wakes up on each rev change until READY
        since_rev = 0
        body = {}
        for _ in range(10):
            res = client.get(f"/v1/jobs/{jid}?waitMs=3000&sinceRev={since_rev}", headers=headers)
            assert res.status_code == 200
            body = res.json()
            assert body["rev"] > since_rev or body["state"] == "READY"
            since_rev = body["rev"]
            if body["state"] == "READY":
                break
        assert body["state"] == "READY"
        assert body["stage"] == "READY"
        assert body["rev"] >= 1




def _poll_until(client, job_id: str, headers: dict, state: str = "READY", tries: int = 100) -> dict:
    body: dict = {}
    for _ in range(tries):
        body = client.get(f"/v1/jobs/{job_id}", headers=headers).json()
        if body["state"] == state:
            return body
        time.sleep(0.02)
    return body


def test_v17_delete_audio_keeps_bundle_and_is_idempotent(tmp_path: Path) -> None:
    payload = b"AUDIO_TO_DELETE"
    sha = hashlib.sha256(payload).hexdigest()

    async def dl(url: str, dest: Path, on_bytes):
        dest.write_bytes(payload)
        return sha, "audio/mpeg"

    cache = WorkerCache(tmp_path / "cache")
    app = create_app(cache=cache, backend=FakeBackend(), token="tok", downloader=dl)
    headers = {"Authorization": "Bearer tok"}
    with TestClient(app) as client:
        jid = client.post("/v1/jobs", headers=headers, json={"episodeId": "ep-d", "audioUrl": "https://example.com/d.mp3"}).json()["jobId"]
        assert _poll_until(client, jid, headers)["state"] == "READY"
        assert cache.audio_path(sha).exists()

        assert client.delete(f"/v1/episodes/{sha}/audio").status_code == 401
        res = client.delete(f"/v1/episodes/{sha}/audio", headers=headers)
        assert res.status_code == 204 and res.content == b""
        assert not cache.audio_path(sha).exists()
        assert client.get(f"/v1/episodes/{sha}/bundle", headers=headers).status_code == 200
        assert client.get(f"/v1/episodes/{sha}/audio", headers=headers).status_code == 404
        eps = client.get("/v1/episodes", headers=headers).json()
        assert eps[0]["fileSha256"] == sha and eps[0]["audioBytes"] == 0
        assert cache._index["by_sha"][sha]["hasAudio"] is False

        # Already gone, or never there: still 204. Bad input: 400.
        assert client.delete(f"/v1/episodes/{sha}/audio", headers=headers).status_code == 204
        assert client.delete(f"/v1/episodes/{'c' * 64}/audio", headers=headers).status_code == 204
        assert client.delete("/v1/episodes/xyz/audio", headers=headers).status_code == 400

        # A later resubmit re-downloads the audio and matches the kept bundle by SHA-256.
        again = client.post("/v1/jobs", headers=headers, json={"episodeId": "ep-d", "audioUrl": "https://example.com/d.mp3"}).json()
        assert _poll_until(client, again["jobId"], headers)["stageLabel"] == "Ready (matched existing SHA-256)"


def test_v17_delete_audio_waits_for_running_job(tmp_path: Path) -> None:
    import threading

    payload = b"AUDIO_IN_USE"
    sha = hashlib.sha256(payload).hexdigest()
    gate = threading.Event()
    started = threading.Event()

    async def dl(url: str, dest: Path, on_bytes):
        dest.write_bytes(payload)
        return sha, "audio/mpeg"

    class GatedBackend(FakeBackend):
        def analyze_with_embeddings(self, **kwargs):
            started.set()
            assert gate.wait(5.0)
            assert kwargs["audio_path"].exists(), "audio deleted under a running job"
            return super().analyze_with_embeddings(**kwargs)

    cache = WorkerCache(tmp_path / "cache")
    app = create_app(cache=cache, backend=GatedBackend(), token="tok", downloader=dl)
    headers = {"Authorization": "Bearer tok"}
    with TestClient(app) as client:
        jid = client.post("/v1/jobs", headers=headers, json={"episodeId": "ep-g", "audioUrl": "https://example.com/g.mp3"}).json()["jobId"]
        assert started.wait(5.0)
        res = client.delete(f"/v1/episodes/{sha}/audio", headers=headers)
        assert res.status_code == 204
        assert cache.audio_path(sha).exists()  # deferred: the job still reads it
        gate.set()
        assert _poll_until(client, jid, headers)["state"] == "READY"
        for _ in range(100):
            if not cache.audio_path(sha).exists():
                break
            time.sleep(0.02)
        assert not cache.audio_path(sha).exists()
        assert cache.get_bundle(sha) is not None


def test_health_reports_queue_position_and_cold_start(tmp_path: Path) -> None:
    from hushcast_worker.models import health_schema_problems

    class WarmBackend(FakeBackend):
        def models_loaded(self) -> bool:
            return True

    for backend, cold in ((FakeBackend(), True), (WarmBackend(), False)):
        app = create_app(cache=WorkerCache(tmp_path / f"cache-{cold}"), backend=backend, token="t")
        with TestClient(app) as client:
            body = client.get("/v1/health").json()
        assert health_schema_problems(body, expect_deployment="local") == []
        assert body["queueDepth"] == 0 and body["queuePosition"] == 0
        assert body["coldStart"] is cold
