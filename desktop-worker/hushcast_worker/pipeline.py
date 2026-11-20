from __future__ import annotations

import difflib
import json
import logging
import os
import platform
import re
import threading
import time
from pathlib import Path
from typing import Callable, Optional, Protocol

from .ad_tagger import (
    compute_segment_features,
    decode_ad_segments,
    extract_audio_frames,
    split_sentences,
)
from .audio import (
    assign_speakers,
    decode_audio_16k_mono,
    decode_audio_streams,
    validate_fetch_url,
)
from .chapters import generate_chapters_and_summary, release_chat_llm
from .fingerprint import (
    FingerprintStore,
    compute_fingerprint,
    find_repeated_segments,
    merge_fingerprint_segments,
    normalize_podcast_key,
)
from .forced_align import MIN_WORD_SCORE, ForcedAligner, count_alignable_words
from .transcript_repair import build_initial_prompt, repair_hard_cuts, strip_prompt_leak
from .models import (
    PIPELINE_VERSION,
    AdSegmentDto,
    DesktopAnalysisBundle,
    DesktopChapterDto,
    JobState,
    SegmentDto,
    WordDto,
    WordIndexBlobDto,
)

logger = logging.getLogger("hushcast_worker.pipeline")

ProgressCallback = Callable[[JobState, int, str], None]

WHISPER_MODEL_CANDIDATES = [
    "ggml-large-v3-turbo.bin",
    "ggml-large-v3-turbo-q8_0.bin",
    "ggml-large-v3-turbo-q5_0.bin",
    "ggml-large-v3.bin",
    "ggml-medium.en.bin",
    "ggml-medium.en-q5_0.bin",
    "ggml-medium.bin",
    "ggml-small.en.bin",
    "ggml-small.en-q5_1.bin",
    "ggml-small.bin",
    "ggml-base.en.bin",
    "ggml-base.en-q5_1.bin",
]

_QWEN3_QUERY_INSTRUCT = (
    "Instruct: Given a podcast transcript search query, retrieve relevant spoken passages\nQuery: "
)

_SRT_TS_RE = re.compile(
    r"(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})"
)
_HTML_TAG_RE = re.compile(r"<[^>]+>")


def _normalize_whisper_language(lang: Optional[str]) -> str:
    if not lang or not lang.strip():
        return "en"
    code = lang.strip().lower().replace("_", "-").split("-")[0]
    if code == "auto" or not code.isalpha() or len(code) > 4:
        return "en"
    return code


def detect_accelerator() -> str:
    if platform.system() == "Darwin" and platform.machine() == "arm64":
        return "metal"
    try:
        import onnxruntime as ort

        providers = ort.get_available_providers()
        if "CUDAExecutionProvider" in providers:
            return "cuda"
        if "CoreMLExecutionProvider" in providers:
            return "coreml"
    except Exception:
        pass
    try:
        import torch

        if torch.cuda.is_available():
            return "cuda"
    except Exception:
        pass
    if os.environ.get("GGML_CUDA") == "1" and Path("/usr/local/cuda").exists():
        return "cuda"
    return "cpu"


def _is_low_memory_machine() -> bool:
    env = os.environ.get("HUSHCAST_LOW_MEM", "").strip().lower()
    if env in ("1", "true", "yes"):
        return True
    if env in ("0", "false", "no"):
        return False
    if detect_accelerator() == "cuda":
        return False
    try:
        page_size = os.sysconf("SC_PAGE_SIZE")
        phys_pages = os.sysconf("SC_PHYS_PAGES")
        total_gb = (page_size * phys_pages) / (1024**3)
        return total_gb < 12.0
    except Exception:
        return False


class AnalysisBackend(Protocol):
    """Pluggable transcription + ad-detection backend."""

    def models_ready(self) -> bool: ...

    def models_loaded(self) -> bool: ...

    def whisper_model_name(self) -> str: ...

    def chat_model_ready(self) -> bool: ...

    def embed_query(self, text: str) -> list[float]: ...

    def warmup(self) -> None: ...

    def analyze(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw: Optional[str],
        feed_transcript_mime: str,
        on_progress: ProgressCallback,
        language: Optional[str] = None,
        feed_transcript_url: Optional[str] = None,
        feed_chapters_url: Optional[str] = None,
        feed_chapters_type: str = "",
        cancel_event: Optional[threading.Event] = None,
        episode_title: Optional[str] = None,
        podcast_title: Optional[str] = None,
    ) -> DesktopAnalysisBundle: ...

    def analyze_with_embeddings(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw: Optional[str],
        feed_transcript_mime: str,
        on_progress: ProgressCallback,
        language: Optional[str] = None,
        feed_transcript_url: Optional[str] = None,
        feed_chapters_url: Optional[str] = None,
        feed_chapters_type: str = "",
        cancel_event: Optional[threading.Event] = None,
        episode_title: Optional[str] = None,
        podcast_title: Optional[str] = None,
    ) -> tuple[DesktopAnalysisBundle, Optional[object]]: ...


def _parse_ts(h: Optional[str], m: str, s: str, ms: str) -> float:
    return (int(h or 0) * 3600) + (int(m) * 60) + int(s) + (int(ms) / 1000.0)


def _parse_srt_or_vtt(raw: str) -> Optional[tuple[list[WordDto], list[SegmentDto], bool]]:
    lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    words: list[WordDto] = []
    segments: list[SegmentDto] = []
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i].strip()
        m = _SRT_TS_RE.search(line)
        if not m:
            i += 1
            continue
        start = _parse_ts(m.group(1), m.group(2), m.group(3), m.group(4))
        end = _parse_ts(m.group(5), m.group(6), m.group(7), m.group(8))
        if end <= start:
            end = start + 0.5
        i += 1
        cue_lines: list[str] = []
        while i < n and lines[i].strip():
            cleaned = _HTML_TAG_RE.sub("", lines[i]).strip()
            if cleaned:
                cue_lines.append(cleaned)
            i += 1
        text = " ".join(cue_lines).strip()
        if not text:
            continue
        seg_words = text.split()
        segments.append(
            SegmentDto(start=round(start, 3), end=round(end, 3), text=text, nWords=len(seg_words))
        )
        step = max(0.01, (end - start) / max(1, len(seg_words)))
        for idx, w in enumerate(seg_words):
            ws = round(start + idx * step, 3)
            we = round(min(end, ws + step), 3)
            words.append(WordDto(start=ws, end=we, text=w))
    if words and segments:
        return words, segments, True  # cue-level: needs forced alignment check (V22)
    return None


def _parse_feed_transcript(
    raw: Optional[str], mime: str
) -> Optional[tuple[list[WordDto], list[SegmentDto], bool]]:
    """Returns (words, segments, needs_alignment) or None."""
    if not raw or not raw.strip():
        return None
    stripped = raw.lstrip()
    low_mime = mime.lower()
    if "vtt" in low_mime or "srt" in low_mime or stripped.startswith("WEBVTT") or _SRT_TS_RE.search(stripped[:600]):
        return _parse_srt_or_vtt(raw)
    if "json" not in low_mime and not stripped.startswith("{"):
        return None
    try:
        data = json.loads(raw)
        words: list[WordDto] = []
        segments: list[SegmentDto] = []
        needs_alignment = False

        if isinstance(data.get("segments"), list):
            raw_segs = data["segments"]
            # Check if Podcasting 2.0 one-word segments (A6/V22)
            word_like = sum(1 for s in raw_segs[:20] if len(str(s.get("body") or s.get("text") or "").split()) <= 1)
            if raw_segs and word_like >= max(1, int(len(raw_segs[:20]) * 0.7)):
                cur_words: list[WordDto] = []
                for seg in raw_segs:
                    w_txt = str(seg.get("body") or seg.get("text") or "").strip()
                    if not w_txt:
                        continue
                    ws = float(seg.get("startTime", seg.get("start", 0.0)))
                    we = float(seg.get("endTime", seg.get("end", ws + 0.1)))
                    w_dto = WordDto(start=round(ws, 3), end=round(max(we, ws + 0.01), 3), text=w_txt)
                    words.append(w_dto)
                    cur_words.append(WordDto(start=w_dto.start, end=w_dto.end, text=(" " + w_txt) if cur_words else w_txt))
                if cur_words:
                    segments = split_sentences(cur_words)
            else:
                needs_alignment = True
                for seg in raw_segs:
                    text = str(seg.get("body") or seg.get("text") or "").strip()
                    if not text:
                        continue
                    start = float(seg.get("startTime", seg.get("start", 0.0)))
                    end = float(seg.get("endTime", seg.get("end", start + 1.0)))
                    if end <= start:
                        end = start + 0.5
                    seg_words = text.split()
                    segments.append(
                        SegmentDto(start=round(start, 3), end=round(end, 3), text=text, nWords=len(seg_words))
                    )
                    step = max(0.01, (end - start) / max(1, len(seg_words)))
                    for idx, w in enumerate(seg_words):
                        ws = round(start + idx * step, 3)
                        we = round(min(end, ws + step), 3)
                        words.append(WordDto(start=ws, end=we, text=w))
        elif isinstance(data.get("words"), list):
            cur_words_dto: list[WordDto] = []
            for item in data["words"]:
                w_txt = str(item.get("text") or item.get("word") or "").strip()
                if not w_txt:
                    continue
                ws = float(item.get("start", 0.0))
                we = float(item.get("end", ws + 0.1))
                w_dto = WordDto(start=round(ws, 3), end=round(max(we, ws + 0.01), 3), text=w_txt)
                words.append(w_dto)
                cur_words_dto.append(
                    WordDto(start=w_dto.start, end=w_dto.end, text=(" " + w_txt) if cur_words_dto else w_txt)
                )
            if cur_words_dto:
                segments = split_sentences(cur_words_dto)

        if words and segments:
            return words, segments, needs_alignment
    except Exception:
        logger.debug("Could not parse feedTranscriptRaw; falling back to Whisper", exc_info=True)
    return None


