# HushCast v0.1.11-hushcast

## What's New
- **Fixed 25% Crash / Reset When Fast Model (`tiny.en`) Is Enabled**:
  - [`AutoMuteAnalyzer`](core/automute-engine/src/main/java/app/hushcast/automute/pipeline/AutoMuteAnalyzer.kt) was previously hardcoding `ggml-base.en-q5_1.bin` and ignoring the **Fast Model** (`tiny.en`) and **Flash Attention** toggles — causing Whisper to fail to load at 25% when only `ggml-tiny.en-q5_1.bin` was downloaded.
  - It now loads `ggml-tiny.en-q5_1.bin` when Fast Model is enabled and passes both `useFlashAttn` and `isTiny` to [`Transcriber`](core/automute-engine/src/main/java/app/hushcast/automute/engine/Transcriber.kt).
- **Worker Failure & Analysis Status Diagnostics**:
  - [`EpisodeAnalysisWorker`](core/automute-engine/src/main/java/app/hushcast/automute/worker/EpisodeAnalysisWorker.kt) now logs worker start, completion, and any caught exception/stacktrace to `HushCastPerf`.
  - `Export Logs` now includes `--- Analysis Statuses ---` (badge, %, stage label, peak RSS, and `errorMessage`) and captures `HushCastPerf:V` logs.
