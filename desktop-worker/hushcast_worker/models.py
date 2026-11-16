from __future__ import annotations

from enum import Enum
from typing import Optional
from pydantic import BaseModel, Field


class WordDto(BaseModel):
    start: float
    end: float
    text: str


class SegmentDto(BaseModel):
    start: float
    end: float
    text: str = ""
    nWords: int = 0
    speaker: Optional[int] = None


class WordIndexBlobDto(BaseModel):
    words: list[WordDto]
    segments: list[SegmentDto]


class AdSegmentDto(BaseModel):
    startMs: int
    endMs: int
    confidence: float
    isHighConfidence: bool
    label: str


class DesktopAnalysisBundle(BaseModel):
    fileSha256: str
    episodeId: str
    durationSec: float
    wordIndexBlob: WordIndexBlobDto
    adSegments: list[AdSegmentDto]
    analysisDurationMs: int = 0
    workerAccelerator: str = "cpu"


class JobState(str, Enum):
    QUEUED = "QUEUED"
    DOWNLOADING = "DOWNLOADING"
    TRANSCRIBING = "TRANSCRIBING"
    EMBEDDING = "EMBEDDING"
    TAGGING = "TAGGING"
    READY = "READY"
    FAILED = "FAILED"


class DesktopJobRequest(BaseModel):
    episodeId: str
    audioUrl: str
    title: str = ""
    feedTranscriptRaw: Optional[str] = None
    feedTranscriptMime: str = ""


class DesktopJobResponse(BaseModel):
    jobId: str
    episodeId: str
    state: JobState
    progressPercent: int = 0
    stageLabel: str = "Queued"
    fileSha256: Optional[str] = None
    errorMessage: Optional[str] = None


class DesktopReadyEpisode(BaseModel):
    fileSha256: str
    episodeId: str
    title: str = ""
    durationSec: float
    audioBytes: int
    wordCount: int
    adCount: int
    createdAtMs: int


class DesktopHealthResponse(BaseModel):
    status: str = "ok"
    version: str
    hostname: str
    accelerator: str
    modelsReady: bool
    cachedEpisodes: int = Field(default=0)
