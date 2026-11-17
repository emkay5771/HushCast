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
