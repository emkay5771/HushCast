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

        words: list[WordDto] = []
        segments: list[SegmentDto] = []
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
            # Distribute timestamps across words if token-level timings aren't exposed
            if seg_words:
                step = max(0.01, (end_s - start_s) / len(seg_words))
                for idx, w in enumerate(seg_words):
                    ws = round(start_s + idx * step, 3)
                    we = round(min(end_s, ws + step), 3)
                    words.append(WordDto(start=ws, end=we, text=w))

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


def _decode_audio_16k_mono(audio_path: Path):
    import av
    import numpy as np

    container = av.open(str(audio_path))
    resampler = av.AudioResampler(format="flt", layout="mono", rate=16000)
    chunks = []
    for frame in container.decode(audio=0):
        for resampled in resampler.resample(frame):
            arr = resampled.to_ndarray().reshape(-1)
            chunks.append(arr)
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


def _decode_ad_segments(probs, segments: list[SegmentDto]) -> list[AdSegmentDto]:
    # Matches core/automute-engine/Decoder.kt thresholds
    out: list[AdSegmentDto] = []
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
                high = avg_p >= 0.80
                out.append(
                    AdSegmentDto(
                        startMs=int(round(segments[i].start * 1000)),
                        endMs=int(round(segments[j].end * 1000)),
                        confidence=round(avg_p, 3),
                        isHighConfidence=high,
                        label="Sponsor / Ad" if high else "Suggested Ad",
                    )
                )
            i = j + 1
        else:
            i += 1
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
