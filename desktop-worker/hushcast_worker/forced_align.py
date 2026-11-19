from __future__ import annotations

import logging
import os
import re
import threading
import unicodedata
from typing import NamedTuple, Optional

import numpy as np

from .models import SegmentDto, WordDto

logger = logging.getLogger("hushcast_worker.forced_align")

_ALPHA_RE = re.compile(r"[^a-z]+")

# MMS_FA span scores are mean token probabilities in 0..1 (production: p10 0.45, p50 0.95).
# Below this a word was likely forced onto audio that doesn't contain it, so it keeps its old time.
MIN_WORD_SCORE = 0.30
MAX_WORD_SPAN_S = 1.5


def normalize_align_word(text: str) -> str:
    """Normalize a transcript word to lowercase ASCII a-z for MMS_FA (folding Latin diacritics)."""
    cleaned = text.lower().replace("'", "").replace("\u2019", "")
    # Fold Latin diacritics (e.g. café -> cafe) before stripping non-a-z (A8)
    nfkd = unicodedata.normalize("NFKD", cleaned)
    ascii_folded = "".join(ch for ch in nfkd if not unicodedata.combining(ch))
    return _ALPHA_RE.sub("", ascii_folded)


def count_alignable_words(words: list[WordDto]) -> int:
    """Words MMS_FA can align at all (some a-z letters after normalizing); numbers and symbols can't."""
    return sum(1 for w in words if normalize_align_word(w.text))


