## What's New in v0.1.5-hushcast

### 🐛 Critical Crash Fixes
- **Fixed Stream-to-Download & Background Analysis Crash**: Resolved an `IllegalStateException` (`Player callback method is called from a wrong thread`) in `PlaybackService` when a download completed during playback or active AI analysis by dispatching player mutations onto `Dispatchers.Main`.

### ⚡ Performance & APK Size (**190 MB → 72 MB**)
- **62% Smaller APK**: Filtered native packaging to `arm64-v8a` (removing 55 MB of unused `x86`/`x86_64`/`armeabi-v7a` binaries) and enabled `--gc-sections` + `--strip-debug` for `libhushcast_llama.so` (52 MB → 5.0 MB) and `libhushcast_whisper.so` (15 MB → 1.4 MB).
- **Faster Audio Decoding & Feature Extraction**:
  - Vectorized `Resampler` inner loop in `Float` and reused scratch buffers to eliminate ~300k+ per-frame allocations per hour.
  - Cascaded 24 kHz → 16 kHz resampling for Whisper and moved `FrameExtractor` (FFTs, cepstrum, K-weighting) to a concurrent worker thread.
  - Smooth `70% → 89%` progress updates during Qwen3 sentence embedding.

### 🎙️ Pocket Casts-Style Home, Playlists & Settings
- **Home Podcasts Grid**: Replaced the single-row home carousel with a full Pocket Casts-style Podcasts Grid (**Large Grid**, **Small Grid**, **List**), **Recently Added** / **A–Z** sorting, **Count** / **Dot** / **Off** badges, a **Tune** options sheet, and a compact **Continue Playing** card at the top.
- **Play All & Add All to Up Next**: Added **Play All** and **Add All to Up Next** actions in the top bar of **Playlists**, **New Episodes**, **Downloads**, and **Continue Playing**.
- **Global Default Playback Speed & Storage Cleanup**: Added **Default playback speed** (`0.5x–3.0x`) in **Settings → Playback** and **Clean up downloaded episodes** in **Settings → Downloads & Storage**.

---

## What's New in v0.1.4-hushcast

### 🎙️ Pocket Casts-Inspired UX (Material 3 Expressive)
- **Subscriptions Options**: Toggle between **Large Grid**, **Small Grid**, and **List** layouts, sort by **New Episodes** or **A–Z**, and choose unplayed badges (**Count**, **Dot**, or **Off**).
- **Up Next Queue Management**:
  - **Play Next** and **Play Last** dropdown on episode queue buttons and episode detail pages.
  - **Tap Up Next to play** (on by default, configurable in **Settings → Playback**) plays the tapped episode immediately and shifts the previous episode to the top of Up Next.
  - Visible drag handles, **Move to Top**, swipe-to-remove, **Clear Up Next** confirmation, and a direct **Up Next** button + swipe-up gesture on the mini-player.
- **Listing & Episode Details**:
  - Quick filter chips (**Unplayed**, **Downloaded**, **In Progress**), duration/remaining-time overlines, played dimming, and inline progress bars.
  - Long-press multi-select mode for bulk **Play Next**, **Play Last**, **Mark Played**, and **Select All**.
  - Per-podcast **Auto add to Up Next** (*Off*, *Play Next*, *Play Last*) and **Playback speed** (*Global*, *0.8x–2.0x*) in Podcast Settings.
- **Full Player Enhancements**: Clickable podcast title to jump to the show, swipe-up to open Up Next, modal **Synced Transcript** sheet, and a **Player Actions** (*More*) sheet.

### ⚡ Playback, OPML Import & Analysis Fixes
- **Seamless Stream-to-Download Swap**: When a streaming episode finishes downloading, playback swaps to the local file at the exact playback position without interruption.
- **OPML Import Fix**: Recursively flattens nested `<outline>` folders (such as Pocket Casts' `<outline text="feeds">`), pre-selects new feeds with **Select all / Deselect all**, shows import progress, and tolerates individual dead feeds.
- **HushCast AI Analysis Feedback & Speed**:
  - Tapping **Analyze now** on a non-downloaded episode now prompts you to **Download** it instead of failing silently.
  - Synced Room status updates with the Live Activity notification so the player badge no longer stalls at 5%.
  - Optimized `AudioDecoder` with bulk buffer reads and smooth 15%→34% decoding progress.

### First-Time Setup
1. Download the `HushCast-*.apk` on your Android phone (`arm64-v8a`, Android 8.0+ / API 26+) and install it.
2. Open **Settings → HushCast** to download the on-device AI models over Wi-Fi.
