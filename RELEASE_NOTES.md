# HushCast v0.1.9-hushcast

## What's New
- **Detailed Performance & Crash Telemetry in Export Logs**:
  - `Export Logs` now includes the installed app version (`versionName` & `versionCode`) and a dedicated `HushCastPerf & Crashes` section so system buffer logs don't crowd out analysis timings.
  - [`AudioDecoder`](core/automute-engine/src/main/java/app/hushcast/automute/engine/AudioDecoder.kt) and [`AutoMuteAnalyzer`](core/automute-engine/src/main/java/app/hushcast/automute/pipeline/AutoMuteAnalyzer.kt) now log `native` resampler status, sample rate/channels, real-time decode speed (`Xx RT`), and per-stage durations.
- **Bug Fix**:
  - Fixed a `NoSuchElementException` crash in Debug Settings when tapping "Delete latest episodes" on podcasts with no episodes.

---

# HushCast v0.1.8-hushcast

## What's New
- **10x–20x Faster Audio Decoding & Feature Extraction**: Moved the polyphase audio resampler (with ARM64 NEON SIMD) and radix-2 FFT into native C++ (`-O2`).
- **Faster AI Model Downloads**: Increased download I/O buffering and skipped redundant SHA-256 hashing when no hash is required.
- **Podcast Folders & Drag-and-Drop Grid Sorting**: Organize your home podcast grid with Pocket Casts-style folders and long-press drag-and-drop reordering.
- **No Wasted Space Above Bottom Bar**: Tightened the mini-player spacing above the compact bottom navigation bar.
- **Analysis Cancellation & Easy Log Export**: Cancel queued/active episode analysis from the notification or Settings, reset stale status when audio is missing, and export diagnostic logs directly from HushCast Settings.
- **Persistent Artwork Caching**: Podcast and episode artwork is now cached on disk across app restarts.
