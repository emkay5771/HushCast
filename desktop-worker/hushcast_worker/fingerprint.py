from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import time
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

import numpy as np

from .models import AdSegmentDto

SAMPLE_RATE = 16000
HOP_SAMPLES = 1600  # 100 ms per frame (10 Hz)
FRAME_MS = 100
FFT_SIZE = 2048
NUM_BANDS = 17  # 16 energy-diff bits per frame
MIN_RMS = 1e-3
MIN_MATCH_FRAMES = 60  # 6.0 seconds
MAX_HAMMING_DIST = 3
MAX_GAP_FRAMES = 4
MIN_ANCHOR_VOTES = 12


def _build_band_edges(sample_rate: int = SAMPLE_RATE, fft_size: int = FFT_SIZE, n_bands: int = NUM_BANDS) -> np.ndarray:
    """Log-spaced FFT bin edges covering 150 Hz .. 5500 Hz."""
    freqs = np.geomspace(150.0, 5500.0, n_bands + 1)
    bins = np.round(freqs * fft_size / sample_rate).astype(np.int32)
    # Ensure strictly monotonic bin boundaries
    for i in range(1, len(bins)):
        if bins[i] <= bins[i - 1]:
            bins[i] = bins[i - 1] + 1
    max_bin = fft_size // 2
    return np.clip(bins, 1, max_bin)


_BAND_EDGES = _build_band_edges()
_HANN_WINDOW = np.hanning(FFT_SIZE).astype(np.float32)


@dataclass(frozen=True)
class EpisodeFingerprint:
    file_sha256: str
    podcast_key: str
    duration_ms: int
    hashes: np.ndarray  # uint16[n_frames]
    active: np.ndarray  # bool[n_frames]

    def to_bytes(self) -> bytes:
        n = len(self.hashes)
        packed_active = np.packbits(self.active.astype(np.uint8)).tobytes()
        header = n.to_bytes(4, "little") + self.duration_ms.to_bytes(8, "little")
        payload = header + self.hashes.astype("<u2", copy=False).tobytes() + packed_active
        return zlib.compress(payload, level=6)

    @classmethod
    def from_bytes(cls, data: bytes, *, file_sha256: str, podcast_key: str) -> "EpisodeFingerprint":
        raw = zlib.decompress(data)
        n = int.from_bytes(raw[0:4], "little")
        duration_ms = int.from_bytes(raw[4:12], "little")
        hash_end = 12 + n * 2
        hashes = np.frombuffer(raw[12:hash_end], dtype="<u2").copy()
        packed = np.frombuffer(raw[hash_end:], dtype=np.uint8)
        active = np.unpackbits(packed)[:n].astype(bool)
        return cls(
            file_sha256=file_sha256,
            podcast_key=podcast_key,
            duration_ms=duration_ms,
            hashes=hashes,
            active=active,
        )


def normalize_podcast_key(podcast_origin: Optional[str], podcast_title: Optional[str]) -> str:
    raw = (podcast_origin or "").strip() or (podcast_title or "").strip().lower()
    if not raw:
        return ""
    return re.sub(r"\s+", " ", raw)