class ForcedAligner:
    """
    Optional CTC phoneme-level forced aligner using torchaudio's MMS_FA bundle.
    Refines Whisper word [start, end] timestamps to ~10-20ms acoustic boundaries.
    Gracefully no-ops when torch/torchaudio are not installed.
    """

    def __init__(self, models_dir: Optional[str] = None) -> None:
        self._lock = threading.Lock()
        self._loaded = False
        self._available: Optional[bool] = None
        self._model = None
        self._tokenizer = None
        self._aligner = None
        self._device = "cpu"
        self._sample_rate = 16000
        self._batchable = False
        if models_dir:
            os.environ.setdefault("TORCH_HOME", str(models_dir))

    def is_available(self) -> bool:
        if self._available is not None:
            return self._available
        try:
            import torch  # noqa: F401
            import torchaudio  # noqa: F401
            from torchaudio.pipelines import MMS_FA  # noqa: F401

            self._available = True
        except Exception:
            self._available = False
        return self._available

    def _ensure_loaded(self) -> bool:
        if self._loaded:
            return self._model is not None
        with self._lock:
            if self._loaded:
                return self._model is not None
            self._loaded = True
            if not self.is_available():
                return False
            try:
                import torch
                from torchaudio.pipelines import MMS_FA as bundle

                if torch.cuda.is_available():
                    self._device = "cuda"
                elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
                    self._device = "mps"
                else:
                    self._device = "cpu"
                self._sample_rate = int(getattr(bundle, "sample_rate", 16000))
                model = bundle.get_model(with_star=False).to(self._device)
                model.eval()
                self._model = model
                self._tokenizer = bundle.get_tokenizer()
                self._aligner = bundle.get_aligner()
                self._batchable = _is_batchable(model)
                logger.info(
                    "Loaded MMS_FA forced aligner on %s (batch size %d)", self._device, self._batch_size()
                )
                return True
            except Exception as exc:
                logger.warning("Failed to load MMS_FA forced aligner: %s", exc)
                self._model = None
                return False

    def _batch_size(self) -> int:
        """How many alignment windows to run through MMS_FA at once (W5)."""
        if not self._batchable:
            return 1
        env = os.environ.get("HUSHCAST_FA_BATCH", "").strip()
        if env.isdigit() and int(env) >= 1:
            return int(env)
        # CUDA only. Measured on an M-series Mac (8 x <=20 s windows, MMS_FA architecture):
        # MPS 4.7 s one at a time vs 6.4 s batched, and the CPU is slower batched too.
        return 8 if self._device == "cuda" else 1

    def _emissions(self, chunks: list[np.ndarray]) -> list:
        """
        CTC emissions (log-probabilities, [frames, tokens] float32 on the CPU) for each window.
        Several windows are padded to one length and run as a single batch when that is safe
        (`_is_batchable`): each window is normalized on its own, exactly as the bundle's wrapper
        does, and `lengths` masks the padding, so every window gets the same emission as alone.
        """
        import contextlib

        import torch

        def _amp():
            if self._device == "cuda":
                return torch.amp.autocast("cuda", dtype=torch.float16)
            return contextlib.nullcontext()

        if len(chunks) == 1 or not self._batchable:
            out = []
            for chunk in chunks:
                wav = torch.from_numpy(chunk).unsqueeze(0).to(device=self._device, dtype=torch.float32)
                with torch.inference_mode(), _amp():
                    emission, _ = self._model(wav)
                out.append(emission[0].float().cpu())
            return out

        wrapper = self._model
        with torch.inference_mode():
            rows = []
            for chunk in chunks:
                w = torch.from_numpy(chunk).unsqueeze(0).to(device=self._device, dtype=torch.float32)
                if wrapper.normalize_waveform:
                    w = torch.nn.functional.layer_norm(w, w.shape)
                rows.append(w[0])
            lengths = torch.tensor([len(c) for c in chunks], device=self._device)
            batch = torch.nn.utils.rnn.pad_sequence(rows, batch_first=True)
            with _amp():
                emission, out_lens = wrapper.model(batch, lengths)
                if wrapper.apply_log_softmax:
                    emission = torch.nn.functional.log_softmax(emission, dim=-1)
            emission = emission.float().cpu()
            out_lens = out_lens.cpu()
        return [emission[b, : int(out_lens[b])] for b in range(len(chunks))]

    def align_words(
        self,
        pcm_16k: np.ndarray,
        segments: list[SegmentDto],
        words: list[WordDto],
        *,
        max_window_sec: float = 20.0,
        pad_sec: float = 0.15,
        cancel_event: Optional[threading.Event] = None,
        min_score: float = MIN_WORD_SCORE,
    ) -> int:
        """
        Refines `words` timestamps in-place using CTC forced alignment grouped into
        windows of up to `max_window_sec`. A word is moved only when its mean MMS_FA token
        score is at least `min_score`. Returns the number of words refined.
        """
        if len(pcm_16k) == 0 or not words or not segments:
            return 0

        # A8: Skip MMS_FA if majority of words have no Latin characters (e.g. CJK, Arabic, Cyrillic)
        sample_words = words[: min(40, len(words))]
        latin_count = sum(1 for w in sample_words if normalize_align_word(w.text))
        if sample_words and latin_count < len(sample_words) * 0.35:
            logger.info("Skipping MMS_FA forced alignment for non-Latin transcript")
            return 0

        if not self._ensure_loaded():
            return 0

        total_samples = len(pcm_16k)
        sr = self._sample_rate
        jobs: list[_Window] = []
        for idx_list in _group_windows(words, max_window_sec):
            norm_pairs: list[tuple[int, str]] = []
            for wi in idx_list:
                nw = normalize_align_word(words[wi].text)
                if nw:
                    norm_pairs.append((wi, nw))
            if not norm_pairs:
                continue

            raw_s = words[idx_list[0]].start
            raw_e = words[idx_list[-1]].end
            if raw_e <= raw_s:
                raw_e = raw_s + 0.5
            chunk_s = max(0.0, raw_s - pad_sec)
            chunk_e = min(total_samples / float(sr), raw_e + pad_sec)
            s_idx = int(chunk_s * sr)
            e_idx = int(chunk_e * sr)
            if e_idx - s_idx < int(0.1 * sr):
                continue
            jobs.append(_Window(chunk_s, s_idx, e_idx, norm_pairs))

        # Similar lengths share a batch so little of it is padding. Windows are independent,
        # so the order they run in doesn't change any result.
        jobs.sort(key=lambda j: j.e_idx - j.s_idx, reverse=True)

        refined = 0
        scores_log: list[float] = []
        pos = 0
        while pos < len(jobs):
            if cancel_event is not None and cancel_event.is_set():
                logger.info("Forced alignment cancelled after %d words", refined)
                break
            batch = jobs[pos : pos + self._batch_size()]
            pos += len(batch)
            chunks = [pcm_16k[j.s_idx : j.e_idx] for j in batch]
            try:
                emissions = self._emissions(chunks)
            except Exception:
                if len(batch) == 1 or not self._batchable:
                    logger.debug("MMS_FA window alignment skipped", exc_info=True)
                    continue
                logger.warning("Batched MMS_FA failed; aligning one window at a time", exc_info=True)
                self._batchable = False
                _free_device_cache(self._device)
                pos -= len(batch)
                continue

            for job, em_cpu in zip(batch, emissions):
                try:
                    if em_cpu.size(0) == 0:
                        continue
                    tokens = self._tokenizer([nw for _, nw in job.norm_pairs])
                    spans_list = self._aligner(em_cpu, tokens)
                    if len(spans_list) != len(job.norm_pairs):
                        continue
                    ratio = (job.e_idx - job.s_idx) / float(em_cpu.size(0) * sr)
                    refined += _apply_spans(
                        words, job.norm_pairs, spans_list, job.chunk_s, ratio, min_score, scores_log
                    )
                except Exception:
                    logger.debug("MMS_FA window alignment skipped", exc_info=True)
                    continue

        if scores_log:
            arr_s = np.asarray(scores_log, dtype=np.float32)
            logger.info(
                "MMS_FA aligned %d/%d words (score >= %.2f; p10=%.3f p50=%.3f p90=%.3f)",
                refined,
                len(words),
                min_score,
                float(np.percentile(arr_s, 10)),
                float(np.percentile(arr_s, 50)),
                float(np.percentile(arr_s, 90)),
            )

        return refined


