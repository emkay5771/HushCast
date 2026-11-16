from __future__ import annotations

import hashlib
import platform
import time
import os
import re
from pathlib import Path
from typing import Callable, Optional, Protocol

import httpx

from .models import (
    AdSegmentDto,
    DesktopAnalysisBundle,
    DesktopChapterDto,
    JobState,
    SegmentDto,
    WordDto,
    WordIndexBlobDto,
)

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
        self.whisper_model = self._resolve_whisper_model()
        self.qwen_model = models_dir / "Qwen3-Embedding-0.6B-Q8_0.gguf"
        self.ad_tagger_onnx = ad_tagger_onnx or (models_dir / "ad_tagger.onnx")

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
        for p in sorted(self.models_dir.glob("*.gguf")):
            lower = p.name.lower()
            if "embedding" in lower:
                continue
            if any(k in lower for k in ("instruct", "chat", "qwen2.5", "llama-3", "gemma", "mistral")):
                return p
        return None

    def chat_model_ready(self) -> bool:
        return self.chat_model_path() is not None

    def models_ready(self) -> bool:
        return (
            self.whisper_model.exists()
            and self.qwen_model.exists()
            and self.ad_tagger_onnx.exists()
        )

    def _load_embedding_llm(self):
        from llama_cpp import Llama

        try:
            return Llama(
                model_path=str(self.qwen_model),
                embedding=True,
                pooling_type=3,  # LLAMA_POOLING_TYPE_LAST (matches llama_jni.cpp)
                n_gpu_layers=-1,
                verbose=False,
            )
        except TypeError:
            return Llama(
                model_path=str(self.qwen_model),
                embedding=True,
                n_gpu_layers=-1,
                verbose=False,
            )

    def embed_query(self, text: str) -> list[float]:
        import numpy as np

        if not self.qwen_model.exists():
            raise FileNotFoundError(f"Missing embedding model: {self.qwen_model}")
        llm = self._load_embedding_llm()
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
        on_progress(JobState.TRANSCRIBING, 25, f"Transcribing ({self.whisper_model.name})")

        # 1. Decode to 16 kHz mono for Whisper & acoustic speaker diarization
        pcm_16k, duration_sec = _decode_audio_16k_mono(audio_path)
        whisper = WhisperModel(str(self.whisper_model), print_progress=False)
        raw_segments = whisper.transcribe(pcm_16k, token_timestamps=True)

        words, segments = _extract_whisper_words_and_segments(whisper, raw_segments)

        # 2. Acoustic speaker diarization
        if segments and len(pcm_16k) > 0:
            _assign_speakers(pcm_16k, segments)

        on_progress(JobState.EMBEDDING, 65, "Embedding sentences (Qwen3 Metal)")
        llm = self._load_embedding_llm()
        texts = [s.text for s in segments]
        ad_segments: list[AdSegmentDto] = []
        emb_2d = None
        summary: Optional[str] = None
        chapters: list[DesktopChapterDto] = []

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
            emb_2d = np.stack(emb_list, axis=0)  # (N, 1024)
            emb = emb_2d[None, :, :]  # (1, N, 1024)

            on_progress(JobState.TAGGING, 85, "Tagging ads & generating chapters")
            feats = _basic_segment_features(segments, duration_sec)[None, :, :]  # (1, N, 28)

            sess = ort.InferenceSession(str(self.ad_tagger_onnx))
            probs = sess.run(None, {"emb": emb, "feats": feats})[0][0]  # (N, 5)
            ad_segments = _decode_ad_segments(probs, segments)

            summary, chapters = _generate_chapters_and_summary(
                segments=segments,
                embeddings=emb_2d,
                ad_segments=ad_segments,
                chat_model_path=self.chat_model_path(),
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


def _estimate_pitch_hz(chunk, sr: int = 16000) -> float:
    import numpy as np

    if len(chunk) < sr // 10:
        return 0.0
    # Autocorrelation on centered chunk (70 Hz .. 350 Hz)
    x = chunk - float(np.mean(chunk))
    min_lag = sr // 350
    max_lag = sr // 70
    if len(x) <= max_lag + 16:
        return 0.0
    corr = np.correlate(x, x, mode="full")[len(x) - 1 :]
    if corr[0] <= 1e-8:
        return 0.0
    sub = corr[min_lag : max_lag + 1]
    peak_idx = int(np.argmax(sub))
    if sub[peak_idx] < 0.25 * corr[0]:
        return 0.0
    return float(sr / (min_lag + peak_idx))


def _assign_speakers(pcm_16k, segments: list[SegmentDto]) -> None:
    """
    Acoustic speaker diarization using MFCCs + fundamental frequency (F0) +
    cosine K-means with silhouette-based speaker count selection.
    """
    import numpy as np

    sr = 16000
    n_segs = len(segments)
    if n_segs == 0 or len(pcm_16k) < sr // 10:
        return

    # Precompute 24-band Mel filterbank & DCT-II matrix for 12 MFCCs
    n_fft = 512
    n_mels = 24
    n_mfcc = 12
    freqs = np.fft.rfftfreq(n_fft, d=1.0 / sr)
    mel_low = 2595.0 * np.log10(1.0 + 80.0 / 700.0)
    mel_high = 2595.0 * np.log10(1.0 + 7500.0 / 700.0)
    mel_pts = np.linspace(mel_low, mel_high, n_mels + 2)
    hz_pts = 700.0 * (10.0 ** (mel_pts / 2595.0) - 1.0)
    bins = np.floor((n_fft + 1) * hz_pts / sr).astype(int)
    fb = np.zeros((n_mels, len(freqs)), dtype=np.float32)
    for m in range(1, n_mels + 1):
        f_l, f_c, f_r = bins[m - 1], bins[m], bins[m + 1]
        if f_c > f_l:
            fb[m - 1, f_l:f_c] = (np.arange(f_l, f_c) - f_l) / float(f_c - f_l)
        if f_r > f_c:
            fb[m - 1, f_c:f_r] = (f_r - np.arange(f_c, f_r)) / float(f_r - f_c)
    dct_idx = np.arange(n_mfcc)[:, None]
    mel_idx = np.arange(n_mels)[None, :]
    dct_mat = np.cos(np.pi * dct_idx * (mel_idx + 0.5) / n_mels).astype(np.float32)
    hann = np.hanning(n_fft).astype(np.float32)

    feats: list[Optional[np.ndarray]] = []
    durs: list[float] = []

    for s in segments:
        dur = max(0.0, s.end - s.start)
        durs.append(dur)
        i0 = max(0, int(s.start * sr))
        i1 = min(len(pcm_16k), int(s.end * sr))
        chunk = pcm_16k[i0:i1]
        if len(chunk) < n_fft:
            feats.append(None)
            continue
        rms = float(np.sqrt(np.mean(chunk * chunk)))
        if rms < 1e-4:
            feats.append(None)
            continue

        if len(chunk) > 4 * sr:
            mid = len(chunk) // 2
            chunk = chunk[mid - 2 * sr : mid + 2 * sr]

        # Frame-averaged MFCCs (hop = 256)
        step = 256
        frames = [
            chunk[pos : pos + n_fft] * hann
            for pos in range(0, len(chunk) - n_fft + 1, step)
        ]
        if not frames:
            feats.append(None)
            continue
        frame_mat = np.stack(frames, axis=0)
        pow_spec = np.abs(np.fft.rfft(frame_mat, n=n_fft)) ** 2
        mel_e = np.log(np.maximum(pow_spec @ fb.T, 1e-9))
        mfccs = mel_e @ dct_mat.T
        mfcc_mean = np.mean(mfccs, axis=0)

        f0 = _estimate_pitch_hz(chunk[: min(len(chunk), sr)], sr)
        f0_log = np.log2(f0 / 150.0) if f0 > 0 else 0.0
        vec = np.concatenate([mfcc_mean[1:n_mfcc], [f0_log * 2.5]]).astype(np.float32)
        feats.append(vec)

    valid_idx = [i for i, v in enumerate(feats) if v is not None]
    if not valid_idx:
        for s in segments:
            s.speaker = 0
        return

    mat = np.stack([feats[i] for i in valid_idx], axis=0)
    mean = np.mean(mat, axis=0)
    std = np.std(mat, axis=0) + 1e-6
    mat_norm = (mat - mean) / std
    mat_unit = mat_norm / (np.linalg.norm(mat_norm, axis=1, keepdims=True) + 1e-12)

    # Fit K-means on substantial voiced segments (>= 0.8s)
    train_mask = np.array([durs[i] >= 0.8 for i in valid_idx], dtype=bool)
    train_vecs = mat_unit[train_mask] if np.sum(train_mask) >= 6 else mat_unit

    def _run_kmeans(vecs: np.ndarray, k: int, iters: int = 18):
        # Farthest-point deterministic initialization
        centroids = [vecs[0].copy()]
        for _ in range(1, k):
            sims = np.max(vecs @ np.stack(centroids, axis=0).T, axis=1)
            centroids.append(vecs[int(np.argmin(sims))].copy())
        c_mat = np.stack(centroids, axis=0)
        labels = np.zeros(len(vecs), dtype=int)
        for _ in range(iters):
            sims = vecs @ c_mat.T
            new_labels = np.argmax(sims, axis=1)
            if np.array_equal(new_labels, labels):
                break
            labels = new_labels
            for c_i in range(k):
                members = vecs[labels == c_i]
                if len(members) > 0:
                    c = np.mean(members, axis=0)
                    c /= float(np.linalg.norm(c)) + 1e-12
                    c_mat[c_i] = c
        return c_mat, labels

    best_centroids = None
    best_sil = 0.14  # minimum silhouette to justify splitting into >= 2 speakers
    max_k = min(4, max(1, len(train_vecs) // 4))
    for k in range(2, max_k + 1):
        c_mat, tr_labels = _run_kmeans(train_vecs, k)
        # Skip degenerate clusters (< 8% of segments)
        counts = np.bincount(tr_labels, minlength=k)
        if np.min(counts) < max(2, int(0.06 * len(train_vecs))):
            continue
        # Fast centroid silhouette approximation
        sims = train_vecs @ c_mat.T
        a_sim = sims[np.arange(len(train_vecs)), tr_labels]
        masked_sims = sims.copy()
        masked_sims[np.arange(len(train_vecs)), tr_labels] = -2.0
        b_sim = np.max(masked_sims, axis=1)
        sil = float(np.mean(a_sim - b_sim))
        if sil > best_sil:
            best_sil = sil
            best_centroids = c_mat

    if best_centroids is None:
        for s in segments:
            s.speaker = 0
        return

    all_labels = np.argmax(mat_unit @ best_centroids.T, axis=1)
    for row_i, seg_i in enumerate(valid_idx):
        segments[seg_i].speaker = int(all_labels[row_i])

    # Fill silent/short segments from neighbor
    last_spk = 0
    for s in segments:
        if s.speaker is None:
            s.speaker = last_spk
        else:
            last_spk = s.speaker

    # Smooth short 1-segment blips (< 1.8s) where prev == next != cur
    for i in range(1, n_segs - 1):
        dur = segments[i].end - segments[i].start
        if (
            dur < 1.8
            and segments[i - 1].speaker == segments[i + 1].speaker
            and segments[i].speaker != segments[i - 1].speaker
        ):
            segments[i].speaker = segments[i - 1].speaker

    # Renumber speakers in order of first appearance (Speaker 1 = 0, Speaker 2 = 1, ...)
    remap: dict[int, int] = {}
    for s in segments:
        if s.speaker is not None:
            if s.speaker not in remap:
                remap[s.speaker] = len(remap)
            s.speaker = remap[s.speaker]


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
        if i > 0 and s.speaker is not None and segments[i - 1].speaker is not None:
            feats[i, 17] = 1.0 if s.speaker != segments[i - 1].speaker else 0.0  # spk_change
    return feats


def _is_inside_ad(start_s: float, end_s: float, ad_segments: list[AdSegmentDto]) -> bool:
    s_ms = int(start_s * 1000)
    e_ms = int(end_s * 1000)
    for ad in ad_segments:
        if s_ms < ad.endMs and e_ms > ad.startMs:
            return True
    return False


_STOPWORDS = {
    "a", "about", "above", "actually", "after", "again", "all", "also", "always",
    "am", "an", "and", "any", "are", "aren't", "as", "at", "be", "because", "been",
    "before", "being", "but", "by", "can", "can't", "could", "did", "didn't", "do",
    "does", "doesn't", "doing", "don't", "down", "during", "each", "even", "ever",
    "every", "feel", "for", "from", "get", "getting", "go", "going", "good", "got",
    "great", "had", "has", "have", "having", "he", "her", "here", "hers", "him",
    "his", "how", "i", "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is",
    "isn't", "it", "it's", "its", "just", "kind", "know", "like", "literally",
    "little", "lot", "made", "make", "many", "maybe", "me", "mean", "more", "most",
    "much", "my", "need", "no", "not", "now", "obviously", "of", "off", "oh", "ok",
    "okay", "on", "one", "only", "or", "other", "our", "out", "over", "own",
    "people", "personally", "pretty", "probably", "put", "really", "right", "said",
    "same", "say", "saying", "see", "she", "should", "so", "some", "something",
    "sort", "still", "stuff", "such", "sure", "take", "talking", "tell", "than",
    "that", "that's", "the", "their", "them", "then", "there", "there's", "these",
    "they", "they're", "thing", "things", "think", "this", "those", "though",
    "through", "time", "to", "too", "totally", "try", "trying", "um", "uh", "up",
    "us", "use", "used", "using", "very", "want", "was", "wasn't", "way", "we",
    "we're", "well", "were", "what", "what's", "when", "where", "which", "while",
    "who", "why", "will", "with", "would", "yeah", "yes", "you", "you're", "your",
    "yours",
}


def _extract_chapter_tokens(text: str) -> list[tuple[str, bool]]:
    """Returns list of (lowercase_token, is_capitalized_proper_noun)."""
    out: list[tuple[str, bool]] = []
    for raw in re.findall(r"[A-Za-z0-9][A-Za-z0-9'-]*", text):
        low = raw.lower().strip("'-")
        if len(low) < 3 or low in _STOPWORDS or low.isdigit():
            continue
        is_cap = raw[0].isupper() and not raw.isupper()
        out.append((low, is_cap))
    return out


def _name_chapter_from_tfidf(
    chunk_texts: list[str],
    all_chunk_token_sets: list[set[str]],
    chapter_idx: int,
) -> str:
    import math
    from collections import Counter

    tf: Counter[str] = Counter()
    proper_forms: dict[str, str] = {}
    for txt in chunk_texts:
        toks = _extract_chapter_tokens(txt)
        for idx, (low, is_cap) in enumerate(toks):
            tf[low] += 1
            if is_cap and low not in proper_forms:
                proper_forms[low] = low.capitalize()
            if idx + 1 < len(toks):
                bi = f"{low} {toks[idx + 1][0]}"
                tf[bi] += 1

    if not tf:
        return f"Part {chapter_idx + 1}"

    n_chapters = max(1, len(all_chunk_token_sets))
    scored: list[tuple[float, str]] = []
    for term, count in tf.items():
        if count < 2 and " " not in term:
            continue
        df = sum(1 for s in all_chunk_token_sets if term in s)
        idf = math.log((n_chapters + 1.0) / (df + 0.5)) + 0.5
        boost = 1.35 if " " in term else 1.0
        if term in proper_forms:
            boost *= 1.25
        scored.append((count * idf * boost, term))

    scored.sort(key=lambda x: x[0], reverse=True)
    picked: list[str] = []
    used_words: set[str] = set()
    for _, term in scored:
        parts = term.split()
        if any(p in used_words for p in parts):
            continue
        used_words.update(parts)
        pretty = " ".join(proper_forms.get(p, p.capitalize()) for p in parts)
        picked.append(pretty)
        if len(picked) >= 3:
            break

    if not picked:
        return f"Part {chapter_idx + 1}"
    return " • ".join(picked)


def _generate_chapters_and_summary(
    *,
    segments: list[SegmentDto],
    embeddings,
    ad_segments: list[AdSegmentDto],
    chat_model_path: Optional[Path] = None,
) -> tuple[Optional[str], list[DesktopChapterDto]]:
    import json
    import numpy as np

    non_ad_indices = [
        i for i, s in enumerate(segments)
        if not _is_inside_ad(s.start, s.end, ad_segments) and len(s.text.split()) >= 3
    ]
    if len(non_ad_indices) < 4:
        return None, []

    # Optional local chat GGUF upgrade when user drops an instruct GGUF in models_dir
    if chat_model_path is not None and chat_model_path.exists():
        try:
            from llama_cpp import Llama

            step = max(1, len(non_ad_indices) // 60)
            sampled = [segments[i] for i in non_ad_indices[::step]][:60]
            transcript_sample = "\n".join(
                f"[{int(s.start // 60):02d}:{int(s.start % 60):02d}] {s.text.strip()}"
                for s in sampled
            )[:6000]
            prompt = (
                "Summarize this podcast episode in 2-3 concise sentences and generate 4-8 "
                "chronological chapter titles with timestamps in seconds.\n"
                "Return ONLY valid JSON: "
                '{"summary": "...", "chapters": [{"startSec": 0, "title": "..."}]}\n\n'
                f"Transcript:\n{transcript_sample}\n\nJSON:"
            )
            chat_llm = Llama(
                model_path=str(chat_model_path),
                n_ctx=4096,
                n_gpu_layers=-1,
                verbose=False,
            )
            out = chat_llm.create_completion(prompt, max_tokens=512, temperature=0.2)
            raw_text = out["choices"][0]["text"].strip()
            m = re.search(r"\{.*\}", raw_text, flags=re.DOTALL)
            if m:
                parsed = json.loads(m.group(0))
                llm_summary = str(parsed.get("summary") or "").strip() or None
                llm_chapters = [
                    DesktopChapterDto(
                        startMs=max(0, int(float(c.get("startSec", 0)) * 1000)),
                        title=str(c.get("title", "Chapter")).strip()[:80],
                    )
                    for c in (parsed.get("chapters") or [])
                    if str(c.get("title", "")).strip()
                ]
                if llm_summary and llm_chapters:
                    return llm_summary, llm_chapters
        except Exception:
            pass

    # Default zero-extra-model path: Topic-shift chapters + TF-IDF naming + thesis summary
    sub_emb = embeddings[non_ad_indices]
    m = len(non_ad_indices)
    win = min(6, max(2, m // 12))
    boundaries: list[int] = [non_ad_indices[0]]
    if m > win * 2 + 2:
        sims: list[tuple[int, float]] = []
        for k in range(win, m - win):
            left = np.mean(sub_emb[k - win : k], axis=0)
            right = np.mean(sub_emb[k : k + win], axis=0)
            ln = float(np.linalg.norm(left))
            rn = float(np.linalg.norm(right))
            sim = float(np.dot(left / (ln + 1e-12), right / (rn + 1e-12)))
            sims.append((k, sim))

        sims.sort(key=lambda x: x[1])
        min_gap_sec = 180.0
        max_chapters = 7
        selected_times: list[float] = [segments[non_ad_indices[0]].start]
        for k, _ in sims:
            seg_idx = non_ad_indices[k]
            t = segments[seg_idx].start
            if all(abs(t - st) >= min_gap_sec for st in selected_times):
                boundaries.append(seg_idx)
                selected_times.append(t)
                if len(boundaries) >= max_chapters:
                    break
        boundaries.sort()

    # Collect per-chapter texts & token sets for TF-IDF chapter naming
    chapter_texts_list: list[list[str]] = []
    all_chunk_token_sets: list[set[str]] = []
    for b_pos, start_idx in enumerate(boundaries):
        end_idx = boundaries[b_pos + 1] if b_pos + 1 < len(boundaries) else len(segments)
        txts = [
            segments[idx].text
            for idx in range(start_idx, end_idx)
            if not _is_inside_ad(segments[idx].start, segments[idx].end, ad_segments)
        ]
        chapter_texts_list.append(txts)
        tset: set[str] = set()
        for txt in txts:
            toks = _extract_chapter_tokens(txt)
            for ti, (low, _) in enumerate(toks):
                tset.add(low)
                if ti + 1 < len(toks):
                    tset.add(f"{low} {toks[ti + 1][0]}")
        all_chunk_token_sets.append(tset)

    chapters: list[DesktopChapterDto] = []
    for b_pos, start_idx in enumerate(boundaries):
        title = _name_chapter_from_tfidf(chapter_texts_list[b_pos], all_chunk_token_sets, b_pos)
        chapters.append(
            DesktopChapterDto(
                startMs=max(0, int(round(segments[start_idx].start * 1000))),
                title=title,
            )
        )

    # Coherent extractive TL;DR: reconstruct complete sentences across contiguous non-ad segments
    from collections import Counter

    ep_keywords = Counter()
    for idx in non_ad_indices:
        for low, _ in _extract_chapter_tokens(segments[idx].text):
            ep_keywords[low] += 1
    top_kw = {w for w, _ in ep_keywords.most_common(15)}

    centroid = np.mean(sub_emb, axis=0)
    centroid /= float(np.linalg.norm(centroid)) + 1e-12

    filler_starts = re.compile(
        r"^(?:yeah|yes|no|well|okay|ok|right|totally|exactly|i mean|you know|so,|but,|and,|uh\b|um\b|like,)\b",
        re.IGNORECASE,
    )
    promo_cues = re.compile(
        r"\b(?:promo code|dot com|slash|subscribe|patreon|sponsor|sponsored by)\b",
        re.IGNORECASE,
    )
    intro_cues = re.compile(
        r"\b(?:talking about|today on the show|welcome to|figure out|going to talk|my question)\b",
        re.IGNORECASE,
    )

    # Group non-ad segments into contiguous speech runs
    runs: list[list[int]] = []
    for k, idx in enumerate(non_ad_indices):
        if not runs:
            runs.append([k])
        else:
            prev_idx = non_ad_indices[runs[-1][-1]]
            gap = segments[idx].start - segments[prev_idx].end
            if idx == prev_idx + 1 and gap < 2.5:
                runs[-1].append(k)
            else:
                runs.append([k])

    sentence_candidates: list[tuple[float, float, str]] = []
    for run in runs:
        spans: list[tuple[int, int, int]] = []
        parts: list[str] = []
        cursor = 0
        for k in run:
            t = re.sub(r"\s+", " ", segments[non_ad_indices[k]].text).strip()
            if not t:
                continue
            if parts:
                parts.append(" ")
                cursor += 1
            start_c = cursor
            parts.append(t)
            cursor += len(t)
            spans.append((start_c, cursor, k))
        run_text = "".join(parts)
        if not run_text:
            continue

        # Split into sentences at terminal punctuation followed by whitespace + capital/quote or end
        sent_start = 0
        for m_split in re.finditer(r'(?<=[.!?])\s+(?=[A-Z"\'(])', run_text):
            sent_end = m_split.start()
            _score_sentence = run_text[sent_start:sent_end].strip()
            _s_start, _s_end = sent_start, sent_end
            sent_start = m_split.end()
            if _score_sentence:
                k_list = [k for cs, ce, k in spans if ce > _s_start and cs < _s_end]
                if not k_list:
                    continue
                wc = len(_score_sentence.split())
                if wc < 10 or wc > 45:
                    continue
                if not (_score_sentence[0].isupper() or _score_sentence[0] in '"\''):
                    continue
                if not _score_sentence.endswith((".", "!")) or _score_sentence.endswith("?"):
                    continue
                if filler_starts.search(_score_sentence) or promo_cues.search(_score_sentence):
                    continue
                sent_vec = np.mean(sub_emb[k_list], axis=0)
                sent_vec /= float(np.linalg.norm(sent_vec)) + 1e-12
                emb_sc = float(np.dot(sent_vec, centroid))
                kw_hits = sum(1 for low, _ in _extract_chapter_tokens(_score_sentence) if low in top_kw)
                score = emb_sc + 0.08 * min(kw_hits, 5) + (0.05 if 14 <= wc <= 32 else 0.0)
                if k_list[0] < max(10, m // 8) and intro_cues.search(_score_sentence):
                    score += 0.35
                sentence_candidates.append((score, segments[non_ad_indices[k_list[0]]].start, _score_sentence))

        # Trailing sentence in run if it ends with terminal punctuation
        tail = run_text[sent_start:].strip()
        if tail and tail.endswith((".", "!")) and not tail.endswith("?"):
            k_list = [k for cs, ce, k in spans if ce > sent_start]
            wc = len(tail.split())
            if (
                k_list
                and 10 <= wc <= 45
                and (tail[0].isupper() or tail[0] in '"\'')
                and not filler_starts.search(tail)
                and not promo_cues.search(tail)
            ):
                sent_vec = np.mean(sub_emb[k_list], axis=0)
                sent_vec /= float(np.linalg.norm(sent_vec)) + 1e-12
                emb_sc = float(np.dot(sent_vec, centroid))
                kw_hits = sum(1 for low, _ in _extract_chapter_tokens(tail) if low in top_kw)
                score = emb_sc + 0.08 * min(kw_hits, 5) + (0.05 if 14 <= wc <= 32 else 0.0)
                if k_list[0] < max(10, m // 8) and intro_cues.search(tail):
                    score += 0.35
                sentence_candidates.append((score, segments[non_ad_indices[k_list[0]]].start, tail))

    # Fallback for unpunctuated transcripts
    if not sentence_candidates:
        emb_scores = sub_emb @ centroid
        for k, idx in enumerate(non_ad_indices):
            seg = segments[idx]
            txt = re.sub(r"\s+", " ", seg.text).strip()
            wc = len(txt.split())
            if wc < 10 or wc > 42 or txt.endswith("?") or filler_starts.search(txt):
                continue
            kw_hits = sum(1 for low, _ in _extract_chapter_tokens(txt) if low in top_kw)
            score = float(emb_scores[k]) + 0.08 * min(kw_hits, 5)
            if k < max(10, m // 8) and intro_cues.search(txt):
                score += 0.35
            cleaned = txt.rstrip(",;:- ")
            if cleaned and cleaned[0].islower():
                cleaned = cleaned[0].upper() + cleaned[1:]
            if cleaned and cleaned[-1] not in ".!?":
                cleaned += "."
            sentence_candidates.append((score, seg.start, cleaned))

    sentence_candidates.sort(key=lambda x: x[0], reverse=True)
    chosen_sents: list[tuple[float, str]] = []
    for _, start_s, sent_txt in sentence_candidates:
        if all(abs(start_s - c_start) >= 60.0 for c_start, _ in chosen_sents):
            chosen_sents.append((start_s, sent_txt))
            if len(chosen_sents) >= 3:
                break
    chosen_sents.sort(key=lambda x: x[0])
    summary = " ".join(sent_txt for _, sent_txt in chosen_sents) if chosen_sents else None

    return summary, chapters


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
