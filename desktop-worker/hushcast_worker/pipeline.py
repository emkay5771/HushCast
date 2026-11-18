from __future__ import annotations

import json
import logging
import os
import platform
import threading
import time
from pathlib import Path
from typing import Callable, Optional, Protocol

from .ad_tagger import compute_segment_features, decode_ad_segments, extract_audio_frames
from .audio import (
    assign_speakers,
    decode_audio_16k_mono,
    download_audio_streaming_sha256,
)
from .chapters import generate_chapters_and_summary
from .forced_align import ForcedAligner
from .models import (
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
    "ggml-large-v3-turbo-q5_0.bin",
    "ggml-large-v3-turbo-q8_0.bin",
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

    def whisper_model_name(self) -> str: ...

    def chat_model_ready(self) -> bool: ...

    def embed_query(self, text: str) -> list[float]: ...

    def analyze(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw: Optional[str],
        feed_transcript_mime: str,
        on_progress: ProgressCallback,
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
    ) -> tuple[DesktopAnalysisBundle, Optional[object]]: ...


def _parse_feed_transcript(
    raw: Optional[str], mime: str
) -> Optional[tuple[list[WordDto], list[SegmentDto]]]:
    if not raw or not raw.strip():
        return None
    if "json" not in mime.lower() and not raw.lstrip().startswith("{"):
        return None
    try:
        data = json.loads(raw)
        words: list[WordDto] = []
        segments: list[SegmentDto] = []

        if isinstance(data.get("segments"), list):
            for seg in data["segments"]:
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
            cur_words: list[str] = []
            seg_start: Optional[float] = None
            seg_end = 0.0
            for item in data["words"]:
                w_txt = str(item.get("text") or item.get("word") or "").strip()
                if not w_txt:
                    continue
                ws = float(item.get("start", 0.0))
                we = float(item.get("end", ws + 0.1))
                words.append(WordDto(start=round(ws, 3), end=round(we, 3), text=w_txt))
                if seg_start is None:
                    seg_start = ws
                cur_words.append(w_txt)
                seg_end = we
                if len(cur_words) >= 14 or w_txt.endswith((".", "!", "?")):
                    segments.append(
                        SegmentDto(
                            start=round(seg_start, 3),
                            end=round(seg_end, 3),
                            text=" ".join(cur_words),
                            nWords=len(cur_words),
                        )
                    )
                    cur_words = []
                    seg_start = None
            if cur_words and seg_start is not None:
                segments.append(
                    SegmentDto(
                        start=round(seg_start, 3),
                        end=round(seg_end, 3),
                        text=" ".join(cur_words),
                        nWords=len(cur_words),
                    )
                )

        if words and segments:
            return words, segments
    except Exception:
        logger.debug("Could not parse feedTranscriptRaw; falling back to Whisper", exc_info=True)
    return None


class LocalMlBackend:
    """
    Runs whisper.cpp + Qwen3-Embedding-0.6B + ad_tagger.onnx on the worker machine.
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
        self._whisper_instance = None
        self._ort_session = None
        self._forced_aligner = ForcedAligner(models_dir=str(models_dir / "torch"))

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
            candidates.sort(key=lambda p: p.stat().st_size, reverse=True)
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

        avail = set(ort.get_available_providers())
        providers = [p for p in ("CUDAExecutionProvider", "CPUExecutionProvider") if p in avail]
        if not providers:
            providers = ["CPUExecutionProvider"]
        self._ort_session = ort.InferenceSession(str(self.ad_tagger_onnx), providers=providers)
        return self._ort_session

    def embed_query(self, text: str) -> list[float]:
        import numpy as np

        if not self.qwen_model.exists():
            raise FileNotFoundError(f"Missing embedding model: {self.qwen_model}")
        with self._model_lock:
            llm = self._get_embedding_llm()
            vec = np.asarray(llm.embed(text), dtype=np.float32)
        if vec.ndim == 2:
            vec = vec[-1]
        elif vec.ndim > 2:
            vec = vec.reshape(-1, vec.shape[-1])[-1]
        norm = float(np.linalg.norm(vec))
        if norm > 1e-12:
            vec = vec / norm
        return vec.tolist()

    def analyze(
        self,
        *,
        audio_path: Path,
        file_sha256: str,
        episode_id: str,
        feed_transcript_raw: Optional[str],
        feed_transcript_mime: str,
        on_progress: ProgressCallback,
    ) -> DesktopAnalysisBundle:
        bundle, _ = self.analyze_with_embeddings(
            audio_path=audio_path,
            file_sha256=file_sha256,
            episode_id=episode_id,
            feed_transcript_raw=feed_transcript_raw,
            feed_transcript_mime=feed_transcript_mime,
            on_progress=on_progress,
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

        import numpy as np
        from pywhispercpp.model import Model as WhisperModel

        t0 = time.monotonic()
        on_progress(JobState.TRANSCRIBING, 25, f"Decoding audio for {self.whisper_model.name}")

        t_dec = time.monotonic()
        pcm_16k, duration_sec = decode_audio_16k_mono(audio_path)

        parsed_feed = _parse_feed_transcript(feed_transcript_raw, feed_transcript_mime)
        if parsed_feed is not None:
            words, segments = parsed_feed
            on_progress(
                JobState.TRANSCRIBING,
                60,
                f"Using feed transcript ({len(segments)} segments / {len(words)} words)",
            )
        else:
            on_progress(
                JobState.TRANSCRIBING,
                30,
                f"Transcribing {int(duration_sec // 60)}m{int(duration_sec % 60):02d}s audio ({self.whisper_model.name}, {time.monotonic() - t_dec:.1f}s decode)",
            )
            t_tr = time.monotonic()
            dur_str = f"{int(duration_sec // 60)}m{int(duration_sec % 60):02d}s"
            tr_done = threading.Event()

            def _whisper_heartbeat() -> None:
                est_sec = max(4.0, duration_sec / 12.0)
                while not tr_done.wait(0.5):
                    el = time.monotonic() - t_tr
                    pct = min(58, 30 + int(28 * (el / est_sec)))
                    on_progress(
                        JobState.TRANSCRIBING,
                        pct,
                        f"Transcribing {dur_str} ({self.whisper_model.name}) • {el:.0f}s",
                    )

            hb = threading.Thread(target=_whisper_heartbeat, daemon=True)
            hb.start()
            def _load_whisper() -> WhisperModel:
                preset = _resolve_dtw_aheads_preset(self.whisper_model.name)
                ctx_params: dict[str, object] = {
                    "dtw_token_timestamps": True,
                    "flash_attn": False,
                }
                if preset:
                    ctx_params["dtw_aheads_preset"] = preset
                try:
                    return WhisperModel(
                        str(self.whisper_model),
                        print_progress=False,
                        context_params=ctx_params,
                    )
                except (TypeError, ValueError):
                    try:
                        return WhisperModel(
                            str(self.whisper_model),
                            print_progress=False,
                            context_params={"dtw_token_timestamps": True},
                        )
                    except (TypeError, ValueError):
                        return WhisperModel(str(self.whisper_model), print_progress=False)

            try:
                if self._low_mem:
                    whisper = _load_whisper()
                else:
                    if self._whisper_instance is None:
                        self._whisper_instance = _load_whisper()
                    whisper = self._whisper_instance
                raw_segments = whisper.transcribe(pcm_16k, token_timestamps=True)
                words, segments = _extract_whisper_words_and_segments(whisper, raw_segments)
            finally:
                tr_done.set()
                hb.join(timeout=1.0)
                if self._low_mem:
                    whisper = None  # allow immediate release on low-memory machines

            tr_sec = max(0.01, time.monotonic() - t_tr)
            rt_speed = duration_sec / tr_sec if duration_sec > 0 else 0.0
            on_progress(
                JobState.TRANSCRIBING,
                60,
                f"Transcribed {len(segments)} segments / {len(words)} words in {tr_sec:.1f}s ({rt_speed:.1f}x realtime)",
            )

        if words and segments and len(pcm_16k) > 0 and self._forced_aligner.is_available():
            on_progress(JobState.TRANSCRIBING, 61, f"Refining word timing ({len(words)} words)")
            t_fa = time.monotonic()
            n_aligned = self._forced_aligner.align_words(pcm_16k, segments, words)
            if n_aligned > 0:
                on_progress(
                    JobState.TRANSCRIBING,
                    62,
                    f"Aligned {n_aligned}/{len(words)} words in {time.monotonic() - t_fa:.1f}s",
                )

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
            emb_list = []
            total_segs = len(texts)
            last_emit = 0.0
            with self._model_lock:
                llm = self._get_embedding_llm()
                for idx_t, t in enumerate(texts):
                    vec = np.asarray(llm.embed(t), dtype=np.float32)
                    if vec.ndim == 2:
                        vec = vec[-1]
                    elif vec.ndim > 2:
                        vec = vec.reshape(-1, vec.shape[-1])[-1]
                    norm = np.linalg.norm(vec)
                    if norm > 1e-12:
                        vec = vec / norm
                    emb_list.append(vec)
                    now = time.monotonic()
                    if (idx_t + 1) == total_segs or (now - last_emit) >= 0.2:
                        last_emit = now
                        pct = 66 + int(19 * (idx_t + 1) / total_segs)
                        on_progress(
                            JobState.EMBEDDING,
                            pct,
                            f"Embedding segments ({idx_t + 1}/{total_segs})",
                        )
            emb_2d = np.stack(emb_list, axis=0)
            emb = emb_2d[None, :, :]

            on_progress(
                JobState.TAGGING,
                86,
                f"Embedded {total_segs} segments in {time.monotonic() - t_emb:.1f}s • Detecting ads",
            )
            frames = extract_audio_frames(pcm_16k) if len(pcm_16k) > 0 else None
            feats = compute_segment_features(frames, segments, duration_sec=duration_sec)[None, :, :]

            sess = self._get_ort_session()
            probs = sess.run(None, {"emb": emb, "feats": feats})[0][0]
            ad_segments = decode_ad_segments(probs, segments)

            chat_path = self.chat_model_path()
            sum_mode = f"LLM ({chat_path.name})" if (chat_path and chat_path.exists()) else "Extractive summary"
            on_progress(
                JobState.TAGGING,
                92,
                f"Found {len(ad_segments)} ad span(s) • Generating chapters & {sum_mode}",
            )
            summary, chapters = generate_chapters_and_summary(
                segments=segments,
                embeddings=emb_2d,
                ad_segments=ad_segments,
                chat_model_path=chat_path,
                audio_path=audio_path,
            )
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
        )
        return bundle, emb_2d


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
        for s_idx in range(n_segs):
            seg_text = (pw.whisper_full_get_segment_text(ctx, s_idx) or "").strip()
            if not seg_text:
                continue
            s_t0 = round(pw.whisper_full_get_segment_t0(ctx, s_idx) / 100.0, 3)
            s_t1 = round(pw.whisper_full_get_segment_t1(ctx, s_idx) / 100.0, 3)
            segments.append(
                SegmentDto(
                    start=s_t0,
                    end=s_t1,
                    text=seg_text,
                    nWords=len(seg_text.split()),
                )
            )
            n_toks = pw.whisper_full_n_tokens(ctx, s_idx)
            cur_text = ""
            cur_t0: Optional[float] = None
            cur_t1: Optional[float] = None
            for t_idx in range(n_toks):
                tok_text = pw.whisper_full_get_token_text(ctx, s_idx, t_idx) or ""
                if not tok_text or tok_text.startswith("[_"):
                    continue
                tdata = pw.whisper_full_get_token_data(ctx, s_idx, t_idx)
                raw_dtw = getattr(tdata, "t_dtw", -1)
                has_dtw = isinstance(raw_dtw, (int, float)) and raw_dtw >= 0
                if has_dtw:
                    t0 = round(raw_dtw / 100.0, 3)
                    if tdata.t1 >= tdata.t0 and tdata.t1 >= raw_dtw and (tdata.t1 - raw_dtw) <= 80:
                        t1 = round(tdata.t1 / 100.0, 3)
                    else:
                        t1 = round(t0 + 0.15, 3)
                else:
                    t0 = round(tdata.t0 / 100.0, 3)
                    t1 = round(max(tdata.t1, tdata.t0 + 1) / 100.0, 3)

                if tok_text[0].isspace() and cur_text.strip():
                    words.append(
                        WordDto(
                            start=cur_t0 if cur_t0 is not None else s_t0,
                            end=cur_t1 if cur_t1 is not None else s_t1,
                            text=cur_text.strip(),
                        )
                    )
                    cur_text = tok_text.lstrip()
                    cur_t0 = t0
                    cur_t1 = t1
                else:
                    if cur_t0 is None:
                        cur_t0 = t0
                    cur_text += tok_text
                    has_alnum = any(ch.isalnum() for ch in tok_text)
                    if cur_t1 is None or (has_alnum and (has_dtw or (t1 - cur_t0) <= 1.2)):
                        cur_t1 = max(cur_t1 or t1, t1)
            if cur_text.strip():
                words.append(
                    WordDto(
                        start=cur_t0 if cur_t0 is not None else s_t0,
                        end=cur_t1 if cur_t1 is not None else s_t1,
                        text=cur_text.strip(),
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
        seg_words = text.split()
        segments.append(
            SegmentDto(
                start=start_s,
                end=end_s,
                text=text,
                nWords=len(seg_words),
            )
        )
        if seg_words:
            step = max(0.01, (end_s - start_s) / len(seg_words))
            for idx, w in enumerate(seg_words):
                ws = round(start_s + idx * step, 3)
                we = round(min(end_s, ws + step), 3)
                words.append(WordDto(start=ws, end=we, text=w))
    return words, segments
