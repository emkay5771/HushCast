# HushCast v1.0.43-hushcast

## What's New
- **Notification permission on initial open**:
  - Restored the `POST_NOTIFICATIONS` permission prompt on first launch (Android 13+) once the UI is ready.
- **Season grouping & serial podcast defaults**:
  - Added a **Group by season** filter chip for podcasts with `itunes:season` metadata, complete with season section headers and segmented card groups.
  - Podcasts marked `itunes:type="serial"` now default to oldest-first ordering and automatically enable season grouping when seasons are available.
- **Richer RSS & Podcasting 2.0 support**:
  - **Trailers (`podcast:trailer`)**: Play a show's trailer directly from the podcast header.
  - **Soundbites (`podcast:soundbite`)**: Playable preview clip chips on episode detail pages.
  - **People (`podcast:person`), Categories (`itunes:category`), & Funding (`podcast:funding`)**: Host/guest chips with avatars, category tags, and creator support links in the podcast Info tab and episode details.
  - **Episode numbers, types, explicit badges, & comments**: Subtle `EP. X`, `TRAILER`/`BONUS`, and `E` (explicit) badges in episode lists, plus clickable episode `comments` links in episode details.
  - **Feed URL migration (`itunes:new-feed-url`)**: Automatically follows new feed URLs on refresh without breaking existing subscriptions, history, or cached HushCast data.

---

# HushCast v1.0.42-hushcast

## What's New
- **Podcast detail layout & miniplayer clearance**:
  - Enlarged podcast artwork in the header and anchored it closer to the title to better fill the top space and remove excess blank space beneath it.
  - Added bottom system bar inset padding to the podcast episode list so the floating miniplayer no longer covers the bottom-most (oldest/first) episode when scrolled.

---

# HushCast v1.0.41-hushcast

## What's New
- **Maintenance & documentation updates**:
  - General maintenance and updated project documentation.

---

# HushCast v1.0.40-hushcast

## What's New
- **Compose 1.13 & Material 3 1.5.0 stable**:
  - Upgraded Jetpack Compose to `1.13.0` (`composeBom 2026.11.01`), Compose Material 3 to `1.5.0` stable, `materialKolor` to `5.0.1`, `adaptive-navigation3` to `1.3.0`, AGP to `9.1.1`, Gradle to `9.3.1`, and `compileSdk` to `37`.
  - Updated `ToggleButton`, `FilledTonalToggleButton`, and `ToggleButtonDefaults` usages to their stable `1.5.0` APIs.
- **Native Compose 1.13 progressive blur**:
  - Replaced `haze` with Compose's built-in `Modifier.blur(BlurRadiusSpec.verticalGradient(...))` on podcast detail and preview header backdrops, removing the `haze` dependency.
- **Unified M3 Expressive choice groups**:
  - Migrated the remaining `SingleChoiceSegmentedButtonRow` controls in the HushCast player sheet and Settings pane to `ConnectedSingleChoiceGroup`.
- **Lint & locale fix**:
  - Fixed `NonObservableLocale` in episode list items by reading `LocalLocale.current.platformLocale`.

---

# HushCast v1.0.39-hushcast

## What's New
- **Transcript view no longer drops boundary words**:
  - Fixed a bug where words at the start or end of a paragraph whose forced-aligned timestamps shifted slightly past the original segment bounds were omitted from the in-app transcript view.
  - The worker also updates segment bounds to match forced-aligned word timings.
- **Up Next queue delete & undo fixed**:
  - Swiping an episode out of Up Next no longer leaves a stuck red delete row at the top of the queue, and tapping **Undo** restores the episode to its original queue position.
- **Clearer episode swipe actions**:
  - Queue swipe actions now use clearer top/bottom alignment icons (`Next` and `End`) and show short text labels directly beneath the icons in the swipe tray.
- **Podcast-appropriate queue icon**:
  - Replaced the music-note queue icon (`QueueMusic`) across the mini player, full player, Up Next empty state, and Episodes tab with non-music list/queue icons.
- **M3E transport animations & larger system speed icon**:
  - Added Material 3 Expressive shape morphs, bouncy spring scale kicks, and rotational skip feedback to Play/Pause and Skip buttons in both the full player and mini player.
  - Scaled up the playback speed icons (`ic_speed_*`) for better readability in the Android system media notification.

---

# HushCast v1.0.38-hushcast

## What's New
- **Media notification skip buttons restored**:
  - The Android system media player (lock screen and Quick Settings) now keeps its skip-back and skip-forward buttons alongside the playback speed control instead of dropping them when speed updates.
- **Player "Both" remaining time display fixed**:
  - Selecting **Both** for remaining time in Settings now shows both the original remaining time and the speed/skip-adjusted time (e.g. `-24:10 (-16:07)`) on the player seek bar.
- **Worker transcript repair fixes**:
  - `repair_hard_cuts` now reconciles multi-word timestamp drift across repair seams and rejects re-decodes that drop words compared to the original span.
  - Added a text-split fallback in `_make_fw_window_decoder` when a re-decoded segment lacks word-level timestamps.

---

# HushCast v1.0.37-hushcast

## What's New
- **Player: the "double bounce" when switching tabs is gone**:
  - Swiping between Playing, Details and Chapters no longer stretches the new page the wrong way about a third of a second after it lands. This happened on every tab, not just the first and last.
  - Pulling past the end of a list in any bottom sheet and letting go now springs back straight away, instead of holding the stretch for a moment first. This covers the player's tabs, Up Next, the country picker, folders, filters and sort order, save to list, downloads, podcast preview and settings, the player's customize and HushCast sheets, and the color and swipe-action sheets in Settings.
  - Cause: Material 3's bottom sheet joins every fling inside it with a 300 ms settle, and reports a sideways swipe's speed back as unused. Sheets now let go of the stretch when your finger lifts, and the fling after it can't restart it. Stretching while you drag looks the same as before.
- **The player's tab underline follows your swipe** instead of jumping across after the page has settled (Discover's topics already did; both now share one implementation).

---

# HushCast v1.0.36-hushcast

## What's New
- **Crash fix**:
  - **Opening the full player no longer crashes the app** (1.0.35, every time). The mini player's show/hide animation moved to M3E's expressive spring, which overshoots below zero when the mini player hides; that value is also the bottom padding of snackbars and lists, and Android rejects negative padding ("Padding must be non-negative"). The value is now clamped, and folder-drag elevation uses the non-bouncy effects spring.
- **Standard overscroll everywhere**:
  - Lists, grids and sheets — including the player sheet's Details and Chapters tabs — have Android's stretch overscroll again. The transcript keeps its deliberate guard so scrolling past its end never drags the player closed.
  - **Swipeable tabs**: the player's tabs and Discover's topics no longer hand leftover fling speed from their page snap to a second stretch.
  - Known: in Compose, a list that is flung into its top or bottom shows its stretch a frame or two after it stops, where classic Android lists stretch on impact. This is unchanged from 1.0.35.
- **Sponsor row polish**:
  - TalkBack announces the sponsor and "Skipped" states once, instead of re-reading the countdown.
  - Skip disappears as soon as playback reaches the end of the ad.
  - The row's text stays centred when only one side has a button (episodes without chapters).
  - With sponsor skipping turned off for a podcast (or globally), the row stays a chapter row; ads are still marked on the seek bar.
  - The Undo on other tabs sits above the player's bottom bar at its measured height, so a taller toolbar or larger font can't overlap it.
- **Settings**:
  - HushCast's sections (Muting, Sponsor skipping, Word list, Processing, Advanced) are now regular settings pages, so Back and the predictive back gesture work like every other settings page.
  - The Automatic / Light / Dark row measures its labels for the new connected buttons and no longer shrinks "Automatic" more than needed.
- **Motion**: every remaining show/hide and content-swap animation uses the M3E motion scheme (content swaps on its non-bouncy effects springs, so whole screens never overshoot).
- **Note on 1.0.35**: its "single-choice selectors migrated to connected button groups" covered 6 of 12. The HushCast settings page (4) and the player's HushCast sheet (2) keep segmented buttons on purpose: their four-option rows don't fit connected buttons at 360 dp with large text, and each screen uses one style throughout.

---

# HushCast v1.0.35-hushcast

## What's New
- **Material 3 Expressive & Native Feel**:
  - **Restored stretch overscroll**: Scoped overscroll suppression to the player sheet only, bringing back native Android stretch overscroll across all lists and grids.
  - **Blur behind sheets fix**: Fixed `BlurBehindSheetWindow` so turning "Blur behind sheets" off (or enabling Battery Saver) cleanly clears the window blur flag; removed dead pre-API-31 bitmap blur fallback.
  - **Mini player M3E polish**: Swipe-up now triggers once per gesture with a `GestureThresholdActivate` haptic, and playback buttons use M3E `IconButtonDefaults.shapes()` / `toggleableShapes()` morphs.
  - **Tactile haptics everywhere**: Added `ToggleOn`/`ToggleOff` haptics to all Settings and player sheet switches, `GestureThresholdActivate` to queue & rule swipe-to-dismiss (plus dynamic left/right trash icon alignment in Up Next), and 15-minute mark `SegmentTick` haptics on the custom sleep timer slider.
  - **M3E motion, indicators & components**: Upgraded 27 `animateItem()` list/grid animations and key controls to `MaterialTheme.motionScheme`, upgraded loading spinners to `LoadingIndicator` / `PullToRefreshDefaults.LoadingIndicator` and full-width processing/download bars to `LinearWavyProgressIndicator`, migrated single-choice selectors to connected `ToggleButton` `ButtonGroup`s (with TalkBack radio semantics), and converted Sleep Timer options to `SegmentedListItem` containers.
- **Player Sponsor Row Consolidation**:
  - **Inline Skip & Undo**: Replaced the artwork-covering `PlayerSkipPill` and toolbar-covering bottom Undo banner on the Playing tab with an inline sponsor/undo state in the segment navigation row (animating in on chapterless episodes only during ads/Undo, with a compact Undo above the bottom bar on Notes/Chapters/Transcript).
  - **Position-based Undo expiry**: Undo now expires after 8s of playback past the skip, on manual seek, or on episode change (`HushCastEngineBridge`), preventing stale Undo prompts when reopening the player.

---

# HushCast v1.0.34-hushcast

## What's New
- **Player**:
  - **Safer downward swipe**: Swiping down over the seek bar to close the player no longer triggers accidental scrubbing; horizontal drags still enter fine-scrubbing mode and taps still seek.
  - **Grouped More actions sheet**: Items in the `⋯` overflow bottom sheet now use Material 3 Expressive grouped containers matching Settings.
  - **Cleaner bottom layout**: Removed the redundant `Up Next` bar/hint at the bottom of the player to reclaim vertical space (swipe up or use the top-right Up Next button).
- **Transcript**:
  - **Collapsed sponsor blocks**: Sponsor spots are now collapsed by default behind an expandable header, keeping the dialogue flow clean while letting you expand any sponsor transcript on tap.

