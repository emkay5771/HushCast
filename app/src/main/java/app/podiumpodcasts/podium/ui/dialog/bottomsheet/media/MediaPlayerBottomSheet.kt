package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

    val isDownloaded = remember(episodeId, currentEpisodeBundle) {
        currentEpisodeBundle?.episode?.craftDownloadFile(context)?.exists() == true
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

    var selectedTab by remember { mutableStateOf(MediaPlayerTab.PLAYER) }
    var showTranscriptOverlay by remember { mutableStateOf(false) }

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
        val mapped = configuredToolbarActions
            .map { if (it == PlayerToolbarAction.ANALYZE) PlayerToolbarAction.HUSHCAST else it }
            .filter { it in availablePlayerActions }
        (mapped + availablePlayerActions.filter { it !in mapped }).distinct()
    }
    val shortcutActions = remember(orderedAllActions) { orderedAllActions.take(4) }
    val overflowActions = remember(orderedAllActions) { orderedAllActions.drop(4) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun dismissGracefully(after: () -> Unit = {}) = scope.launch {
        sheetState.hide()
        after()
        onDismiss()
    }

    fun triggerAnalyzeNow() {
        if (episodeId.isBlank()) return
        val hasDesktopWorker = HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()
        if (hasDesktopWorker) {
            scope.launch(Dispatchers.IO) {
                DownloadManager.downloadEpisode(
                    context = context,
                    db = appDb,
                    episodeId = episodeId,
                    downloadMethod = DownloadAnalysisMethod.DESKTOP,
                    forceReprocess = true
                )
            }
            return
        }
        val localFile = currentEpisodeBundle?.episode?.craftDownloadFile(context)
        if (isDownloaded && localFile != null && localFile.exists()) {
            scope.launch(Dispatchers.IO) {
                val autoMuteDb = AutoMuteDatabase.getInstance(context)
                autoMuteDb.dao().upsertStatus(
                    EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        badge = ReadinessBadge.ANALYZING.name,
                        progressPercent = 0,
                        stageLabel = "Queued for AI analysis…"
                    )
                )
                EpisodeAnalysisWorker.enqueueAnalyzeNow(
                    context = context,
                    episodeId = episodeId,
                    audioPath = localFile.absolutePath,
                    actionMode = HushCastEngineBridge.defaultActionMode,
                    episodeTitle = currentEpisodeBundle?.episode?.title
                )
            }
        } else {
            showNotDownloadedDialog = true
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
            PlayerToolbarAction.FAVORITE -> playerVm.toggleFavorite(isFavorite)
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

    val livePosMs = if (showTranscriptOverlay) {
        vm.rememberLivePositionMs(100L)
    } else {
        vm.rememberLivePositionMs(500L)
    }
    val durationMs = vm.currentDuration.coerceAtLeast(0L)

    val currentChapterIndex = remember(resolvedChapters, livePosMs) {
        if (resolvedChapters.isEmpty()) -1
        else resolvedChapters.indexOfFirst { livePosMs in it.startMs until it.endMs }
    }
    val currentChapter = resolvedChapters.getOrNull(currentChapterIndex)

    SwitchableDynamicMaterialExpressiveTheme(
        enable = enableArtworkColors,
        seedColor = Color(vm.metadataImageSeedColor ?: 1)
    ) {
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
            ) {
                // Top row: Collapse, Tabs, Up Next (hidden cleanly during transcript overlay, Tune icon removed per I9)
                if (!showTranscriptOverlay) {
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
                                    modifier = Modifier.fillMaxSize(),
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
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxWidth(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                AnimatedVisibility(visible = isCastingExternal) {
                                                    CastingBypassNoticeBanner(modifier = Modifier.padding(bottom = 8.dp))
                                                }

                                                // Artwork with chapter image cross-fade & link button (C3) + swipe-up for Up Next (M4)
                                                Box(
                                                    modifier = Modifier
                                                        .pointerInput(Unit) {
                                                            val thresholdPx = 64.dp.toPx()
                                                            awaitEachGesture {
                                                                val down = awaitFirstDown(requireUnconsumed = true)
                                                                var totalDy = 0f
                                                                var triggered = false
                                                                while (true) {
                                                                    val event = awaitPointerEvent()
                                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                    if (!change.pressed) break
                                                                    totalDy += (change.position.y - change.previousPosition.y)
                                                                    if (!triggered && totalDy < -thresholdPx) {
                                                                        triggered = true
                                                                        change.consume()
                                                                        showQueueBottomSheet = true
                                                                    }
                                                                }
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    val artSize = if (compactLayout) 128.dp else 300.dp
                                                    val artUri = currentChapter?.imageUrl?.takeIf { it.isNotBlank() }
                                                        ?: vm.mediaMetadata?.artworkUri?.toString()

                                                    AnimatedContent(
                                                        targetState = vm.isLoading to artUri,
                                                        label = "playerArtwork"
                                                    ) { (loading, uri) ->
                                                        if (loading) {
                                                            Box(
                                                                modifier = Modifier.size(artSize),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                ContainedLoadingIndicator(Modifier.size(artSize / 2))
                                                            }
                                                        } else {
                                                            ShimmerAsyncImage(
                                                                modifier = Modifier
                                                                    .size(artSize)
                                                                    .clip(RoundedCornerShape(if (compactLayout) 16.dp else 28.dp)),
                                                                model = uri,
                                                                contentDescription = null,
                                                                contentScale = ContentScale.Crop
                                                            )
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
                                                }

                                                Spacer(Modifier.height(if (compactLayout) 10.dp else 20.dp))

                                                // Episode title
                                                Text(
                                                    text = vm.mediaMetadata?.title?.toString() ?: "",
                                                    style = if (compactLayout) Typography.titleMediumEmphasized else Typography.headlineSmall,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
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
                                                    if (vm.mediaSourceType is SourceTypeState.Streaming && !downloadBannerDismissedForEp) {
                                                        Text(
                                                            text = " · Streaming",
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            maxLines = 1,
                                                            modifier = Modifier.clickable {
                                                                triggerDownloadNow()
                                                                downloadBannerDismissedForEp = true
                                                            }
                                                        )
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
                                                        positionMs = livePosMs,
                                                        speed = vm.speed,
                                                        adjustForSpeed = adjustRemainingForSpeed,
                                                        onSeekToMs = { vm.seek(it) },
                                                        onOpenChaptersTab = {
                                                            if (MediaPlayerTab.CHAPTERS in visibleTabs) {
                                                                selectedTab = MediaPlayerTab.CHAPTERS
                                                            }
                                                        }
                                                    )
                                                }
                                            }

                                            // Fixed-height skip/undo pill slot (M7, D1)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val activeAd = remember(livePosMs, adWindows) {
                                                    adWindows.firstOrNull { livePosMs in it.startMs..it.endMs }
                                                }
                                                val undo = pendingUndo
                                                when {
                                                    undo != null -> {
                                                        OneTapUndoAdSkipBanner(
                                                            event = undo,
                                                            onUndo = {
                                                                HushCastEngineBridge.adSkipObserver.bypassAd(undo.adWindow.startMs)
                                                                vm.seek(undo.adWindow.startMs)
                                                                HushCastEngineBridge.clearPendingUndo()
                                                            },
                                                            onDismiss = { HushCastEngineBridge.clearPendingUndo() }
                                                        )
                                                    }
                                                    activeAd != null -> {
                                                        FilledTonalButton(
                                                            onClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                                                vm.seek(activeAd.endMs)
                                                            },
                                                            modifier = Modifier.height(40.dp)
                                                        ) {
                                                            Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("Skip ${formatAdDisplayLabel(activeAd)}")
                                                        }
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
                                            TranscriptOverlay(
                                                state = transcriptState,
                                                episodeTitle = vm.mediaMetadata?.title?.toString() ?: "",
                                                currentPositionMs = livePosMs,
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
                                            positionMs = livePosMs,
                                            durationMs = durationMs,
                                            playbackSpeed = vm.speed,
                                            adjustRemainingForSpeed = adjustRemainingForSpeed,
                                            muteWindows = muteWindows,
                                            adWindows = adWindows,
                                            onSeekToMs = { vm.seek(it) },
                                            onSkipAd = { ad -> vm.seek(ad.endMs) },
                                            compact = showTranscriptOverlay
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
                                                    text = "AI Summary",
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
                                PlayerChaptersTab(
                                    chapters = resolvedChapters,
                                    skippedPublishedStarts = skippedChapterStarts,
                                    currentPositionMs = livePosMs,
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
                                    contentBottomPadding = 16.dp
                                )
                            }
                        }
                    }
                }

                // M5: Single AnimatedContent bottom slot switching toolbar (Playing) <-> mini playback strip (other tabs)
                if (!showTranscriptOverlay) {
                    val bottomSlotFadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    AnimatedContent(
                        targetState = selectedTab == MediaPlayerTab.PLAYER,
                        transitionSpec = {
                            fadeIn(animationSpec = bottomSlotFadeSpec) togetherWith
                                fadeOut(animationSpec = bottomSlotFadeSpec)
                        },
                        label = "playerBottomSlot"
                    ) { isPlayingTab ->
                        if (isPlayingTab) {
                            PlayerActionToolbar(
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
                EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
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
            title = { Text("Download & process episode") },
            text = { Text("Download this episode so HushCast can transcribe it and detect sponsors.") },
            confirmButton = {
                Button(
                    onClick = {
                        showNotDownloadedDialog = false
                        scope.launch {
                            val method = if (HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()) {
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
                    Text("Download & Process")
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
        val cleanWord = word.text.trim().trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
        val wStartMs = (word.start * 1000.0).toLong()
        val wEndMs = (word.end * 1000.0).toLong()
        val existingMute = muteEntities.firstOrNull { m ->
            m.enabled && wEndMs >= m.startMs && wStartMs <= m.endMs
        }
        if (existingMute != null) {
            AlertDialog(
                onDismissRequest = { pendingWordMute = null },
                title = { Text("Unmute \"$cleanWord\"?") },
                text = { Text("Restore audio for this word in the current episode.") },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingWordMute = null
                            playerVm.toggleMuteSegment(existingMute.id, false)
                        }
                    ) {
                        Text("Unmute")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingWordMute = null }) {
                        Text("Cancel")
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { pendingWordMute = null },
                title = { Text("Mute \"$cleanWord\"?") },
                text = { Text("Add \"$cleanWord\" to your HushCast word mute list so it is muted across episodes.") },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingWordMute = null
                            playerVm.addMuteWordRule(cleanWord, HushCastEngineBridge.defaultActionMode)
                        }
                    ) {
                        Text("Mute word")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingWordMute = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChapterNavigationLine(
    chapters: List<ResolvedChapter>,
    currentChapter: ResolvedChapter,
    currentIndex: Int,
    skippedStarts: Set<Long>,
    positionMs: Long,
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
    val remainingFraction = ((currentChapter.endMs - positionMs).toFloat() / chapterDurMs.toFloat()).coerceIn(0f, 1f)
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
    val remMins = ((remainingWallMs + 30_000L) / 60_000L).coerceAtLeast(1L)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(
            enabled = prevChapter != null,
            onClick = {
                if (prevChapter != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSeekToMs(prevChapter.startMs)
                }
            },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous chapter")
        }

        val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
        AnimatedContent(
            targetState = currentChapter,
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
            }
        }

        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { remainingFraction },
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp
            )
            IconButton(
                enabled = nextChapter != null,
                onClick = {
                    if (nextChapter != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSeekToMs(nextChapter.startMs)
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next chapter")
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

        FilledIconToggleButton(
            checked = isPlaying,
            onCheckedChange = { onTogglePlay() },
            shapes = IconButtonDefaults.toggleableShapes(),
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

@Composable
private fun PlayerActionToolbar(
    shortcutActions: List<PlayerToolbarAction>,
    overflowActions: List<PlayerToolbarAction>,
    isProcessed: Boolean,
    protectionActive: Boolean,
    speed: Float,
    sleepTimerMinutes: Int?,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    transcriptState: TranscriptLoadState,
    showMoreMenu: Boolean,
    onToggleMoreMenu: (Boolean) -> Unit,
    onPerformAction: (PlayerToolbarAction) -> Unit,
    onOpenCustomize: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        shortcutActions.forEach { action ->
            val icon = when (action) {
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
                            contentDescription = action.label,
                            tint = when {
                                action == PlayerToolbarAction.HUSHCAST && isProcessed && protectionActive ->
                                    MaterialTheme.colorScheme.primary
                                action == PlayerToolbarAction.SLEEP && sleepTimerMinutes != null ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (action == PlayerToolbarAction.TRANSCRIPT && transcriptState is TranscriptLoadState.Transcribing) {
                        CircularProgressIndicator(
                            progress = { transcriptState.progressPercent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp
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

        Box {
            IconButton(
                onClick = { onToggleMoreMenu(true) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "More actions")
            }

            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { onToggleMoreMenu(false) }
            ) {
                overflowActions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            onToggleMoreMenu(false)
                            onPerformAction(action)
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    text = { Text("Customize player") },
                    onClick = onOpenCustomize
                )
            }
        }
    }
}