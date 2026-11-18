from __future__ import annotations

import json
import logging
import math
import re
import shutil
import subprocess
import threading
from collections import Counter
from pathlib import Path
from typing import Any, Optional

import numpy as np

from .models import AdSegmentDto, DesktopChapterDto, SegmentDto

logger = logging.getLogger("hushcast_worker.chapters")

_CHAT_LLM_LOCK = threading.Lock()
_CACHED_CHAT_LLM: Optional[tuple[str, int, Any]] = None


def _get_chat_llm(chat_model_path: Path):
    global _CACHED_CHAT_LLM
    from llama_cpp import Llama

    key_path = str(chat_model_path)
    key_cls = id(Llama)
    with _CHAT_LLM_LOCK:
        if (
            _CACHED_CHAT_LLM is not None
            and _CACHED_CHAT_LLM[0] == key_path
            and _CACHED_CHAT_LLM[1] == key_cls
        ):
            return _CACHED_CHAT_LLM[2]
        try:
            llm = Llama(
                model_path=key_path,
                n_ctx=4096,
                n_gpu_layers=-1,
                verbose=False,
            )
        except Exception:
            logger.warning("Chat LLM GPU init failed; retrying with CPU layers", exc_info=True)
            llm = Llama(
                model_path=key_path,
                n_ctx=4096,
                n_gpu_layers=0,
                verbose=False,
            )
        _CACHED_CHAT_LLM = (key_path, key_cls, llm)
        return llm