def _fetch_feed_url_text(url: str, *, allow_lan: bool = False) -> Optional[str]:
    try:
        import httpx

        safe_url = validate_fetch_url(url, allow_lan_feeds=allow_lan)
        with httpx.Client(follow_redirects=True, timeout=20.0) as client:
            resp = client.get(safe_url)
            resp.raise_for_status()
            if len(resp.content) > 10 * 1024 * 1024:
                return None
            return resp.text
    except Exception:
        logger.debug("Failed to fetch feed URL %s", url, exc_info=True)
        return None


# --- faster-whisper model pin ------------------------------------------------------------------
# faster-whisper 1.2.1 resolves "large-v3-turbo" to mobiuslabsgmbh/faster-whisper-large-v3-turbo on
# `main`, which now redirects to dropbox-dash/faster-whisper-large-v3-turbo. Pin the final repo id
# and a commit so a cold start never resolves `main` (from the HF API on 2026-11-18).
# model.bin: 1,617,884,929 bytes, sha256 e76620f83d5f5b69efd3d87e3dc180c1bd21df9fbebacfd4335e5e1efcc018da.
FASTER_WHISPER_PINS: dict[str, tuple[str, str]] = {
    "large-v3-turbo": (
        "dropbox-dash/faster-whisper-large-v3-turbo",
        "0a363e9161cbc7ed1431c9597a8ceaf0c4f78fcf",
    ),
}
_FASTER_WHISPER_ALIASES = {
    "turbo": "large-v3-turbo",
    "mobiuslabsgmbh/faster-whisper-large-v3-turbo": "large-v3-turbo",
    "dropbox-dash/faster-whisper-large-v3-turbo": "large-v3-turbo",
}
# Older commits with byte-identical model files (0a363e9 only edited the README), so a cache made
# before the pin is still used offline instead of downloading 1.6 GB again.
_FASTER_WHISPER_EQUIVALENT_REVISIONS: dict[str, tuple[str, ...]] = {
    "0a363e9161cbc7ed1431c9597a8ceaf0c4f78fcf": ("0c94664816ec82be77b20e824c8e8675995b0029",),
}
# Repo ids older workers cached the same weights under (the HF cache folder is named after the id).
_FASTER_WHISPER_LEGACY_REPOS: dict[str, tuple[str, ...]] = {
    "dropbox-dash/faster-whisper-large-v3-turbo": ("mobiuslabsgmbh/faster-whisper-large-v3-turbo",),
}
# A cached snapshot is used only if complete: without tokenizer.json faster-whisper fetches a
# tokenizer online, and without preprocessor_config.json large-v3 models get the wrong mel count.
_FASTER_WHISPER_REQUIRED_FILES = ("model.bin", "config.json", "tokenizer.json")
_FASTER_WHISPER_PINNED_FILES = (
    "model.bin",
    "config.json",
    "tokenizer.json",
    "preprocessor_config.json",
    "vocabulary.json",
)


def resolve_faster_whisper_source(name: str, revision_override: str = "") -> tuple[str, Optional[str]]:
    """
    Maps `HUSHCAST_FASTER_WHISPER_MODEL` to (local directory or HF repo id, revision).
    Known names get the pinned repo and commit; `revision_override` replaces the pinned commit.
    Unknown names pass through unpinned unless an override is given.
    """
    name = (name or "").strip() or "large-v3-turbo"
    local = Path(name).expanduser()
    if local.is_dir():
        return str(local), None
    key = _FASTER_WHISPER_ALIASES.get(name, name)
    if key in FASTER_WHISPER_PINS:
        repo_id, revision = FASTER_WHISPER_PINS[key]
        return repo_id, (revision_override or revision)
    return name, (revision_override or None)


def load_faster_whisper_model_dir(
    model: str,
    revision: Optional[str],
    download_root: str,
    *,
    download_fn: Optional[Callable[..., str]] = None,
) -> str:
    """
    Local first: returns the snapshot of `revision` already in `download_root` (also under a legacy
    repo id) without any network call, and downloads that revision only when it is missing.
    `download_fn` defaults to faster-whisper's `download_model` (huggingface_hub snapshot_download).
    """
    if os.path.isdir(model):
        return model
    if download_fn is None:
        from faster_whisper.utils import download_model as download_fn
    revisions = (revision, *_FASTER_WHISPER_EQUIVALENT_REVISIONS.get(revision or "", ()))
    pinned_repos = {repo for repo, _ in FASTER_WHISPER_PINS.values()}
    required = _FASTER_WHISPER_PINNED_FILES if model in pinned_repos else _FASTER_WHISPER_REQUIRED_FILES
    for repo in (model, *_FASTER_WHISPER_LEGACY_REPOS.get(model, ())):
        for rev in revisions:
            try:
                path = download_fn(repo, local_files_only=True, cache_dir=download_root, revision=rev)
            except Exception:
                continue
            if path and all(os.path.isfile(os.path.join(path, f)) for f in required):
                logger.info("faster-whisper: using cached %s@%s", repo, (rev or "main")[:12])
                return str(path)
    logger.info("faster-whisper: %s@%s not cached in %s; downloading", model, (revision or "main")[:12], download_root)
    return str(download_fn(model, local_files_only=False, cache_dir=download_root, revision=revision))


# --- publisher transcript checks ----------------------------------------------------------------
# Word-level publisher JSON is trusted only if three ~20 s speech clips (at ~20/50/80% of the
# episode) transcribed by the loaded ASR match the publisher words at the same times. Dynamic ad
# insertion shifts the audio against a publisher transcript, and the clips catch that.
SPOT_CHECK_FRACTIONS = (0.2, 0.5, 0.8)
SPOT_CHECK_CLIP_S = 20.0
SPOT_CHECK_TOLERANCE_S = 1.5
SPOT_CHECK_MIN_OVERLAP = 0.60
SPOT_CHECK_MIN_TOKENS = 8  # fewer recognized words than this: not a speech clip, try a nearby one
# An SRT/VTT (or segment-level JSON) transcript is used only if MMS_FA aligns at least this share
# of its alignable words at MIN_WORD_SCORE.
FEED_ALIGN_MIN_FRACTION = 0.90

ClipTranscriber = Callable[[float, float], list[WordDto]]  # (start_s, end_s) -> words, absolute times
_SPOT_TOKEN_RE = re.compile(r"[\W_]+", re.UNICODE)


def _spot_token(text: str) -> str:
    return _SPOT_TOKEN_RE.sub("", (text or "").lower())


def _tokens_match(a: str, b: str) -> bool:
    if a == b:
        return True
    if min(len(a), len(b)) < 4:
        return False
    return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio() >= 0.8


def clip_token_overlap(
    asr_words: list[WordDto],
    publisher_words: list[WordDto],
    clip_start: float,
    clip_end: float,
    tolerance_s: float = SPOT_CHECK_TOLERANCE_S,
) -> tuple[float, int, int]:
    """
    Fuzzy-matches the clip's ASR words against publisher words that start within `tolerance_s` of
    each ASR word. Returns (overlap, n_asr_tokens, n_publisher_tokens_in_clip), where overlap is
    matched / max(n_asr, n_publisher), so missing words on either side count against it.
    """
    asr = [(tok, float(w.start)) for w in asr_words if (tok := _spot_token(w.text))]
    near: list[tuple[str, float]] = []
    n_pub = 0
    for w in publisher_words:
        ws = float(w.start)
        if ws < clip_start - tolerance_s or ws > clip_end + tolerance_s:
            continue
        tok = _spot_token(w.text)
        if not tok:
            continue
        near.append((tok, ws))
        if clip_start <= ws < clip_end:
            n_pub += 1
    used = [False] * len(near)
    matched = 0
    for tok, t in asr:
        best_k = -1
        best_dt = tolerance_s + 1e-9
        for k, (ptok, pt) in enumerate(near):
            dt = abs(pt - t)
            if used[k] or dt > best_dt or not _tokens_match(tok, ptok):
                continue
            best_k, best_dt = k, dt
        if best_k >= 0:
            used[best_k] = True
            matched += 1
    denom = max(len(asr), n_pub)
    return (matched / denom if denom else 0.0), len(asr), n_pub