---

# HushCast v1.0.33-hushcast

## What's New
- **Player**:
  - **Bottom-anchored Undo Ad-Skip toast**: Moved the "Skipped Sponsor / Ad … Undo" toast out of the artwork overlay to the bottom of the player sheet so it no longer covers episode artwork and remains accessible across all player tabs.
- **Transcript**:
  - **Word-level tap-to-seek**: Tapping any word in a transcript paragraph (or choosing "Play from here" on a long-pressed word) now starts playback from that exact word while keeping paragraph grouping intact.

---

# HushCast v1.0.32-hushcast

## What's New
- **Player**:
  - **Blur off by default & smooth transition**: The player's blurred-artwork background now defaults to off, and animates smoothly (`Modifier.blur` on Android 12+ with an alpha crossfade fallback on older devices) when toggled.
  - **Swipe up anywhere for Up Next**: Swiping up anywhere on the player outside active buttons or sliders opens Up Next.
  - **Slide-up overflow menu**: The `⋯` action bar button now opens a slide-up bottom sheet matching HushCast and Speed.
- **HushCast**:
  - **Advanced stage reprocessing**: Re-running individual pipeline stages (`Summary & Chapters`, `Ads & Summary`, `Align Words`) is back under **Advanced** in the player's HushCast sheet.
  - **Per-podcast direct download**: Added **Download direct (skip auto-processing)** in per-podcast settings so selected podcasts download without queuing HushCast processing automatically.
  - **Sponsor transcript separation**: Transcript paragraphs and segments now split cleanly at ad boundaries so sponsor spots never share a paragraph with regular dialogue.
  - **Tighter bleep timing**: Short CTC-aligned words receive a 35 ms minimum overlap padding floor and a shorter 5 ms mute ramp to reduce edge bleed.

---

# HushCast v1.0.31-hushcast

## What's New
- **Transcripts**:
  - **Whole sentences again**: The sentence splitter no longer cuts one word per line when a stretch has no punctuation. The worker and the phone use the same rule.
  - **No more lowercase windows**: The worker prompts Whisper with the podcast and episode titles, so every 30-second window keeps capitals, punctuation and name spellings.
  - **No more dropped words at window edges**: Hard cuts between windows are re-decoded and stitched, and sentences flow across window edges.
  - **Speaker labels and paragraphs**: Transcripts show "Speaker N" again and group sentences into paragraphs, on screen and in the exported text.
  - **Publisher transcripts**: When a feed lists several transcripts, the app tries them best format first (JSON, VTT, SRT, then HTML) and shows the first that loads. The worker spot-checks word-level publisher timings against the audio, and only uses aligned SRT/VTT text when nearly all of it lines up.
  - The pipeline version is now 6, so episodes are re-processed with these fixes.
- **Processing**:
  - **Mute mode applies again**: Words you've added follow the global mute mode. Existing words are switched over once.
  - **Analyze buttons always run**: Up Next, the player and the episode page share one path. A tap replaces a queued charging-only job, uses your worker when it's switched on, and says when it's waiting for a charger.
  - **One meaning for the worker switch**: The worker is used only when it's switched on and has an address, everywhere. If it's off, the phone does the work and says so.
  - **Re-process from the player**: The HushCast sheet offers "Re-process episode", or "Update processing" when a newer pipeline is available.
  - **Progress ring**: The HushCast icon shows a progress ring again while an episode is processing.
  - **Re-runs**: Stale results re-run on your worker when it's switched on; otherwise on the phone, only while charging on unmetered Wi-Fi. Three per refresh, or ten while charging on Wi-Fi. Re-runs use the feed's transcript when there is one, keep the old results if they fail, and no longer loop or restart each other.
  - **Cloud jobs**: "Cancel processing" stops the cloud job. A retried download re-attaches to its running job instead of starting a second one, and the phone cancels the job when it gives up waiting.
  - **Worker status**: A failed worker job shows why. An out-of-date worker is flagged. While a job waits, the row says the cloud GPU is starting or how many jobs are ahead. The worker deletes its copy of the audio once the phone has it.
  - **Dynamic ad insertion**: Ad breaks are placed after the last word before the ad, not mid-clip, and words are shifted instead of stretched across ads. MP3 clips seek through a cached table, about 10× faster on long episodes.
  - **SRT/VTT word times**: Words a rule matches inside estimated cues are muted for the whole cue until a short Whisper pass fixes their times, which runs when the episode plays.
  - **Lighter on the phone**: Analysis uses fewer threads while audio plays or the phone is warm, a second tap no longer restarts a running analysis, and leftover checkpoints and old-file results are cleaned up.
- **Player**:
  - **Layout**: The artwork sizes to the available space and the title is centered. The chapter row has matching buttons and a thin progress bar. The skip and undo pill sits on the artwork, and play/pause always uses the primary color.
  - **Seek bar**: A rounded M3E track with a gap at the handle; marks are clipped to the track.
  - **Scrubbing**: The scrub card floats above the bar without moving the page. It names the chapter you'd land in and gives a haptic tick at each chapter boundary.
  - **Swipe up for Up Next**: Swiping up anywhere above the seek bar opens Up Next. A small "Up Next" grip shows that it's there.
  - **Toolbar**: Choose how many toolbar icons show (3, 4 or 5), and drag to reorder them in Customize player, with a live preview and Reset to default.
  - **Blur**: A blurred-artwork background behind the player, and blur behind the player's sheets where the device supports it. Both can be switched off in Appearance.
  - **Per-podcast speed works**: A podcast's own speed is applied whenever its episodes play, and changing the speed no longer overwrites the global speed. The notification's speed button follows the same rule.
  - **Sleep timer**: End of chapter and End of episode count down from the playback position and speed, and hold while paused. Switching or cancelling a timer no longer cancels a later one, and End of chapter follows seeks.
  - **Episode page**: No longer flashes the previous episode; chapter taps, including a chapter at 0:00, seek correctly. "Unmute word" unmutes that spot.
  - **Transcript**: Sponsor blocks have a "Play anyway" button. Long-press a line to play from it, copy it, or mute or unmute a word. The overlay animates in and out and supports predictive back.
  - **HushCast sheet**: Auto-skip is "Confirmed sponsors" or "All detected ads", with a per-podcast reset. Mute style is a segmented row.
  - **⋯ menu**: One "Process episode" item and Share; developer details appear only in developer mode.
  - **Smoother**: The Playing page no longer redraws twice a second, the selected tab survives rotation, sheets animate closed, and colors cross-fade between episodes.
  - The mini player's ad marks use the theme color.
- **Sounds**:
  - **Mute sound**: Choose Soft tone (the new default), Warm boop or Classic bleep, each with a Test button.
  - **Skip sound**: Choose Whoosh (the new default) or Soft two-note.
  - Custom sounds saved before 1.0.26 are moved into your sound list once, so they can be renamed and deleted, and picking a tone no longer loses them. Custom-sound previews stop after 5 seconds.
- **Home, Lists & Navigation**:
  - **Refresh**: Pull-to-refresh tells you when you're offline instead of spinning forever, stops once your feeds are in, and says when a refresh or some podcasts failed.
  - **Removing downloads**: Swiping away a download can be undone without re-downloading; the file is deleted only after the Undo message goes away. The episode that's playing can't be removed.
  - **Back**: Back from a tab goes to your startup tab.
  - **Steady grid**: The Home grid no longer reshuffles when you come back to it.
  - **Folders**: Organize Folders opens again, and folders can be reordered by dragging.
  - **Long-press a podcast**: Adds Settings; Mark all as played and Unsubscribe ask first.
  - **Top bars**: Search from Home. List and Downloads show a count or size and keep extra actions in a menu.
  - **Lists under the mini player**: Tab screens no longer add the system bar padding twice, so lists scroll under the mini player down to the nav bar.
  - **M3E navigation layouts**: The short nav bar on phones, its wide form in landscape, and the wide rail on tablets and foldables. Tabs fade through when you switch.
  - **Faster start**: A splash screen instead of a blocking startup, and a baseline profile.
  - **History**: An episode played again no longer appears several times; existing duplicates are cleaned up once.
  - Library → Up Next opens the tapped episode again. Bottom-bar tab names match the tab labels.
- **Feeds & Background Work**:
  - **Faster refresh**: Chapter files are fetched only for new or changed episodes, in parallel, after notifications and auto-download, with back-off for broken links.
  - **One parse per feed**, and feeds that rarely change still pick up chapter and transcript links once after this update.
  - New-episode counts stay correct after a refresh, and download notifications appear only once a download actually starts.
- **Settings & Appearance**:
  - **Grouped settings**: Every settings page, including per-podcast settings, uses the same grouped lists with headers. Group shapes number themselves. The HushCast cards and loose boxes are gone.
  - **One name per mute mode**: Silence, Duck, Sound and Skip, used everywhere.
  - **Appearance redesign**: a live preview; mode buttons with icons; one row of color swatches (Wallpaper, eight palettes and a new **Custom** color with a hue slider, suggestions and a hex field); "Fine-tune colors" for color style and contrast; dark background tiles; a Player group with the artwork-color and blur switches; a Navigation group.
  - **Small screens**: Segmented buttons and labels fit on 360 dp screens.
- **Accessibility & Copy**:
  - Episode rows announce their state ("Played, downloaded, HushCast ready"); switch rows are announced as switches; swipe actions are named.
  - Undo, the streaming pill and color swatches have 48 dp touch targets.
  - Dialogs name their action ("Delete download?" → Delete / Cancel), and the app says "played" everywhere.
  - Filled icons for the selected tab, outlined for the rest.
- **Cloud & Desktop Worker**:
  - **Pinned models**: Every model is pinned to a fixed version and checked by SHA-256; downloads resume and bad files are never used. Cold starts no longer reach Hugging Face.
  - **Faster embeddings on Modal**: llama-cpp-python now matches the lock file (0.3.37), so embeddings are batched again.
  - **Reliable jobs**: Jobs that wait too long for a GPU fail cleanly instead of starting later; health reports real model and queue state; progress writes no longer block the server.
  - **Deploy check**: Every deploy ends with a health check.
  - **Mac worker**: Setup verifies every file and installs a launchd service; HTTPS with certificate pinning; the server stays responsive during jobs.
  - **Leaner**: Lower memory use, faster audio decoding and batched word alignment on the GPU.

---

# HushCast v1.0.30-hushcast

