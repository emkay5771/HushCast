"""
Clean-up for faster-whisper's batched output (independent <=30 s windows).

- `build_initial_prompt`: a short, punctuated prompt applied to every window so the model keeps
  casing and punctuation.
- `strip_prompt_leak`: drops a window that just repeats the prompt (near-silent audio).
- `repair_hard_cuts`: re-decodes the audio around windows that were cut mid-speech, restoring the
  word lost on the cut and the false period Whisper adds at a window's end.

Pure Python: no ML imports, so tests can pass a fake decoder.
"""

from __future__ import annotations

import logging
import re
import threading
from typing import Callable, Optional, Sequence

from .ad_tagger import _ABBREVIATIONS
from .models import WordDto

logger = logging.getLogger("hushcast_worker.transcript_repair")

PROMPT_SENTENCE = "Hello, and welcome back to the show."
_MAX_PODCAST_TITLE_CHARS = 80
_MAX_EPISODE_TITLE_CHARS = 120

HARD_CUT_MAX_GAP_S = 0.12
REPAIR_WINDOW_S = 10.0
REPAIR_RADIUS_S = 2.0
REPAIR_PROMPT_WORDS = 30
# Two decodes of the same word rarely disagree on its midpoint by more than this.
_SEAM_MATCH_S = 1.0

# decode_window_fn(window_start_s, window_end_s, prompt) -> words with times relative to window_start_s
DecodeWindowFn = Callable[[float, float, str], Sequence[WordDto]]

_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_WS_RE = re.compile(r"\s+")
_NON_ALNUM_RE = re.compile(r"[\W_]+", re.UNICODE)


def _norm(text: str) -> str:
    return _NON_ALNUM_RE.sub("", text.lower())


def _mid(w: WordDto) -> float:
    return 0.5 * (float(w.start) + float(w.end))


def _clean_title(raw: Optional[str], max_chars: int) -> str:
    if not raw:
        return ""
    t = _WS_RE.sub(" ", _CONTROL_RE.sub(" ", str(raw))).strip()
    if len(t) > max_chars:
        cut = t[:max_chars]
        space = cut.rfind(" ")
        t = cut[:space] if space >= max_chars // 2 else cut
    return t.rstrip(" :;,-–—|/").strip()


def build_initial_prompt(
    podcast_title: Optional[str],
    episode_title: Optional[str],
    language: Optional[str] = "en",
) -> Optional[str]:
    """
    `"{podcast}: {episode}. Hello, and welcome back to the show."` with titles cleaned and
    truncated (well under Whisper's 224-token prompt limit). The English sentence is only added
    for English; other languages get the titles alone (or no prompt).
    """
    podcast = _clean_title(podcast_title, _MAX_PODCAST_TITLE_CHARS)
    episode = _clean_title(episode_title, _MAX_EPISODE_TITLE_CHARS)
    if podcast and episode and episode.lower().startswith(podcast.lower()):
        podcast = ""
    head = f"{podcast}: {episode}" if (podcast and episode) else (podcast or episode)
    if head and head[-1] not in ".?!":
        head += "."
    lang = (language or "en").strip().lower()
    if lang.startswith("en"):
        return f"{head} {PROMPT_SENTENCE}" if head else PROMPT_SENTENCE
    return head or None


def _leading_match_count(words: Sequence[WordDto], target: str) -> int:
    """Number of leading words whose letters/digits spell exactly `target` (0 if none)."""
    goal = _norm(target)
    if not goal:
        return 0
    acc = ""
    for i, w in enumerate(words):
        acc += _norm(w.text)
        if acc == goal:
            return i + 1
        if not goal.startswith(acc):
            return 0
    return 0


def strip_prompt_leak(words: Sequence[WordDto], prompt: Optional[str]) -> tuple[list[WordDto], int]:
    """
    Drops the leading words of one decoded window when they repeat the prompt (the whole prompt,
    or its closing sentence). Returns (kept words, number dropped).
    """
    if not prompt or not words:
        return list(words), 0
    candidates = [prompt]
    if PROMPT_SENTENCE in prompt and prompt.strip() != PROMPT_SENTENCE:
        candidates.append(PROMPT_SENTENCE)
    for cand in candidates:
        n = _leading_match_count(words, cand)
        if n:
            return list(words[n:]), n
    return list(words), 0


def find_hard_cuts(
    segments_words: Sequence[Sequence[WordDto]],
    max_gap_s: float = HARD_CUT_MAX_GAP_S,
) -> list[float]:
    """Times of window boundaries with (almost) no gap between the words on either side."""
    groups = [g for g in segments_words if g]
    cuts: list[float] = []
    for prev, nxt in zip(groups, groups[1:]):
        a = float(prev[-1].end)
        b = float(nxt[0].start)
        if b - a < max_gap_s:
            cuts.append(0.5 * (a + b))
    return cuts


def _strip_cut_period(left_text: str, right_text: str) -> Optional[str]:
    """New text for the word before a cut when its period is Whisper's window-end artifact."""
    core = left_text.strip()
    if not core.endswith(".") or core.endswith(".."):
        return None
    body = core[:-1]
    if not body or "." in body or core.lower() in _ABBREVIATIONS:
        return None  # initialisms ("U.S."), abbreviations ("Dr.")
    nxt = right_text.strip()
    if not nxt or not nxt[0].islower():
        return None
    return left_text.rstrip()[:-1]


