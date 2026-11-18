from __future__ import annotations

import logging
import os
import re
import threading
import unicodedata
from typing import Optional

import numpy as np

from .models import SegmentDto, WordDto

logger = logging.getLogger("hushcast_worker.forced_align")

_ALPHA_RE = re.compile(r"[^a-z]+")


def normalize_align_word(text: str) -> str:
    """Normalize a transcript word to lowercase ASCII a-z for MMS_FA (folding Latin diacritics)."""
    cleaned = text.lower().replace("'", "").replace("\u2019", "")
    # Fold Latin diacritics (e.g. café -> cafe) before stripping non-a-z (A8)
    nfkd = unicodedata.normalize("NFKD", cleaned)
    ascii_folded = "".join(ch for ch in nfkd if not unicodedata.combining(ch))
    return _ALPHA_RE.sub("", ascii_folded)


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
                logger.info("Loaded MMS_FA forced aligner on %s", self._device)
                return True
            except Exception as exc:
                logger.warning("Failed to load MMS_FA forced aligner: %s", exc)
                self._model = None
                return False

    def align_words(
        self,
        pcm_16k: np.ndarray,
        segments: list[SegmentDto],
        words: list[WordDto],
        *,
        max_window_sec: float = 20.0,
        pad_sec: float = 0.15,
        cancel_event: Optional[threading.Event] = None,
    ) -> int:
        """
        Refines `words` timestamps in-place using CTC forced alignment grouped into
        windows of up to `max_window_sec`. Returns the number of words refined.
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

        import torch

        total_samples = len(pcm_16k)
        # Group words into contiguous time windows <= max_window_sec
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

        refined = 0
        sr = self._sample_rate
        scores_log: list[float] = []

        for idx_list in windows:
            if cancel_event is not None and cancel_event.is_set():
                logger.info("Forced alignment cancelled after %d words", refined)
                break

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

            try:
                wav = torch.from_numpy(pcm_16k[s_idx:e_idx]).unsqueeze(0).to(
                    device=self._device, dtype=torch.float32
                )
                transcript = [nw for _, nw in norm_pairs]
                tokens = self._tokenizer(transcript)
                with torch.inference_mode():
                    if self._device == "cuda":
                        with torch.amp.autocast("cuda", dtype=torch.float16):
                            emission, _ = self._model(wav)
                    else:
                        emission, _ = self._model(wav)
                em_cpu = emission[0].float().cpu()
                if em_cpu.size(0) == 0:
                    continue
                spans_list = self._aligner(em_cpu, tokens)
                if len(spans_list) != len(norm_pairs):
                    continue

                ratio = (e_idx - s_idx) / float(em_cpu.size(0) * sr)
                for (wi, _), spans in zip(norm_pairs, spans_list):
                    if not spans:
                        continue
                    avg_score = sum(sp.score for sp in spans) / len(spans)
                    scores_log.append(float(avg_score))
                    # Only apply if aligner has reasonable confidence and sane span (<= 1.5s/word per V23)
                    ns = round(chunk_s + spans[0].start * ratio, 3)
                    ne = round(chunk_s + spans[-1].end * ratio, 3)
                    if ne > ns and (ne - ns) <= 1.5 and avg_score > -5.0:
                        words[wi].start = ns
                        words[wi].end = ne
                        refined += 1
            except Exception:
                logger.debug("MMS_FA window alignment skipped", exc_info=True)
                continue

        if scores_log:
            arr_s = np.asarray(scores_log, dtype=np.float32)
            logger.info(
                "MMS_FA aligned %d/%d words (score p10=%.3f p50=%.3f p90=%.3f)",
                refined,
                len(words),
                float(np.percentile(arr_s, 10)),
                float(np.percentile(arr_s, 50)),
                float(np.percentile(arr_s, 90)),
            )

        return refined