## What's New
- **Player, Navigation & UX**:
  - **Inline Error & Buffering States**: Added inline playback error messages with a **Retry** button in both the full player and floating mini player, and overlaid buffering indicators on artwork (after 300ms) instead of swapping artwork out.
  - **Exact Sleep Timer**: Wired **End of episode** (`pauseAtEndOfMediaItems`) and **End of chapter** (`createMessage` at chapter end) to exact playback positions instead of wall-clock estimates, and added an active check mark on the selected timer entry.
  - **Back Navigation & Swipe Polish**: Back on Discover, Library, or custom tabs now returns to Home instead of exiting; Back exits Home reorder mode; Library **Up Next** taps now open the episode; swipe-to-delete removes downloads immediately with an **Undo** snackbar and fixes RTL swipe offsets.
  - **Streaming, Speed & Chapter Polish**: Labeled the streaming indicator with an explicit **Download** action, defaulted the speed sheet scope to **This podcast** when a per-podcast speed is saved, fixed chapter/transcript start positions on episode detail pages, and named global defaults in per-podcast settings.
  - **Accessibility & Performance**: Restored TalkBack actions on transcript rows and seek bar progress semantics, replaced hard-coded amber seek bar ad marks with theme-aware `secondary` color, reduced player & transcript recompositions, moved transcript state building off the main thread, pre-bound player state before sheet open, and fixed the episode detail ViewModel leak.
  - **Settings & Discovery**: Added a **Source code** row in Settings, formatted HushCast action mode labels cleanly, updated queue icons to `QueuePlayNext` / `PlaylistAdd`, replaced text arrows in Appearance settings, and ensured Discover search still shows Podcast Index results when Apple search fails.
- **Engine, Chapters & Background Work**:
  - **Live Settings Word-List & Mute Mode**: Settings word-list edits and mute mode changes now update the playing episode's `AutoMuteAudioProcessor` live without overwriting per-rule modes.
  - **DAI, Chapters & Re-analysis**: Improved `DaiRetimer` break placement and sentence splitting, fixed `OffsetMap` intro/outro false positives, keyed chapter offset maps by `fileSha256`, extracted ID3 chapters at download time, parsed inline Podlove `<psc:chapter>` elements and applied feed extras on refresh, queued background re-analysis when `pipelineVersion` is behind (charging on unmetered Wi-Fi or when a worker is configured), increased stream buffers to 60s/300s, sized notification artwork to 256px, debounced podcast search, and moved pull-to-refresh to WorkManager unique work.
- **Cloud / Modal & Desktop Worker**:
  - **Resilience & Liveness**: Throttled Modal `Dict` progress writes with a heartbeat thread and stale-job detection, added `new_segment_callback`, `abort_callback`, and `on_token` progress/cancel support, computed ETA on Modal, disabled whisper.cpp VAD on Mac to keep token timestamps aligned, warmed models at Mac startup, and verified `CUDAExecutionProvider` in the Modal GPU image.

---

# HushCast v1.0.29-hushcast

## What's New
- **Player & UI Fixes**:
  - **Action Bar Alignment**: Removed the `2.0x` text label beneath the playback speed icon in `PlayerActionToolbar` so all action bar icons align evenly.
  - **Queue Count Badge**: Restored the Up Next queue count badge (`BadgedBox`) on the player top bar.
  - **Player Space & Controls**: Increased player artwork (`300.dp`) and transport control sizing for better use of screen space.
  - **Precise Scrubber & Sponsor Names**: Restored vertical-drag variable-speed scrubbing (`1x`, `½x`, `1/5x`, `1/10x`) with the zoomed ±90s mini timeline pill in `PlayerSeekBar`, and restored sponsor names on the Skip button (`Skip <Sponsor>`).
  - **Transcript Fixes**: Fixed missing spaces between words in `TranscriptOverlay`, restored exact word hit-testing on long-press (`getOffsetForPosition`), added unmute support for already-muted words, and fixed `TranscriptOverlay` layout so `PlayerSeekBar` and `PlayerTransportControls` remain pinned and clickable below it.
  - **Startup Screen Picker**: Fixed the oversized `AlertDialog` box in `SettingsAppearancePane` by using `fillMaxWidth()` and `verticalScroll(...)`.
  - **Mini Player on Relaunch**: Restored mini player visibility after app relaunch (`controller.prepare()` and `mediaItemCount > 0`).
- **Cloud / Modal Worker & Reprocessing Fixes**:
  - **Modal `POST /v1/jobs` & Reprocessing**: Fixed `422` (`loc: ["query", "req"]`) and `ForwardRef` schema errors in `modal_app.py`, fixed `stage_audio` keyword arguments, added `lookup_by_episode_id` for `reprocessStage`, and avoided unnecessary `volume.reload()` calls.
  - **Phone/Worker JSON Compatibility**: Enabled `coerceInputValues = true` and `explicitNulls = false` in `DesktopWorkerClient` and `response_model_exclude_none=True` on worker endpoints.
  - **Reprocess Routing**: Routed player "Analyze / Reprocess" actions through `DownloadManager` (`DESKTOP` + `forceReprocess`) when a Desktop/Cloud worker URL is configured instead of failing on missing on-device models.
  - **Worker Model & ETA**: Preferred the 3B Qwen2.5 `.gguf` chat model over 7B when both exist, wired `find_ad_candidates` into `decode_ad_segments`, and fixed worker ETA calculation.
- **Phone Engine & Stability Fixes**:
  - **Publisher Transcript Hand-off & Skip Check**: Fixed `EpisodeAnalysisWorker` to read `KEY_FEED_TRANSCRIPT_PATH` (and clean up temp files), moved `AutoMuteAnalyzer`'s `READY` + `pipelineVersion` skip check before writing `ANALYZING`, and added fallback to full Whisper if `DaiRetimer` throws.
  - **Live Muting & Episode Isolation**: Restored **Duck** and **Micro-skip** in `PlayerHushCastSheet` with live updates to `AutoMuteAudioProcessor`, applied new word mute rules immediately to the playing episode, scoped `PlayerScreenViewModel` per episode in `PodcastEpisodeDetailView`, and prevented 60s play-state saves from flickering the transcript.
  - **Crash & Backup Fixes**: Fixed `DiscoverSearchViewModel` crash when Apple search fails (`supervisorScope`), fixed `LibrarySearch` crash on `%` in transcript snippets, and bumped `MAX_AUTOMUTE_DB_VERSION = 4` so backups restore properly.

---

# HushCast v1.0.28-hushcast

## What's New
- **Player Sheet, Playback & Queue Overhaul**:
  - **Modular Player Architecture**: Split the monolithic `MediaPlayerBottomSheet` into dedicated, smoother components (`PlayerScreenViewModel`, `PlayerSeekBar`, `PlayerChaptersTab`, `PlayerHushCastSheet`, `PlayerCustomizeSheet`, and `TranscriptOverlay`) with reduced recompositions and faster sheet opening.
  - **Smoother Motion & Gestures**: Tuned `MediaPlayerBottomSheet` spring physics and swipe-to-dismiss / queue reorder responsiveness (`SwipeableItem`), plus `SleepTimer` and `PlaybackSpeed` bottom sheet polish.
  - **Real-Time Queue & Play-Next Latency**: Fixed queue updates, Play Next / Play Last ordering, and auto-play transitions in `MediaPlayerViewModel` and `PlaybackService` (`PodcastEpisodeQueueButton`).
  - **Chapters Tab & ID3 Fallback**: Added dedicated `PlayerChaptersTab` backed by `EpisodeChapterDao` / `EpisodeChapterHelper` — extracts embedded MP3 ID3 `CHAP` / `CTOC` chapters via Media3 and falls back to AI-generated chapters when none exist.
  - **Transcript Search & Export**: Added inline transcript search and share/export in `TranscriptOverlay`.
- **Home, Library, Discovery & Episode List UX**:
  - **Pull-to-Refresh & Expandable Folders**: Added `PullToRefreshBox` on Home (`HomeRoute`) to refresh feeds via `PodcastUpdateWork`, removed the duplicate top-bar folder button, and made folders expandable inline in List layout.
  - **Long-Press Context Menus**:
    - **Podcast Cards** (`PodcastCard`, `SubscriptionCard`, `HomeRoute`): Long-press opens a context menu with **Open podcast**, **Mark all as listened**, **Move to folder**, and **Unsubscribe**.
    - **Episode Rows** (`PodcastEpisodeListItem`): Long-press opens a context menu (**Play next**, **Play last**, **Download**, **Mark played / unplayed**) alongside TalkBack `CustomAccessibilityAction`s for **Play next** and **Play last**.
  - **Mark All As Listened Safety**: Added confirmation dialog and undo/status snackbar in `PodcastDetailView` when marking all episodes as listened.
  - **Paged Podcast Episodes**: Upgraded `PodcastDetailViewModel` to Paging 3 (`pageSize = 30, prefetchDistance = 30`) for fast scrolling on large feeds.
  - **Faster Podcast Adding & OPML Import**: Added URI keyboard (`KeyboardType.Uri`), **Paste** button, **Import OPML file** button, and **Subscribe** label in `AddPodcastView` / `AddPodcastRoute`, and removed the 2-second artwork color-analysis gate from both `AddPodcastView` and `PodcastPreviewView`.
  - **Smarter Discover Search**: Cancelled stale in-flight searches (`searchJob?.cancel()`), kept previous results visible beneath an inline progress bar while searching, added typed network/server error cards with **Retry**, and detected pasted RSS URLs to show an **Add this feed** card opening `AddPodcastView` in a `ModalBottomSheet`.
  - **Navigation, Downloads & Search Polish**: Improved `NavBarScaffold` / `NavBarItems`, `NewEpisodesRoute`, `DownloadsRoute`, and `LibrarySearch` state handling.
- **Settings Reorganization**:
  - **Inline HushCast Settings Sub-Pages**: `SettingsPane` / `SettingsRoute` now manages HushCast sub-sections inline with `BackHandler` support and updated `SettingsHeader`, `SettingsListItem`, and `SettingsSwitchListItem`.
  - **Cleaner Appearance Settings**: Reordered items in `SettingsAppearancePane` and replaced the inline **Startup screen** list with a single row opening a clean `AlertDialog` picker.
- **Processing Efficiency & Cloud Worker Upgrades**:
  - **2–4x Faster Cloud Transcription (`faster-whisper`)**: Switched default Cloud Worker ASR to `faster-whisper` (`large-v3-turbo`, `int8_float16`) with `whisper.cpp` fallback, Silero VAD pre-filtering, separate CPU web container + GPU worker (`modal.Dict` job state), and 3B JSON-mode LLM (`Qwen2.5-3B-Instruct`).
  - **On-Device Speed & Battery Improvements**: Faster chunked analysis (`CpuTopology` `min(4, cores)` threads, `float32` audio path, `large` vs `tiny.en` model selection), battery/charging gate, and reduced storage overhead.
- **DAI Ad-Retiming Bridge**:
  - **Cross-Download Dynamic Ad Retiming**: Added automatic retiming (`DaiRetimer`, `OffsetMap`) when a dynamic-ad (DAI) re-download shifts timestamps, preserving transcript, chapter, and ad-window alignment without re-transcribing.

---

# HushCast v1.0.27-hushcast

## What's New
- **Stability & Build Fixes**:
  - Fixed missing Compose imports and `SwipeableItemActionResult` arguments introduced in `v1.0.26-hushcast`.
  - Includes all Dev Mode stage reprocessing, multi-sound bleep presets, custom tab bar ordering, and UI polish from `v1.0.26-hushcast`.