def compute_fingerprint(
    pcm_16k: np.ndarray,
    *,
    file_sha256: str = "",
    podcast_key: str = "",
    sample_rate: int = SAMPLE_RATE,
) -> EpisodeFingerprint:
    """Computes a compact 16-bit-per-100ms time-frequency energy-difference fingerprint."""
    if pcm_16k.ndim != 1:
        pcm_16k = np.mean(pcm_16k, axis=-1)
    pcm = np.asarray(pcm_16k, dtype=np.float32)
    duration_ms = int(round(len(pcm) * 1000.0 / sample_rate))

    if len(pcm) < FFT_SIZE:
        return EpisodeFingerprint(
            file_sha256=file_sha256,
            podcast_key=podcast_key,
            duration_ms=duration_ms,
            hashes=np.zeros(0, dtype=np.uint16),
            active=np.zeros(0, dtype=bool),
        )

    n_frames = 1 + (len(pcm) - FFT_SIZE) // HOP_SAMPLES
    # Strided framing without copying the full signal unnecessarily
    stride = pcm.strides[0]
    frames = np.lib.stride_tricks.as_strided(
        pcm,
        shape=(n_frames, FFT_SIZE),
        strides=(stride * HOP_SAMPLES, stride),
    )

    rms = np.sqrt(np.mean(frames * frames, axis=1))
    active = rms >= MIN_RMS

    windowed = frames * _HANN_WINDOW
    spec = np.abs(np.fft.rfft(windowed, axis=1))
    power = spec * spec

    band_energy = np.empty((n_frames, NUM_BANDS), dtype=np.float32)
    for b in range(NUM_BANDS):
        lo, hi = int(_BAND_EDGES[b]), int(_BAND_EDGES[b + 1])
        band_energy[:, b] = np.log1p(np.mean(power[:, lo:hi], axis=1))

    # Spectral contrast across adjacent bands, then temporal diff across consecutive frames
    freq_diff = band_energy[:, 1:] - band_energy[:, :-1]  # (n_frames, 16)
    temp_diff = np.zeros_like(freq_diff)
    if n_frames > 1:
        temp_diff[1:] = freq_diff[1:] - freq_diff[:-1]

    bits = (temp_diff > 0.0).astype(np.uint16)
    weights = (1 << np.arange(NUM_BANDS - 1, dtype=np.uint16))
    hashes = (bits * weights).sum(axis=1, dtype=np.uint16)

    # Zero out silent frames so they never participate in matching
    hashes[~active] = 0

    return EpisodeFingerprint(
        file_sha256=file_sha256,
        podcast_key=podcast_key,
        duration_ms=duration_ms,
        hashes=hashes,
        active=active,
    )


def _popcount16(x: np.ndarray) -> np.ndarray:
    """Vectorized popcount for uint16 arrays."""
    v = x.astype(np.uint32, copy=False)
    v = v - ((v >> 1) & 0x55555555)
    v = (v & 0x33333333) + ((v >> 2) & 0x33333333)
    return (((v + (v >> 4)) & 0x0F0F0F0F) * 0x01010101) >> 24


def _verify_diagonal(
    q_hashes: np.ndarray,
    q_active: np.ndarray,
    r_hashes: np.ndarray,
    r_active: np.ndarray,
    offset: int,
) -> list[tuple[int, int]]:
    """Finds contiguous matching query frame ranges [q_start, q_end) for a fixed offset = t_q - t_r."""
    q_lo = max(0, offset)
    q_hi = min(len(q_hashes), len(r_hashes) + offset)
    if q_hi - q_lo < MIN_MATCH_FRAMES:
        return []

    r_lo = q_lo - offset
    r_hi = q_hi - offset

    both_active = q_active[q_lo:q_hi] & r_active[r_lo:r_hi]
    xor_vals = q_hashes[q_lo:q_hi] ^ r_hashes[r_lo:r_hi]
    hamming = _popcount16(xor_vals)
    matched = both_active & (hamming <= MAX_HAMMING_DIST)

    spans: list[tuple[int, int]] = []
    n = len(matched)
    i = 0
    while i < n:
        if not matched[i]:
            i += 1
            continue
        start = i
        last_good = i
        gap = 0
        j = i + 1
        while j < n:
            if matched[j]:
                last_good = j
                gap = 0
            else:
                gap += 1
                if gap > MAX_GAP_FRAMES:
                    break
            j += 1
        length = last_good - start + 1
        if length >= MIN_MATCH_FRAMES:
            # Require at least 75% active matched frames in the span
            active_in_span = int(np.sum(both_active[start : last_good + 1]))
            matched_in_span = int(np.sum(matched[start : last_good + 1]))
            if active_in_span >= MIN_MATCH_FRAMES // 2 and matched_in_span >= 0.70 * length:
                spans.append((q_lo + start, q_lo + last_good + 1))
        i = j + 1
    return spans


