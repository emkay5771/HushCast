from __future__ import annotations

import json
import logging
import math
import re
import shutil
import subprocess
from collections import Counter
from pathlib import Path
from typing import Optional

import numpy as np

from .models import AdSegmentDto, DesktopChapterDto, SegmentDto

logger = logging.getLogger("hushcast_worker.chapters")

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


def _is_inside_ad(start_s: float, end_s: float, ad_segments: list[AdSegmentDto]) -> bool:
    s_ms = int(start_s * 1000)
    e_ms = int(end_s * 1000)
    for ad in ad_segments:
        if s_ms < ad.endMs and e_ms > ad.startMs:
            return True
    return False


def _extract_chapter_tokens(text: str) -> list[tuple[str, bool]]:
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


def extract_embedded_chapters(audio_path: Optional[Path]) -> list[DesktopChapterDto]:
    if audio_path is None or not audio_path.exists():
        return []
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        return []
    try:
        proc = subprocess.run(
            [ffprobe, "-v", "quiet", "-print_format", "json", "-show_chapters", str(audio_path)],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        if proc.returncode != 0 or not proc.stdout.strip():
            return []
        data = json.loads(proc.stdout)
        raw_chapters = data.get("chapters") or []
        out: list[DesktopChapterDto] = []
        for idx, ch in enumerate(raw_chapters):
            start_sec = float(ch.get("start_time", 0.0) or 0.0)
            tags = ch.get("tags") or {}
            title = str(tags.get("title") or tags.get("TITLE") or f"Chapter {idx + 1}").strip()[:80]
            if not title:
                title = f"Chapter {idx + 1}"
            out.append(
                DesktopChapterDto(
                    startMs=max(0, int(round(start_sec * 1000.0))),
                    title=title,
                    source="podcast",
                )
            )
        return out
    except Exception:
        logger.exception("Failed to extract embedded chapters from %s", audio_path)
        return []


def generate_chapters_and_summary(
    *,
    segments: list[SegmentDto],
    embeddings: np.ndarray,
    ad_segments: list[AdSegmentDto],
    chat_model_path: Optional[Path] = None,
    audio_path: Optional[Path] = None,
) -> tuple[Optional[str], list[DesktopChapterDto]]:
    embedded_chapters = extract_embedded_chapters(audio_path)

    non_ad_indices = [
        i for i, s in enumerate(segments)
        if not _is_inside_ad(s.start, s.end, ad_segments) and len(s.text.split()) >= 3
    ]
    if len(non_ad_indices) < 4:
        return None, embedded_chapters

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
                llm_chapters = sorted(
                    [
                        DesktopChapterDto(
                            startMs=max(0, int(float(c.get("startSec", 0)) * 1000)),
                            title=str(c.get("title", "Chapter")).strip()[:80],
                            source="auto",
                        )
                        for c in (parsed.get("chapters") or [])
                        if str(c.get("title", "")).strip()
                    ],
                    key=lambda ch: ch.startMs,
                )
                final_chapters = embedded_chapters if len(embedded_chapters) >= 2 else llm_chapters
                if llm_summary and final_chapters:
                    return llm_summary, final_chapters
        except Exception:
            logger.exception("Chat model summary/chapters failed; falling back to extractive")

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

    if embedded_chapters:
        chapters = embedded_chapters
    else:
        chapters = []
        for b_pos, start_idx in enumerate(boundaries):
            title = _name_chapter_from_tfidf(chapter_texts_list[b_pos], all_chunk_token_sets, b_pos)
            chapters.append(
                DesktopChapterDto(
                    startMs=max(0, int(round(segments[start_idx].start * 1000))),
                    title=title,
                    source="auto",
                )
            )

    ep_keywords: Counter[str] = Counter()
    for idx in non_ad_indices:
        for low, _ in _extract_chapter_tokens(segments[idx].text):
            ep_keywords[low] += 1
    top_kw = {w for w, _ in ep_keywords.most_common(15)}

    centroid = np.mean(sub_emb, axis=0)
    centroid /= float(np.linalg.norm(centroid)) + 1e-12

    filler_starts = re.compile(
        r"^(?:yeah|yes|no|well|okay|ok|right|totally|exactly|i mean|you know|so it is|i guess|so,|but,|and,|uh\b|um\b|like,|wait,)\b",
        re.IGNORECASE,
    )
    promo_cues = re.compile(
        r"\b(?:promo code|dot com|slash|subscribe|patreon|sponsor|sponsored by)\b",
        re.IGNORECASE,
    )
    banter_cues = re.compile(
        r"(?:\s-\s|\?|good guess|we'll explain|you said|my question|two acceptable)",
        re.IGNORECASE,
    )
    intro_cues = re.compile(
        r"\b(?:talking about|today on the show|welcome to|going to talk about|this week we)\b",
        re.IGNORECASE,
    )

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

        sent_start = 0
        for m_split in re.finditer(r'(?<=[.!?])\s+(?=[A-Z"\'(\-])', run_text):
            sent_end = m_split.start()
            _score_sentence = run_text[sent_start:sent_end].strip().lstrip("- ")
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
                if (
                    filler_starts.search(_score_sentence)
                    or promo_cues.search(_score_sentence)
                    or banter_cues.search(_score_sentence)
                ):
                    continue
                sent_vec = np.mean(sub_emb[k_list], axis=0)
                sent_vec /= float(np.linalg.norm(sent_vec)) + 1e-12
                emb_sc = float(np.dot(sent_vec, centroid))
                kw_hits = sum(1 for low, _ in _extract_chapter_tokens(_score_sentence) if low in top_kw)
                score = emb_sc + 0.08 * min(kw_hits, 5) + (0.05 if 14 <= wc <= 32 else 0.0)
                if k_list[0] < max(12, m // 6) and intro_cues.search(_score_sentence):
                    score += 0.55
                sentence_candidates.append((score, segments[non_ad_indices[k_list[0]]].start, _score_sentence))

        tail = run_text[sent_start:].strip().lstrip("- ")
        if tail and tail.endswith((".", "!")) and not tail.endswith("?"):
            k_list = [k for cs, ce, k in spans if ce > sent_start]
            wc = len(tail.split())
            if (
                k_list
                and 10 <= wc <= 45
                and (tail[0].isupper() or tail[0] in '"\'')
                and not filler_starts.search(tail)
                and not promo_cues.search(tail)
                and not banter_cues.search(tail)
            ):
                sent_vec = np.mean(sub_emb[k_list], axis=0)
                sent_vec /= float(np.linalg.norm(sent_vec)) + 1e-12
                emb_sc = float(np.dot(sent_vec, centroid))
                kw_hits = sum(1 for low, _ in _extract_chapter_tokens(tail) if low in top_kw)
                score = emb_sc + 0.08 * min(kw_hits, 5) + (0.05 if 14 <= wc <= 32 else 0.0)
                if k_list[0] < max(12, m // 6) and intro_cues.search(tail):
                    score += 0.55
                sentence_candidates.append((score, segments[non_ad_indices[k_list[0]]].start, tail))

    if not sentence_candidates:
        emb_scores = sub_emb @ centroid
        for k, idx in enumerate(non_ad_indices):
            seg = segments[idx]
            txt = re.sub(r"\s+", " ", seg.text).strip()
            wc = len(txt.split())
            if (
                wc < 10
                or wc > 42
                or txt.endswith("?")
                or filler_starts.search(txt)
                or banter_cues.search(txt)
            ):
                continue
            kw_hits = sum(1 for low, _ in _extract_chapter_tokens(txt) if low in top_kw)
            score = float(emb_scores[k]) + 0.08 * min(kw_hits, 5)
            if k < max(12, m // 6) and intro_cues.search(txt):
                score += 0.55
            cleaned = txt.rstrip(",;:- ")
            if cleaned and cleaned[0].islower():
                cleaned = cleaned[0].upper() + cleaned[1:]
            if cleaned and cleaned[-1] not in ".!?":
                cleaned += "."
            sentence_candidates.append((score, seg.start, cleaned))

    sentence_candidates.sort(key=lambda x: x[0], reverse=True)
    chosen_sents: list[tuple[float, str]] = []
    for _, start_s, sent_txt in sentence_candidates:
        if all(abs(start_s - c_start) >= 90.0 for c_start, _ in chosen_sents):
            chosen_sents.append((start_s, sent_txt))
            if len(chosen_sents) >= 2:
                break
    chosen_sents.sort(key=lambda x: x[0])
    summary = " ".join(sent_txt for _, sent_txt in chosen_sents) if chosen_sents else None

    return summary, chapters
