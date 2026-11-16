# HushCast v0.1.13-hushcast

## What's New
- **Whisper Flash Attention Fix (Fixes 25% Stall / High Memory)**:
  - Fixed `whisper_jni.cpp` where `useDtw` previously overrode `useFlashAttn`, preventing Flash Attention from ever turning on and forcing full FP32 DTW alignment matrices.
- **Rich Live Activity Progress (`X of Y` + Episode Title)**:
  - `EpisodeAnalysisWorker` now displays the current episode title and `X of Y` episode count in the Live Activity notification, status chip, and `HushCastPerf` logs.
- **In-Player Notes & Transcript Tabs**:
  - Added `Player | Notes | Transcript` tabs directly inside `MediaPlayerBottomSheet` so you can read show notes or follow/tap the synced transcript without leaving the player.
- **Prevent Queueing Currently Playing Episode**:
  - `PodcastEpisodeQueueButton` and `MediaPlayerViewModel` now hide/guard queue actions for the currently playing episode and prevent duplicate queueing.
- **Pocket Casts-Style Library & List Flows**:
  - Added quick **Move to Folder** on Home list rows, **Mark All Seen** in New Episodes, and **Remove Played Downloads** in Downloads.

---

# HushCast v0.1.12-hushcast

## What's New
- **Live Whisper Transcription Progress (25% → 59%)**:
  - Wired `onWhisperProgress` in [`AutoMuteAnalyzer`](core/automute-engine/src/main/java/app/hushcast/automute/pipeline/AutoMuteAnalyzer.kt) so the notification and episode card update smoothly during Whisper transcription instead of holding at 25% until 60%.
- **Prevent Duplicate Dictionary Rules**:
  - [`AutoMuteDao`](core/automute-engine/src/main/java/app/hushcast/automute/storage/AutoMuteDatabase.kt) now deduplicates existing and newly added muted word rules (case-insensitive pattern + regex flag), and preset buttons in [`SettingsHushCastPane`](app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsHushCastPane.kt) show `Profanity Added` / `Explicit Added` once already present.
- **System Navbar Inset Fix**:
  - Added `navigationBarsPadding()` to the [`FloatingMediaPlayer`](app/src/main/java/app/podiumpodcasts/podium/ui/Main.kt) container so the miniplayer always stays above the system navigation bar even on detail/settings screens where the app bottom bar is hidden.

---

# HushCast v0.1.11-hushcast

## What's New
- **Fixed 25% Crash / Reset When Fast Model (`tiny.en`) Is Enabled**:
  - [`AutoMuteAnalyzer`](core/automute-engine/src/main/java/app/hushcast/automute/pipeline/AutoMuteAnalyzer.kt) now loads `ggml-tiny.en-q5_1.bin` when Fast Model is enabled and passes `useFlashAttn` and `isTiny` to [`Transcriber`](core/automute-engine/src/main/java/app/hushcast/automute/engine/Transcriber.kt).
- **Worker Failure & Analysis Status Diagnostics**:
  - [`EpisodeAnalysisWorker`](core/automute-engine/src/main/java/app/hushcast/automute/worker/EpisodeAnalysisWorker.kt) and `Export Logs` now surface `Analysis Statuses` and `HushCastPerf:V` error logs.