def _same_word(a: Optional[WordDto], b: Optional[WordDto]) -> bool:
    if a is None or b is None:
        return False
    na = _norm(a.text)
    return bool(na) and na == _norm(b.text) and abs(_mid(a) - _mid(b)) <= _SEAM_MATCH_S


def repair_hard_cuts(
    segments_words: Sequence[Sequence[WordDto]],
    decode_window_fn: DecodeWindowFn,
    *,
    max_gap_s: float = HARD_CUT_MAX_GAP_S,
    window_s: float = REPAIR_WINDOW_S,
    radius_s: float = REPAIR_RADIUS_S,
    prompt_words: int = REPAIR_PROMPT_WORDS,
    audio_duration_s: Optional[float] = None,
    cancel_event: Optional[threading.Event] = None,
) -> tuple[list[WordDto], int]:
    """
    Flattens per-window word lists and repairs every hard cut (consecutive windows with a gap
    under `max_gap_s`):

    1. Re-decode `window_s` of audio centred on the cut with `decode_window_fn`, prompted with the
       last `prompt_words` words before the window. Decoded times are relative to the window
       start and are shifted to episode time here.
    2. Replace the original words whose midpoints are within `radius_s` of the cut with the
       decoded words whose midpoints are in the same span (only when the decoder returned words
       there and the count is plausible). The span edges are reconciled so a word whose two
       timings straddle an edge is neither doubled nor lost.
    3. If the word before the cut ends with a plain "." and the word after starts lowercase,
       strip that period.

    Returns (flat word list, number of cuts whose words were replaced). The input is not mutated.
    """
    flat: list[WordDto] = [w.model_copy() for g in segments_words for w in g]
    cuts = find_hard_cuts(segments_words, max_gap_s=max_gap_s)
    n_replaced = 0
    n_periods = 0
    n_failed = 0
    half = 0.5 * window_s

    for t in cuts:
        if cancel_event is not None and cancel_event.is_set():
            raise RuntimeError("Job cancelled")
        win_start = max(0.0, t - half)
        win_end = t + half
        if audio_duration_s is not None and audio_duration_s > 0:
            win_end = min(win_end, float(audio_duration_s))
        lo, hi = t - radius_s, t + radius_s

        decoded: list[WordDto] = []
        if win_end - win_start >= 1.0:
            prior = [w.text.strip() for w in flat if float(w.end) <= win_start + 1e-6]
            prompt = " ".join(prior[-prompt_words:]) if prompt_words > 0 else ""
            try:
                raw = decode_window_fn(win_start, win_end, prompt) or []
                for d in raw:
                    if not str(d.text).strip():
                        continue
                    ds = round(win_start + float(d.start), 3)
                    de = round(win_start + max(float(d.end), float(d.start) + 0.01), 3)
                    decoded.append(WordDto(start=ds, end=de, text=str(d.text)))
            except Exception:
                if cancel_event is not None and cancel_event.is_set():
                    raise RuntimeError("Job cancelled")
                n_failed += 1
                logger.debug("Hard-cut re-decode at %.2fs failed", t, exc_info=True)
                decoded = []

        i0 = next((i for i, w in enumerate(flat) if _mid(w) >= lo), len(flat))
        i1 = next((i for i in range(i0, len(flat)) if _mid(flat[i]) > hi), len(flat))
        j0 = next((j for j, d in enumerate(decoded) if _mid(d) >= lo), len(decoded))
        j1 = next((j for j in range(j0, len(decoded)) if _mid(decoded[j]) > hi), len(decoded))
        removed = flat[i0:i1]
        insert = decoded[j0:j1]

        if insert:
            left_keep = flat[i0 - 1] if i0 > 0 else None
            right_keep = flat[i1] if i1 < len(flat) else None
            # Left edge: drop a doubled word, or pull in one the decoder timed just outside.
            if _same_word(insert[0], left_keep):
                insert = insert[1:]
            elif removed and j0 > 0 and _same_word(decoded[j0 - 1], removed[0]) \
                    and not _same_word(decoded[j0 - 1], left_keep) \
                    and not _same_word(insert[0], removed[0]):
                insert = [decoded[j0 - 1]] + insert
            # Right edge, mirrored.
            if insert and _same_word(insert[-1], right_keep):
                insert = insert[:-1]
            elif insert and removed and j1 < len(decoded) and _same_word(decoded[j1], removed[-1]) \
                    and not _same_word(decoded[j1], right_keep) \
                    and not _same_word(insert[-1], removed[-1]):
                insert = insert + [decoded[j1]]

        # Plausibility: a re-decode that lost half the words or looped is worse than the original.
        if insert and (len(insert) * 2 >= len(removed)) and len(insert) <= 2 * len(removed) + 4:
            flat[i0:i1] = insert
            n_replaced += 1

        b = next((i for i, w in enumerate(flat) if _mid(w) >= t), len(flat))
        if 0 < b < len(flat):
            new_text = _strip_cut_period(flat[b - 1].text, flat[b].text)
            if new_text is not None:
                flat[b - 1] = WordDto(start=flat[b - 1].start, end=flat[b - 1].end, text=new_text)
                n_periods += 1

    logger.info(
        "Hard-cut repair: %d cut(s) found, %d re-decoded, %d boundary period(s) removed, %d decode failure(s)",
        len(cuts),
        n_replaced,
        n_periods,
        n_failed,
    )
    return flat, n_replaced
