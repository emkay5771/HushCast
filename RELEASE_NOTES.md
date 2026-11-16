# HushCast v0.1.10-hushcast

## What's New
- **20x+ Faster MP3 Decoding (Native `dr_mp3` Fast-Path)**:
  - Telemetry from `v0.1.9-hushcast` showed that Android's `MediaCodec` (`c2.android.mp3.decoder`) over Binder IPC was capped at ~15x realtime (~8+ minutes for a 2-hour MP3).
  - [`AudioDecoder`](core/automute-engine/src/main/java/app/hushcast/automute/engine/AudioDecoder.kt) now decodes local MP3 (`audio/mpeg`) files directly in native C++ using `dr_mp3` (`libhushcast_whisper.so`) in large 32,768-frame chunks, completely eliminating Binder IPC overhead (~1–2 seconds for a 2-hour MP3 decode).
  - Non-MP3 formats (M4A/AAC/Opus) continue to use `MediaCodec` seamlessly.

---

# HushCast v0.1.9-hushcast

## What's New
- **Detailed Performance & Crash Telemetry in Export Logs**:
  - `Export Logs` now includes the installed app version (`versionName` & `versionCode`) and a dedicated `HushCastPerf & Crashes` section.
  - Added `HushCastPerf` timing logs in [`AudioDecoder`](core/automute-engine/src/main/java/app/hushcast/automute/engine/AudioDecoder.kt) and [`AutoMuteAnalyzer`](core/automute-engine/src/main/java/app/hushcast/automute/pipeline/AutoMuteAnalyzer.kt).
- **Bug Fix**:
  - Fixed a `NoSuchElementException` crash in Debug Settings when tapping "Delete latest episodes" on podcasts with no episodes.
