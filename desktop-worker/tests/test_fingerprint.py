from __future__ import annotations

import numpy as np

from hushcast_worker.fingerprint import (
    SAMPLE_RATE,
    FingerprintStore,
    compute_fingerprint,
    find_repeated_segments,
    merge_fingerprint_segments,
)
from hushcast_worker.models import AdSegmentDto
from hushcast_worker.storage import WorkerCache


def _synth_segment(duration_sec: float, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    n = int(duration_sec * SAMPLE_RATE)
    t = np.arange(n, dtype=np.float32) / SAMPLE_RATE
    # Multi-tone + modulated harmonic texture so spectral energy diffs are rich
    sig = np.zeros(n, dtype=np.float32)
    for k in range(6):
        freq = rng.uniform(220.0, 2800.0)
        mod = rng.uniform(1.5, 6.0)
        sig += 0.15 * np.sin(2.0 * np.pi * (freq + 40.0 * np.sin(2.0 * np.pi * mod * t)) * t)
    # Add mild broadband texture
    sig += 0.04 * rng.normal(0.0, 1.0, size=n).astype(np.float32)
    return sig


def test_fingerprint_detects_intro_outro_and_midroll_promo():
    intro = _synth_segment(12.0, seed=101)
    outro = _synth_segment(10.0, seed=202)
    promo = _synth_segment(9.0, seed=303)

    ep1 = np.concatenate([intro, _synth_segment(35.0, seed=1), promo, _synth_segment(35.0, seed=2), outro])
    # Shift intro slightly via a 4s pre-roll ad in ep2 and add slight noise/gain change
    preroll = _synth_segment(4.0, seed=404)
    ep2_raw = np.concatenate([preroll, intro, _synth_segment(30.0, seed=3), promo, _synth_segment(30.0, seed=4), outro])
    rng = np.random.default_rng(999)
    ep2 = (ep2_raw * 0.85 + 0.004 * rng.normal(0.0, 1.0, size=len(ep2_raw))).astype(np.float32)

    fp1 = compute_fingerprint(ep1, file_sha256="a" * 64, podcast_key="https://example.com/feed.xml")
    fp2 = compute_fingerprint(ep2, file_sha256="b" * 64, podcast_key="https://example.com/feed.xml")

    segs = find_repeated_segments(fp2, [fp1])
    classes = {(s.adClass, s.label) for s in segs}
    assert ("boilerplate", "Intro") in classes
    assert ("boilerplate", "Outro") in classes
    assert ("house_promo", "Recurring Promo") in classes

    intro_seg = next(s for s in segs if s.label == "Intro")
    assert 3000 <= intro_seg.startMs <= 5000
    assert 15000 <= intro_seg.endMs <= 17000


def test_fingerprint_ignores_silence():
    silence = np.zeros(int(20.0 * SAMPLE_RATE), dtype=np.float32)
    ep1 = np.concatenate([silence, _synth_segment(15.0, seed=11)])
    ep2 = np.concatenate([silence, _synth_segment(15.0, seed=22)])

    fp1 = compute_fingerprint(ep1, file_sha256="c" * 64, podcast_key="show")
    fp2 = compute_fingerprint(ep2, file_sha256="d" * 64, podcast_key="show")

    assert find_repeated_segments(fp2, [fp1]) == []


def test_fingerprint_cross_show_promo_classified_as_house_promo():
    promo = _synth_segment(10.0, seed=555)
    ep_show_a = np.concatenate([_synth_segment(20.0, seed=12), promo, _synth_segment(20.0, seed=13)])
    ep_show_b = np.concatenate([promo, _synth_segment(40.0, seed=14)])

    fp_a = compute_fingerprint(ep_show_a, file_sha256="e" * 64, podcast_key="show_a")
    fp_b = compute_fingerprint(ep_show_b, file_sha256="f" * 64, podcast_key="show_b")

    segs = find_repeated_segments(fp_b, [fp_a])
    assert len(segs) == 1
    assert segs[0].adClass == "house_promo"
    assert segs[0].label == "Recurring Promo"


def test_merge_fingerprint_segments_preserves_existing_sponsor_ads():
    existing = [
        AdSegmentDto(startMs=10_000, endMs=30_000, confidence=0.95, isHighConfidence=True, label="Sponsor / Ad", adClass="sponsor_ad"),
    ]
    fp_segs = [
        AdSegmentDto(startMs=0, endMs=12_000, confidence=0.92, isHighConfidence=True, label="Intro", adClass="boilerplate"),
        AdSegmentDto(startMs=11_000, endMs=29_000, confidence=0.92, isHighConfidence=True, label="Recurring Promo", adClass="house_promo"),
    ]
    merged = merge_fingerprint_segments(existing, fp_segs)
    assert len(merged) == 2
    assert merged[0].label == "Intro"
    assert merged[1].label == "Sponsor / Ad"


def test_fingerprint_store_roundtrip_pruning_and_clear(tmp_path):
    store = FingerprintStore(tmp_path, max_per_show=2, max_total=3)
    for idx in range(3):
        fp = compute_fingerprint(
            _synth_segment(8.0, seed=700 + idx),
            file_sha256=f"{idx:064x}",
            podcast_key="feed-1",
        )
        store.save(fp)

    refs = store.load_references(exclude_sha256="9" * 64, podcast_key="feed-1")
    assert len(refs) == 2  # pruned to max_per_show=2

    cache = WorkerCache(tmp_path)
    cache.clear(audio_only=False)
    assert store.load_references(exclude_sha256="9" * 64, podcast_key="feed-1") == []


def test_fingerprint_ignores_same_episode_dai_redownload():
    body = _synth_segment(40.0, seed=808)
    ep_dai_a = np.concatenate([_synth_segment(12.0, seed=801), body])
    ep_dai_b = np.concatenate([_synth_segment(8.0, seed=802), body])

    # Same episode_id with different file_sha256 (DAI ad shift) must be skipped.
    fp_a = compute_fingerprint(ep_dai_a, file_sha256="1" * 64, podcast_key="show", episode_id="ep-2552")
    fp_b = compute_fingerprint(ep_dai_b, file_sha256="2" * 64, podcast_key="show", episode_id="ep-2552")
    assert find_repeated_segments(fp_b, [fp_a]) == []

    # Even if episode_id is empty/mismatched (legacy cache), >35% full-episode match is rejected.
    fp_a_legacy = compute_fingerprint(ep_dai_a, file_sha256="1" * 64, podcast_key="show", episode_id="")
    fp_b_other = compute_fingerprint(ep_dai_b, file_sha256="2" * 64, podcast_key="show", episode_id="ep-2552")
    assert find_repeated_segments(fp_b_other, [fp_a_legacy]) == []


def test_fingerprint_store_multiwriter_preserves_both(tmp_path):
    store_a = FingerprintStore(tmp_path)
    store_b = FingerprintStore(tmp_path)
    reader = FingerprintStore(tmp_path)

    fp_a = compute_fingerprint(_synth_segment(8.0, seed=901), file_sha256="a" * 64, podcast_key="show", episode_id="ep-a")
    fp_b = compute_fingerprint(_synth_segment(8.0, seed=902), file_sha256="b" * 64, podcast_key="show", episode_id="ep-b")

    store_a.save(fp_a)
    store_b.save(fp_b)
    # Simulate an isolated writer overwriting index.json without fp_a
    store_b.index_path.write_text("{}", encoding="utf-8")

    refs = reader.load_references(exclude_sha256="c" * 64, podcast_key="show")
    assert {r.file_sha256 for r in refs} == {"a" * 64, "b" * 64}