def find_repeated_segments(
    query: EpisodeFingerprint,
    references: Iterable[EpisodeFingerprint],
    *,
    min_duration_ms: int = MIN_MATCH_FRAMES * FRAME_MS,
) -> list[AdSegmentDto]:
    """Matches `query` against `references` to detect recurring intros, outros, and promos."""
    if len(query.hashes) < MIN_MATCH_FRAMES:
        return []

    q_h = query.hashes
    q_a = query.active
    n_q = len(q_h)

    # Build two 16-bit sub-anchors per consecutive active frame pair (lo-byte pair & hi-byte pair)
    # so single-bit flips in one half don't prevent offset voting, while _verify_diagonal checks all 16 bits.
    q_pair_valid = q_a[:-1] & q_a[1:]
    q_lo = ((q_h[:-1] & 0x00FF) << 8) | (q_h[1:] & 0x00FF)
    q_hi = ((q_h[:-1] >> 8) << 8) | (q_h[1:] >> 8)

    q_lo_pos: dict[int, list[int]] = {}
    q_hi_pos: dict[int, list[int]] = {}
    for t_q in np.flatnonzero(q_pair_valid):
        tq_int = int(t_q)
        q_lo_pos.setdefault(int(q_lo[tq_int]), []).append(tq_int)
        q_hi_pos.setdefault(int(q_hi[tq_int]), []).append(tq_int)

    if not q_lo_pos:
        return []

    raw_spans: list[tuple[int, int, str, str]] = []  # (start_ms, end_ms, ad_class, label)

    for ref in references:
        if ref.file_sha256 and ref.file_sha256 == query.file_sha256:
            continue
        if len(ref.hashes) < MIN_MATCH_FRAMES:
            continue

        same_show = bool(query.podcast_key and ref.podcast_key and query.podcast_key == ref.podcast_key)
        r_h = ref.hashes
        r_a = ref.active
        r_pair_valid = r_a[:-1] & r_a[1:]
        r_lo = ((r_h[:-1] & 0x00FF) << 8) | (r_h[1:] & 0x00FF)
        r_hi = ((r_h[:-1] >> 8) << 8) | (r_h[1:] >> 8)

        # Vote on quantized offset = t_q - t_r
        offset_votes: dict[int, int] = {}
        for t_r in np.flatnonzero(r_pair_valid):
            tr_int = int(t_r)
            q_positions = q_lo_pos.get(int(r_lo[tr_int]))
            if q_positions:
                for t_q in q_positions:
                    bucket = (t_q - tr_int) // 2
                    offset_votes[bucket] = offset_votes.get(bucket, 0) + 1
            q_positions_hi = q_hi_pos.get(int(r_hi[tr_int]))
            if q_positions_hi:
                for t_q in q_positions_hi:
                    bucket = (t_q - tr_int) // 2
                    offset_votes[bucket] = offset_votes.get(bucket, 0) + 1

        if not offset_votes:
            continue

        # Verify top candidate offsets
        candidates = sorted(
            ((cnt, bkt) for bkt, cnt in offset_votes.items() if cnt >= MIN_ANCHOR_VOTES),
            reverse=True,
        )[:12]

        checked_offsets: set[int] = set()
        for _, bucket in candidates:
            for off in (bucket * 2, bucket * 2 + 1, bucket * 2 - 1):
                if off in checked_offsets:
                    continue
                checked_offsets.add(off)
                for q_start, q_end in _verify_diagonal(q_h, q_a, r_h, r_a, off):
                    start_ms = q_start * FRAME_MS
                    end_ms = min(query.duration_ms, q_end * FRAME_MS)
                    if end_ms - start_ms < min_duration_ms:
                        continue
                    r_start_ms = (q_start - off) * FRAME_MS
                    r_end_ms = (q_end - off) * FRAME_MS
                    q_intro_limit = min(90_000, int(0.20 * query.duration_ms)) if query.duration_ms > 0 else 90_000
                    r_intro_limit = min(120_000, int(0.25 * ref.duration_ms)) if ref.duration_ms > 0 else 120_000
                    q_outro_margin = min(150_000, int(0.20 * query.duration_ms)) if query.duration_ms > 0 else 0
                    r_outro_margin = min(150_000, int(0.20 * ref.duration_ms)) if ref.duration_ms > 0 else 0
                    if same_show and start_ms <= q_intro_limit and r_start_ms <= r_intro_limit:
                        raw_spans.append((start_ms, end_ms, "boilerplate", "Intro"))
                    elif (
                        same_show
                        and query.duration_ms > 0
                        and end_ms >= query.duration_ms - q_outro_margin
                        and ref.duration_ms > 0
                        and r_end_ms >= ref.duration_ms - r_outro_margin
                    ):
                        raw_spans.append((start_ms, end_ms, "boilerplate", "Outro"))
                    else:
                        raw_spans.append((start_ms, end_ms, "house_promo", "Recurring Promo"))

    if not raw_spans:
        return []

    # Merge overlapping spans of the same class
    raw_spans.sort(key=lambda s: (s[0], s[1]))
    merged: list[tuple[int, int, str, str]] = []
    for start_ms, end_ms, ad_class, label in raw_spans:
        if merged:
            prev_s, prev_e, prev_cls, prev_lbl = merged[-1]
            if start_ms <= prev_e + 1000 and prev_cls == ad_class:
                merged[-1] = (prev_s, max(prev_e, end_ms), prev_cls, prev_lbl)
                continue
        merged.append((start_ms, end_ms, ad_class, label))

    return [
        AdSegmentDto(
            startMs=s,
            endMs=e,
            confidence=0.92,
            isHighConfidence=True,
            label=lbl,
            adClass=cls,
        )
        for s, e, cls, lbl in merged
    ]