class _Window(NamedTuple):
    chunk_s: float
    s_idx: int
    e_idx: int
    norm_pairs: list[tuple[int, str]]


def _group_windows(words: list[WordDto], max_window_sec: float) -> list[list[int]]:
    """Groups word indices into contiguous time windows of at most `max_window_sec`."""
    windows: list[list[int]] = []
    cur_indices: list[int] = []
    win_start = words[0].start
    for idx, w in enumerate(words):
        if cur_indices and (w.end - win_start > max_window_sec or w.start - words[cur_indices[-1]].end > 2.5):
            windows.append(cur_indices)
            cur_indices = [idx]
            win_start = w.start
        else:
            cur_indices.append(idx)
    if cur_indices:
        windows.append(cur_indices)
    return windows


def _apply_spans(
    words: list[WordDto],
    norm_pairs: list[tuple[int, str]],
    spans_list,
    chunk_s: float,
    ratio: float,
    min_score: float,
    scores_log: list[float],
) -> int:
    """Moves each word to its aligned span when the span is sane and confident. Returns the count."""
    refined = 0
    for (wi, _), spans in zip(norm_pairs, spans_list):
        if not spans:
            continue
        avg_score = sum(float(sp.score) for sp in spans) / len(spans)
        scores_log.append(avg_score)
        ns = round(chunk_s + spans[0].start * ratio, 3)
        ne = round(chunk_s + spans[-1].end * ratio, 3)
        # Sane span (<= 1.5 s per word, V23) and a confident alignment. The scores are mean token
        # probabilities in 0..1; a word forced onto audio that doesn't contain it scores low.
        if ne > ns and (ne - ns) <= MAX_WORD_SPAN_S and avg_score >= min_score:
            words[wi].start = ns
            words[wi].end = ne
            refined += 1
    return refined


def _free_device_cache(device: str) -> None:
    if device != "cuda":
        return
    try:
        import torch

        torch.cuda.empty_cache()
    except Exception:
        pass


def _is_batchable(model) -> bool:
    """
    True when windows can be padded into one batch without changing any window's emission:
    the bundle wrapper exposes its inner model and flags, and no feature-extractor layer uses
    GroupNorm (which normalizes over time, so padding would change it). MMS_FA uses per-frame
    LayerNorm ("layer_norm" extractor mode), and the encoder masks padded frames via `lengths`.
    """
    try:
        import torch

        if getattr(model, "append_star", True):
            return False
        if not hasattr(model, "normalize_waveform") or not hasattr(model, "apply_log_softmax"):
            return False
        inner = getattr(model, "model", None)
        conv_layers = inner.feature_extractor.conv_layers
        for layer in conv_layers:
            if isinstance(getattr(layer, "layer_norm", None), torch.nn.GroupNorm):
                return False
        return True
    except Exception:
        return False
