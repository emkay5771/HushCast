from __future__ import annotations

import hashlib
import platform
import time
from pathlib import Path
from typing import Callable, Optional, Protocol

import httpx

from .models import (
    AdSegmentDto,
    DesktopAnalysisBundle,
    JobState,
    SegmentDto,
    WordDto,
    WordIndexBlobDto,
)

ProgressCallback = Callable[[JobState, int, str], None]


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
    return "cpu"


class AnalysisBackend(Protocol):
    """Pluggable transcription + ad-detection backend."""

    def models_ready(self) -> bool: ...

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


class LocalMlBackend:
    """
    Runs whisper.cpp (Metal on Apple Silicon) + Qwen3-Embedding-0.6B (llama.cpp Metal)
    + ad_tagger.onnx on the desktop machine.
    """

    def __init__(self, models_dir: Path, ad_tagger_onnx: Optional[Path] = None) -> None:
        self.models_dir = models_dir
        self.models_dir.mkdir(parents=True, exist_ok=True)
        self.whisper_model = models_dir / "ggml-base.en-q5_1.bin"
        self.qwen_model = models_dir / "Qwen3-Embedding-0.6B-Q8_0.gguf"
        self.ad_tagger_onnx = ad_tagger_onnx or (models_dir / "ad_tagger.onnx")

    def models_ready(self) -> bool:
        return (
            self.whisper_model.exists()
            and self.qwen_model.exists()
            and self.ad_tagger_onnx.exists()
        )

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
        if not self.models_ready():
            missing = [
                str(p.name)
                for p in (self.whisper_model, self.qwen_model, self.ad_tagger_onnx)
                if not p.exists()
            ]
            raise FileNotFoundError(
                f"Missing models in {self.models_dir}: {', '.join(missing)}. "
                "See desktop-worker/README.md for model setup."
            )

        try:
            import av  # noqa: F401
            import numpy as np  # noqa: F401
            import onnxruntime as ort  # noqa: F401
            from llama_cpp import Llama  # noqa: F401
            from pywhispercpp.model import Model as WhisperModel  # noqa: F401
        except ImportError as exc:
            raise RuntimeError(
                "ML dependencies not installed. Install with: pip install -e '.[ml]'"
            ) from exc

        t0 = time.monotonic()
        on_progress(JobState.TRANSCRIBING, 25, "Decoding & transcribing on GPU")

        # 1. Decode to 16 kHz mono for Whisper
        pcm_16k, duration_sec = _decode_audio_16k_mono(audio_path)
        whisper = WhisperModel(str(self.whisper_model), print_progress=False)
        raw_segments = whisper.transcribe(pcm_16k, token_timestamps=True)

        words, segments = _extract_whisper_words_and_segments(whisper, raw_segments)

        on_progress(JobState.EMBEDDING, 65, "Embedding sentences (Qwen3 Metal)")
        try:
            llm = Llama(
                model_path=str(self.qwen_model),
                embedding=True,
                pooling_type=3,  # LLAMA_POOLING_TYPE_LAST (matches llama_jni.cpp)
                n_gpu_layers=-1,
                verbose=False,
            )
        except TypeError:
            llm = Llama(
                model_path=str(self.qwen_model),
                embedding=True,
                n_gpu_layers=-1,
                verbose=False,
            )
        texts = [s.text for s in segments]
        ad_segments: list[AdSegmentDto] = []

        if texts:
            import numpy as np

            emb_list = []
            for t in texts:
                vec = np.asarray(llm.embed(t), dtype=np.float32)
                if vec.ndim == 2:
                    vec = vec[-1]
                elif vec.ndim > 2:
                    vec = vec.reshape(-1, vec.shape[-1])[-1]
                norm = np.linalg.norm(vec)
                if norm > 1e-12:
                    vec = vec / norm
                emb_list.append(vec)
            emb = np.stack(emb_list, axis=0)[None, :, :]  # (1, N, 1024)

            on_progress(JobState.TAGGING, 85, "Running ad_tagger.onnx")
            feats = _basic_segment_features(segments, duration_sec)[None, :, :]  # (1, N, 28)

            sess = ort.InferenceSession(str(self.ad_tagger_onnx))
            probs = sess.run(None, {"emb": emb, "feats": feats})[0][0]  # (N, 5)
            ad_segments = _decode_ad_segments(probs, segments)

        elapsed_ms = int((time.monotonic() - t0) * 1000)
        return DesktopAnalysisBundle(
            fileSha256=file_sha256,
            episodeId=episode_id,
            durationSec=round(duration_sec, 1),
            wordIndexBlob=WordIndexBlobDto(words=words, segments=segments),
            adSegments=ad_segments,
            analysisDurationMs=elapsed_ms,
            workerAccelerator=detect_accelerator(),
        )


def _extract_whisper_words_and_segments(
    whisper, raw_segments
) -> tuple[list[WordDto], list[SegmentDto]]:
    words: list[WordDto] = []
    segments: list[SegmentDto] = []

    # Try extracting exact token-level timestamps from pywhispercpp C++ context
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
                    cur_t1 = t1
            if cur_text.strip():
                words.append(
                    WordDto(
                        start=cur_t0 if cur_t0 is not None else s_t0,
                        end=cur_t1 if cur_t1 is not None else s_t1,
                        text=cur_text.strip(),
                    )
                )
        if segments:
            return words, segments

    # Fallback: use raw_segments (with seg.tokens if exposed, else segment bounds)
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


