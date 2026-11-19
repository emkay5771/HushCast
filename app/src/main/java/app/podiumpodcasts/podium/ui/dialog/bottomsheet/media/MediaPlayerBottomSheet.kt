package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.dsp.SkipAwareSeekCalculator
import app.hushcast.automute.engine.DownloadAnalysisMethod
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.engine.Word
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.podiumpodcasts.podium.PlayerTabConfig
import app.podiumpodcasts.podium.PlayerToolbarAction
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.chapters.ResolvedChapter
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.ui.component.common.SwitchableDynamicMaterialExpressiveTheme
import app.podiumpodcasts.podium.ui.component.media.CastingBypassNoticeBanner
import app.podiumpodcasts.podium.ui.component.media.OneTapUndoAdSkipBanner
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.ui.vm.SourceTypeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MediaPlayerTab(val label: String) {
    PLAYER("Playing"),
    NOTES("Details"),
    CHAPTERS("Chapters")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPlayerBottomSheet(
    onOpenEpisode: (origin: String, id: String) -> Unit,
    onOpenPodcast: ((origin: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val compactLayout = !windowSizeClass.isHeightAtLeastBreakpoint(500)

    val settingsRepository = LocalSettingsRepository.current
    val appDb = LocalDatabase.current
    val vm = viewModel<MediaPlayerViewModel>()
    val playerVm = viewModel<PlayerScreenViewModel>()

    val episodeId = vm.metadataEpisodeId ?: ""
    LaunchedEffect(episodeId, vm.currentDuration) {
        playerVm.bindEpisode(episodeId, vm.currentDuration)
    }

    LaunchedEffect(vm.isPlayerVisible) {
        if (!vm.isPlayerVisible) onDismiss()
    }

    val enableArtworkColors by settingsRepository.appearance.enableArtworkColors.collectAsStateWithLifecycle(initialValue = true)
    val remainingTimeDisplay by settingsRepository.behavior.remainingTimeDisplay.collectAsStateWithLifecycle(
        initialValue = app.podiumpodcasts.podium.RemainingTimeDisplayMode.BOTH
    )
    val adjustRemainingForSpeed = remainingTimeDisplay != app.podiumpodcasts.podium.RemainingTimeDisplayMode.RAW
    val setupCompleted by settingsRepository.hushCast.setupCompleted.collectAsStateWithLifecycle(initialValue = false)
    val devMode by settingsRepository.hushCast.devMode.collectAsStateWithLifecycle(initialValue = false)
    val wordMutingGlobal by settingsRepository.hushCast.wordMutingEnabled.collectAsStateWithLifecycle(initialValue = true)
    val adPolicyGlobal by settingsRepository.hushCast.adPolicyMode.collectAsStateWithLifecycle(initialValue = AdPolicyMode.AUTO_SKIP)
    val blurBackground by settingsRepository.appearance.playerBlurBackground.collectAsStateWithLifecycle(initialValue = false)
    val blurSheets by settingsRepository.appearance.playerBlurSheets.collectAsStateWithLifecycle(initialValue = true)
    val toolbarVisibleCount by settingsRepository.behavior.playerToolbarVisibleCount.collectAsStateWithLifecycle(initialValue = 4)

    val currentEpisodeBundle by playerVm.episodeBundle.collectAsStateWithLifecycle()
    val analysisStatus by playerVm.analysisStatus.collectAsStateWithLifecycle()
    val muteEntities by playerVm.muteSegments.collectAsStateWithLifecycle()
    val adEntities by playerVm.adSegments.collectAsStateWithLifecycle()
    val resolvedChapters by playerVm.resolvedChapters.collectAsStateWithLifecycle()
    val skippedChapterStarts by playerVm.skippedChapterStarts.collectAsStateWithLifecycle()
    val isFavorite by playerVm.isFavorite.collectAsStateWithLifecycle()
    val transcriptState by playerVm.transcriptState.collectAsStateWithLifecycle()
    val episodeSummary by playerVm.episodeSummary.collectAsStateWithLifecycle()
    val wordIndexBlob by playerVm.wordIndexBlob.collectAsStateWithLifecycle()

    val pendingUndo by HushCastEngineBridge.pendingUndoEvent.collectAsStateWithLifecycle()
    val isCastingExternal by HushCastEngineBridge.isCastingExternal.collectAsStateWithLifecycle()
    val sleepTimerMinutes = vm.getSleepTimerState().value

    val muteWindows = remember(muteEntities) { muteEntities.map { it.toDomain() } }
    val adWindows = remember(adEntities) { adEntities.map { it.toDomain() } }

    val shieldBadge = remember(analysisStatus?.badge) {
        runCatching { analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) } }.getOrNull()
            ?: ReadinessBadge.NOT_PROCESSED
    }
    val isProcessed = shieldBadge == ReadinessBadge.READY || shieldBadge == ReadinessBadge.WORD_MUTE_READY
    val protectionActive = wordMutingGlobal || adPolicyGlobal == AdPolicyMode.AUTO_SKIP

    // S5: File.exists() runs off the main thread.
    val isDownloaded by androidx.compose.runtime.produceState(
        initialValue = vm.mediaSourceType is SourceTypeState.Downloaded,
        episodeId,
        currentEpisodeBundle?.episode?.id,
        currentEpisodeBundle?.download?.state
    ) {
        val episode = currentEpisodeBundle?.episode?.takeIf { it.id == episodeId }
        value = if (episode == null) false else withContext(Dispatchers.IO) {
            episode.craftDownloadFile(context).exists()
        }
    }

    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) {
            delay(6000L)
            HushCastEngineBridge.clearPendingUndo()
        }
    }

    val playerTabsConfig by settingsRepository.behavior.playerTabsConfig.collectAsStateWithLifecycle(
        initialValue = listOf(
            PlayerTabConfig("PLAYER", true),
            PlayerTabConfig("NOTES", true),
            PlayerTabConfig("CHAPTERS", true)
        )
    )

    val visibleTabs = remember(playerTabsConfig, resolvedChapters.isNotEmpty()) {
        val mapped = playerTabsConfig
            .filter { it.visible }
            .mapNotNull { cfg ->
                when (cfg.id) {
                    "PLAYER" -> MediaPlayerTab.PLAYER
                    "NOTES" -> MediaPlayerTab.NOTES
                    "CHAPTERS" -> if (resolvedChapters.isNotEmpty()) MediaPlayerTab.CHAPTERS else null
                    else -> null
                }
            }
        val withPlayer = if (MediaPlayerTab.PLAYER in mapped) mapped else listOf(MediaPlayerTab.PLAYER) + mapped
        withPlayer
    }

    // S6: the tab survives configuration changes.
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(MediaPlayerTab.PLAYER) }
    var chaptersScrollTargetIndex by remember { mutableStateOf<Int?>(null) }
    var showTranscriptOverlay by remember { mutableStateOf(false) }

    // M6: a new episode opens on the Playing tab.
    var lastEpisodeId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(episodeId) }
    LaunchedEffect(episodeId) {
        if (episodeId.isBlank()) return@LaunchedEffect
        if (lastEpisodeId.isNotBlank() && episodeId != lastEpisodeId) {
            selectedTab = MediaPlayerTab.PLAYER
            chaptersScrollTargetIndex = null
        }
        lastEpisodeId = episodeId
    }

    val pagerState = rememberPagerState(
        initialPage = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
    ) { visibleTabs.size }

    LaunchedEffect(pagerState.settledPage, visibleTabs) {
        val tab = visibleTabs.getOrNull(pagerState.settledPage)
        if (tab != null && tab != selectedTab) {
            selectedTab = tab
        }
    }
    LaunchedEffect(selectedTab, visibleTabs) {
        val targetIdx = visibleTabs.indexOf(selectedTab)
        if (targetIdx >= 0 && pagerState.currentPage != targetIdx && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(targetIdx)
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab != MediaPlayerTab.PLAYER && showTranscriptOverlay) {
            showTranscriptOverlay = false
        }
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showCustomizeSheet by remember { mutableStateOf(false) }
    var showHushCastSheet by remember { mutableStateOf(false) }
    var showSleepTimerBottomSheet by remember { mutableStateOf(false) }
    var showSpeedBottomSheet by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    var showNotDownloadedDialog by remember { mutableStateOf(false) }
    var showDeleteDownloadConfirm by remember { mutableStateOf(false) }
    var transcriptPromptState by remember { mutableStateOf<TranscriptLoadState?>(null) }
    var pendingWordMute by remember { mutableStateOf<Word?>(null) }
    var downloadBannerDismissedForEp by remember(episodeId) { mutableStateOf(false) }

    val availablePlayerActions = remember {
        listOf(
            PlayerToolbarAction.HUSHCAST,
            PlayerToolbarAction.SPEED,
            PlayerToolbarAction.SLEEP,
            PlayerToolbarAction.TRANSCRIPT,
            PlayerToolbarAction.FAVORITE,
            PlayerToolbarAction.DOWNLOAD,
            PlayerToolbarAction.GO_TO_PODCAST,
            PlayerToolbarAction.MARK_PLAYED
        )
    }

    val configuredToolbarActions by settingsRepository.behavior.playerToolbarActions.collectAsStateWithLifecycle(
        initialValue = listOf(
            PlayerToolbarAction.HUSHCAST,
            PlayerToolbarAction.SPEED,
            PlayerToolbarAction.SLEEP,
            PlayerToolbarAction.TRANSCRIPT
        )
    )

    val orderedAllActions = remember(configuredToolbarActions) {
        // Actions the user never placed come after theirs, Transcript first (T1).
        PlayerToolbarOrder.ordered(configuredToolbarActions, availablePlayerActions)
    }
    val shortcutActions = remember(orderedAllActions, toolbarVisibleCount) { orderedAllActions.take(toolbarVisibleCount) }
    val overflowActions = remember(orderedAllActions, toolbarVisibleCount) { orderedAllActions.drop(toolbarVisibleCount) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun dismissGracefully(after: () -> Unit = {}) = scope.launch {
        sheetState.hide()
        after()
        onDismiss()
    }

    fun triggerAnalyzeNow() {
        if (episodeId.isBlank()) return
        scope.launch {
            // Same routing everywhere: worker if set up, else on the phone (replacing any queued job).
            val outcome = app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.start(
                context = context,
                db = appDb,
                episodeId = episodeId,
                actionMode = HushCastEngineBridge.defaultActionMode
            )
            when (outcome) {
                app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.Outcome.NEEDS_DOWNLOAD -> showNotDownloadedDialog = true
                app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.Outcome.WAITING_FOR_CHARGER ->
                    android.widget.Toast.makeText(
                        context,
                        app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.message(outcome),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                else -> Unit
            }
        }
    }

    fun triggerDownloadNow() {
        if (episodeId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            DownloadManager.downloadEpisode(
                context = context,
                db = appDb,
                episodeId = episodeId
            )
        }
    }

    fun performToolbarAction(action: PlayerToolbarAction) {
        when (action) {
            PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.ANALYZE -> showHushCastSheet = true
            PlayerToolbarAction.SPEED -> showSpeedBottomSheet = true
            PlayerToolbarAction.SLEEP -> showSleepTimerBottomSheet = true
            PlayerToolbarAction.TRANSCRIPT -> {
                when (val ts = transcriptState) {
                    is TranscriptLoadState.Ready -> showTranscriptOverlay = true
                    is TranscriptLoadState.NotProcessed,
                    is TranscriptLoadState.Transcribing -> {
                        transcriptPromptState = ts
                    }
                    else -> showTranscriptOverlay = true
                }
            }
            PlayerToolbarAction.FAVORITE -> {
                // M10
                haptic.performHapticFeedback(
                    if (isFavorite) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                )
                playerVm.toggleFavorite(isFavorite)
            }
            PlayerToolbarAction.DOWNLOAD -> {
                if (isDownloaded) showDeleteDownloadConfirm = true else triggerDownloadNow()
            }
            PlayerToolbarAction.GO_TO_PODCAST -> {
                val origin = vm.metadataOrigin
                if (onOpenPodcast != null && origin != null) {
                    dismissGracefully { onOpenPodcast(origin) }
                }
            }
            PlayerToolbarAction.EPISODE_DETAILS -> {
                val origin = vm.metadataOrigin
                val id = vm.metadataEpisodeId
                if (origin != null && id != null) {
                    dismissGracefully { onOpenEpisode(origin, id) }
                }
            }
            PlayerToolbarAction.MARK_PLAYED -> {
                if (episodeId.isNotBlank()) {
                    scope.launch { appDb.podcastEpisodePlayStates().savePlayed(episodeId, true) }
                    vm.removeEpisodeFromPlaybackAndQueue(episodeId)
                }
            }
        }
    }

    val livePosState = vm.rememberLivePositionMsState(if (showTranscriptOverlay) 100L else 500L)
    val durationMs = vm.currentDuration.coerceAtLeast(0L)

    val currentChapterIndex by remember(resolvedChapters) {
        derivedStateOf {
            val pos = livePosState.value
            if (resolvedChapters.isEmpty()) -1
            else resolvedChapters.indexOfFirst { pos in it.startMs until it.endMs }
        }
    }
    val currentChapter = resolvedChapters.getOrNull(currentChapterIndex)

    // M6: one shared, cached palette (also used by the mini player and Up Next), cross-faded on change.
    PlayerArtworkTheme(
        enable = enableArtworkColors,
        seedColor = vm.metadataImageSeedColor
    ) {
        val blurredArtwork = app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.rememberBlurredArtwork(
            artworkUri = vm.mediaMetadata?.artworkUri?.toString(),
            enabled = blurBackground
        )
        val backdropAlpha by animateFloatAsState(
            targetValue = if (blurBackground && blurredArtwork != null) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "playerBackdropAlpha"
        )
        ModalBottomSheet(
            sheetState = sheetState,
            sheetMaxWidth = androidx.compose.ui.unit.Dp.Infinity,
            onDismissRequest = onDismiss
        ) {
            val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
            val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val transcriptTransition = updateTransition(targetState = showTranscriptOverlay, label = "transcriptOverlay")

            val chromeAlpha by transcriptTransition.animateFloat(
                transitionSpec = { fastEffects },
                label = "chromeAlpha"
            ) { open -> if (open) 0f else 1f }

            val transportScale by transcriptTransition.animateFloat(
                transitionSpec = { spatialSpec },
                label = "transportScale"
            ) { open -> if (open) 0.80f else 1f }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // R19: blurred artwork behind the player, blending into the sheet at the top.
                    .playerBackdrop(
                        image = blurredArtwork,
                        scrimColor = androidx.compose.material3.BottomSheetDefaults.ContainerColor,
                        alpha = backdropAlpha
                    )
            ) {
                // Top row: Collapse, Tabs, Up Next.  T2: slides away (instead of popping) while the
                // transcript is open.
                AnimatedVisibility(
                    visible = !showTranscriptOverlay,
                    enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = chromeAlpha },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(48.dp),
                            onClick = { dismissGracefully() }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Collapse player"
                            )
                        }

                        if (visibleTabs.size > 1) {
                            val selectedIdx = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
                            SecondaryTabRow(
                                selectedTabIndex = selectedIdx,
                                modifier = Modifier.weight(1f),
                                containerColor = Color.Transparent,
                                divider = {},
                                indicator = {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(selectedIdx, matchContentSize = false)
                                    )
                                }
                            ) {
                                visibleTabs.forEach { tab ->
                                    Tab(
                                        selected = selectedTab == tab,
                                        onClick = { selectedTab = tab },
                                        text = {
                                            Text(
                                                text = tab.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }

                        IconButton(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(48.dp),
                            onClick = { showQueueBottomSheet = true }
                        ) {
                            BadgedBox(
                                badge = {
                                    if (vm.queue.isNotEmpty()) {
                                        Badge {
                                            Text(vm.queue.size.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = "Up Next (${vm.queue.size})"
                                )
                            }
                        }
                    }
                }

                // Main content area: Pager (or TranscriptOverlay when open on Playing page)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !showTranscriptOverlay,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        when (visibleTabs.getOrNull(pageIndex) ?: MediaPlayerTab.PLAYER) {
                            MediaPlayerTab.PLAYER -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .swipeUpForUpNext(enabled = !showTranscriptOverlay) {
                                            showQueueBottomSheet = true
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Top area: Artwork + Titles + Skip pill (or TranscriptOverlay when open)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 20.dp)
                                                .graphicsLayer { alpha = chromeAlpha },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            androidx.compose.foundation.layout.BoxWithConstraints(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxWidth()
                                            ) {
                                            // R7: the artwork takes the room the text below leaves, instead of a fixed 300 dp.
                                            val reservedForText = if (compactLayout) 110.dp else 200.dp
                                            val fittedArt = minOf(maxWidth, maxHeight - reservedForText)
                                                .coerceIn(if (compactLayout) 96.dp else 160.dp, 420.dp)
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                AnimatedVisibility(visible = isCastingExternal) {
                                                    CastingBypassNoticeBanner(modifier = Modifier.padding(bottom = 8.dp))
                                                }

                                                // Artwork with chapter image cross-fade & link button (C3) + swipe-up for Up Next (M4)
                                                Box(
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    val artSize = fittedArt
                                                    val artUri = currentChapter?.imageUrl?.takeIf { it.isNotBlank() }
                                                        ?: vm.mediaMetadata?.artworkUri?.toString()
                                                    val isActivelyBuffering =
                                                        vm.playbackState == androidx.media3.common.Player.STATE_BUFFERING && vm.playWhenReady
                                                    var showBufferingOverlay by remember { mutableStateOf(false) }
                                                    LaunchedEffect(isActivelyBuffering) {
                                                        if (isActivelyBuffering) {
                                                            kotlinx.coroutines.delay(300L)
                                                            showBufferingOverlay = true
                                                        } else {
                                                            showBufferingOverlay = false
                                                        }
                                                    }

                                                    Box(
                                                        modifier = Modifier.size(artSize),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        AnimatedContent(
                                                            targetState = artUri,
                                                            label = "playerArtwork"
                                                        ) { uri ->
                                                            ShimmerAsyncImage(
                                                                modifier = Modifier
                                                                    .size(artSize)
                                                                    .clip(RoundedCornerShape(if (compactLayout) 16.dp else 28.dp)),
                                                                model = uri,
                                                                contentDescription = null,
                                                                contentScale = ContentScale.Crop
                                                            )
                                                        }
                                                        if (showBufferingOverlay) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .matchParentSize()
                                                                    .clip(RoundedCornerShape(if (compactLayout) 16.dp else 28.dp))
                                                                    .background(Color.Black.copy(alpha = 0.35f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                ContainedLoadingIndicator(Modifier.size(artSize / 3))
                                                            }
                                                        }
                                                    }

                                                    val chapterUrl = currentChapter?.linkUrl
                                                    if (!chapterUrl.isNullOrBlank()) {
                                                        FilledTonalIconButton(
                                                            onClick = { runCatching { uriHandler.openUri(chapterUrl) } },
                                                            modifier = Modifier
                                                                .align(Alignment.BottomEnd)
                                                                .padding(8.dp)
                                                                .size(48.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                                contentDescription = "Open chapter link"
                                                            )
                                                        }
                                                    }

                                                    // R7: the skip / undo pill sits on the artwork's bottom edge instead of
                                                    // holding an empty 48 dp slot above the seek bar.
                                                    PlayerSkipPill(
                                                        adWindows = adWindows,
                                                        positionMsState = livePosState,
                                                        pendingUndo = pendingUndo,
                                                        onUndo = { undo ->
                                                            HushCastEngineBridge.adSkipObserver.bypassAd(undo.adWindow.startMs)
                                                            vm.seek(undo.adWindow.startMs)
                                                            HushCastEngineBridge.clearPendingUndo()
                                                        },
                                                        onDismissUndo = { HushCastEngineBridge.clearPendingUndo() },
                                                        onSkip = { ad ->
                                                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                                            vm.seek(ad.endMs)
                                                        },
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .padding(bottom = 12.dp)
                                                    )
                                                }

                                                vm.playerError?.let { errMsg ->
                                                    Spacer(Modifier.height(8.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.errorContainer,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = errMsg,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            TextButton(onClick = { vm.retry() }) {
                                                                Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                                                            }
                                                        }
                                                    }
                                                }

                                                Spacer(Modifier.height(if (compactLayout) 10.dp else 20.dp))

                                                // Episode title
                                                Text(
                                                    text = vm.mediaMetadata?.title?.toString() ?: "",
                                                    style = if (compactLayout) Typography.titleMediumEmphasized else Typography.headlineSmall,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    // R7: centered like the podcast name and chapter row below it.
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )

                                                Spacer(Modifier.height(6.dp))

                                                // Podcast name + single-line streaming notice (I11)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center,
                                                    modifier = Modifier
                                                        .clickable(enabled = onOpenPodcast != null && vm.metadataOrigin != null) {
                                                            val origin = vm.metadataOrigin
                                                            if (onOpenPodcast != null && origin != null) {
                                                                dismissGracefully { onOpenPodcast(origin) }
                                                            }
                                                        }
                                                ) {
                                                    Text(
                                                        text = vm.mediaMetadata?.artist?.toString() ?: "",
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                // Item 14: hidden once the file is on the phone (the stream
                                                // item is swapped for the file a moment later).
                                                if (vm.mediaSourceType is SourceTypeState.Streaming && !downloadBannerDismissedForEp && !isDownloaded) {
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            text = "Streaming",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        // Item 14: a 32 dp pill inside a 48 dp touch target, announced as a button.
                                                        // The 48 dp box gives the pill's extended touch area room.
                                                        Box(
                                                            modifier = Modifier.heightIn(min = 48.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                        Surface(
                                                            shape = RoundedCornerShape(50),
                                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier
                                                                .heightIn(min = 32.dp)
                                                                .clip(RoundedCornerShape(50))
                                                                .clickable(
                                                                    role = androidx.compose.ui.semantics.Role.Button,
                                                                    onClickLabel = "Download episode"
                                                                ) {
                                                                    triggerDownloadNow()
                                                                    downloadBannerDismissedForEp = true
                                                                }
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .heightIn(min = 32.dp)
                                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Rounded.Download,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                                Text(
                                                                    text = "Download",
                                                                    style = MaterialTheme.typography.labelLarge
                                                                )
                                                            }
                                                        }
                                                        }
                                                    }
                                                }

                                                // C2: Chapter line ‹ Chapter title · 3 of 12 › + 28dp progress ring + time left
                                                if (currentChapter != null && resolvedChapters.isNotEmpty()) {
                                                    Spacer(Modifier.height(8.dp))
                                                    ChapterNavigationLine(
                                                        chapters = resolvedChapters,
                                                        currentChapter = currentChapter,
                                                        currentIndex = currentChapterIndex,
                                                        skippedStarts = skippedChapterStarts,
                                                        positionMsState = livePosState,
                                                        speed = vm.speed,
                                                        adjustForSpeed = adjustRemainingForSpeed,
                                                        onSeekToMs = { vm.seek(it) },
                                                        onOpenChaptersTab = {
                                                            if (MediaPlayerTab.CHAPTERS in visibleTabs) {
                                                                chaptersScrollTargetIndex = currentChapterIndex.takeIf { it >= 0 }
                                                                selectedTab = MediaPlayerTab.CHAPTERS
                                                            }
                                                        }
                                                    )
                                                }
                                            }

                                            }
                                        }

                                        // T2: TranscriptOverlay fills only the top area above the pinned seek bar & transport
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = showTranscriptOverlay,
                                            enter = slideInVertically(
                                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
                                            ) { it / 6 } + fadeIn(
                                                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
                                            ),
                                            exit = slideOutVertically(
                                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
                                            ) { it / 6 } + fadeOut(
                                                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
                                            )
                                        ) {
                                            // T3: which sponsor blocks will auto-skip, so they get "Play anyway".
                                            val autoSkipAdStarts = remember(adWindows, showTranscriptOverlay) {
                                                HushCastEngineBridge.adSkipObserver.getActiveAutoSkipRanges()
                                                    .map { it.startMs }
                                                    .toSet()
                                            }
                                            TranscriptOverlay(
                                                state = transcriptState,
                                                episodeTitle = vm.mediaMetadata?.title?.toString() ?: "",
                                                positionMsState = livePosState,
                                                adWindows = adWindows,
                                                autoSkipAdStarts = autoSkipAdStarts,
                                                onPlayAnyway = { ad ->
                                                    // Same as Undo: let this block play once, from its start.
                                                    HushCastEngineBridge.adSkipObserver.bypassAd(ad.startMs)
                                                    vm.seek(ad.startMs)
                                                    if (!vm.isPlaying) vm.play()
                                                },
                                                isPlaying = vm.isPlaying,
                                                muteWindows = muteWindows,
                                                onClose = { showTranscriptOverlay = false },
                                                onSeekToMs = { vm.seek(it) },
                                                onMuteWord = { word -> pendingWordMute = word },
                                                onShareTxt = { lines ->
                                                    playerVm.shareTranscriptTxt(
                                                        context = context,
                                                        episodeTitle = vm.mediaMetadata?.title?.toString() ?: "Episode",
                                                        lines = lines
                                                    )
                                                },
                                                onRetry = { playerVm.retryPublisherTranscript() },
                                                onProcessNow = {
                                                    showTranscriptOverlay = false
                                                    triggerAnalyzeNow()
                                                },
                                                onDownloadNow = {
                                                    showTranscriptOverlay = false
                                                    triggerDownloadNow()
                                                },
                                                bottomControlsPadding = 12.dp
                                            )
                                        }
                                    }

                                    // Seek bar & Transport (always pinned & clickable below TranscriptOverlay)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp)
                                            .graphicsLayer {
                                                scaleX = transportScale
                                                scaleY = transportScale
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        PlayerSeekBar(
                                            positionMsState = livePosState,
                                            durationMs = durationMs,
                                            playbackSpeed = vm.speed,
                                            adjustRemainingForSpeed = adjustRemainingForSpeed,
                                            muteWindows = muteWindows,
                                            adWindows = adWindows,
                                            onSeekToMs = { vm.seek(it) },
                                            onSkipAd = { ad -> vm.seek(ad.endMs) },
                                            compact = showTranscriptOverlay,
                                            chapters = resolvedChapters
                                        )

                                        Spacer(Modifier.height(if (showTranscriptOverlay) 4.dp else 10.dp))

                                        PlayerTransportControls(
                                            isPlaying = vm.isPlaying,
                                            seekBackMs = vm.seekBackIncrement,
                                            seekForwardMs = vm.seekForwardIncrement,
                                            onSeekBack = {
                                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                                val target = SkipAwareSeekCalculator.computeBackTargetMs(
                                                    fromMs = vm.livePositionNowMs(),
                                                    incrementMs = vm.seekBackIncrement,
                                                    skipRanges = HushCastEngineBridge.getActiveSkipRanges()
                                                )
                                                vm.seek(target)
                                            },
                                            onTogglePlay = {
                                                haptic.performHapticFeedback(
                                                    if (vm.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                                                )
                                                if (vm.isPlaying) vm.pause() else vm.play()
                                            },
                                            onSeekForward = {
                                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                                val target = SkipAwareSeekCalculator.computeForwardTargetMs(
                                                    fromMs = vm.livePositionNowMs(),
                                                    incrementMs = vm.seekForwardIncrement,
                                                    durationMs = vm.currentDuration,
                                                    skipRanges = HushCastEngineBridge.getActiveSkipRanges()
                                                )
                                                vm.seek(target)
                                            }
                                        )
                                    }

                                    Spacer(Modifier.height(10.dp))
                                }
                            }

                            MediaPlayerTab.NOTES -> {
                                val desc = currentEpisodeBundle?.episode?.description
                                    ?: vm.mediaMetadata?.description?.toString()
                                    ?: ""
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 24.dp, vertical = 12.dp)
                                ) {
                                    if (!episodeSummary.isNullOrBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 12.dp)
                                        ) {
                                            Column(Modifier.padding(14.dp)) {
                                                Text(
                                                    text = "AI summary",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = episodeSummary!!,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }

                                    SelectionContainer {
                                        Text(
                                            text = AnnotatedString.fromHtml(desc),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    Spacer(Modifier.height(16.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            val origin = vm.metadataOrigin
                                            val id = vm.metadataEpisodeId
                                            if (origin != null && id != null) {
                                                dismissGracefully { onOpenEpisode(origin, id) }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Open full episode page")
                                    }
                                    Spacer(Modifier.height(16.dp))
                                }
                            }

                            MediaPlayerTab.CHAPTERS -> {
                                // S6: off-screen (the pager keeps one neighbor composed), the tab
                                // doesn't read the position at all.
                                val chaptersPageActive = pagerState.currentPage == pageIndex ||
                                    pagerState.targetPage == pageIndex
                                PlayerChaptersTab(
                                    chapters = resolvedChapters,
                                    skippedPublishedStarts = skippedChapterStarts,
                                    positionMsState = livePosState,
                                    active = chaptersPageActive,
                                    onTargetConsumed = { chaptersScrollTargetIndex = null },
                                    onToggleSkip = { pubStart, skip ->
                                        playerVm.toggleSkippedChapter(pubStart, skip)
                                    },
                                    onSelectChapter = { ch, isCurrent ->
                                        if (isCurrent) {
                                            selectedTab = MediaPlayerTab.PLAYER
                                        } else {
                                            vm.seek(ch.startMs)
                                            if (!vm.isPlaying) vm.play()
                                        }
                                    },
                                    targetChapterIndex = chaptersScrollTargetIndex,
                                    contentBottomPadding = 16.dp
                                )
                            }
                        }
                    }
                }

                // M5: one bottom slot switching toolbar (Playing) <-> mini playback strip (other tabs).
                // It follows the tab being swiped to (targetPage), and keeps the taller of the two
                // heights, so switching never resizes the pager above it.  T2: it slides away while
                // the transcript is open instead of popping out.
                AnimatedVisibility(
                    visible = !showTranscriptOverlay,
                    enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
                ) {
                    val bottomSlotFadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    var slotMinHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    val targetIsPlaying = (visibleTabs.getOrNull(pagerState.targetPage) ?: MediaPlayerTab.PLAYER) ==
                        MediaPlayerTab.PLAYER
                    AnimatedContent(
                        targetState = targetIsPlaying,
                        transitionSpec = {
                            fadeIn(animationSpec = bottomSlotFadeSpec) togetherWith
                                fadeOut(animationSpec = bottomSlotFadeSpec)
                        },
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = with(density) { slotMinHeightPx.toDp() }),
                        label = "playerBottomSlot"
                    ) { isPlayingTab ->
                      Box(
                          modifier = Modifier.onSizeChanged { size ->
                              if (size.height > slotMinHeightPx) slotMinHeightPx = size.height
                          }
                      ) {
                        if (isPlayingTab) {
                          Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PlayerActionToolbar(
                                analysisPercent = if (shieldBadge == ReadinessBadge.ANALYZING) {
                                    (analysisStatus?.progressPercent ?: 0).coerceIn(0, 100)
                                } else {
                                    null
                                },
                                shortcutActions = shortcutActions,
                                overflowActions = overflowActions,
                                isProcessed = isProcessed,
                                protectionActive = protectionActive,
                                speed = vm.speed,
                                sleepTimerMinutes = sleepTimerMinutes,
                                isFavorite = isFavorite,
                                isDownloaded = isDownloaded,
                                transcriptState = transcriptState,
                                showMoreMenu = showMoreMenu,
                                onToggleMoreMenu = { showMoreMenu = it },
                                onPerformAction = ::performToolbarAction,
                                onOpenCustomize = {
                                    showMoreMenu = false
                                    showCustomizeSheet = true
                                }
                            )
                            UpNextGrip(onClick = { showQueueBottomSheet = true })
                          }
                        } else {
                            // Mini playback strip for Details / Chapters tabs
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedTab = MediaPlayerTab.PLAYER }
                                    ) {
                                        Text(
                                            text = vm.mediaMetadata?.title?.toString() ?: "",
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = currentChapter?.title ?: (vm.mediaMetadata?.artist?.toString() ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { vm.seekBack() },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Rounded.Replay10, contentDescription = "Seek back")
                                        }
                                        FilledIconToggleButton(
                                            checked = vm.isPlaying,
                                            onCheckedChange = {
                                                if (vm.isPlaying) vm.pause() else vm.play()
                                            },
                                            shapes = IconButtonDefaults.toggleableShapes(),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = if (vm.isPlaying) "Pause" else "Play"
                                            )
                                        }
                                        IconButton(
                                            onClick = { vm.seekForward() },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Rounded.Forward10, contentDescription = "Seek forward")
                                        }
                                    }
                                }
                            }
                        }
                      }
                    }
                }
            }
        }
    }

    // Bottom sheets & dialogs
    if (showSleepTimerBottomSheet) {
        MediaPlayerSleepTimerBottomSheet { showSleepTimerBottomSheet = false }
    }
    if (showSpeedBottomSheet) {
        MediaPlayerSpeedBottomSheet { showSpeedBottomSheet = false }
    }
    if (showQueueBottomSheet) {
        MediaPlayerQueueBottomSheet(
            onOpenEpisode = { origin, id -> dismissGracefully { onOpenEpisode(origin, id) } },
            onDismiss = { showQueueBottomSheet = false }
        )
    }
    if (showCustomizeSheet) {
        PlayerCustomizeSheet(
            tabsConfig = playerTabsConfig,
            onUpdateTabs = { updated ->
                scope.launch { settingsRepository.behavior.setPlayerTabsConfig(updated) }
            },
            actionsOrder = orderedAllActions,
            availableActions = availablePlayerActions,
            onUpdateActions = { updated ->
                scope.launch { settingsRepository.behavior.setPlayerToolbarActions(updated) }
            },
            onDismiss = { showCustomizeSheet = false }
        )
    }
    if (showHushCastSheet) {
        PlayerHushCastSheet(
            episodeId = episodeId,
            episodeTitle = vm.mediaMetadata?.title?.toString() ?: "",
            origin = vm.metadataOrigin ?: "",
            isDownloaded = isDownloaded,
            analysisStatus = analysisStatus,
            muteSegments = muteEntities,
            adSegments = adEntities,
            chapters = resolvedChapters,
            transcriptBlob = wordIndexBlob,
            settingsRepository = settingsRepository,
            devMode = devMode,
            onProcessNow = {
                showHushCastSheet = false
                triggerAnalyzeNow()
            },
            onCancelProcessing = {
                scope.launch { app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.cancel(context, episodeId) }
            },
            onDismiss = { showHushCastSheet = false }
        )
    }

    transcriptPromptState?.let { ts ->
        AlertDialog(
            onDismissRequest = { transcriptPromptState = null },
            title = {
                Text(
                    when (ts) {
                        is TranscriptLoadState.Transcribing -> "Transcribing… ${ts.progressPercent}%"
                        is TranscriptLoadState.NotProcessed -> if (ts.isDownloaded) "No transcript yet" else "Download to get a transcript"
                        else -> "Transcript"
                    }
                )
            },
            text = {
                Text(
                    when (ts) {
                        is TranscriptLoadState.Transcribing -> "HushCast is currently transcribing this episode."
                        is TranscriptLoadState.NotProcessed -> if (ts.isDownloaded) {
                            "Process this episode with HushCast to generate a synced transcript."
                        } else {
                            "Download this episode to generate a synced transcript."
                        }
                        else -> ""
                    }
                )
            },
            confirmButton = {
                when (ts) {
                    is TranscriptLoadState.NotProcessed -> {
                        Button(
                            onClick = {
                                transcriptPromptState = null
                                if (ts.isDownloaded) triggerAnalyzeNow() else triggerDownloadNow()
                            }
                        ) {
                            Text(if (ts.isDownloaded) "Process" else "Download")
                        }
                    }
                    else -> {
                        TextButton(onClick = { transcriptPromptState = null }) {
                            Text("OK")
                        }
                    }
                }
            },
            dismissButton = {
                if (ts is TranscriptLoadState.NotProcessed) {
                    TextButton(onClick = { transcriptPromptState = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showNotDownloadedDialog) {
        AlertDialog(
            onDismissRequest = { showNotDownloadedDialog = false },
            title = { Text("Download and process this episode?") },
            text = { Text("HushCast needs the episode on your phone to transcribe it and find ads.") },
            confirmButton = {
                Button(
                    onClick = {
                        showNotDownloadedDialog = false
                        scope.launch {
                            val method = if (HushCastPrefs.isWorkerActive(context)) {
                                DownloadAnalysisMethod.AUTO
                            } else {
                                DownloadAnalysisMethod.ON_DEVICE
                            }
                            DownloadManager.downloadEpisode(
                                context = context,
                                db = appDb,
                                episodeId = episodeId,
                                downloadMethod = method
                            )
                        }
                    }
                ) {
                    Text("Download & process")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotDownloadedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDownloadConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadConfirm = false },
            title = { Text("Remove download?") },
            text = { Text("Delete the downloaded audio file from this device?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDownloadConfirm = false
                        currentEpisodeBundle?.episode?.let { ep ->
                            scope.launch(Dispatchers.IO) {
                                ep.craftDownloadFile(context).delete()
                                appDb.podcastEpisodeDownloads().delete(ep.id)
                            }
                        }
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingWordMute?.let { word ->
        WordMuteDialog(
            word = word,
            muteSegments = muteEntities,
            onUnmuteSegments = { ids -> playerVm.setMuteSegmentsEnabled(ids, false) },
            onAddMuteWord = { clean -> playerVm.addMuteWordRule(clean, HushCastEngineBridge.defaultActionMode) },
            onDismiss = { pendingWordMute = null }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChapterNavigationLine(
    chapters: List<ResolvedChapter>,
    currentChapter: ResolvedChapter,
    currentIndex: Int,
    skippedStarts: Set<Long>,
    /** S4: read only in derived state (minutes left) and in the progress bar's draw lambda. */
    positionMsState: androidx.compose.runtime.State<Long>,
    speed: Float,
    adjustForSpeed: Boolean,
    onSeekToMs: (Long) -> Unit,
    onOpenChaptersTab: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val prevChapter = remember(chapters, currentIndex, skippedStarts) {
        chapters.take(currentIndex).lastOrNull { it.publishedStartMs !in skippedStarts }
    }
    val nextChapter = remember(chapters, currentIndex, skippedStarts) {
        chapters.drop(currentIndex + 1).firstOrNull { it.publishedStartMs !in skippedStarts }
    }

    val chapterDurMs = (currentChapter.endMs - currentChapter.startMs).coerceAtLeast(1L)
    val remMins by remember(currentChapter, speed, adjustForSpeed, positionMsState) {
        derivedStateOf {
            val positionMs = positionMsState.value
            val remainingWallMs = if (adjustForSpeed) {
                SkipAwareSeekCalculator.computeWallRemainingMs(
                    fromMs = positionMs,
                    targetEndMs = currentChapter.endMs,
                    speed = speed,
                    skipRanges = HushCastEngineBridge.getActiveSkipRanges()
                )
            } else {
                (currentChapter.endMs - positionMs).coerceAtLeast(0L)
            }
            ((remainingWallMs + 30_000L) / 60_000L).coerceAtLeast(1L)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // R7: both arrows in the same 40 dp tonal container (previously a bare arrow vs a ringed one).
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FilledTonalIconButton(
                enabled = prevChapter != null,
                onClick = {
                    if (prevChapter != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSeekToMs(prevChapter.startMs)
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous chapter")
            }
        }

        val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
        AnimatedContent(
            targetState = currentChapter,
            transitionSpec = {
                fadeIn(animationSpec = spatial) togetherWith fadeOut(animationSpec = spatial)
            },
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenChaptersTab),
            label = "chapterTitleSwitch"
        ) { ch ->
            val idx = chapters.indexOf(ch).coerceAtLeast(0)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ch.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${idx + 1} of ${chapters.size} · ${remMins}m left",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = {
                        // Evaluated when drawing, so position ticks don't recompose the row.
                        val remainingFraction = ((currentChapter.endMs - positionMsState.value).toFloat() /
                            chapterDurMs.toFloat()).coerceIn(0f, 1f)
                        1f - remainingFraction
                    },
                    modifier = Modifier
                        .width(96.dp)
                        .height(3.dp),
                    drawStopIndicator = {}
                )
            }
        }

        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            FilledTonalIconButton(
                enabled = nextChapter != null,
                onClick = {
                    if (nextChapter != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSeekToMs(nextChapter.startMs)
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next chapter")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    seekBackMs: Long,
    seekForwardMs: Long,
    onSeekBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekForward: () -> Unit
) {
    val backIcon = when (seekBackMs) {
        5000L -> Icons.Rounded.Replay5
        10000L -> Icons.Rounded.Replay10
        30000L -> Icons.Rounded.Replay30
        else -> Icons.Rounded.Replay
    }
    val forwardIcon = when (seekForwardMs) {
        5000L -> Icons.Rounded.Forward5
        10000L -> Icons.Rounded.Forward10
        30000L -> Icons.Rounded.Forward30
        else -> Icons.Rounded.FastForward
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onSeekBack,
            modifier = Modifier.size(60.dp)
        ) {
            Icon(
                imageVector = backIcon,
                contentDescription = "Seek back ${seekBackMs / 1000L} seconds",
                modifier = Modifier.size(30.dp)
            )
        }

        // R7: play/pause is an action, not a toggle, so it keeps the primary color in both states
        // (as a toggle, "paused" used the dim unchecked colors and looked disabled).
        FilledIconButton(
            onClick = onTogglePlay,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(width = 96.dp, height = 68.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(38.dp)
            )
        }

        FilledTonalIconButton(
            onClick = onSeekForward,
            modifier = Modifier.size(60.dp)
        ) {
            Icon(
                imageVector = forwardIcon,
                contentDescription = "Seek forward ${seekForwardMs / 1000L} seconds",
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

private fun Modifier.swipeUpForUpNext(
    enabled: Boolean,
    onSwipeUp: () -> Unit
): Modifier {
    if (!enabled) return this
    return this.pointerInput(Unit) {
        val thresholdPx = 64.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            var totalDy = 0f
            var totalDx = 0f
            var triggered = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                totalDy += (change.position.y - change.previousPosition.y)
                totalDx += (change.position.x - change.previousPosition.x)
                if (!triggered && totalDy < -thresholdPx &&
                    kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx) * 1.5f
                ) {
                    triggered = true
                    change.consume()
                    onSwipeUp()
                }
            }
        }
    }
}

private fun playerToolbarActionIcon(
    action: PlayerToolbarAction,
    isProcessed: Boolean,
    protectionActive: Boolean,
    isFavorite: Boolean,
    isDownloaded: Boolean
) = when (action) {
    PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.ANALYZE ->
        if (isProcessed && protectionActive) Icons.Rounded.Shield else Icons.Outlined.Shield
    PlayerToolbarAction.SPEED -> Icons.Filled.Speed
    PlayerToolbarAction.SLEEP -> Icons.Filled.Bedtime
    PlayerToolbarAction.TRANSCRIPT -> Icons.Rounded.Subtitles
    PlayerToolbarAction.FAVORITE -> if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder
    PlayerToolbarAction.DOWNLOAD -> if (isDownloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download
    PlayerToolbarAction.GO_TO_PODCAST -> Icons.Rounded.Podcasts
    PlayerToolbarAction.EPISODE_DETAILS -> Icons.Rounded.Info
    PlayerToolbarAction.MARK_PLAYED -> Icons.Rounded.Done
}

@Composable
private fun PlayerActionToolbar(
    /** Processing progress while the episode is being analyzed, drawn as a ring on the HushCast icon (R16). */
    analysisPercent: Int?,
    shortcutActions: List<PlayerToolbarAction>,
    isProcessed: Boolean,
    protectionActive: Boolean,
    speed: Float,
    sleepTimerMinutes: Int?,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    transcriptState: TranscriptLoadState,
    onToggleMoreMenu: (Boolean) -> Unit,
    onPerformAction: (PlayerToolbarAction) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        shortcutActions.forEach { action ->
            val icon = playerToolbarActionIcon(
                action = action,
                isProcessed = isProcessed,
                protectionActive = protectionActive,
                isFavorite = isFavorite,
                isDownloaded = isDownloaded
            )

            val isTranscriptUnavailable = action == PlayerToolbarAction.TRANSCRIPT &&
                transcriptState !is TranscriptLoadState.Ready
            val alpha = if (isTranscriptUnavailable) 0.40f else 1f

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(alpha)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { onPerformAction(action) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = action.uiLabel(),
                            tint = when {
                                action == PlayerToolbarAction.HUSHCAST && isProcessed && protectionActive ->
                                    MaterialTheme.colorScheme.primary
                                action == PlayerToolbarAction.SLEEP && sleepTimerMinutes != null ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if ((action == PlayerToolbarAction.HUSHCAST || action == PlayerToolbarAction.ANALYZE) && analysisPercent != null) {
                        // R16: processing progress traced around the HushCast icon (1.0.27 behavior).
                        CircularProgressIndicator(
                            progress = { (analysisPercent / 100f).coerceAtLeast(0.03f) },
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = "HushCast, processing $analysisPercent%" },
                            strokeWidth = 2.5.dp
                        )
                    }
                }

                val badgeText = when (action) {
                    PlayerToolbarAction.SLEEP -> sleepTimerMinutes?.let { "${it}m" }
                    else -> null
                }
                if (badgeText != null) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        IconButton(
            onClick = { onToggleMoreMenu(true) },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Rounded.MoreHoriz, contentDescription = "More actions")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMoreActionsSheet(
    overflowActions: List<PlayerToolbarAction>,
    isProcessed: Boolean,
    protectionActive: Boolean,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    onPerformAction: (PlayerToolbarAction) -> Unit,
    onOpenCustomize: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hideThen(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss
    ) {
        PlayerSheetBlur()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 16.dp)
        ) {
            overflowActions.forEach { action ->
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(action.uiLabel()) },
                    leadingContent = {
                        Icon(
                            imageVector = playerToolbarActionIcon(
                                action = action,
                                isProcessed = isProcessed,
                                protectionActive = protectionActive,
                                isFavorite = isFavorite,
                                isDownloaded = isDownloaded
                            ),
                            contentDescription = null
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            hideThen {
                                onDismiss()
                                onPerformAction(action)
                            }
                        }
                )
            }
            if (overflowActions.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Customize player") },
                leadingContent = {
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        hideThen {
                            onDismiss()
                            onOpenCustomize()
                        }
                    }
            )
        }
    }
}

/**
 * The skip-ad / undo pill (R7).  Overlaid on the artwork's bottom edge, so it no longer reserves an
 * empty 48 dp slot above the seek bar.  Reads the position only inside derived state.
 */
@Composable
private fun PlayerSkipPill(
    adWindows: List<app.hushcast.automute.dsp.AdWindow>,
    positionMsState: androidx.compose.runtime.State<Long>,
    pendingUndo: app.hushcast.automute.dsp.AdSkipEvent?,
    onUndo: (app.hushcast.automute.dsp.AdSkipEvent) -> Unit,
    onDismissUndo: () -> Unit,
    onSkip: (app.hushcast.automute.dsp.AdWindow) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAd by remember(adWindows) {
        derivedStateOf {
            val pos = positionMsState.value
            adWindows.firstOrNull { pos in it.startMs..it.endMs }
        }
    }
    Box(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        val ad = activeAd
        val undo = pendingUndo
        when {
            undo != null -> OneTapUndoAdSkipBanner(
                event = undo,
                onUndo = { onUndo(undo) },
                onDismiss = onDismissUndo
            )
            ad != null -> FilledTonalButton(
                onClick = { onSkip(ad) },
                modifier = Modifier.height(40.dp)
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Skip ${formatAdDisplayLabel(ad)}")
            }
        }
    }
}

/** A small "Up Next" grip under the toolbar, so the swipe-up gesture is discoverable (R18). */
@Composable
private fun UpNextGrip(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open Up Next", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Up Next",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