---

# HushCast v1.0.26-hushcast

## What's New
- **Dev Mode Stage Reprocessing**:
  - Added per-stage reprocessing controls in Developer / Diagnostics tools to re-run ad classification, chapter/summary generation, or word matching without re-transcribing.
- **Multi-Sound Bleep Presets & Tab Customization**:
  - Added built-in bleep/chime sound presets alongside custom sound files.
  - Added navigation tab customization and general UI polish across the player and settings screens.

---

# HushCast v1.0.25-hushcast

## What's New
- **Word Mute Timing Precision Fix**:
  - Switched `neighborBoundedWindow` boundary calculations to `roundToLong()` to eliminate sub-millisecond truncation drift when clamping mute windows against adjacent words.

---

# HushCast v1.0.24-hushcast

## What's New
- **Word Matcher Test Coverage**:
  - Updated `WordMatcherTest` to cover neighbor-bounded mute padding behavior introduced in `v1.0.23`.

---

# HushCast v1.0.23-hushcast

## What's New
- **MMS_FA CTC Forced Alignment & Neighbor-Bounded Mute Timing**:
  - Added `MMS_FA` CTC forced alignment on the Cloud / Desktop Worker for tighter word-level timestamps.
  - Added neighbor-bounded mute window padding (`WordMatcher`) so bleep/mute expansion never bleeds into adjacent non-target words.
  - Enabled Whisper DTW alignment preset and `t_dtw` token timestamp parsing.

---

# HushCast v1.0.22-hushcast

## What's New
- **Custom Mute Sound & Live Mode Switching in Player**:
  - Added **Custom** (`CUSTOM_SOUND`) and **Micro-Skip** chips plus an inline **Choose file / Change / Clear** custom sound picker to the in-player HushCast menu (`MediaPlayerBottomSheet`).
  - Wired `muteActionMode` changes in `PlaybackService` and `MediaPlayerBottomSheet` to immediately update `HushCastEngineBridge.defaultActionMode` and reload the active episode's mute windows by `episodeId` into `AutoMuteAudioProcessor`.

---

# HushCast v1.0.21-hushcast

## What's New
- **Reorganized HushCast Settings**:
  - Grouped settings into clear sections: **CLOUD / DESKTOP WORKER**, **ON-DEVICE ANALYSIS (OFFLINE FALLBACK)** (models + on-device speed/battery toggles), **WORD BLEEPING & MUTING** (action mode, timing precision, dictionary), **AD & SECTION SKIPPING**, and **DIAGNOSTICS**.

---

# HushCast v1.0.20-hushcast

## What's New
- **Higher-Accuracy Word Bleeping (DTW Alignment + Timing Precision)**:
  - Enabled DTW token timestamps (`dtw_token_timestamps=True`) on the Cloud / Desktop Whisper worker (`pipeline.py`) to eliminate 300–500 ms early word-boundary drift after silences.
  - Added **Bleep / Mute Timing Precision** (`Tight (40/60 ms)`, `Balanced (80/100 ms)`, `Wide (150/180 ms)`) under **Word Auto-Muting** in HushCast Settings (`SettingsHushCastPane`), wired into both Cloud and On-Device matching and automatic rule re-evaluation.
- **Clearer Settings Grouping**:
  - Clarified **On-Device Performance & Battery** (`tiny.en`, `Flash Attention`, `Analyze only while charging`) as **On-device only (ignored by Cloud / Desktop Worker)**.

---

# HushCast v1.0.19-hushcast

## What's New
- **Fix Stale / Shifted Ad Segments on Dynamic-Ad Podcasts (DAI)**:
  - `DesktopWorkerClient` and `AutoMuteAnalyzer` now clear previous `ad_segments` (and `mute_segments`) by `episodeId` before inserting newly analyzed segments, preventing old dynamic-ad timestamps from previous `sha256` downloads (e.g. Megaphone DAI) from stacking on top of the updated transcript and causing false "Sponsor" labels on normal content.

---

# HushCast v1.0.18-hushcast

## What's New
- **Debug Timeline Export**:
  - Added **Export Timeline Data (Debug)** in the in-player HushCast AI sheet (`MediaPlayerBottomSheet`) to share an episode's existing `adSegments` (including confidence, `adClass`, and `label`), `muteWindows`, `chapters`, `summary`, and `transcriptSegments` as a `.json` file via the Android Share sheet with zero extra APK or per-episode storage overhead.
- **Accurate Transcript Ad Labels**:
  - Updated collapsed ad chips in the Transcript tab (`HushCastPlayerOverlays`) to show `displayLabel` (e.g. `Suggested Ad`, `Sponsor: <Brand>`, `Show Promo`, `Intro / Outro`) instead of hardcoding `Sponsor skipped` for every detected span.

---

# HushCast v1.0.17-hushcast

## What's New
- **Unified Seekbar Context & Skip/Undo Pill**:
  - Merged the **Skip** and **Undo** controls into the rounded `AnnotatedPlaybackSlider` context pill above the seekbar, eliminating the separate stacked popup above it and restoring the clean `ActiveChapterPill` below the album art.
  - Displays the specific detected ad/sponsor name (from `AdWindow.label`, e.g. `Sponsor: <Brand>`) in the rounded pill when available, falling back to the friendly section type (`Sponsor`, `Suggested Ad`, `Show Promo`, `Membership`, `Intro / Outro`) when generic.

---

# HushCast v1.0.16-hushcast

## What's New
- **New Episodes & Podcast List Improvements**:
  - Fixed **Mark as listened** in the New Episodes feed (`NewEpisodesRoute`, `PodcastEpisodePlayStateDao.upsertPlayed`) so swiping or tapping Mark all as listened properly persists played state and removes episodes when excluding listened.
  - Added **Exclude listened**, **In Progress**, and **Downloaded** filter chips to the New Episodes feed, fixed SQLite `NULL` handling in `PodcastEpisodesFilter.PLAYED`/`PAUSED` so **Exclude listened** works on podcast pages, and added **Mark all as listened** to the podcast detail overflow menu.
- **Per-Section Auto-Skip Settings**:
  - Added **Section types to auto-skip** toggles (**Sponsors / Ads**, **Show Promos**, **Membership Pitches**, **Intros / Outros**) in both HushCast Settings and the in-player HushCast sheet, wired into `AdSkipPlaybackObserver.autoSkipClasses`.
- **Integrated Skip / Undo Bar**:
  - Moved the **Skip** and **Undo** controls into the active context pill below the album art (`ActivePlaybackContextBar`) instead of a floating toast over the artwork.
- **Smoother Scroll Boundaries & Cleaner Queue**:
  - Disabled the secondary overscroll bounce-back across scrollable lists, pagers, and sheets via `LocalOverscrollFactory provides null` in `PodiumTheme`.
  - Removed the redundant **Move to top** and **Remove from queue** buttons from Up Next queue items so removal is a clean swipe gesture.

---

# HushCast v1.0.15-hushcast

## What's New
- **Retry Visibility & Cold-Start Resilience**:
  - Updated `PodcastEpisodeDownloadWork` so retryable desktop/cloud worker errors persist `"Retrying worker…"` and the error message in `AutoMuteDatabase` instead of leaving stale `ANALYZING` progress with `err=none` during WorkManager backoff.
  - Increased `DesktopWorkerClient` HTTPS request timeouts from 35s to 60s so Modal serverless GPU cold starts do not prematurely time out `submitJob` or `healthCheck`.
  - Added `HushCastDesktop` milestone logging across `PodcastEpisodeDownloadWorker` and `PodcastEpisodeDownloadWork` so diagnostics (`HushCastPerf & Crashes`) capture worker attempts, job IDs, and cancellation reasons.

---

# HushCast v1.0.14-hushcast

## What's New
- **Reliable Monotonic Processing & Retry Recovery**:
  - Fixed desktop/cloud offload retries (`PodcastEpisodeDownloadWork` / `PodcastEpisodeDownloadWorker`) so retries pass `force = false` and re-attach to the already in-flight or cached job instead of restarting transcription from 0%.
  - Serialized desktop status writes and clamped notification progress monotonically (`EpisodeAnalysisWorker`, `PodcastEpisodeDownloadWorker`, and `AutoMuteAnalyzer` chunked transcription) so progress never jumps backwards.
  - Added proper `CancellationException` / `stopReason` handling in `PodcastEpisodeDownloadWorker` and used `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` on Android 15+ for AI analysis downloads.
  - Increased `DesktopWorkerClient` queued-job wait timeout to 25 minutes and `fetchBundle` timeout to 30s, and skipped redundant HTTP re-downloads when analyzing an already-downloaded episode on-device.

---

# HushCast v1.0.13-hushcast

## What's New
- **Upgraded HushCast Action Bar Sheet**:
  - Added a prominent **Refresh AI Analysis** / **Analyze with HushCast AI** action inside the HushCast sheet in `MediaPlayerBottomSheet`.
  - Fixed **Cancel processing** in the HushCast sheet so it cancels both `EpisodeAnalysisWorker` and `PodcastEpisodeDownloadWorker` and immediately resets the episode's `ANALYZING` status in `AutoMuteDatabase`.
  - Organized all HushCast controls into clean Material 3 Expressive cards: AI analysis status, **Mute words** + **Mute style** (`Silence`, `Bleep`, `Duck`), and **Sponsor skipping** (`Auto-Skip`, `Show Only`, `Off`), **Auto-skip confidence** (`High (80%+)`, `Balanced`, `All Ads`), and **Sponsor skip chime**.
- **Desktop / Cloud Analysis Live Reload Fix**:
  - Emitted `EpisodeAnalysisWorker.analysisCompletedEvents` after `DesktopWorkerClient.ingestBundleIntoDb` in `PodcastEpisodeDownloadWork` so active playback immediately loads newly ingested mute words, sponsor segments, and chapters.

---

# HushCast v1.0.12-hushcast

## What's New
- **Consolidated Queue Swipe Action**:
  - When an episode is already in the queue and both Primary and Secondary swipe actions on the same side are queue actions (`Play Next` / `Play Last`), the duplicate "Remove from queue" icon is consolidated into a single action.
- **Intelligent Playback Resumption**:
  - Added **Intelligent Playback Resumption** in **Settings → Playback**, automatically rewinding 3s / 10s / 15s when resuming after pauses of 30s+ / 5m+ / 1h+ in `PlaybackService`.
- **Player Action Bar Downloaded Icon & HushCast Consolidation**:
  - Replaced the trash can (`DeleteOutline`) icon on downloaded episodes in `MediaPlayerBottomSheet`'s player action bar with `DownloadForOffline` while keeping tap-to-remove functionality.
  - Consolidated the separate **HushCast** shield button and **Analyze with HushCast AI** action into the unified **HushCast** action and sheet.
