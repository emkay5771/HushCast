from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

from hushcast_worker.chapters import generate_chapters_and_summary
from hushcast_worker.models import SegmentDto


class ChaptersTimestampTest(unittest.TestCase):
    def _build_52min_episode(self) -> tuple[list[SegmentDto], np.ndarray]:
        segments: list[SegmentDto] = []
        vecs: list[np.ndarray] = []
        rng = np.random.default_rng(42)

        topics = [
            (0, 150, "intro podcast movie confession religion western civilization", 0),
            (150, 320, "american dream income mobility economy young people", 1),
            (320, 480, "artificial intelligence education college tuition loans", 2),
            (480, 640, "nvidia data centers semiconductors gpu export controls", 3),
            (640, 800, "dallas austin texas municipal budget infrastructure", 4),
        ]
        dim = 16
        for start_i, end_i, text, topic_idx in topics:
            base = np.zeros(dim, dtype=np.float32)
            base[(topic_idx * 3) % dim] = 1.0
            base[(topic_idx * 3 + 1) % dim] = 0.8
            for i in range(start_i, end_i):
                t0 = float(i * 4)
                t1 = t0 + 3.8
                segments.append(SegmentDto(start=t0, end=t1, text=f"{text} segment {i}."))
                v = base + rng.normal(0.0, 0.05, size=dim).astype(np.float32)
                v /= np.linalg.norm(v) + 1e-12
                vecs.append(v)

        return segments, np.vstack(vecs)

    def test_extractive_chapters_span_full_52_minutes(self) -> None:
        segments, embeddings = self._build_52min_episode()
        summary, chapters = generate_chapters_and_summary(
            segments=segments,
            embeddings=embeddings,
            ad_segments=[],
            chat_model_path=None,
            audio_path=None,
        )
        self.assertGreaterEqual(len(chapters), 4)
        self.assertEqual(chapters[0].startMs, 0)
        # Verify timestamps are strictly monotonic and span well past 30 minutes
        for a, b in zip(chapters, chapters[1:]):
            self.assertLess(a.startMs, b.startMs)
        self.assertGreater(chapters[-1].startMs, 2000_000)

    def test_llm_titles_preserve_grounded_timestamps_and_deduplicate(self) -> None:
        segments, embeddings = self._build_52min_episode()

        class FakeLlama:
            def __init__(self, **kwargs):
                pass

            def create_chat_completion(self, messages, max_tokens=512, temperature=0.2):
                payload = {
                    "summary": "Ben Shapiro discusses the American dream, AI in education, and tech policy.",
                    "titles": [
                        "Introduction",
                        "Income Mobility",
                        "AI and Education",
                        "NVIDIA Data Centers",
                        "NVIDIA Data Centers",  # intentional duplicate to test dedup fallback
                        "Texas Infrastructure",
                    ],
                }
                return {"choices": [{"message": {"content": json.dumps(payload)}}]}

        fake_llama_mod = types.ModuleType("llama_cpp")
        fake_llama_mod.Llama = FakeLlama

        with tempfile.NamedTemporaryFile(suffix=".gguf") as tmp_model:
            with patch.dict(sys.modules, {"llama_cpp": fake_llama_mod}):
                summary, chapters = generate_chapters_and_summary(
                    segments=segments,
                    embeddings=embeddings,
                    ad_segments=[],
                    chat_model_path=Path(tmp_model.name),
                    audio_path=None,
                )

        self.assertIsNotNone(summary)
        self.assertGreaterEqual(len(chapters), 4)
        self.assertEqual(chapters[0].startMs, 0)
        self.assertGreater(chapters[-1].startMs, 2000_000)
        # Titles must be unique (duplicate replaced with TF-IDF fallback)
        lower_titles = [c.title.lower() for c in chapters]
        self.assertEqual(len(lower_titles), len(set(lower_titles)))


if __name__ == "__main__":
    unittest.main()