def _decode_audio_16k_mono(audio_path: Path):
    import av
    import numpy as np

    container = av.open(str(audio_path))
    resampler = av.AudioResampler(format="flt", layout="mono", rate=16000)
    chunks = []
    for frame in container.decode(audio=0):
        for resampled in resampler.resample(frame):
            chunks.append(resampled.to_ndarray().reshape(-1))
    for resampled in resampler.resample(None):
        chunks.append(resampled.to_ndarray().reshape(-1))
    container.close()
    pcm = np.concatenate(chunks).astype(np.float32) if chunks else np.zeros(0, dtype=np.float32)
    return pcm, float(len(pcm) / 16000.0)


def _basic_segment_features(segments: list[SegmentDto], duration_sec: float):
    import numpy as np

    n = len(segments)
    feats = np.zeros((n, 28), dtype=np.float32)
    dur = max(duration_sec, 1.0)
    for i, s in enumerate(segments):
        gap_before = s.start - segments[i - 1].end if i > 0 else s.start
        gap_after = segments[i + 1].start - s.end if i + 1 < n else max(0.0, dur - s.end)
        feats[i, 10] = float(max(0.0, gap_before))
        feats[i, 11] = float(max(0.0, gap_after))
        feats[i, 14] = float(s.start / dur)  # rel_pos
        feats[i, 15] = float(max(0.0, s.end - s.start))  # seg_dur
        feats[i, 16] = float(s.nWords)  # n_words
    return feats


def _extract_sponsor_name(text: str) -> Optional[str]:
    import re

    patterns = [
        r"(?:support for (?:the|this) show comes from|sponsored by|brought to you by|thanks to|this episode is brought to you by)\s+([A-Z][A-Za-z0-9&'.-]+(?:\s+[A-Z][A-Za-z0-9&'.-]+){0,2})",
        r"(?:learn more at|visit|head to|go to|check out)\s+([A-Za-z0-9-]+)\.(?:com|io|co|org|net|ai)\b",
    ]
    for pat in patterns:
        m = re.search(pat, text, flags=re.IGNORECASE)
        if m:
            candidate = m.group(1).strip(" .,:;!?\"'")
            if 2 <= len(candidate) <= 28:
                # Title-case domain-like lowercase matches
                if candidate.islower():
                    candidate = candidate.capitalize()
                return candidate
    return None


def _decode_ad_segments(probs, segments: list[SegmentDto]) -> list[AdSegmentDto]:
    # Matches core/automute-engine/Decoder.kt thresholds, then merges nearby ad chunks
    raw: list[tuple[int, int, float, bool]] = []
    n = len(segments)
    i = 0
    while i < n:
        p_ad = float(probs[i][1])
        if p_ad >= 0.25:
            j = i
            total_p = p_ad
            while j + 1 < n and float(probs[j + 1][1]) >= 0.25:
                j += 1
                total_p += float(probs[j][1])
            count = j - i + 1
            if count >= 2:
                avg_p = total_p / count
                raw.append((i, j, avg_p, avg_p >= 0.80))
            i = j + 1
        else:
            i += 1

    # Merge adjacent/nearby spans within 8s of the same confidence tier
    merged: list[tuple[int, int, float, bool]] = []
    for i, j, avg_p, high in raw:
        if merged:
            pi, pj, p_avg, p_high = merged[-1]
            gap_s = segments[i].start - segments[pj].end
            if gap_s <= 8.0 and p_high == high:
                w1 = pj - pi + 1
                w2 = j - i + 1
                merged[-1] = (pi, j, (p_avg * w1 + avg_p * w2) / (w1 + w2), high)
                continue
        merged.append((i, j, avg_p, high))

    out: list[AdSegmentDto] = []
    for i, j, avg_p, high in merged:
        combined_text = " ".join(s.text for s in segments[i : j + 1])
        sponsor = _extract_sponsor_name(combined_text)
        base_label = "Sponsor" if high else "Suggested Ad"
        label = f"{base_label}: {sponsor}" if sponsor else ("Sponsor / Ad" if high else "Suggested Ad")
        out.append(
            AdSegmentDto(
                startMs=int(round(segments[i].start * 1000)),
                endMs=int(round(segments[j].end * 1000)),
                confidence=round(avg_p, 3),
                isHighConfidence=high,
                label=label,
            )
        )
    return out


async def download_audio_streaming_sha256(
    audio_url: str,
    dest_path: Path,
    on_bytes: Optional[Callable[[int, int], None]] = None,
) -> tuple[str, str]:
    """Downloads audio_url to dest_path while computing SHA-256 on the fly."""
    hasher = hashlib.sha256()
    content_type = "audio/mpeg"
    async with httpx.AsyncClient(follow_redirects=True, timeout=120.0) as client:
        async with client.stream("GET", audio_url) as resp:
            resp.raise_for_status()
            content_type = resp.headers.get("content-type", "audio/mpeg").split(";")[0].strip()
            total = int(resp.headers.get("content-length") or 0)
            received = 0
            with dest_path.open("wb") as f:
                async for chunk in resp.aiter_bytes(chunk_size=65536):
                    f.write(chunk)
                    hasher.update(chunk)
                    received += len(chunk)
                    if on_bytes:
                        on_bytes(received, total)
    return hasher.hexdigest(), content_type