def spot_check_publisher_timings(
    publisher_words: list[WordDto],
    duration_sec: float,
    transcribe_clip: ClipTranscriber,
    *,
    cancel_event: Optional[threading.Event] = None,
    on_clip: Optional[Callable[[int, int], None]] = None,
) -> tuple[bool, list[tuple[float, float, int, int]]]:
    """
    Returns (accepted, [(clip_start, overlap, n_asr, n_publisher), ...]). Accepted only when every
    clip has speech and at least SPOT_CHECK_MIN_OVERLAP of its tokens match.
    """
    clip = SPOT_CHECK_CLIP_S
    last_start = max(0.0, duration_sec - clip)
    starts: list[float] = []
    for frac in SPOT_CHECK_FRACTIONS:
        st = round(min(last_start, max(0.0, frac * duration_sec - clip / 2.0)), 3)
        if st not in starts:
            starts.append(st)
    results: list[tuple[float, float, int, int]] = []
    accepted = bool(starts) and duration_sec > 0
    for i, base in enumerate(starts):
        if cancel_event is not None and cancel_event.is_set():
            raise RuntimeError("Job cancelled")
        if on_clip is not None:
            on_clip(i + 1, len(starts))
        best: Optional[tuple[float, float, int, int]] = None
        # Music beds and silences have no words to compare: slide to the next clip, then the previous.
        for offset in (0.0, clip, -clip):
            st = min(last_start, max(0.0, base + offset))
            if best is not None and st == best[0]:
                continue
            asr = transcribe_clip(st, min(duration_sec, st + clip))
            overlap, n_asr, n_pub = clip_token_overlap(asr, publisher_words, st, min(duration_sec, st + clip))
            best = (st, overlap, n_asr, n_pub)
            if n_asr >= SPOT_CHECK_MIN_TOKENS:
                break
        assert best is not None
        results.append(best)
        if best[2] < SPOT_CHECK_MIN_TOKENS or best[1] < SPOT_CHECK_MIN_OVERLAP:
            accepted = False
            break  # one failed clip decides it; skip the remaining ASR work
    return accepted, results


def feed_alignment_accepted(n_aligned: int, n_alignable: int, n_words: int) -> bool:
    """SRT/VTT gate: >= 90% of the alignable words aligned, and most words alignable at all."""
    if n_words <= 0 or n_alignable <= 0 or n_alignable < 0.5 * n_words:
        return False
    return n_aligned >= FEED_ALIGN_MIN_FRACTION * n_alignable


# --- progress -----------------------------------------------------------------------------------
class _MonotonicProgress:
    """Wraps a ProgressCallback so the reported percentage never goes down. Thread-safe."""

    def __init__(self, callback: ProgressCallback) -> None:
        self._callback = callback
        self._lock = threading.Lock()
        self._last = -1

    def __call__(self, state: JobState, pct: int, message: str) -> None:
        with self._lock:
            pct = max(int(pct), self._last)
            self._last = pct
            self._callback(state, pct, message)