_STOPWORDS = {
    "a", "about", "above", "across", "actually", "after", "again", "against", "all",
    "almost", "already", "also", "always", "am", "an", "and", "any", "are", "aren't",
    "around", "as", "at", "basically", "be", "became", "because", "become", "been",
    "before", "behind", "being", "between", "beyond", "but", "by", "can", "can't",
    "certainly", "comes", "coming", "could", "definitely", "did", "didn't", "do",
    "does", "doesn't", "doing", "don't", "down", "during", "each", "either", "enough",
    "especially", "essentially", "even", "ever", "every", "feel", "for", "from",
    "get", "getting", "go", "goes", "going", "good", "got", "great", "had", "has",
    "have", "having", "he", "her", "here", "hers", "him", "his", "how", "however",
    "i", "i'd", "i'll", "i'm", "i've", "if", "in", "inside", "instead", "into", "is",
    "isn't", "it", "it's", "its", "just", "kind", "know", "like", "literally",
    "little", "looking", "looks", "lot", "made", "make", "many", "maybe", "me",
    "mean", "meanwhile", "more", "most", "much", "my", "need", "neither", "no",
    "not", "now", "obviously", "of", "off", "oh", "ok", "okay", "on", "one",
    "only", "or", "other", "our", "out", "outside", "over", "own", "people",
    "personally", "pretty", "probably", "put", "rather", "really", "right", "said",
    "same", "say", "saying", "see", "seemed", "seems", "she", "should", "so",
    "some", "something", "sort", "still", "stuff", "such", "sure", "take",
    "talking", "tell", "than", "that", "that's", "the", "their", "them", "then",
    "there", "there's", "therefore", "these", "they", "they're", "thing", "things",
    "think", "this", "those", "though", "through", "time", "to", "too", "totally",
    "toward", "towards", "try", "trying", "uh", "um", "under", "up", "us", "use",
    "used", "using", "very", "wait", "want", "was", "wasn't", "way", "we", "we're",
    "well", "were", "what", "what's", "when", "where", "whether", "which", "while",
    "who", "why", "will", "with", "within", "without", "would", "yeah", "yes",
    "you", "you're", "your", "yours",
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
        boost = 1.65 if " " in term else 1.0
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
        if len(picked) >= 2:
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

    sub_emb = embeddings[non_ad_indices]
    m = len(non_ad_indices)
    total_dur_sec = max(
        0.0,
        float(segments[non_ad_indices[-1]].end) - float(segments[non_ad_indices[0]].start),
    )
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
        max_chapters = min(9, max(4, int(round(total_dur_sec / 360.0)) + 1))
        min_gap_sec = max(120.0, min(240.0, total_dur_sec / max(1, max_chapters + 2)))
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

    chapter_starts_ms: list[int] = []
    for b_pos, start_idx in enumerate(boundaries):
        raw_start = float(segments[start_idx].start)
        if b_pos == 0 and raw_start < 15.0:
            raw_start = 0.0
        chapter_starts_ms.append(max(0, int(round(raw_start * 1000.0))))

    if chat_model_path is not None and chat_model_path.exists():
        try:
            chapter_blocks: list[str] = []
            for b_pos, txts in enumerate(chapter_texts_list):
                non_empty = [t.strip() for t in txts if t.strip()]
                if non_empty:
                    step_c = max(1, len(non_empty) // 5)
                    excerpt = " ".join(non_empty[::step_c][:5])[:420]
                else:
                    excerpt = ""
                start_sec = chapter_starts_ms[b_pos] // 1000
                chapter_blocks.append(
                    f"Chapter {b_pos + 1} [{start_sec // 60:02d}:{start_sec % 60:02d}]: {excerpt}"
                )
            chapters_prompt_text = "\n".join(chapter_blocks)
            n_ch = len(boundaries)
            prompt = (
                "Summarize this podcast episode in 2-3 concise sentences, and write a distinct "
                f"short 3-7 word topic title for each of the {n_ch} chapters below.\n"
                "Return ONLY valid JSON: "
                '{"summary": "...", "titles": ["Title 1", "Title 2"]}\n\n'
                f"Chapters:\n{chapters_prompt_text}\n\nJSON:"
            )
            chat_llm = _get_chat_llm(chat_model_path)
            with _CHAT_LLM_LOCK:
                if hasattr(chat_llm, "create_chat_completion"):
                    messages = [
                        {
                            "role": "system",
                            "content": (
                                "You are a concise podcast editor. Return ONLY valid JSON with no "
                                "markdown fences or extra commentary."
                            ),
                        },
                        {"role": "user", "content": prompt},
                    ]
                    try:
                        out = chat_llm.create_chat_completion(
                            messages=messages,
                            max_tokens=768,
                            temperature=0.2,
                            response_format={"type": "json_object"},
                        )
                    except TypeError:
                        out = chat_llm.create_chat_completion(
                            messages=messages,
                            max_tokens=768,
                            temperature=0.2,
                        )
                    raw_text = out["choices"][0]["message"]["content"].strip()
                else:
                    out = chat_llm.create_completion(prompt, max_tokens=768, temperature=0.2)
                    raw_text = out["choices"][0]["text"].strip()

            cleaned_raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw_text.strip(), flags=re.IGNORECASE)
            m_json = re.search(r"\{.*\}", cleaned_raw, flags=re.DOTALL)
            if m_json:
                json_candidate = re.sub(r",\s*([}\]])", r"\1", m_json.group(0))
                parsed = json.loads(json_candidate)
                llm_summary = str(parsed.get("summary") or "").strip() or None
                raw_titles = parsed.get("titles") or []
                seen_titles: set[str] = set()
                llm_chapters: list[DesktopChapterDto] = []
                for b_pos in range(n_ch):
                    candidate = (
                        str(raw_titles[b_pos]).strip()[:80]
                        if b_pos < len(raw_titles)
                        else ""
                    )
                    norm_cand = candidate.lower()
                    if not candidate or norm_cand in seen_titles:
                        candidate = _name_chapter_from_tfidf(
                            chapter_texts_list[b_pos], all_chunk_token_sets, b_pos
                        )
                    if candidate.lower() in seen_titles:
                        candidate = f"{candidate[:72]} ({b_pos + 1})"
                    seen_titles.add(candidate.lower())
                    llm_chapters.append(
                        DesktopChapterDto(
                            startMs=chapter_starts_ms[b_pos],
                            title=candidate,
                            source="auto",
                        )
                    )
                final_chapters = embedded_chapters if len(embedded_chapters) >= 2 else llm_chapters
                if llm_summary and final_chapters:
                    return llm_summary, final_chapters
                logger.warning("Chat model JSON missing summary/chapters; raw=%r", raw_text[:240])
            else:
                logger.warning("Chat model returned non-JSON output; raw=%r", raw_text[:240])
        except Exception:
            logger.exception("Chat model summary/chapters failed; falling back to extractive")
    else:
        logger.info("No chat model found at %s; using extractive summary & TF-IDF chapters", chat_model_path)

    if embedded_chapters:
        chapters = embedded_chapters
    else:
        chapters = []
        for b_pos in range(len(boundaries)):
            title = _name_chapter_from_tfidf(chapter_texts_list[b_pos], all_chunk_token_sets, b_pos)
            chapters.append(
                DesktopChapterDto(
                    startMs=chapter_starts_ms[b_pos],
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
