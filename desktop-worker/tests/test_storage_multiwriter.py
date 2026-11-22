import json
import time
from pathlib import Path

from hushcast_worker.models import (
    PIPELINE_VERSION,
    DesktopAnalysisBundle,
    SegmentDto,
    WordDto,
    WordIndexBlobDto,
)
from hushcast_worker.storage import WorkerCache


def _dummy_bundle(sha: str, ep_id: str) -> DesktopAnalysisBundle:
    return DesktopAnalysisBundle(
        fileSha256=sha,
        episodeId=ep_id,
        durationSec=60.0,
        wordIndexBlob=WordIndexBlobDto(
            words=[WordDto(start=0.0, end=0.5, text="hello")],
            segments=[SegmentDto(start=0.0, end=0.5, text="hello", nWords=1)],
        ),
        adSegments=[],
        summary="Test",
        chapters=[],
        whisperModel="large-v3-turbo",
        analysisDurationMs=100,
        workerAccelerator="cuda",
        pipelineVersion=PIPELINE_VERSION,
    )


def test_multiwriter_cache_preserves_both_episodes(tmp_path: Path) -> None:
    writer_a = WorkerCache(tmp_path)
    writer_b = WorkerCache(tmp_path)
    reader = WorkerCache(tmp_path)

    sha_a = "a" * 64
    sha_b = "b" * 64

    writer_a.save_episode(
        sha256=sha_a,
        audio_tmp_path=None,
        bundle=_dummy_bundle(sha_a, "ep-a"),
        audio_url="https://example.com/a.mp3",
        title="Episode A",
    )
    # writer_b has stale in-memory _index from before writer_a saved, then saves ep-b.
    writer_b.save_episode(
        sha256=sha_b,
        audio_tmp_path=None,
        bundle=_dummy_bundle(sha_b, "ep-b"),
        audio_url="https://example.com/b.mp3",
        title="Episode B",
    )

    reader.force_reload_index()
    ready_shas = {ep.fileSha256 for ep in reader.list_ready()}
    assert ready_shas == {sha_a, sha_b}
    assert reader.lookup_by_episode_id("ep-a", "https://example.com/a.mp3") == sha_a
    assert reader.lookup_by_episode_id("ep-b", "https://example.com/b.mp3") == sha_b


def test_stale_index_json_missing_episode_still_recovers_from_meta(tmp_path: Path) -> None:
    writer_a = WorkerCache(tmp_path)
    writer_b = WorkerCache(tmp_path)
    reader = WorkerCache(tmp_path)

    sha_a = "a" * 64
    sha_b = "b" * 64

    writer_a.save_episode(
        sha256=sha_a,
        audio_tmp_path=None,
        bundle=_dummy_bundle(sha_a, "ep-a"),
        audio_url="https://example.com/a.mp3",
        title="Episode A",
    )
    writer_b.save_episode(
        sha256=sha_b,
        audio_tmp_path=None,
        bundle=_dummy_bundle(sha_b, "ep-b"),
        audio_url="https://example.com/b.mp3",
        title="Episode B",
    )

    # Simulate an isolated container overwriting index.json with only sha_b (leaving out sha_a).
    idx_data = json.loads(writer_b.index_path.read_text(encoding="utf-8"))
    idx_data["by_sha"].pop(sha_a, None)
    idx_data["by_url"].pop("https://example.com/a.mp3", None)
    writer_b.index_path.write_text(json.dumps(idx_data), encoding="utf-8")

    reader.force_reload_index()
    assert {ep.fileSha256 for ep in reader.list_ready()} == {sha_a, sha_b}


def test_touch_audio_last_accessed_persists_across_save(tmp_path: Path) -> None:
    cache = WorkerCache(tmp_path)
    sha_a = "a" * 64
    sha_b = "b" * 64

    cache.save_episode(
        sha256=sha_a,
        audio_tmp_path=None,
        bundle=_dummy_bundle(sha_a, "ep-a"),
        audio_url="https://example.com/a.mp3",
        title="Episode A",
    )
    time.sleep(0.01)
    cache.touch_audio(sha_a)
    touched_ts = cache._index["by_sha"][sha_a]["lastAccessedAtMs"]

    # Saving another episode triggers _save_index() -> _merge_meta_files(); touch must survive.
    cache.save_episode(
        sha256=sha_b,
        audio_tmp_path=None,
        bundle=_dummy_bundle(sha_b, "ep-b"),
        audio_url="https://example.com/b.mp3",
        title="Episode B",
    )
    assert cache._index["by_sha"][sha_a]["lastAccessedAtMs"] == touched_ts


def test_has_audio_false_after_eviction_and_audio_only_clear(tmp_path: Path) -> None:
    cache = WorkerCache(tmp_path, max_cache_bytes=10)
    sha_a = "a" * 64
    sha_b = "b" * 64

    audio_a = tmp_path / "a.part"
    audio_a.write_bytes(b"0" * 20)
    cache.save_episode(
        sha256=sha_a,
        audio_tmp_path=audio_a,
        bundle=_dummy_bundle(sha_a, "ep-a"),
        audio_url="https://example.com/a.mp3",
        title="Episode A",
    )
    audio_b_evict = tmp_path / "b_evict.part"
    audio_b_evict.write_bytes(b"1" * 20)
    cache.save_episode(
        sha256=sha_b,
        audio_tmp_path=audio_b_evict,
        bundle=_dummy_bundle(sha_b, "ep-b"),
        audio_url="https://example.com/b.mp3",
        title="Episode B",
    )
    assert cache._index["by_sha"][sha_a]["hasAudio"] is False  # evicted when sha_b was saved (>10 bytes)
    cache.force_reload_index()
    assert cache._index["by_sha"][sha_a]["hasAudio"] is False

    # Now test clear(audio_only=True)
    cache_big = WorkerCache(tmp_path / "big")
    audio_b = tmp_path / "b.part"
    audio_b.write_bytes(b"1" * 20)
    cache_big.save_episode(
        sha256=sha_b,
        audio_tmp_path=audio_b,
        bundle=_dummy_bundle(sha_b, "ep-b"),
        audio_url="https://example.com/b.mp3",
        title="Episode B",
    )
    assert cache_big._index["by_sha"][sha_b]["hasAudio"] is True
    cache_big.clear(audio_only=True)
    cache_big.force_reload_index()
    assert cache_big._index["by_sha"][sha_b]["hasAudio"] is False