class _TranscribeProgress:
    """
    Progress for one Whisper run: pywhispercpp's segment callback once it fires, and the elapsed-time
    estimate only before the first segment (or for the whole run on faster-whisper, which has no
    callback). Pass a `_MonotonicProgress` so the switch-over can't step backwards.
    """

    def __init__(
        self,
        on_progress: ProgressCallback,
        *,
        duration_sec: float,
        est_sec: float,
        model_name: str,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._on_progress = on_progress
        self._duration = duration_sec
        self._est = max(1e-6, est_sec)
        self._model = model_name
        self._clock = clock
        self._t0 = clock()
        self._dur_str = f"{int(duration_sec // 60)}m{int(duration_sec % 60):02d}s"
        self._segment_seen = threading.Event()
        self._last_segment_emit: Optional[float] = None

    @property
    def segments_seen(self) -> bool:
        return self._segment_seen.is_set()

    def tick(self) -> bool:
        """Timer fallback. Returns False (and reports nothing) once segments drive the progress."""
        if self._segment_seen.is_set():
            return False
        el = self._clock() - self._t0
        pct = min(58, 30 + int(28 * (el / self._est)))
        self._on_progress(JobState.TRANSCRIBING, pct, f"Transcribing {self._dur_str} ({self._model}) • {el:.0f}s")
        return True

    def segment(self, t_end_sec: float) -> None:
        self._segment_seen.set()
        now = self._clock()
        if self._last_segment_emit is not None and now - self._last_segment_emit < 0.75:
            return
        if self._duration > 0 and t_end_sec > 0:
            self._last_segment_emit = now
            pct = min(59, 30 + int(29 * min(1.0, t_end_sec / self._duration)))
            self._on_progress(
                JobState.TRANSCRIBING,
                pct,
                f"Transcribing ({int(t_end_sec // 60)}m/{int(self._duration // 60)}m)",
            )


def _close_llm(llm) -> None:
    close = getattr(llm, "close", None)
    if callable(close):
        try:
            close()
        except Exception:
            logger.debug("LLM close() failed", exc_info=True)


class LocalMlBackend:
    """
    Runs whisper.cpp (or faster-whisper on CUDA) + Qwen3-Embedding-0.6B + ad_tagger.onnx.
    """

    def __init__(self, models_dir: Path, ad_tagger_onnx: Optional[Path] = None) -> None:
        self.models_dir = models_dir
        self.models_dir.mkdir(parents=True, exist_ok=True)
        self.whisper_model = self._resolve_whisper_model()
        self.qwen_model = models_dir / "Qwen3-Embedding-0.6B-Q8_0.gguf"
        self.ad_tagger_onnx = ad_tagger_onnx or (models_dir / "ad_tagger.onnx")
        self._low_mem = _is_low_memory_machine()
        self._model_lock = threading.Lock()
        self._embedding_llm = None
        self._whisper_instance: Optional[tuple[str, bool, object]] = None  # (path, use_dtw, model) (V24)
        self._faster_whisper_pipeline = None
        self._ort_session = None
        self._forced_aligner = ForcedAligner(models_dir=str(models_dir / "torch"))
        self._rtf_by_model: dict[str, float] = {}

    def _resolve_whisper_model(self) -> Path:
        env_model = os.environ.get("HUSHCAST_WHISPER_MODEL", "").strip()
        if env_model:
            p = Path(env_model).expanduser()
            if p.exists():
                return p
            candidate = self.models_dir / env_model
            if candidate.exists():
                return candidate
        for name in WHISPER_MODEL_CANDIDATES:
            candidate = self.models_dir / name
            if candidate.exists():
                return candidate
        return self.models_dir / "ggml-base.en-q5_1.bin"

    def whisper_model_name(self) -> str:
        return self.whisper_model.name

    def chat_model_path(self) -> Optional[Path]:
        env_chat = os.environ.get("HUSHCAST_CHAT_MODEL", "").strip()
        if env_chat:
            p = Path(env_chat).expanduser()
            if p.exists():
                return p
            candidate = self.models_dir / env_chat
            if candidate.exists():
                return candidate
        candidates = [
            p for p in self.models_dir.glob("*.gguf")
            if "embedding" not in p.name.lower()
            and any(k in p.name.lower() for k in ("instruct", "chat", "qwen2.5", "qwen3", "llama-3", "gemma", "mistral"))
        ]
        if candidates:
            candidates.sort(
                key=lambda p: (
                    "3b" not in p.name.lower(),
                    "1.5b" not in p.name.lower(),
                    p.stat().st_size,
                )
            )
            return candidates[0]
        return None

    def chat_model_ready(self) -> bool:
        return self.chat_model_path() is not None

    @staticmethod
    def ml_imports_ready() -> bool:
        try:
            import av  # noqa: F401
            import numpy  # noqa: F401
            import onnxruntime  # noqa: F401
            from llama_cpp import Llama  # noqa: F401
            from pywhispercpp.model import Model  # noqa: F401
            return True
        except ImportError:
            return False

    def models_ready(self) -> bool:
        return (
            self.whisper_model.exists()
            and self.qwen_model.exists()
            and self.ad_tagger_onnx.exists()
            and self.ml_imports_ready()
        )

    def models_loaded(self) -> bool:
        return (
            (self._whisper_instance is not None or self._faster_whisper_pipeline is not None)
            and self._embedding_llm is not None
            and self._ort_session is not None
        )

    def estimate_eta_sec(self, duration_sec: float) -> Optional[int]:
        if duration_sec <= 0:
            return None
        accel = detect_accelerator()
        default_rtf = 15.0 if accel == "cuda" else (8.0 if accel == "metal" else 1.5)
        rtf = self._rtf_by_model.get(self.whisper_model.name, default_rtf)
        return max(5, int(round(duration_sec / max(0.2, rtf))))

    def _record_rtf(self, duration_sec: float, elapsed_sec: float) -> None:
        if duration_sec > 5.0 and elapsed_sec > 0.5:
            rtf = duration_sec / elapsed_sec
            prev = self._rtf_by_model.get(self.whisper_model.name)
            self._rtf_by_model[self.whisper_model.name] = rtf if prev is None else (0.6 * prev + 0.4 * rtf)

    def _should_use_faster_whisper(self) -> bool:
        if detect_accelerator() != "cuda":
            return False
        if os.environ.get("HUSHCAST_ASR_ENGINE", "faster-whisper").strip().lower() == "whispercpp":
            return False
        try:
            import faster_whisper  # noqa: F401
            return True
        except ImportError:
            return False

    def _get_faster_whisper_pipeline(self):
        if self._faster_whisper_pipeline is not None:
            return self._faster_whisper_pipeline
        from faster_whisper import BatchedInferencePipeline, WhisperModel

        model_id, revision = resolve_faster_whisper_source(
            os.environ.get("HUSHCAST_FASTER_WHISPER_MODEL", "large-v3-turbo"),
            os.environ.get("HUSHCAST_FASTER_WHISPER_REVISION", "").strip(),
        )
        model_dir = load_faster_whisper_model_dir(model_id, revision, str(self.models_dir / "faster-whisper"))
        compute_type = os.environ.get("HUSHCAST_FASTER_WHISPER_COMPUTE", "float16").strip() or "float16"
        fw = WhisperModel(model_dir, device="cuda", compute_type=compute_type)
        self._faster_whisper_pipeline = BatchedInferencePipeline(model=fw)
        return self._faster_whisper_pipeline

    def _get_whisper_cpp(self, use_dtw: bool):
        from pywhispercpp.model import Model as WhisperModel

        key_path = str(self.whisper_model)
        if (
            not self._low_mem
            and self._whisper_instance is not None
            and self._whisper_instance[0] == key_path
            and self._whisper_instance[1] == use_dtw
        ):
            return self._whisper_instance[2]

        ctx_params: dict[str, object] = {
            "dtw_token_timestamps": use_dtw,
            "flash_attn": not use_dtw,
        }
        if use_dtw:
            preset = _resolve_dtw_aheads_preset(self.whisper_model.name)
            if preset:
                ctx_params["dtw_aheads_preset"] = preset
        try:
            model = WhisperModel(
                key_path,
                print_progress=False,
                context_params=ctx_params,
            )
        except (TypeError, ValueError):
            try:
                model = WhisperModel(
                    key_path,
                    print_progress=False,
                    context_params={"dtw_token_timestamps": use_dtw},
                )
            except (TypeError, ValueError):
                model = WhisperModel(key_path, print_progress=False)

        if not self._low_mem:
            self._whisper_instance = (key_path, use_dtw, model)
        return model

    def warmup(self) -> None:
        """Pre-loads models at worker startup so the first job doesn't pay cold-load latency (V7)."""
        if not self.models_ready():
            return
        try:
            self._get_ort_session()
            if self._should_use_faster_whisper():
                self._get_faster_whisper_pipeline()
            else:
                use_dtw = not self._forced_aligner.is_available()
                self._get_whisper_cpp(use_dtw=use_dtw)
            self._forced_aligner._ensure_loaded()
            with self._model_lock:
                self._get_embedding_llm()
            if not self._low_mem:
                chat_path = self.chat_model_path()
                if chat_path is not None and chat_path.exists():
                    from .chapters import _get_chat_llm
                    _get_chat_llm(chat_path)
        except Exception:
            logger.warning("Model warmup partially failed", exc_info=True)

    def _get_embedding_llm(self):
        if self._embedding_llm is not None:
            return self._embedding_llm
        from llama_cpp import Llama

        try:
            llm = Llama(
                model_path=str(self.qwen_model),
                embedding=True,
                pooling_type=3,  # LLAMA_POOLING_TYPE_LAST
                n_ctx=2048,
                n_batch=2048,
                n_ubatch=2048,
                n_gpu_layers=-1,
                verbose=False,
            )
        except TypeError:
            try:
                llm = Llama(
                    model_path=str(self.qwen_model),
                    embedding=True,
                    pooling_type=3,
                    n_ctx=2048,
                    n_gpu_layers=-1,
                    verbose=False,
                )
            except TypeError:
                llm = Llama(
                    model_path=str(self.qwen_model),
                    embedding=True,
                    n_ctx=2048,
                    n_gpu_layers=-1,
                    verbose=False,
                )
        self._embedding_llm = llm
        return llm

    def _get_ort_session(self):
        if self._ort_session is not None:
            return self._ort_session
        import onnxruntime as ort

        # W9: Run 11.9 MB ad_tagger.onnx on CPUExecutionProvider to save 1-3s & GPU memory
        self._ort_session = ort.InferenceSession(
            str(self.ad_tagger_onnx),
            providers=["CPUExecutionProvider"],
        )
        return self._ort_session

    def embed_query(self, text: str) -> list[float]:
        import numpy as np

        if not self.qwen_model.exists():
            raise FileNotFoundError(f"Missing embedding model: {self.qwen_model}")
        prompt = f"{_QWEN3_QUERY_INSTRUCT}{text}"
        with self._model_lock:
            llm = self._get_embedding_llm()
            vec = np.asarray(llm.embed(prompt), dtype=np.float32)
        if vec.ndim == 2:
            vec = vec[-1]
        elif vec.ndim > 2:
            vec = vec.reshape(-1, vec.shape[-1])[-1]
        norm = float(np.linalg.norm(vec))
        if norm > 1e-12:
            vec = vec / norm
        return vec.tolist()

    def _embed_segments(
        self,
        texts: list[str],
        on_progress: Optional[ProgressCallback] = None,
        cancel_event: Optional[threading.Event] = None,
    ):
        import numpy as np

        total_segs = len(texts)
        emb_list: list[np.ndarray] = []
        batch_size = 16
        last_emit = 0.0
        n_batches = 0
        n_fallbacks = 0
        first_error: Optional[str] = None

        try:
            for b_start in range(0, total_segs, batch_size):
                if cancel_event is not None and cancel_event.is_set():
                    raise RuntimeError("Job cancelled")
                batch = texts[b_start : b_start + batch_size]
                n_batches += 1
                with self._model_lock:
                    llm = self._get_embedding_llm()
                    try:
                        raw = llm.embed(batch)
                        arr = np.asarray(raw, dtype=np.float32)
                        if arr.ndim == 2 and arr.shape[0] == len(batch):
                            batch_vecs = [arr[i] for i in range(len(batch))]
                        else:
                            raise ValueError(
                                f"unexpected batch embedding shape {arr.shape} for {len(batch)} texts"
                            )
                    except Exception as exc:
                        # Keep going one sentence at a time, but don't hide it: on Modal this was
                        # llama-cpp-python 0.3.19 failing every batch, ~15 s per episode.
                        n_fallbacks += 1
                        if first_error is None:
                            first_error = f"{type(exc).__name__}: {str(exc)[:300]}"
                        batch_vecs = []
                        for t in batch:
                            v = np.asarray(llm.embed(t), dtype=np.float32)
                            if v.ndim == 2:
                                v = v[-1]
                            elif v.ndim > 2:
                                v = v.reshape(-1, v.shape[-1])[-1]
                            batch_vecs.append(v)

                for vec in batch_vecs:
                    norm = float(np.linalg.norm(vec))
                    if norm > 1e-12:
                        vec = vec / norm
                    emb_list.append(vec.astype(np.float32))

                if on_progress is not None:
                    done = min(total_segs, b_start + len(batch))
                    now = time.monotonic()
                    if done == total_segs or (now - last_emit) >= 0.2:
                        last_emit = now
                        pct = 66 + int(19 * done / total_segs)
                        on_progress(
                            JobState.EMBEDDING,
                            pct,
                            f"Embedding segments ({done}/{total_segs})",
                        )
        finally:
            if n_fallbacks:
                logger.warning(
                    "Batch embedding failed for %d/%d batch(es) (%d segments); used one call per "
                    "sentence instead. First error: %s",
                    n_fallbacks,
                    n_batches,
                    total_segs,
                    first_error,
                )
            if self._low_mem:
                with self._model_lock:
                    llm_to_free, self._embedding_llm = self._embedding_llm, None
                if llm_to_free is not None:
                    _close_llm(llm_to_free)

        return np.stack(emb_list, axis=0)

    def _release_chat_model_if_low_mem(self) -> None:
        """V18: on a low-memory machine the chat model (~1-2 GB) is loaded per job, not kept."""
        if self._low_mem and release_chat_llm():
            logger.info("Released chat model (low-memory mode)")

    def _make_clip_transcriber(
        self,
        pcm_16k,
        lang: str,
        cancel_event: Optional[threading.Event] = None,
    ) -> ClipTranscriber:
        """Transcribes [start_s, end_s) of the episode with the loaded ASR; word times are absolute."""
        import numpy as np

        sr = 16000
        if self._should_use_faster_whisper():
            decode = _make_fw_window_decoder(self._get_faster_whisper_pipeline().model, pcm_16k, lang, sr)

            def _fw_clip(start_s: float, end_s: float) -> list[WordDto]:
                return [
                    WordDto(
                        start=round(start_s + float(w.start), 3),
                        end=round(start_s + float(w.end), 3),
                        text=w.text.strip(),
                    )
                    for w in decode(start_s, end_s, "")
                ]

            return _fw_clip

        whisper = self._get_whisper_cpp(use_dtw=not self._forced_aligner.is_available())

        def _noop_segment(_seg) -> None:
            # pywhispercpp keeps the last segment callback on the class; replace the previous
            # job's so it doesn't report progress for this clip.
            return None

        def _abort_cb() -> bool:
            return bool(cancel_event is not None and cancel_event.is_set())

        def _cpp_clip(start_s: float, end_s: float) -> list[WordDto]:
            s_idx = max(0, int(round(start_s * sr)))
            e_idx = min(len(pcm_16k), int(round(end_s * sr)))
            if e_idx - s_idx < sr // 2:
                return []
            clip = np.ascontiguousarray(pcm_16k[s_idx:e_idx], dtype=np.float32)
            try:
                raw = whisper.transcribe(
                    clip,
                    token_timestamps=True,
                    language=lang,
                    temperature_inc=0.0,
                    new_segment_callback=_noop_segment,
                    abort_callback=_abort_cb,
                )
            except TypeError:
                raw = whisper.transcribe(clip, token_timestamps=True, new_segment_callback=_noop_segment)
            clip_words, _ = _extract_whisper_words_and_segments(whisper, raw)
            return [
                WordDto(start=round(start_s + w.start, 3), end=round(start_s + w.end, 3), text=w.text)
                for w in clip_words
            ]

        return _cpp_clip

    def _publisher_timings_ok(
        self,
        words: list[WordDto],
        pcm_16k,
        duration_sec: float,
        lang: str,
        on_progress: ProgressCallback,
        cancel_event: Optional[threading.Event],
    ) -> bool:
        """Spot-checks word-level publisher timings against the audio (see SPOT_CHECK_*)."""
        if len(pcm_16k) == 0 or duration_sec <= 0 or not words:
            return False
        on_progress(JobState.TRANSCRIBING, 35, f"Checking publisher word timings ({len(words)} words)")

        def _on_clip(i: int, n: int) -> None:
            on_progress(JobState.TRANSCRIBING, 35 + 3 * i, f"Checking publisher word timings (clip {i}/{n})")

        t_chk = time.monotonic()
        try:
            clip_fn = self._make_clip_transcriber(pcm_16k, lang, cancel_event)
            accepted, results = spot_check_publisher_timings(
                words, duration_sec, clip_fn, cancel_event=cancel_event, on_clip=_on_clip
            )
        except Exception:
            if cancel_event is not None and cancel_event.is_set():
                raise RuntimeError("Job cancelled")
            logger.warning("Publisher transcript spot check failed; using Whisper", exc_info=True)
            return False
        if cancel_event is not None and cancel_event.is_set():
            raise RuntimeError("Job cancelled")
        detail = ", ".join(
            f"{int(st // 60)}:{int(st % 60):02d} {ov:.0%} ({n_asr} asr/{n_pub} pub words)"
            for st, ov, n_asr, n_pub in results
        )
        logger.info(
            "Publisher word timings %s in %.1fs (need >= %.0f%% on every clip): %s",
            "accepted" if accepted else "rejected, using Whisper",
            time.monotonic() - t_chk,
            SPOT_CHECK_MIN_OVERLAP * 100,
            detail,
        )
        return accepted

    def analyze(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw: Optional[str],
        feed_transcript_mime: str,
        on_progress: ProgressCallback,
        language: Optional[str] = None,
        feed_transcript_url: Optional[str] = None,
        feed_chapters_url: Optional[str] = None,
        feed_chapters_type: str = "",
        cancel_event: Optional[threading.Event] = None,
        episode_title: Optional[str] = None,
        podcast_title: Optional[str] = None,
        podcast_origin: Optional[str] = None,
        fingerprint_store: Optional[FingerprintStore] = None,
    ) -> DesktopAnalysisBundle:
        bundle, _ = self.analyze_with_embeddings(
            audio_path=audio_path,
            file_sha256=file_sha256,
            episode_id=episode_id,
            feed_transcript_raw=feed_transcript_raw,
            feed_transcript_mime=feed_transcript_mime,
            on_progress=on_progress,
            language=language,
            feed_transcript_url=feed_transcript_url,
            feed_chapters_url=feed_chapters_url,
            feed_chapters_type=feed_chapters_type,
            cancel_event=cancel_event,
            episode_title=episode_title,
            podcast_title=podcast_title,
            podcast_origin=podcast_origin,
            fingerprint_store=fingerprint_store,
        )
        return bundle

    def analyze_with_embeddings(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw: Optional[str],
        feed_transcript_mime: str,
        on_progress: ProgressCallback,
        language: Optional[str] = None,
        feed_transcript_url: Optional[str] = None,
        feed_chapters_url: Optional[str] = None,
        feed_chapters_type: str = "",
        cancel_event: Optional[threading.Event] = None,
        episode_title: Optional[str] = None,
        podcast_title: Optional[str] = None,
        podcast_origin: Optional[str] = None,
        fingerprint_store: Optional[FingerprintStore] = None,
    ):
        self.whisper_model = self._resolve_whisper_model()
        if not self.ml_imports_ready():
            raise RuntimeError("ML dependencies not installed. Install with: pip install -e '.[ml]'")
        if not (self.whisper_model.exists() and self.qwen_model.exists() and self.ad_tagger_onnx.exists()):
            missing = [
                str(p.name)
                for p in (self.whisper_model, self.qwen_model, self.ad_tagger_onnx)
                if not p.exists()
            ]
            raise FileNotFoundError(
                f"Missing models in {self.models_dir}: {', '.join(missing)}. "
                "Run 'hushcast-worker setup' to download models."
            )

        t0 = time.monotonic()
        lang = _normalize_whisper_language(language)
        # Stages report out of order (a rejected publisher transcript, the Whisper timer vs its
        # segment callback); the phone should never see the percentage go down.
        on_progress = _MonotonicProgress(on_progress)
        on_progress(JobState.TRANSCRIBING, 25, f"Decoding audio for {self.whisper_model.name}")

        t_dec = time.monotonic()
        pcm_16k, pcm_24k_mid, pcm_24k_side, duration_sec = decode_audio_streams(audio_path)
        if cancel_event is not None and cancel_event.is_set():
            raise RuntimeError("Job cancelled")

        # V22: Fetch feed transcript from URL if raw not provided
        tx_raw = feed_transcript_raw
        tx_mime = feed_transcript_mime
        if (not tx_raw or not tx_raw.strip()) and feed_transcript_url:
            tx_raw = _fetch_feed_url_text(feed_transcript_url)
            if not tx_mime and feed_transcript_url:
                low_u = feed_transcript_url.lower()
                if ".vtt" in low_u:
                    tx_mime = "text/vtt"
                elif ".srt" in low_u:
                    tx_mime = "application/x-subrip"
                elif ".json" in low_u:
                    tx_mime = "application/json"

        parsed_feed = _parse_feed_transcript(tx_raw, tx_mime)
        use_feed_transcript = False
        words: list[WordDto] = []
        segments: list[SegmentDto] = []

        if parsed_feed is not None:
            f_words, f_segments, needs_align = parsed_feed
            if not needs_align:
                # Word-level publisher JSON: trust its timings only if spot checks against the
                # audio agree (dynamic ad insertion shifts the audio against the transcript).
                if self._publisher_timings_ok(f_words, pcm_16k, duration_sec, lang, on_progress, cancel_event):
                    words, segments = f_words, f_segments
                    use_feed_transcript = True
                    on_progress(
                        JobState.TRANSCRIBING,
                        60,
                        f"Using feed transcript ({len(segments)} segments / {len(words)} words)",
                    )
            elif self._forced_aligner.is_available() and len(pcm_16k) > 0:
                on_progress(
                    JobState.TRANSCRIBING,
                    45,
                    f"Aligning feed transcript ({len(f_words)} words)",
                )
                n_alignable = count_alignable_words(f_words)
                n_aligned = self._forced_aligner.align_words(
                    pcm_16k, f_segments, f_words, cancel_event=cancel_event
                )
                if cancel_event is not None and cancel_event.is_set():
                    raise RuntimeError("Job cancelled")
                if feed_alignment_accepted(n_aligned, n_alignable, len(f_words)):
                    words, segments = f_words, f_segments
                    use_feed_transcript = True
                    logger.info(
                        "Feed transcript accepted: %d/%d alignable words (%d total) aligned at score >= %.2f",
                        n_aligned,
                        n_alignable,
                        len(f_words),
                        MIN_WORD_SCORE,
                    )
                    on_progress(
                        JobState.TRANSCRIBING,
                        60,
                        f"Aligned feed transcript ({n_aligned}/{len(f_words)} words)",
                    )
                else:
                    logger.info(
                        "Feed transcript aligned only %d/%d alignable words (%d total) at score >= %.2f "
                        "(need %.0f%%); falling back to Whisper",
                        n_aligned,
                        n_alignable,
                        len(f_words),
                        MIN_WORD_SCORE,
                        FEED_ALIGN_MIN_FRACTION * 100,
                    )

        if not use_feed_transcript:
            on_progress(
                JobState.TRANSCRIBING,
                30,
                f"Transcribing {int(duration_sec // 60)}m{int(duration_sec % 60):02d}s audio ({self.whisper_model.name}, {time.monotonic() - t_dec:.1f}s decode)",
            )
            t_tr = time.monotonic()
            tr_done = threading.Event()
            est_sec = max(4.0, float(self.estimate_eta_sec(duration_sec) or (duration_sec / 12.0)))
            tr_progress = _TranscribeProgress(
                on_progress,
                duration_sec=duration_sec,
                est_sec=est_sec,
                model_name=self.whisper_model.name,
            )

            def _whisper_heartbeat() -> None:
                # Elapsed-time estimate until whisper.cpp's segment callback takes over.
                while not tr_done.wait(0.5):
                    if cancel_event is not None and cancel_event.is_set():
                        return
                    try:
                        if not tr_progress.tick():
                            return
                    except Exception:
                        return

            hb = threading.Thread(target=_whisper_heartbeat, daemon=True)
            hb.start()
            try:
                if self._should_use_faster_whisper():
                    fw_pipe = self._get_faster_whisper_pipeline()
                    words, segments = _transcribe_faster_whisper(
                        fw_pipe,
                        pcm_16k,
                        lang=lang,
                        duration_sec=duration_sec,
                        initial_prompt=build_initial_prompt(podcast_title, episode_title, lang),
                        cancel_event=cancel_event,
                    )
                else:
                    use_dtw = not self._forced_aligner.is_available()
                    whisper = self._get_whisper_cpp(use_dtw=use_dtw)

                    def _on_new_segment(seg) -> None:
                        if cancel_event is not None and cancel_event.is_set():
                            return
                        t_end_sec = getattr(seg, "t1", 0) / 100.0 if hasattr(seg, "t1") else 0.0
                        tr_progress.segment(t_end_sec)

                    def _abort_cb() -> bool:
                        return bool(cancel_event is not None and cancel_event.is_set())

                    transcribe_kwargs: dict[str, object] = {
                        "token_timestamps": True,
                        "language": lang,
                        "temperature_inc": 0.0,
                        "new_segment_callback": _on_new_segment,
                        "abort_callback": _abort_cb,
                    }
                    try:
                        raw_segments = whisper.transcribe(pcm_16k, **transcribe_kwargs)
                    except TypeError:
                        # Fallback if pywhispercpp version doesn't accept all kwargs
                        fallback_kwargs: dict[str, object] = {"token_timestamps": True}
                        if not self.whisper_model.name.endswith(".en.bin"):
                            fallback_kwargs["language"] = lang
                        raw_segments = whisper.transcribe(pcm_16k, **fallback_kwargs)
                    if cancel_event is not None and cancel_event.is_set():
                        raise RuntimeError("Job cancelled")
                    words, segments = _extract_whisper_words_and_segments(whisper, raw_segments)
            finally:
                tr_done.set()
                hb.join(timeout=1.0)

            tr_sec = max(0.01, time.monotonic() - t_tr)
            self._record_rtf(duration_sec, tr_sec)
            rt_speed = duration_sec / tr_sec if duration_sec > 0 else 0.0
            on_progress(
                JobState.TRANSCRIBING,
                60,
                f"Transcribed {len(segments)} segments / {len(words)} words in {tr_sec:.1f}s ({rt_speed:.1f}x realtime)",
            )

            if words and segments and len(pcm_16k) > 0 and self._forced_aligner.is_available():
                on_progress(JobState.TRANSCRIBING, 61, f"Refining word timing ({len(words)} words)")
                t_fa = time.monotonic()
                n_aligned = self._forced_aligner.align_words(
                    pcm_16k, segments, words, cancel_event=cancel_event
                )
                if n_aligned > 0:
                    if sum(s.nWords for s in segments) == len(words):
                        pos = 0
                        for seg in segments:
                            if seg.nWords > 0:
                                seg_words = words[pos : pos + seg.nWords]
                                seg.start = seg_words[0].start
                                seg.end = seg_words[-1].end
                                pos += seg.nWords
                    on_progress(
                        JobState.TRANSCRIBING,
                        62,
                        f"Aligned {n_aligned}/{len(words)} words in {time.monotonic() - t_fa:.1f}s",
                    )

        if cancel_event is not None and cancel_event.is_set():
            raise RuntimeError("Job cancelled")

        if segments and len(pcm_16k) > 0:
            on_progress(JobState.TRANSCRIBING, 63, f"Identifying speakers across {len(segments)} segments")
            assign_speakers(pcm_16k, segments)
            n_speakers = len({s.speaker for s in segments if s.speaker is not None})
            on_progress(JobState.TRANSCRIBING, 65, f"Detected {max(1, n_speakers)} speaker(s)")

        on_progress(JobState.EMBEDDING, 66, f"Loading embedding model ({self.qwen_model.name})")
        texts = [s.text for s in segments]
        ad_segments: list[AdSegmentDto] = []
        emb_2d = None
        summary: Optional[str] = None
        chapters: list[DesktopChapterDto] = []

        if texts:
            t_emb = time.monotonic()
            emb_2d = self._embed_segments(texts, on_progress=on_progress, cancel_event=cancel_event)
            emb = emb_2d[None, :, :]

            on_progress(
                JobState.TAGGING,
                86,
                f"Embedded {len(texts)} segments in {time.monotonic() - t_emb:.1f}s • Detecting ads",
            )
            frames = extract_audio_frames(pcm_24k_mid, sr=24000, side_pcm=pcm_24k_side) if len(pcm_24k_mid) > 0 else None
            feats = compute_segment_features(frames, segments, duration_sec=duration_sec)[None, :, :]

            sess = self._get_ort_session()
            probs = sess.run(None, {"emb": emb, "feats": feats})[0][0]
            ad_segments = decode_ad_segments(probs, segments)

            if fingerprint_store is not None and len(pcm_16k) > 0:
                try:
                    podcast_key = normalize_podcast_key(podcast_origin, podcast_title)
                    fp = compute_fingerprint(
                        pcm_16k,
                        file_sha256=file_sha256,
                        podcast_key=podcast_key,
                    )
                    refs = fingerprint_store.load_references(
                        exclude_sha256=file_sha256,
                        podcast_key=podcast_key,
                    )
                    fp_segments = find_repeated_segments(fp, refs)
                    if fp_segments:
                        logger.info(
                            "Fingerprinting detected %d recurring segment(s) for %s",
                            len(fp_segments),
                            episode_id,
                        )
                        ad_segments = merge_fingerprint_segments(ad_segments, fp_segments)
                    fingerprint_store.save(fp)
                except Exception:
                    logger.warning("Cross-episode fingerprinting failed for %s", episode_id, exc_info=True)

            chat_path = self.chat_model_path()
            sum_mode = f"LLM ({chat_path.name})" if (chat_path and chat_path.exists()) else "Extractive summary"
            on_progress(
                JobState.TAGGING,
                92,
                f"Found {len(ad_segments)} ad span(s) • Generating chapters & {sum_mode}",
            )
            def _on_llm_token(tok_count: int) -> None:
                if cancel_event is not None and cancel_event.is_set():
                    raise RuntimeError("Job cancelled")
                if tok_count == 1 or tok_count % 20 == 0:
                    pct = min(97, 92 + tok_count // 120)
                    on_progress(JobState.TAGGING, pct, f"Summarizing ({tok_count} tokens)")

            try:
                summary, chapters = generate_chapters_and_summary(
                    segments=segments,
                    embeddings=emb_2d,
                    ad_segments=ad_segments,
                    chat_model_path=chat_path,
                    audio_path=audio_path,
                    has_feed_chapters=bool(feed_chapters_url),
                    on_token=_on_llm_token,
                )
            finally:
                self._release_chat_model_if_low_mem()
            on_progress(
                JobState.TAGGING,
                98,
                f"Generated {len(chapters)} chapters ({sum_mode})",
            )

        elapsed_ms = int((time.monotonic() - t0) * 1000)
        bundle = DesktopAnalysisBundle(
            fileSha256=file_sha256,
            episodeId=episode_id,
            durationSec=round(duration_sec, 1),
            wordIndexBlob=WordIndexBlobDto(words=words, segments=segments),
            adSegments=ad_segments,
            summary=summary,
            chapters=chapters,
            whisperModel=self.whisper_model.name,
            analysisDurationMs=elapsed_ms,
            workerAccelerator=detect_accelerator(),
            pipelineVersion=PIPELINE_VERSION,
        )
        return bundle, emb_2d

    def reprocess_bundle(
        self,
        *,
        stage: str,
        existing_bundle: DesktopAnalysisBundle,
        audio_path: Optional[Path],
        existing_embeddings: Optional[object],
        on_progress: ProgressCallback,
        cancel_event: Optional[threading.Event] = None,
    ) -> tuple[DesktopAnalysisBundle, Optional[object]]:
        import numpy as np

        t0 = time.monotonic()
        stage_upper = stage.strip().upper()
        words = [w.model_copy() for w in existing_bundle.wordIndexBlob.words]
        segments = [s.model_copy() for s in existing_bundle.wordIndexBlob.segments]
        ad_segments = [a.model_copy() for a in existing_bundle.adSegments]
        summary = existing_bundle.summary
        chapters = [c.model_copy() for c in existing_bundle.chapters]
        emb_2d = existing_embeddings

        if stage_upper == "ALIGN_WORDS":
            if audio_path is not None and audio_path.exists() and words and segments:
                on_progress(JobState.TRANSCRIBING, 30, "Decoding audio for word alignment")
                pcm_16k, _ = decode_audio_16k_mono(audio_path)
                if len(pcm_16k) > 0 and self._forced_aligner.is_available():
                    on_progress(JobState.TRANSCRIBING, 60, f"Re-aligning {len(words)} words (MMS_FA)")
                    n_aligned = self._forced_aligner.align_words(
                        pcm_16k, segments, words, cancel_event=cancel_event
                    )
                    for i in range(len(words) - 1):
                        if words[i + 1].start > words[i].start and words[i].end > words[i + 1].start:
                            words[i].end = words[i + 1].start
                    on_progress(JobState.TAGGING, 95, f"Aligned {n_aligned}/{len(words)} words")
        elif stage_upper == "ADS_AND_SUMMARY":
            pcm_24k_mid = np.zeros(0, dtype=np.float32)
            pcm_24k_side: Optional[np.ndarray] = None
            duration_sec = existing_bundle.durationSec
            if audio_path is not None and audio_path.exists():
                on_progress(JobState.EMBEDDING, 25, "Decoding audio for ad detection")
                _, pcm_24k_mid, pcm_24k_side, duration_sec = decode_audio_streams(audio_path)
            texts = [s.text for s in segments]
            if texts:
                if emb_2d is None or getattr(emb_2d, "shape", (0,))[0] != len(texts):
                    on_progress(JobState.EMBEDDING, 45, f"Embedding {len(texts)} segments")
                    emb_2d = self._embed_segments(texts, cancel_event=cancel_event)
                emb = emb_2d[None, :, :]
                on_progress(JobState.TAGGING, 75, "Re-running ad detector")
                frames = (
                    extract_audio_frames(pcm_24k_mid, sr=24000, side_pcm=pcm_24k_side)
                    if len(pcm_24k_mid) > 0
                    else None
                )
                feats = compute_segment_features(frames, segments, duration_sec=duration_sec)[None, :, :]
                sess = self._get_ort_session()
                probs = sess.run(None, {"emb": emb, "feats": feats})[0][0]
                ad_segments = decode_ad_segments(probs, segments)

                chat_path = self.chat_model_path()
                sum_mode = f"LLM ({chat_path.name})" if (chat_path and chat_path.exists()) else "Extractive summary"
                on_progress(JobState.TAGGING, 90, f"Generating chapters & {sum_mode}")
                try:
                    summary, chapters = generate_chapters_and_summary(
                        segments=segments,
                        embeddings=emb_2d,
                        ad_segments=ad_segments,
                        chat_model_path=chat_path,
                        audio_path=audio_path,
                    )
                finally:
                    self._release_chat_model_if_low_mem()
        elif stage_upper == "SUMMARY_CHAPTERS":
            chat_path = self.chat_model_path()
            sum_mode = f"LLM ({chat_path.name})" if (chat_path and chat_path.exists()) else "Extractive summary"
            on_progress(JobState.TAGGING, 70, f"Regenerating chapters & {sum_mode}")
            try:
                summary, chapters = generate_chapters_and_summary(
                    segments=segments,
                    embeddings=emb_2d,
                    ad_segments=ad_segments,
                    chat_model_path=chat_path,
                    audio_path=audio_path,
                )
            finally:
                self._release_chat_model_if_low_mem()
            on_progress(JobState.TAGGING, 95, f"Generated {len(chapters)} chapters")

        elapsed_ms = int((time.monotonic() - t0) * 1000)
        bundle = DesktopAnalysisBundle(
            fileSha256=existing_bundle.fileSha256,
            episodeId=existing_bundle.episodeId,
            durationSec=existing_bundle.durationSec,
            wordIndexBlob=WordIndexBlobDto(words=words, segments=segments),
            adSegments=ad_segments,
            summary=summary,
            chapters=chapters,
            whisperModel=existing_bundle.whisperModel or self.whisper_model.name,
            analysisDurationMs=elapsed_ms,
            workerAccelerator=detect_accelerator(),
            pipelineVersion=PIPELINE_VERSION,
        )
        return bundle, emb_2d


def _fw_words_to_dtos(raw_words, default_start: float) -> list[WordDto]:
    """faster-whisper Word objects -> WordDto, preserving sub-word attachment (e.g. .5, ,000, %)."""
    out: list[WordDto] = []
    for w in raw_words or []:
        raw = str(getattr(w, "word", "") or "").lstrip("\r\n\t")
        if not raw.strip():
            continue
        ws = round(float(getattr(w, "start", default_start)), 3)
        we = round(max(ws + 0.01, float(getattr(w, "end", ws + 0.1))), 3)
        out.append(WordDto(start=ws, end=we, text=raw))
    return out


_NO_SPACE_BEFORE_RE = re.compile(r"^[.,%?!:;)\]\u2019'\u201d\"]")
_NO_SPACE_AFTER_RE = re.compile(r"[-/($#\u201c\"\[(\s]$")


def _join_word_text(prev_text: Optional[str], cur_text: str) -> str:
    """Keep sub-word tokens attached while ensuring a space at segment/repair boundaries."""
    stripped = cur_text.strip()
    if not stripped:
        return ""
    if cur_text.startswith(" "):
        return " " + stripped
    if prev_text is None:
        return stripped
    if _NO_SPACE_BEFORE_RE.match(stripped) or _NO_SPACE_AFTER_RE.search(prev_text):
        return stripped
    return " " + stripped


def _make_fw_window_decoder(fw_model, pcm_16k, lang: str, sr: int = 16000):
    """Sequential (non-batched) re-decode of one audio window, for `repair_hard_cuts`."""

    def _decode(start_s: float, end_s: float, prompt: str) -> list[WordDto]:
        s_idx = max(0, int(round(start_s * sr)))
        e_idx = min(len(pcm_16k), int(round(end_s * sr)))
        if e_idx - s_idx < int(0.5 * sr):
            return []
        segs, _ = fw_model.transcribe(
            pcm_16k[s_idx:e_idx],
            language=lang,
            temperature=0.0,
            condition_on_previous_text=False,
            vad_filter=False,
            word_timestamps=True,
            initial_prompt=prompt or None,
        )
        out: list[WordDto] = []
        for seg in segs:
            seg_words = _fw_words_to_dtos(getattr(seg, "words", None), float(getattr(seg, "start", 0.0)))
            if not seg_words and (getattr(seg, "text", "") or "").strip():
                toks = seg.text.split()
                s0 = float(getattr(seg, "start", 0.0))
                s1 = max(float(getattr(seg, "end", s0)), s0 + 0.01 * len(toks))
                step = (s1 - s0) / len(toks)
                seg_words = [
                    WordDto(start=round(s0 + i * step, 3), end=round(s0 + (i + 1) * step, 3), text=" " + t)
                    for i, t in enumerate(toks)
                ]
            out.extend(seg_words)
        return out

    return _decode


def _transcribe_faster_whisper(
    fw_pipe,
    pcm_16k,
    *,
    lang: str,
    duration_sec: float,
    initial_prompt: Optional[str],
    cancel_event: Optional[threading.Event] = None,
) -> tuple[list[WordDto], list[SegmentDto]]:
    """
    Batched faster-whisper pass, then: drop prompt leakage, repair hard window cuts, and split
    sentences over the whole episode so they can span windows. `words` and `segments` come
    from the same flat word list.
    """
    fw_segs, _ = fw_pipe.transcribe(
        pcm_16k,
        batch_size=8,
        language=lang,
        temperature=0.0,
        condition_on_previous_text=False,
        vad_filter=True,
        vad_parameters={
            "threshold": 0.35,
            "min_silence_duration_ms": 700,
            "speech_pad_ms": 600,
        },
        word_timestamps=True,
        initial_prompt=initial_prompt,
    )
    window_words: list[list[WordDto]] = []
    n_leaked = 0
    for seg in fw_segs:
        if cancel_event is not None and cancel_event.is_set():
            raise RuntimeError("Job cancelled")
        seg_words = _fw_words_to_dtos(getattr(seg, "words", None), float(seg.start))
        if not seg_words and (seg.text or "").strip():
            # No word timings: spread the text evenly so words and segments stay in step.
            toks = seg.text.split()
            s0, s1 = float(seg.start), max(float(seg.end), float(seg.start) + 0.01 * len(toks))
            step = (s1 - s0) / len(toks)
            seg_words = [
                WordDto(start=round(s0 + i * step, 3), end=round(s0 + (i + 1) * step, 3), text=" " + t)
                for i, t in enumerate(toks)
            ]
        seg_words, dropped = strip_prompt_leak(seg_words, initial_prompt)
        n_leaked += dropped
        if seg_words:
            window_words.append(seg_words)
    if n_leaked:
        logger.info("Dropped %d prompt-leak word(s) from near-silent windows", n_leaked)

    fw_logger = logging.getLogger("faster_whisper")
    prev_level = fw_logger.level
    fw_logger.setLevel(max(prev_level, logging.WARNING))  # one "Processing audio" line per re-decode
    try:
        flat, _ = repair_hard_cuts(
            window_words,
            _make_fw_window_decoder(fw_pipe.model, pcm_16k, lang),
            audio_duration_s=duration_sec,
            cancel_event=cancel_event,
        )
    finally:
        fw_logger.setLevel(prev_level)

    joined_flat: list[WordDto] = []
    for w in flat:
        if not w.text.strip():
            continue
        prev_txt = joined_flat[-1].text if joined_flat else None
        joined_flat.append(WordDto(start=w.start, end=w.end, text=_join_word_text(prev_txt, w.text)))
    flat = joined_flat
    for i in range(len(flat) - 1):
        # Re-decoded words can overlap their neighbours slightly at the repair seams.
        if flat[i + 1].start > flat[i].start and flat[i].end > flat[i + 1].start:
            flat[i].end = flat[i + 1].start
    segments = split_sentences(flat)
    words = [WordDto(start=w.start, end=w.end, text=w.text.strip()) for w in flat]
    return words, segments


_DTW_PRESET_TABLE: tuple[tuple[str, str, int], ...] = (
    ("large-v3-turbo", "WHISPER_AHEADS_LARGE_V3_TURBO", 14),
    ("large-v3", "WHISPER_AHEADS_LARGE_V3", 13),
    ("large-v2", "WHISPER_AHEADS_LARGE_V2", 12),
    ("large-v1", "WHISPER_AHEADS_LARGE_V1", 11),
    ("medium.en", "WHISPER_AHEADS_MEDIUM_EN", 9),
    ("medium", "WHISPER_AHEADS_MEDIUM", 10),
    ("small.en", "WHISPER_AHEADS_SMALL_EN", 7),
    ("small", "WHISPER_AHEADS_SMALL", 8),
    ("base.en", "WHISPER_AHEADS_BASE_EN", 5),
    ("base", "WHISPER_AHEADS_BASE", 6),
    ("tiny.en", "WHISPER_AHEADS_TINY_EN", 3),
    ("tiny", "WHISPER_AHEADS_TINY", 4),
)


def _resolve_dtw_aheads_preset(model_name: str, pw_mod=None) -> Optional[object]:
    low = (model_name or "").lower()
    if pw_mod is None:
        try:
            import _pywhispercpp as pw_mod
        except Exception:
            pw_mod = None
    for needle, symbol_name, enum_val in _DTW_PRESET_TABLE:
        if needle in low:
            if pw_mod is not None:
                val = getattr(pw_mod, symbol_name, None)
                if val is not None:
                    return val
                enum_cls = getattr(pw_mod, "whisper_alignment_heads_preset", None)
                if enum_cls is not None:
                    val = getattr(enum_cls, symbol_name, None)
                    if val is not None:
                        return val
            return enum_val
    return None


def _read_token_bytes(pw, ctx, s_idx: int, t_idx: int) -> str:
    """
    Reads raw token bytes when available (A7) so multi-byte UTF-8 characters split
    across BPE tokens don't raise UnicodeDecodeError.
    """
    if hasattr(pw, "whisper_full_get_token_id") and hasattr(pw, "whisper_token_to_bytes"):
        try:
            tid = pw.whisper_full_get_token_id(ctx, s_idx, t_idx)
            raw = pw.whisper_token_to_bytes(ctx, tid)
            if isinstance(raw, (bytes, bytearray)):
                return bytes(raw).decode("utf-8", errors="replace")
            if isinstance(raw, str):
                return raw
        except Exception:
            pass
    try:
        return pw.whisper_full_get_token_text(ctx, s_idx, t_idx) or ""
    except UnicodeDecodeError:
        return ""


def _extract_whisper_words_and_segments(
    whisper, raw_segments
) -> tuple[list[WordDto], list[SegmentDto]]:
    words: list[WordDto] = []
    segments: list[SegmentDto] = []

    pw = None
    ctx = getattr(whisper, "_ctx", None)
    if ctx is not None:
        try:
            import _pywhispercpp as pw_mod

            if hasattr(pw_mod, "whisper_full_n_segments") and hasattr(
                pw_mod, "whisper_full_get_token_data"
            ):
                pw = pw_mod
        except Exception:
            pw = None

    if pw is not None and ctx is not None:
        n_segs = pw.whisper_full_n_segments(ctx)
        has_mapped_t0 = hasattr(pw, "whisper_full_get_token_t0") and hasattr(pw, "whisper_full_get_token_t1")
        has_token_bytes = hasattr(pw, "whisper_full_get_token_id") and hasattr(pw, "whisper_token_to_bytes")
        for s_idx in range(n_segs):
            seg_text = (pw.whisper_full_get_segment_text(ctx, s_idx) or "").strip()
            if not seg_text:
                continue
            s_t0 = round(pw.whisper_full_get_segment_t0(ctx, s_idx) / 100.0, 3)
            s_t1 = round(pw.whisper_full_get_segment_t1(ctx, s_idx) / 100.0, 3)

            n_toks = pw.whisper_full_n_tokens(ctx, s_idx)
            seg_words: list[WordDto] = []
            cur_bytes = bytearray()
            cur_text = ""
            cur_t0: Optional[float] = None
            cur_t1: Optional[float] = None

            def _flush_word() -> None:
                nonlocal cur_bytes, cur_text, cur_t0, cur_t1
                txt = cur_bytes.decode("utf-8", errors="replace") if cur_bytes else cur_text
                if txt.strip():
                    w_start = cur_t0 if cur_t0 is not None else s_t0
                    w_end = cur_t1 if cur_t1 is not None else s_t1
                    clean = txt.strip()
                    words.append(WordDto(start=w_start, end=w_end, text=clean))
                    seg_words.append(WordDto(start=w_start, end=w_end, text=txt))
                cur_bytes = bytearray()
                cur_text = ""
                cur_t0 = None
                cur_t1 = None

            for t_idx in range(n_toks):
                raw_b: Optional[bytes] = None
                if has_token_bytes:
                    try:
                        tid = pw.whisper_full_get_token_id(ctx, s_idx, t_idx)
                        tb = pw.whisper_token_to_bytes(ctx, tid)
                        if isinstance(tb, (bytes, bytearray)):
                            raw_b = bytes(tb)
                            tok_text = raw_b.decode("utf-8", errors="replace")
                        else:
                            tok_text = str(tb or "")
                    except Exception:
                        tok_text = _read_token_bytes(pw, ctx, s_idx, t_idx)
                else:
                    tok_text = _read_token_bytes(pw, ctx, s_idx, t_idx)

                if not tok_text or tok_text.startswith("[_"):
                    continue
                tdata = pw.whisper_full_get_token_data(ctx, s_idx, t_idx)
                mapped_t0 = pw.whisper_full_get_token_t0(ctx, s_idx, t_idx) if has_mapped_t0 else tdata.t0
                mapped_t1 = pw.whisper_full_get_token_t1(ctx, s_idx, t_idx) if has_mapped_t0 else tdata.t1
                raw_dtw = getattr(tdata, "t_dtw", -1)
                has_dtw = isinstance(raw_dtw, (int, float)) and raw_dtw >= 0
                if has_dtw:
                    vad_shift = mapped_t0 - tdata.t0
                    adj_dtw = raw_dtw + vad_shift
                    t0 = round(adj_dtw / 100.0, 3)
                    if mapped_t1 >= mapped_t0 and mapped_t1 >= adj_dtw and (mapped_t1 - adj_dtw) <= 80:
                        t1 = round(mapped_t1 / 100.0, 3)
                    else:
                        t1 = round(t0 + 0.15, 3)
                else:
                    t0 = round(mapped_t0 / 100.0, 3)
                    t1 = round(max(mapped_t1, mapped_t0 + 1) / 100.0, 3)

                if tok_text[0].isspace() and (cur_bytes or cur_text.strip()):
                    _flush_word()
                    if raw_b is not None:
                        cur_bytes.extend(raw_b)
                    else:
                        cur_text = tok_text
                    cur_t0 = t0
                    cur_t1 = t1
                else:
                    if cur_t0 is None:
                        cur_t0 = t0
                    if raw_b is not None:
                        cur_bytes.extend(raw_b)
                    else:
                        cur_text += tok_text
                    has_alnum = any(ch.isalnum() for ch in tok_text)
                    if cur_t1 is None or (has_alnum and (has_dtw or (t1 - cur_t0) <= 1.2)):
                        cur_t1 = max(cur_t1 or t1, t1)

            if cur_bytes or cur_text.strip():
                _flush_word()

            if seg_words:
                segments.extend(split_sentences(seg_words))
            else:
                segments.append(
                    SegmentDto(
                        start=s_t0,
                        end=s_t1,
                        text=seg_text,
                        nWords=len(seg_text.split()),
                    )
                )
        if segments:
            for i in range(len(words) - 1):
                if words[i + 1].start > words[i].start and words[i].end > words[i + 1].start:
                    words[i].end = words[i + 1].start
            return words, segments

    for seg in raw_segments:
        text = (seg.text or "").strip()
        if not text:
            continue
        start_s = round(seg.t0 / 100.0, 3)
        end_s = round(seg.t1 / 100.0, 3)
        seg_words_str = text.split()
        if seg_words_str:
            step = max(0.01, (end_s - start_s) / len(seg_words_str))
            seg_word_dtos: list[WordDto] = []
            for idx, w in enumerate(seg_words_str):
                ws = round(start_s + idx * step, 3)
                we = round(min(end_s, ws + step), 3)
                words.append(WordDto(start=ws, end=we, text=w))
                seg_word_dtos.append(WordDto(start=ws, end=we, text=(" " + w) if idx > 0 else w))
            segments.extend(split_sentences(seg_word_dtos))
        else:
            segments.append(
                SegmentDto(
                    start=start_s,
                    end=end_s,
                    text=text,
                    nWords=0,
                )
            )
    return words, segments
