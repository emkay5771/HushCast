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
    adClass: str = "sponsor_ad"


class DesktopChapterDto(BaseModel):
    startMs: int
    title: str
    source: str = "auto"  # "podcast" (ID3/MP4/RSS) or "auto" (HushCast AI)


PIPELINE_VERSION = 5


class DesktopAnalysisBundle(BaseModel):
    fileSha256: str
    episodeId: str
    durationSec: float
    wordIndexBlob: WordIndexBlobDto
    adSegments: list[AdSegmentDto]
    summary: Optional[str] = None
    chapters: list[DesktopChapterDto] = Field(default_factory=list)
    whisperModel: str = ""
    analysisDurationMs: int = 0
    workerAccelerator: str = "cpu"
    pipelineVersion: int = PIPELINE_VERSION


class JobState(str, Enum):
    QUEUED = "QUEUED"
    DOWNLOADING = "DOWNLOADING"
    TRANSCRIBING = "TRANSCRIBING"
    EMBEDDING = "EMBEDDING"
    TAGGING = "TAGGING"
    READY = "READY"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class JobStage(str, Enum):
    QUEUED = "QUEUED"
    DOWNLOADING = "DOWNLOADING"
    DECODING = "DECODING"
    TRANSCRIBING = "TRANSCRIBING"
    ALIGNING = "ALIGNING"
    SPEAKER_ID = "SPEAKER_ID"
    EMBEDDING = "EMBEDDING"
    TAGGING = "TAGGING"
    SUMMARIZING = "SUMMARIZING"
    READY = "READY"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class DesktopJobRequest(BaseModel):
    episodeId: str
    audioUrl: str
    title: str = ""
    language: Optional[str] = None
    feedTranscriptRaw: Optional[str] = None
    feedTranscriptMime: Optional[str] = None
    feedTranscriptUrl: Optional[str] = None
    feedChaptersUrl: Optional[str] = None
    feedChaptersType: Optional[str] = None
    force: bool = False
    reprocessStage: Optional[str] = None


class DesktopJobResponse(BaseModel):
    jobId: str
    episodeId: str
    state: JobState
    progressPercent: int = 0
    stageLabel: str = "Queued"
    stage: Optional[JobStage] = None
    stageProgress: Optional[int] = None
    fileSha256: Optional[str] = None
    errorMessage: Optional[str] = None
    errorCode: Optional[str] = None
    retryable: Optional[bool] = None
    queuePosition: Optional[int] = None
    etaSec: Optional[int] = None
    rev: int = 0


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
    apiVersion: int = 2
    pipelineVersion: int = PIPELINE_VERSION
    hostname: str
    accelerator: str
    deployment: str = "local"
    gpuName: Optional[str] = None
    modelsReady: bool
    modelsLoaded: bool = False
    whisperModel: str = ""
    chatModelReady: bool = False
    cachedEpisodes: int = Field(default=0)
    queueDepth: int = 0
    activeJob: Optional[str] = None
    cacheBytes: int = 0
    diskFreeBytes: int = 0


class DesktopSearchRequest(BaseModel):
    query: str
    topK: int = Field(default=15, ge=1, le=50)


class DesktopSearchHit(BaseModel):
    fileSha256: str
    episodeId: str
    episodeTitle: str = ""
    startMs: int
    endMs: int
    text: str
    score: float


class DesktopSearchResponse(BaseModel):
    query: str
    hits: list[DesktopSearchHit]