def merge_fingerprint_segments(
    ad_segments: list[AdSegmentDto],
    fp_segments: list[AdSegmentDto],
) -> list[AdSegmentDto]:
    """Merges fingerprinted intro/outro/promo segments with ad_tagger segments without clobbering ads."""
    if not fp_segments:
        return ad_segments
    if not ad_segments:
        return sorted(fp_segments, key=lambda s: s.startMs)

    result = list(ad_segments)
    for fp in fp_segments:
        fp_len = max(1, fp.endMs - fp.startMs)
        overlaps_existing = False
        for existing in ad_segments:
            inter = min(fp.endMs, existing.endMs) - max(fp.startMs, existing.startMs)
            if inter > 0 and inter >= 0.45 * min(fp_len, max(1, existing.endMs - existing.startMs)):
                overlaps_existing = True
                break
        if not overlaps_existing:
            result.append(fp)

    result.sort(key=lambda s: s.startMs)
    return result


class FingerprintStore:
    """Compact disk store for per-episode audio fingerprints in desktop-worker."""

    def __init__(self, root: Path, *, max_per_show: int = 12, max_total: int = 80) -> None:
        self.root = Path(root) / "fingerprints"
        self.index_path = self.root / "index.json"
        self.max_per_show = max_per_show
        self.max_total = max_total
        self._lock = threading.Lock()
        self._index_mtime: Optional[float] = None
        self._index: dict[str, dict] = self._load_index()

    def _load_index(self) -> dict[str, dict]:
        try:
            self._index_mtime = self.index_path.stat().st_mtime
            data = json.loads(self.index_path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            self._index_mtime = None
            return {}

    def _refresh_index_locked(self) -> None:
        try:
            mtime = self.index_path.stat().st_mtime
        except OSError:
            return
        if self._index_mtime is None or mtime != self._index_mtime:
            self._index = self._load_index()

    def _save_index(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        tmp = self.index_path.with_name(self.index_path.name + ".tmp")
        tmp.write_text(json.dumps(self._index, indent=1, sort_keys=True), encoding="utf-8")
        os.replace(tmp, self.index_path)
        try:
            self._index_mtime = self.index_path.stat().st_mtime
        except OSError:
            pass

    def _fp_path(self, file_sha256: str) -> Path:
        safe = hashlib.sha256(file_sha256.encode("utf-8")).hexdigest()[:32] if len(file_sha256) < 16 else file_sha256[:64]
        return self.root / f"{safe}.fpz"

    def save(self, fp: EpisodeFingerprint) -> None:
        if not fp.file_sha256 or len(fp.hashes) == 0:
            return
        with self._lock:
            self._refresh_index_locked()
            self.root.mkdir(parents=True, exist_ok=True)
            path = self._fp_path(fp.file_sha256)
            tmp = path.with_name(path.name + ".tmp")
            tmp.write_bytes(fp.to_bytes())
            os.replace(tmp, path)
            self._index[fp.file_sha256] = {
                "podcastKey": fp.podcast_key,
                "durationMs": fp.duration_ms,
                "updatedAt": int(time.time()),
                "file": path.name,
            }
            self._prune_locked()
            self._save_index()

    def _prune_locked(self) -> None:
        # Cap per show
        by_show: dict[str, list[tuple[int, str]]] = {}
        for sha, meta in self._index.items():
            key = str(meta.get("podcastKey") or "")
            by_show.setdefault(key, []).append((int(meta.get("updatedAt") or 0), sha))
        to_remove: list[str] = []
        for key, items in by_show.items():
            if key and len(items) > self.max_per_show:
                items.sort(reverse=True)
                to_remove.extend(sha for _, sha in items[self.max_per_show :])
        for sha in to_remove:
            self._delete_entry_locked(sha)

        # Cap total
        if len(self._index) > self.max_total:
            all_items = sorted(
                ((int(m.get("updatedAt") or 0), sha) for sha, m in self._index.items()),
                reverse=True,
            )
            for _, sha in all_items[self.max_total :]:
                self._delete_entry_locked(sha)

    def _delete_entry_locked(self, sha: str) -> None:
        meta = self._index.pop(sha, None)
        if meta and meta.get("file"):
            (self.root / str(meta["file"])).unlink(missing_ok=True)

    def load_references(
        self,
        *,
        exclude_sha256: str,
        podcast_key: str,
        max_same_show: int = 8,
        max_cross_show: int = 6,
    ) -> list[EpisodeFingerprint]:
        with self._lock:
            self._refresh_index_locked()
            same: list[tuple[int, str, dict]] = []
            other: list[tuple[int, str, dict]] = []
            for sha, meta in self._index.items():
                if sha == exclude_sha256:
                    continue
                ts = int(meta.get("updatedAt") or 0)
                if podcast_key and meta.get("podcastKey") == podcast_key:
                    same.append((ts, sha, meta))
                else:
                    other.append((ts, sha, meta))
            same.sort(reverse=True)
            other.sort(reverse=True)
            selected = same[:max_same_show] + other[:max_cross_show]

        refs: list[EpisodeFingerprint] = []
        for _, sha, meta in selected:
            fname = meta.get("file")
            if not fname:
                continue
            path = self.root / str(fname)
            try:
                data = path.read_bytes()
                refs.append(
                    EpisodeFingerprint.from_bytes(
                        data,
                        file_sha256=sha,
                        podcast_key=str(meta.get("podcastKey") or ""),
                    )
                )
            except (OSError, ValueError, zlib.error):
                continue
        return refs

    def clear(self) -> int:
        with self._lock:
            count = len(self._index)
            for sha in list(self._index.keys()):
                self._delete_entry_locked(sha)
            if self.index_path.exists():
                self.index_path.unlink(missing_ok=True)
            return count