- **Desktop / Modal Worker AI Speed & Consistency**:
  - Cached the `Qwen2.5-7B-Instruct` `Llama` instance in `chapters.py` across episodes and reduced `n_ctx` from `4096` to `2048` so the 4.7 GB model is not reloaded from disk on every episode and fits comfortably in T4 VRAM alongside Whisper and `Qwen3-Embedding-0.6B`.

---

# HushCast v1.0.11-hushcast

## What's New
- **Unified Monotonic Analysis Progress**:
  - Fixed mismatched and backwards-dropping progress percentages between the Live Activity notification (`DownloadTracker`) and episode/Up Next badges (`AutoMuteDatabase`) in `PodcastEpisodeDownloadWork` by reporting a single monotonic `unifiedPct` across desktop analysis (5–70%) and phone transfer (72–98%).
- **Home Page Cleanup**:
  - Removed the redundant "Up Next" card from the top of `HomeRoute` (the queue remains accessible from the miniplayer).
- **Row Swipe Actions Sheet Layout Fix**:
  - Redesigned `PocketCastsSwipeActionsSheet` in `SettingsPlaybackPane` with separate fixed-height **Swipe Right →** and **← Swipe Left** preview rows, compact icon+label action chips, and clean slot rows so badges never squish or wrap vertically.

---

# HushCast v1.0.10-hushcast

## What's New
- **Pocket Casts–Style Row Swipe Actions**:
  - Added a **Row swipe actions** configurator in **Settings → Playback** with a live episode row preview and independent primary/secondary selectors for both Swipe Right and Swipe Left (`Play Next`, `Play Last`, `Download`, `Mark Played`, `None`), wired into `NewEpisodesRoute` and `PodcastDetailView`.
- **Higher-Contrast Material 3 Expressive Play Buttons**:
  - Upgraded play/pause buttons in `FloatingMediaPlayer`, `MediaPlayerBottomSheet`, and `PodcastEpisodeListItem` with high-contrast `primary`/`onPrimary` and `primaryContainer` colors alongside M3E shape-morphing.
- **Smoother Page Entry Frame Rate**:
  - Removed the initial `fadeIn` delay and reduced offscreen alpha/scale compositing overhead on forward navigation in `Navigation` and `Main` while keeping native predictive back intact.
- **In-Place List Refresh & Zero Empty Flash**:
  - Cached `NewEpisodesViewModel` with `cachedIn(viewModelScope)` and scoped `SubscriptionsViewModel` and `NewEpisodesViewModel` to the activity so **Home** and **New Episodes** stay populated, preserve scroll position, and refresh in place without flashing empty.
  - Pre-seeded `PodcastDetailView` subscription state from the cached subscriptions list so the subscribed status never flashes on open.
- **Home Badges (Count & Dot) Fix**:
  - Added live `unplayedCountsFlow` in `PodcastSubscriptionDao` and wired it into `HomeRoute`, `SubscriptionCard`, and `FolderCard` so toggling **Count** and **Dot** in the Home options sheet immediately updates podcast tiles, folders, and list rows.
- **Word-Mute Bleep Fix**:
  - Switching **Action Mode** (including **Bleep**) in `SettingsHushCastPane` now updates existing mute rules and pre-analyzed mute segments in `AutoMuteDatabase` and refreshes the active `AutoMuteAudioProcessor` immediately, with a crisper bleep amplitude.
- **Auto-Skip Confidence Thresholds & Ad-Skip Toast**:
  - Added **Auto-Skip When** (`High (80%+)`, `Balanced`, `All Ads`) via `AdAutoSkipScope` in `AdSkipPlaybackObserver`, `SettingsRepository`, and `SettingsHushCastPane`.
  - Added `ActiveAdSkipToastBanner` in the player overlay toast section to offer a one-tap **Skip Ad** button whenever playback enters a suggested or non-auto-skipped confirmed ad.

---

# HushCast v1.0.9-hushcast

## What's New
- **Auto-Skip & Playback Reliability**:
  - Fixed `AdSkipPlaybackObserver.checkPosition` and `PlaybackService` `PlayerMessage` scheduling with lead-tolerance and explicit media item indexing so auto-skip never misses sponsor boundaries due to clock skew or item transitions.
  - Fixed ad-skip Undo so bypassing an ad persists before seeking back (`bypassAd`).
  - Fixed chapter skips leaking across episodes by clearing and observing per-episode chapter skips directly in `PlaybackService`.
  - Fixed live observation of `wordMutingEnabled` and `adPolicyMode` in `PlaybackService`, plus valid `defaultActionMode` and live processor updates for transcript mutes.
  - Preserved resume position when moving an interrupted episode into Up Next, and saved finished episodes on auto-advance.
- **Native Predictive Back Rewrite**:
  - Rewrote `Navigation` `predictivePopTransitionSpec` with `swipeEdge`-aware horizontal motion and `LinearEasing` 1:1 finger tracking, and removed the competing `PredictiveBackHandler` in `Main.kt` so `NavigableListDetailPaneScaffold` drives native Material 3 predictive back smoothly.
- **AI Engine & Setup**:
  - Pinned `ad_tagger.onnx` to commit `5f3b525` (`11,867,080` bytes) and added word-mute-only fallback when optional ad models are missing.
  - Improved Whisper chunk overlap deduplication (midpoint split + skipping pure-overlap trailing chunks) and audio-thread-safe mute window updates (`AtomicReference`).
  - Reset `isHighConfidence = 0` on pre-upgrade ad rows in `MIGRATION_2_3` so old boilerplate/promos don't auto-skip.
  - Wired "Where to process" (`This phone` / `My computer` / `Off`) and `ModelDownloadWorker` into the HushCast setup sheet and "Not Set Up" dialog, and prevented infinite worker retries on permanent errors.
- **UI, Downloads, Backup & Desktop Worker**:
  - Rendered the Unsubscribe confirmation dialog in `PodcastDetailView`, added miniplayer clearance on the multi-select bar, and displayed localized `"Favorites"` in Library.
  - Synced `MediaPlayerBottomSheet` chapter pill with the pager, allowed downward drags to dismiss the sheet, fixed live countdown on the playback strip, and added **End of chapter** / **End of episode** presets to the Sleep Timer.
  - Added staging + SQLite `quick_check` & `user_version` validation before deleting live databases during restore, excluded secrets from backups/`backup_rules.xml`, and handled backup export errors safely.
  - Fixed `PodcastEpisodeDownloadWork` `NonCancellable` cancel cleanup, `KEEP` work policy for in-flight downloads, `enqueueAnalyzeNow` for local files, live download bytes in Downloads, and opening the queue from Home's Up Next strip.
  - Fixed desktop worker `ad_tagger.onnx` setup path, bumped `PIPELINE_VERSION = 4`, extracted real 100 ms acoustic frames for `ad_tagger.onnx`, added 6-hour decoded audio cap, safe thread cancellation, and audio-only cache-clear handling.

---

# HushCast v1.0.8-hushcast

## What's New
- **Predictive Back Preview Fix**:
  - Updated [`Navigation`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/navigation/Navigation.kt) `popTransitionSpec` and `predictivePopTransitionSpec` with `targetContentZIndex = -1f`, `slideInHorizontally`, and high-opacity `fadeIn(initialAlpha = 0.85f)` so the previous destination renders underneath during back swipes instead of fading over a black screen.
  - Updated [`Main`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/Main.kt) `DetailPane` to drive `scaffoldNavigator.seekBack(behavior, fraction)` during predictive back so the underlying list pane is revealed cleanly.
- **Sponsor Skip Chime Fix**:
  - Updated [`HushCastEngineBridge`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/background/HushCastEngineBridge.kt) to route the skip chime and custom skip sounds over `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` with higher gain and a longer buffer drain so it is clearly audible over headphones/Bluetooth, and added a **Test chime** button in [`SettingsHushCastPane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsHushCastPane.kt).
- **More Color Themes & Dark Surface Styles**:
  - Added **Dim**, **AMOLED Black**, **Midnight**, **Warm**, and **Forest** dark surface styles plus 8 accent color palettes (`Purple`, `Indigo`, `Ocean`, `Teal`, `Emerald`, `Amber`, `Coral`, `Rose`) in [`Theme.kt`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/theme/Theme.kt) and [`SettingsAppearancePane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsAppearancePane.kt).
- **Material 3 Expressive Miniplayer**:
  - Upgraded [`FloatingMediaPlayer`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/component/media/FloatingMediaPlayer.kt) with shape-morphing Play/Pause `FilledIconToggleButton`, tonal squircle skip buttons with press bounce & rotational spring kicks, animated artwork transitions, and `LinearWavyProgressIndicator`.
- **Precision Podcast Scrubbing & Sponsor Spot Callout**:
  - Upgraded [`AnnotatedPlaybackSlider`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) with vertical-drag variable scrub speed (`1x` → `½x` → `⅕x` → `1/10x`), haptic ticks when crossing sponsor boundaries, a highlighted active sponsor track/needle, and a live floating callout pill with a zoomed ±90s mini-timeline.

---

# HushCast v1.0.7-hushcast

