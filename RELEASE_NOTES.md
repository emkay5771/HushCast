# HushCast v0.1.15-hushcast

## What's New
- **Full-Screen Player & Transcript Improvements**:
  - Swipe up (or tap the expand icon) on `MediaPlayerBottomSheet` to expand the player to full page for reading Show Notes and Transcripts.
  - Added transcript search, `mm:ss` timestamps, tap-to-jump on any line/word, long-press-to-mute/unmute (with working rule removal), working **Play** on Sponsor Read cards, and a 1-tap **Copy Transcript & Ad Report** button.
- **Episode & Queue AI Analysis Status + One-Tap Analyze**:
  - Episode list items (`PodcastEpisodeListItem`) and Up Next queue rows (`MediaItemListItem`) now display HushCast readiness badges (`AI Ready`, `Word-Mute`, or live `%` progress) and a one-tap **Analyze** (`AutoAwesome`) button so you can analyze downloaded episodes without queueing them.
- **Miniplayer Seek Controls (M3E)**:
  - Added Pocket Casts-style fast-forward and rewind buttons flanking the Play/Pause control in `FloatingMediaPlayer`, styled with Material 3 Expressive sizing and dynamic seek increments.
- **Cleaner Folder Editing UI**:
  - Replaced the per-item `× Remove` text buttons in `FolderBottomSheet` with a header `Edit` / `Done` toggle and subtle top-right `×` badges on tiles in edit mode.
- **Home Grid Badges, Layout & Add Button Polish**:
  - Recalculated unplayed episode counts on Home load and enlarged the `LATEST_ONLY` dot badge so grid count and dot badges update reliably.
  - Removed the redundant "Continue Playing" carousel from Home now that the miniplayer persists across restarts.
  - Replaced the oversized extended Add FAB on Home with a compact `SmallFloatingActionButton`.
- **Single-Version GitHub Release Notes**:
  - Updated `release-apk.yml` so GitHub Releases only include the latest release section above the first `---` separator.

---

# HushCast v0.1.14-hushcast

## What's New
- **Chunked Whisper Transcription & Native Abort Support**:
  - Long episodes (>3 min) now transcribe in 180-second chunks with per-chunk `HushCastPerf` speed telemetry and `whisper_full_params.abort_callback` wired to WorkManager cancellation.
- **Phase 3 (Qwen3 + ONNX Ad Tagger) Hardening & Cached-Transcript Retry**:
  - Reduced `llama_jni.cpp` `n_ubatch` to 512 (~4× smaller attention buffer), added empty-batch/null-handle guards, 64-sentence chunking for smoother progress & cancellation, ONNX performance-core thread config, and stage-tagged error diagnostics.
  - Retrying a failed Phase 3 job now reuses the cached `WordIndexBlob` and extracts only the 24 kHz acoustic frames instead of re-running Whisper.
  - Preserves `WORD_MUTE_READY` status if Phase 3 fails or is cancelled.
- **Model Download Concurrency Fix**:
  - Synchronized `Downloads.fetch` per destination path to eliminate `.part` rename races between Settings and `EpisodeAnalysisWorker`, plus HTTP status and 0-byte/truncated download checks.
- **ProGuard JNI Keep Rules Fix**:
  - Updated `consumer-rules.pro` from `podcast.adtagger.*` to `app.hushcast.automute.nativebridge.*` (`LlamaJni`, `WhisperJni`, `WhisperCallbacks`).
- **Playback Persistence, Navbar Spacing & Settings UX**:
  - Persists the currently playing episode and restores it alongside the queue on app restart (`MediaPlayerViewModel`).
  - Removed leftover bottom navbar space in `HomeRoute` and `Main`.
  - Collapsed the Muted Words Dictionary list by default in `SettingsHushCastPane` and prevented the keyboard from auto-opening on entry.

---

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