## What's New
- **Desktop / Cloud Analysis Queue Hang Fix**:
  - Updated [`server.py`](https://github.com/emkay5771/HushCast/blob/main/desktop-worker/hushcast_worker/server.py) to mark orphaned non-terminal jobs loaded from disk on worker startup (and in `GET /v1/jobs/{job_id}` if no background task is running) as `FAILED` instead of leaving them stuck in `QUEUED` indefinitely after a container restart or scale-down.
  - Added a 5-minute `QUEUED` stall timeout in [`DesktopWorkerClient.pollJobUntilReady`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/desktop/DesktopWorkerClient.kt) so stalled queue jobs fail cleanly rather than hanging at `1%` Live Activity / `10%` Diagnostics for 30 minutes.
  - Separated `"Queued on worker…"` from `"Starting analysis…"` in [`PodcastEpisodeDownloadWork`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/background/work/PodcastEpisodeDownloadWork.kt) for clearer live progress status.

---

# HushCast v1.0.6-hushcast

## What's New
- **Miniplayer Redesign & Blocked Content Markers**:
  - Updated [`FloatingMediaPlayer`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/component/media/FloatingMediaPlayer.kt) to an uncluttered Pocket Casts-style layout with Material 3 Expressive styling: rounded-square artwork on the left, centered Skip Back / Play-Pause / Skip Forward controls, and a queue count pill on the right.
  - Added ad-skip and word-mute marker overlays directly on the miniplayer's bottom progress bar.
- **System Player Speed Value**:
  - Updated [`PlaybackService`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/background/PlaybackService.kt) to render the actual playback speed value (`0.5x`–`3.0x`) on the Android system media player button and refresh it live whenever speed changes.

---

# HushCast v1.0.5-hushcast

## What's New
- **Player Top Bar & Tab Pager Fix**:
  - Switched the player top bar from fixed-width `PrimaryTabRow` to `PrimaryScrollableTabRow(edgePadding = 0.dp, minTabWidth = 0.dp)` so tab labels (`Player`, `Transcript`, `Chapters`, `Notes`) never truncate.
  - Fixed the `HorizontalPager` two-way `LaunchedEffect` race that cancelled `animateScrollToPage` mid-swipe and left pages stuck between tabs, and switched `CHAPTERS`/`NOTES`/`TRANSCRIPT` full-screen containers inside the pager to `Modifier.fillMaxSize()`.

---

# HushCast v1.0.4-hushcast

## What's New
- **Predictive Back Gestures Across All Pages**:
  - Removed the unconditional `BackHandler` in [`SettingsPane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/SettingsPane.kt) so `NavigableListDetailPaneScaffold` drives native predictive back animations on all Settings sub-panes.
  - Added `scaleOut` + `fadeOut` to `popTransitionSpec` and `predictivePopTransitionSpec` in [`Navigation`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/navigation/Navigation.kt) for Material 3 predictive back transitions across all routes.
  - Added a `PredictiveBackHandler` in [`Main`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/Main.kt) `DetailPane` when `backStack.size > 1` so `NavDisplay` in the background list pane doesn't intercept predictive back gestures from open podcast/episode/settings detail panes.
- **Auto-Created Chapter Timestamp Fix (Desktop & Cloud Worker)**:
  - Fixed [`generate_chapters_and_summary`](https://github.com/emkay5771/HushCast/blob/main/desktop-worker/hushcast_worker/chapters.py) so chapter timestamps are grounded in the embedding-based topic-shift boundaries from actual transcript segment timestamps rather than asking the 1.5B chat LLM to convert `[MM:SS]` into seconds (which previously compressed 50+ minute episodes into `0:00–6:15` and produced duplicate chapters).
  - The LLM now writes only the episode summary and distinct per-chapter titles for each grounded chapter boundary, with automatic TF-IDF + disambiguation fallback.

---

# HushCast v1.0.3-hushcast

## What's New
- **Database Migration Fix**:
  - Fixed Room migration validation crash on `podcastEpisode` by adding the matching `@Entity` indices (`origin`, `pubDate` and `pubDate`) and bumping the schema to v18 with `MIGRATION_17_18`.
- **Player & Queue Improvements**:
  - Marking an episode as played now removes it from the **Up Next** queue and stops/advances playback if it is currently playing.
  - Fixed toast/banner overlays (like Undo Ad Skip and streaming notices) in the player so they float cleanly without pushing the play buttons into a scrolling area.
  - Replaced the player top tab bar with a compact `PrimaryTabRow` + `HorizontalPager` for smooth single-phase side-to-side swiping and reduced whitespace.
  - Added swipe-up gesture in the full-screen player to open the **Up Next** queue.
  - Updated remaining time to show speed- and skip-adjusted time in parentheses (e.g., `-24:10 (-16:07)`), configurable in **Settings → Playback → Remaining time display**.
- **Playback Effects & Miniplayer**:
  - Upgraded the speed sheet to **Playback Effects** with **Trim silence**, **Volume boost**, speed `-`/`+` stepper, and preset chips.
  - Redesigned the miniplayer to a Pocket Casts-style layout with rounded-square artwork, maximized title/subtitle space, Play/Pause + Queue buttons, and a bottom progress bar.
- **Audio Effects & Transcript Contrast**:
  - Added **Bleep** and **Custom** sound effect options for word auto-muting, plus an optional **Sponsor skip indicator sound** (chime or custom audio) in **Settings → HushCast**.
  - Improved dark-mode contrast for spoken and muted words in the synced transcript view.
- **Swipe Actions & Analysis Reliability**:
  - Upgraded episode list swipe actions to support Pocket Casts-style half-swipe 2-option trays and full-swipe primary triggers.
  - Fixed `autoDownloadIfEnabled` and `PodcastEpisodeDownloadWorker` (`ExistingWorkPolicy.REPLACE` on manual/reprocess triggers) so unanalyzed episodes auto-send for analysis reliably.

---

# HushCast v1.0.2-hushcast

## What's New
- **Consistent CI App Signing**:
  - Restored `app/ci-debug.keystore` so all CI APK builds share the same signing certificate (matching `v0.1.x` builds) and future updates install in-place without requiring uninstalls.
  - Note: If updating from `v1.0.0-hushcast` or `v1.0.1-hushcast` (which were signed with ephemeral runner keys), back up your data in **Settings → Backup**, uninstall once, install `v1.0.2-hushcast`, and restore.

---

# HushCast v1.0.1-hushcast

## What's New
- **Player Action Bar & Overflow**:
  - Made the player action bar customizable (up to 4 shortcuts + pinned **More**) with a Pocket Casts-style **Rearrange Actions** sheet (`SettingsRepository.behavior.playerToolbarActions`).
  - Removed the status and Downloaded/Streaming chips from the player header (the 4-state HushCast shield in the action bar now serves as the status indicator) and added **Download Episode / Remove Downloaded File** to the overflow menu (and customizable action bar).
- **Analysis Pipeline & Live Activity**:
  - Increased Cloud/Modal HTTPS read timeout from 35s to 90s in [`DesktopWorkerClient`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/desktop/DesktopWorkerClient.kt) so cold-starting GPU containers don't time out during job submission.
  - Skipped doomed on-device analysis fallback when local models aren't downloaded, and updated Live Activity progress labels to clear, plain-English messages.
  - Clarified on-device model messaging in [`SettingsHushCastPane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsHushCastPane.kt) when Cloud/Desktop Worker is active.
- **Backup, Restore, Permissions & Up Next**:
  - Fixed the `SQLiteDatabase: attempt to re-open an already-closed object` crash in [`RestoreRoute`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/restore/RestoreRoute.kt) by removing premature `db.close()` on screen entry and cancelling WorkManager jobs before replacing DB files.
  - Extended backup & restore in [`DatabaseManager`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/manager/DatabaseManager.kt) to include portable `HushCastPrefs` AI/Worker configuration (including re-encrypted worker tokens) and playback state alongside `podiumDB`, `AutoMuteDatabase`, and DataStore settings.
  - Added startup `POST_NOTIFICATIONS` permission request on Android 13+ in [`AppActivity`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/AppActivity.kt).
  - Updated [`MediaPlayerViewModel`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) so adding an episode to Up Next when nothing is playing loads it as the first item and opens the player sheet.

---

# HushCast v1.0.0-hushcast

## What's New
- **HushCast AI & Audio Engine (`F1–F10`, `E1–E19`, `W1–W12`)**:
  - Added [`ModelDownloadWorker`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/worker/ModelDownloadWorker.kt) and [`RuleReevaluationWorker`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/worker/RuleReevaluationWorker.kt) for resilient background model downloads and instant mute-rule re-evaluation across already-processed episodes.
  - Added [`AutoMuteAudioProcessor`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/dsp/AutoMuteAudioProcessor.kt) improvements (soft-ducking envelopes, 16-bit & float PCM, `HARD_SILENCE` / `SOFT_DUCK` / `BEEP` / `SKIP` modes, and `AudioFocus` ducking).
  - Hardened [`DesktopWorkerClient`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/desktop/DesktopWorkerClient.kt) and [`desktop-worker`](https://github.com/emkay5771/HushCast/blob/main/desktop-worker/hushcast_worker/server.py) with SSRF validation, SHA-256 verification, job persistence & cancellation, pairing token auth, and ad-tagger / pitch feature parity.
  - Removed the bundled `ad_tagger.onnx` asset from the APK in favor of on-demand [`ModelManager`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/main/java/app/hushcast/automute/engine/ModelManager.kt) downloads (`minSdk` aligned to `34`).
- **Player, Playback & Chapters (`P1–P23`, `UI5`)**:
  - Unified the player experience in [`MediaPlayerBottomSheet`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) with a 4-state HushCast shield sheet, 3-step setup guide, streaming notice banner, one-tap word-mute scope dialog (`This episode` vs `All episodes`), and live chapter/transcript tracking (`500ms` / `100ms`).
  - Added [`ChapterSkipDao`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/api/db/dao/ChapterSkipDao.kt) & [`ChapterSkipModel`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/api/db/model/ChapterSkipModel.kt) (AppDatabase v17) for persistent per-episode chapter skipping.
  - Added configurable Headset Next/Previous button actions, `PLAY_NEXT` swipe action, and seekbar drag-hold (`2000ms` timeout / `0.02f` settle threshold).
- **Downloads, Sync & Discovery (`D1–D17`, `V1–V13`)**:
  - Added [`DownloadTracker`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/api/download/DownloadTracker.kt) and reliable download/re-analysis flows across episode rows and detail views.
  - Added periodic sync interval scheduling in [`SettingsSynchronizationPane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsSynchronizationPane.kt) and notification permission handling in [`SettingsBackgroundActivityPane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsBackgroundActivityPane.kt).
  - Kept PodcastIndex search and RSS URL entry available when Apple Podcasts API is disabled, with debounced search (`250ms`) and friendly error/retry states in [`AddPodcastView`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/view/model/AddPodcastView.kt).
- **Settings, Navigation, Localization & Reliability (`U1–U11`, `L1–L8`, `R1–R6`)**:
  - Converted `DetailPaneKey` and all 9 `Settings*Key` classes to `@Parcelize` for full process-death state restoration.
  - Wired [`SettingsHushCastPane`](https://github.com/emkay5771/HushCast/blob/main/app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsHushCastPane.kt) directly to `SettingsRepository.hushCast`, masked worker tokens, saved credentials on focus loss, and gated `SettingsDebugKey` behind debug/7-tap unlock.
  - Completed all missing string translations across `de`, `fr`, `it`, `es`, and `tr` and enforced `MissingTranslation` as a lint error.
  - Added Room migration unit tests ([`AutoMuteMigrationTest`](https://github.com/emkay5771/HushCast/blob/main/core/automute-engine/src/test/java/app/hushcast/automute/storage/AutoMuteMigrationTest.kt), [`AppDatabaseMigrationTest`](https://github.com/emkay5771/HushCast/blob/main/app/src/test/java/app/podiumpodcasts/podium/api/db/AppDatabaseMigrationTest.kt)) and removed dead code.

---

# HushCast v0.1.31-hushcast

## What's New
- **Full-Screen Player Sheet & Queue Badge**:
  - Updated [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) to open full-screen by default with vertically centered content, larger artwork (`260.dp`), and refined spacing.
  - Added a theme-matched queue count badge (capped at `99+`) on the queue button.
- **Reliable Seek & Queue Restoration**:
  - Updated [`MediaPlayerViewModel.seek`](app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) to clamp seek positions to `0..currentDuration` and immediately update UI progress state.
  - Updated [`MediaPlayerViewModel.restoreQueueIfNeeded`](app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) and [`registerMediaController`](app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) to re-restore an emptied queue while guarding against concurrent restoration runs.
- **Transcript Opens at Active Segment**:
  - Updated [`SyncedTranscriptCard`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) to initialize `rememberLazyListState` at `activeRowIndex` so the transcript opens directly on the currently playing segment.

---

# HushCast v0.1.30-hushcast

## What's New
- **Skip-Aware Fast-Forward & Rewind**:
  - Added [`SkipAwareSeekCalculator`](core/automute-engine/src/main/java/app/hushcast/automute/dsp/SkipAwareSeekCalculator.kt) and wrapped `ExoPlayer` in a `ForwardingPlayer` inside [`PlaybackService`](app/src/main/java/app/podiumpodcasts/podium/background/PlaybackService.kt) so fast-forward and rewind count unskipped content and jump cleanly over active auto-skip ad spans and skipped chapters (including from notifications and lockscreen controls).
- **Smooth Live Chapter Tracking**:
  - Added [`MediaPlayerViewModel.rememberLivePositionMs`](app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) so the active chapter pill, Chapters tab, and skipped-chapter auto-skip track playback position continuously instead of jumping only on seek/skip events.
  - Sorted chapters chronologically in [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) and [`pipeline.py`](desktop-worker/hushcast_worker/pipeline.py).
- **Apple Podcasts-Style Transcript**:
  - Updated [`SyncedTranscriptCard`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) to hide per-line timestamps and group segments into logical paragraphs split by speaker turns, natural pauses, and sentence boundaries, while keeping word-level tap-to-seek, long-press-to-mute, and live spoken-word highlighting.

---

# HushCast v0.1.29-hushcast

## What's New
- **Word Muting & Ad-Skip Playback Loading Fix**:
  - Fixed [`PlaybackService`](app/src/main/java/app/podiumpodcasts/podium/background/PlaybackService.kt) so mute windows and ad-skip spans are always loaded on `onMediaItemTransition`, `onMediaMetadataChanged`, download/desktop offload completion, and on-device [`EpisodeAnalysisWorker`](core/automute-engine/src/main/java/app/hushcast/automute/worker/EpisodeAnalysisWorker.kt) completion (previously they were only loaded in `onMediaMetadataChanged`, causing already-queued/playing episodes to miss mute & ad windows).
  - Added `HushCastPerf` diagnostics logging when playback windows are loaded or cleared.

---

# HushCast v0.1.28-hushcast

## What's New
- **Cloud & Desktop Worker Reliability Fixes**:
  - Increased `https://` cloud worker timeouts to 35s across `healthCheck`, `submitJob`, `getJob`, `fetchBundle`, `listReadyEpisodes`, and `semanticSearch` in [`DesktopWorkerClient`](core/automute-engine/src/main/java/app/hushcast/automute/desktop/DesktopWorkerClient.kt) so Modal cold starts don't time out mid-request.
  - Updated **Scan QR** and **Test** in [`SettingsHushCastPane`](app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsHushCastPane.kt) to verify the Pairing Token against an authenticated endpoint (`/v1/episodes`) instead of only unauthenticated `/v1/health`, preventing false "Connected" status when the token is invalid.
  - Preserved desktop/cloud offload failure reasons in `EpisodeAnalysisStatusEntity.errorMessage` inside [`PodcastEpisodeDownloadWork`](app/src/main/java/app/podiumpodcasts/podium/background/work/PodcastEpisodeDownloadWork.kt) and added `HushCastDesktop` logs to **Export Logs** diagnostics.
  - Fixed Modal worker `LocalMlBackend` import, `ad_tagger.onnx` fallback download, and embedded chapter extraction in [`modal_app.py`](desktop-worker/modal_app.py) and [`pipeline.py`](desktop-worker/hushcast_worker/pipeline.py).

---

# HushCast v0.1.27-hushcast

## What's New
- **Pocket Casts-Style Episode Rows**:
  - Redesigned [`PodcastEpisodeListItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/episode/PodcastEpisodeListItem.kt) across podcast pages, playlists, New Releases, Continue Playing, Downloads, and History to match Pocket Casts: crisp square artwork, uppercase date added + AI badge, 2-line title, Up Next & green download status icons, compact duration (`1h 13m` / `24m left`), thin progress bar, and a circular outlined Play/Pause button.
- **App-Wide Smoothness & Performance Optimizations**:
  - Eliminated main-thread HTML parsing (`AnnotatedString.fromHtml`) and nested `LazyRow` subcomposition from every episode row during fast scroll, and cached parsed HTML with `remember(...)` in [`PodcastListItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/PodcastListItem.kt), [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt), [`PodcastDetailView`](app/src/main/java/app/podiumpodcasts/podium/ui/view/model/PodcastDetailView.kt), and [`PodcastEpisodeDetailView`](app/src/main/java/app/podiumpodcasts/podium/ui/view/model/PodcastEpisodeDetailView.kt).
  - Downsampled queue & playlist artwork thumbnails (`targetSizePx = 128`) in [`MediaItemListItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/MediaItemListItem.kt), [`ListLibraryItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/list/ListLibraryItem.kt), and [`PodcastListItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/PodcastListItem.kt) to cut memory & decode jitter.
  - Stopped the 100ms progress polling loop in [`MediaPlayerViewModel.getProgressState`](app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) when playback is paused.

---

# HushCast v0.1.26-hushcast

## What's New
- **Pocket Casts-Style Chapters & Chapter Skipping**:
  - Added a dedicated **Chapters** tab in [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) and [`ChaptersTabContent`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) with **Previous / Next Chapter** navigation, live active chapter progress bar, tap-to-seek, and per-episode **chapter skip toggles** (persisted and auto-skipped during playback).
  - Clearly distinguishes **`Podcast Chapters`** (extracted from embedded ID3/MP4 chapters via `ffprobe` in [`pipeline.py`](desktop-worker/hushcast_worker/pipeline.py)) from **`Auto-Created Chapters (HushCast AI)`**, with [`DesktopChapterDto`](core/automute-engine/src/main/java/app/hushcast/automute/desktop/DesktopWorkerModels.kt) tracking `source` (`"podcast"` vs `"auto"`).
  - Tapping the active chapter pill in the Player tab now jumps directly to the **Chapters** tab.
- **Cleaner AI Summaries & Chapter Titles (`PIPELINE_VERSION = 3`)**:
  - Improved [`pipeline.py`](desktop-worker/hushcast_worker/pipeline.py) extractive summaries to split on speaker turns, filter out Q&A/banter lines, and favor early episode intro sentences, and improved TF-IDF chapter naming to filter generic filler/number words into concise 1–2 topic titles. Bumped `PIPELINE_VERSION = 3` in [`models.py`](desktop-worker/hushcast_worker/models.py) so cached v2 bundles auto-upgrade.
- **Home & Discover Cleanup (Pocket Casts Style)**:
  - Removed the floating `+` Add button from [`HomeRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/home/HomeRoute.kt) (keeping folder creation in the top bar) and moved **Add Podcast** (`+`) into [`DiscoverRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/discover/DiscoverRoute.kt).
  - Removed the floating **"Powered by Apple Podcasts"** chip from [`DiscoverRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/discover/DiscoverRoute.kt) and [`DiscoverSearch`](app/src/main/java/app/podiumpodcasts/podium/ui/route/discover/DiscoverSearch.kt).
- **Reliable Transcript Live Follow & Folder Interior Reordering**:
  - Fixed [`SyncedTranscriptCard`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) so `activeRowIndex` holds the most recent segment during brief inter-segment pauses instead of flickering to `-1` and dropping live follow.
  - Updated [`FolderBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/route/home/FolderDialogs.kt) edit mode to switch to a clear vertical list with `▲`/`▼` reorder buttons and remove action.
- **Configurable Episode Swipe Actions**:
  - Added **Episode swipe right action** and **Episode swipe left action** settings in [`SettingsPlaybackPane`](app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsPlaybackPane.kt) (`EpisodeSwipeAction`: **Add to Up Next (Play Next)**, **Add to Up Next (Play Last)**, **Hear Later**, **Mark Played / Unplayed**, **Download / Delete**, or **None**), wired into [`PodcastDetailView`](app/src/main/java/app/podiumpodcasts/podium/ui/view/model/PodcastDetailView.kt) and [`NewEpisodesRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/content/NewEpisodesRoute.kt).

---

# HushCast v0.1.25-hushcast

## What's New
- **Smooth Player & Throttled Download Notifications**:
  - Removed the 60fps seekbar `Animatable` feedback loop, restored natural scroll fling, and isolated header fullscreen drag gestures in [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt).
  - Throttled `onProgressUpdate` in [`PodcastEpisodeDownloadWork`](app/src/main/java/app/podiumpodcasts/podium/background/work/PodcastEpisodeDownloadWork.kt) to eliminate 242/s `NotificationManager` rate-limit shedding and main-thread frame drops.
  - Fixed Android 16/17 Live Activity doubled status lines in [`PodcastEpisodeDownloadWorker`](app/src/main/java/app/podiumpodcasts/podium/background/worker/PodcastEpisodeDownloadWorker.kt).
- **Cleaner Player Chips & Unified "Analyze w/ HushCast AI"**:
  - Consolidated source (`Downloaded`/`Streaming`), readiness badge, and active chapter pill into a single compact `FlowRow` in [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) and [`EpisodeReadinessBadge`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt).
  - Unified `triggerAnalyzeNow()` to open [`HushCastAnalyzeDialog`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) so Desktop/Cloud Worker refresh and On-Device analysis work consistently for both downloaded and streaming episodes.
- **Synced Transcript "Catch Up" & Word Highlighting**:
  - Fixed `followLive` in [`SyncedTranscriptCard`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt) using `DragInteraction.Start` so tapping **Catch up to live** reliably resumes following active lines, with active spoken word highlighting.
- **Folder Reordering**:
  - Added left/right item reordering inside [`FolderBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/route/home/FolderDialogs.kt) edit mode and an **Organize Folders** dialog in [`HomeRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/home/HomeRoute.kt).
- **Customizable Bottom Bar & Startup Screen / Playlist**:
  - Added **Startup screen** (Podcasts, Discover, Library, New Releases, In Progress, Downloads, History, or any Playlist) and **Bottom navigation bar** customization (choose 2–5 tabs and reorder) in [`SettingsAppearancePane`](app/src/main/java/app/podiumpodcasts/podium/ui/route/settings/pane/SettingsAppearancePane.kt), plus one-tap startup pinning in [`ListRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/list/ListRoute.kt).

---

# HushCast v0.1.24-hushcast

## What's New
- **Desktop Worker CLI Progress Bars & Detailed Logs**:
  - [`server.py`](desktop-worker/hushcast_worker/server.py) and [`pipeline.py`](desktop-worker/hushcast_worker/pipeline.py) now display live terminal progress bars (`[████████████░░░░░░░░] 60%`) and timestamped stage logs (download MB, audio duration, Whisper realtime speed, speaker count, per-segment embedding progress, ad spans, and chapter/summary mode).
- **Automatic Pipeline Cache Upgrade (`PIPELINE_VERSION = 2`)**:
  - [`models.py`](desktop-worker/hushcast_worker/models.py) and [`storage.py`](desktop-worker/hushcast_worker/storage.py) now track `pipelineVersion` so cached v1 bundles automatically re-run and upgrade without manual cache clearing.
- **Completed Download & Analysis Notification**:
  - [`PodcastEpisodeDownloadWorker`](app/src/main/java/app/podiumpodcasts/podium/background/worker/PodcastEpisodeDownloadWorker.kt) now posts a persistent, tappable completion notification when an episode finishes downloading/analyzing instead of silently disappearing.
- **Media Notification Shade Sync on Episode Switch**:
  - [`MediaPlayerViewModel`](app/src/main/java/app/podiumpodcasts/podium/ui/vm/MediaPlayerViewModel.kt) now calls `prepare()` before `play()`, and [`PlaybackService`](app/src/main/java/app/podiumpodcasts/podium/background/PlaybackService.kt) triggers `onUpdateNotification` on `onMediaItemTransition` so the notification shade updates immediately when switching episodes.

---

# HushCast v0.1.23-hushcast

## What's New
- **Force Reprocessing on Desktop / Cloud Refresh**:
  - Added `force` flag to [`DesktopJobRequest`](core/automute-engine/src/main/java/app/hushcast/automute/desktop/DesktopWorkerModels.kt) and `POST /v1/jobs` in [`server.py`](desktop-worker/hushcast_worker/server.py) so **Refresh on Desktop / Cloud** (from episode rows, detail menu, or player sheet) bypasses URL and SHA-256 cache fast-paths and re-runs full analysis.
  - [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) now also reloads summary and chapters whenever `stageLabel` updates.
- **Cleaner Extractive Summary Fallback**:
  - Updated [`pipeline.py`](desktop-worker/hushcast_worker/pipeline.py) to reconstruct complete sentences across contiguous non-ad segments (with filler/promo filtering) instead of joining mid-sentence Whisper fragments when no local Instruct GGUF is installed.

---

# HushCast v0.1.22-hushcast

## What's New
- **Sync Delete With Currently Playing Episode & AI Status**:
  - [`DownloadManager.deleteEpisodeDownload`](app/src/main/java/app/podiumpodcasts/podium/manager/DownloadManager.kt) now also clears the episode's `AutoMuteDatabase` analysis status, transcript, insights, and ad/mute segments via [`clearAnalysisForEpisode`](core/automute-engine/src/main/java/app/hushcast/automute/storage/AutoMuteDatabase.kt).
  - [`MediaPlayerBottomSheet`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) now observes `podcastEpisodes().get(episodeId)` reactively so deleting a download updates the player's download & AI Ready status immediately.
- **Distinct Processing Type Badges**:
  - Episode cards in [`PodcastEpisodeListItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/episode/PodcastEpisodeListItem.kt) now display **`Desktop AI`**, **`Cloud AI`**, **`On-Device AI`**, **`Word-Mute`**, or **`Audio Only`** so you can tell at a glance how each episode was processed.
- **Desktop Worker Cache Clearing**:
  - Added `DELETE /v1/cache` (and `?audio_only=true`) plus `--clear-cache` / `--clear-audio-cache` CLI flags in `desktop-worker`.

---

# HushCast v0.1.21-hushcast

## What's New
- **Distinct Listened vs Downloaded Icons**:
  - Updated [`PodcastEpisodeDownloadButton`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/episode/PodcastEpisodeDownloadButton.kt) to use `DownloadForOffline` (circle with down arrow) for downloaded episodes and `Schedule` for queued downloads, and updated [`PodcastEpisodeMarkAsPlayedButton`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/episode/PodcastEpisodeMarkAsPlayedButton.kt) to show `DoneAll` when played, eliminating the visual overlap between `Check` and `DownloadDone`.
- **Swipe-to-Toggle Played / Unplayed**:
  - Swiping an already-played episode a second time in [`PodcastDetailView`](app/src/main/java/app/podiumpodcasts/podium/ui/view/model/PodcastDetailView.kt) now marks it as unplayed again (with a `RemoveDone` swipe icon and **Undo** snackbar).
- **24-Hour Cache Lag Before Deleting Played Downloads**:
  - [`DeletePlayedDownloadsWork`](app/src/main/java/app/podiumpodcasts/podium/background/work/DeletePlayedDownloadsWork.kt) now waits **24 hours** after an episode is marked played (`p.lastUpdate`) before removing its cached audio, protecting desktop-analyzed and recently finished episodes from immediate deletion.

---

# HushCast v0.1.20-hushcast

## What's New
- **Smooth Initial Podcast Grid Load**:
  - Deferred startup subscription sync and `PodcastGridOrganization` disk initialization off the initial composition frame, replaced `BoxWithConstraints` subcomposition in [`HomeRoute`](app/src/main/java/app/podiumpodcasts/podium/ui/route/home/HomeRoute.kt) with `LocalConfiguration.current.screenWidthDp`, and added `targetSizePx = 128` to subscription list thumbnails.
- **Unified HushCast Analysis Dialog**:
  - Standardized all HushCast analysis prompts across episode rows, media rows, and the player sheet via [`HushCastAnalyzeDialog`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt), following the consistent option tree: Desktop/Cloud Worker connected → On-Device models ready → Not set up yet.
- **Obvious "Refresh on Desktop / Cloud" Action**:
  - Added a direct refresh icon button on analyzed episode rows in [`PodcastEpisodeListItem`](app/src/main/java/app/podiumpodcasts/podium/ui/component/model/episode/PodcastEpisodeListItem.kt) when a Desktop/Cloud worker is connected, and renamed the detail overflow menu action in [`PodcastEpisodeDetailView`](app/src/main/java/app/podiumpodcasts/podium/ui/view/model/PodcastEpisodeDetailView.kt) to **Refresh on Desktop / Cloud**.

---

# HushCast v0.1.19-hushcast

## What's New
- **QR Code Pairing (Desktop & Cloud Workers)**:
  - `hushcast-worker` now prints a scannable `hushcast://pair?url=...&token=...` QR code directly in the terminal on startup (and `modal_app.py::pair_qr` generates one for Modal cloud deployments).
  - Added an in-app **Scan QR** button in **Settings → HushCast → Desktop Companion**, plus `hushcast://pair` deep-link support so scanning with your phone's Camera app also pairs automatically.
- **Modal Serverless GPU Support**:
  - Added [`desktop-worker/modal_app.py`](desktop-worker/modal_app.py) for one-command serverless GPU deployment on Modal's free tier, while keeping true on-device Android and local Mac/LAN worker modes completely intact.

---

# HushCast v0.1.18-hushcast

## What's New
- **Performance & Fluidity**:
  - Pre-grouped transcript words per segment in a single $O(S+W)$ pass and binary-searched mute windows in [`SyncedTranscriptCard`](app/src/main/java/app/podiumpodcasts/podium/ui/component/media/HushCastPlayerOverlays.kt), eliminating playback scroll jank.
  - Isolated [`PlayerSeekBarSection`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) and [`LiveSyncedTranscriptCard`](app/src/main/java/app/podiumpodcasts/podium/ui/dialog/bottomsheet/media/MediaPlayerBottomSheet.kt) so 60fps seekbar animations and playback ticks no longer recompose the entire player sheet.
  - Offloaded transcript blob decompression and chapter JSON parsing to `Dispatchers.Default`.
  - Replaced per-row Room `Flow` subscriptions in episode and queue lists with a single shared `statusMapFlow` on `AutoMuteDatabase`.
- **UI Simplification**:
  - Added a clickable **Active Chapter** pill in the player header that jumps directly to the Transcript & Chapters tab.
  - Organized **Settings → HushCast** with section headers and auto-collapsed local model cards when Desktop Companion is enabled.

---

# HushCast v0.1.17-hushcast

## What's New
- **Desktop-Class AI Upgrades (Companion Worker)**:
  - **Larger Whisper Model Auto-Selection**: Automatically selects the best available Whisper model in `~/.hushcast-worker/models/` (e.g. `ggml-large-v3-turbo.bin`, `medium.en`, `small.en`, or `HUSHCAST_WHISPER_MODEL`) and reports it in worker health & Settings.
  - **Acoustic Speaker Diarization**: Clusters speakers per turn on 16 kHz PCM, feeds speaker changes into `ad_tagger.onnx`, and displays color-coded `Speaker 1`, `Speaker 2`, … badges in the transcript.
  - **Auto-Generated Chapters & Episode TL;DR**: Generates an episode summary and tap-to-seek chapters using Qwen3 embeddings (or a local Instruct GGUF if present), persisted in Room (`AutoMuteDatabase` v2) and shown in a collapsible **AI Summary & Chapters** card.
  - **Cross-Episode Semantic Search (`POST /v1/search`)**: Saves per-episode Qwen3 sentence embeddings on the desktop worker so you can search concepts across all analyzed episodes from **Settings → HushCast**.
- **Transcript & Live Activity Polish**:
  - **Smart Sponsor Grouping**: Merges adjacent ad chunks into collapsible sponsor cards with auto-extracted sponsor names (e.g., `Sponsor: Jira`) and expandable transcript lines.
  - **Live Activity for Desktop Offload**: Desktop downloads and GPU analysis now update `EpisodeAnalysisStatusEntity` in real time, matching on-device Live Activity progress.
  - **Catch Up to Live**: Scrolling away from the active speaker in the transcript reveals a floating **Catch up to live** button to snap back to auto-scroll.

---

# HushCast v0.1.16-hushcast

## What's New
- **Desktop Companion Worker (Phase 1 — Mac / GPU Offload)**:
  - Added [`desktop-worker/`](desktop-worker/README.md), a headless LAN companion server (`FastAPI` + `zeroconf` mDNS `_hushcast._tcp.`) that downloads episodes on your Mac/desktop, runs `whisper.cpp` + `Qwen3-Embedding-0.6B` + `ad_tagger.onnx`, and serves both the exact audio file and pre-computed `WordIndexBlob` + `AdSegment`s keyed by `fileSha256` (guaranteeing 100% DAI timestamp alignment).
  - Added **Desktop Companion (Mac / GPU)** settings in **Settings → HushCast** with Wi-Fi mDNS discovery, pairing token, and connection testing.
- **Per-Episode Download Method Picker & Smooth Replacement**:
  - Added a download options menu on the episode detail page to choose **Download via Desktop Companion**, **Download & Analyze On-Device**, or **Download Audio Only (Skip Analysis)**.
  - Added **Replace with Desktop Version** in the episode menu and `DownloadManagementBottomSheet` — downloads to a `.part` file, verifies `fileSha256` before swapping, cancels any pending on-device analysis, and preserves your existing download if replacement fails.
- **Auto-Download Analysis Control**:
  - Added an **Auto-Download Analysis** setting (`Auto`, `On-Device`, `Skip`) in **Settings → HushCast** controlling how newly auto-downloaded episodes are processed.

---

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
