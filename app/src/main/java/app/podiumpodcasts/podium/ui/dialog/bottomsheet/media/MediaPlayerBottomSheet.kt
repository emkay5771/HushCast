package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import app.podiumpodcasts.podium.PlayerToolbarAction
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonDefaults.IconButtonWidthOption
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.ui.platform.LocalContext
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.MuteRuleEntity
import app.hushcast.automute.storage.WordIndexBlob
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.component.common.SwitchableDynamicMaterialExpressiveTheme
import app.podiumpodcasts.podium.ui.component.media.AnnotatedScrubberOverlay
import app.podiumpodcasts.podium.ui.component.media.CastingBypassNoticeBanner
import app.podiumpodcasts.podium.ui.component.media.EpisodeReadinessBadge
import app.podiumpodcasts.podium.ui.component.media.OneTapUndoAdSkipBanner
import app.podiumpodcasts.podium.ui.component.media.SyncedTranscriptCard
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.custom.icons.Forward
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.formatPlayerTime
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.ui.vm.SourceTypeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class MediaPlayerTab(val label: String) {
    PLAYER("Playing"),
    NOTES("Details"),
    CHAPTERS("Chapters"),
    TRANSCRIPT("Transcript")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPlayerBottomSheet(
    onOpenEpisode: (
        origin: String,
        id: String
    ) -> Unit,
    onOpenPodcast: ((origin: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    val compactLayout = !windowSizeClass.isHeightAtLeastBreakpoint(500)

    val settingsRepository = LocalSettingsRepository.current
    val vm = viewModel<MediaPlayerViewModel>()

    val enableArtworkColors = settingsRepository.appearance.enableArtworkColors.collectAsState(true)

    LaunchedEffect(vm.isPlayerVisible) {
        if(vm.isPlayerVisible) return@LaunchedEffect
        onDismiss()
    }

    val sleepTimerState = vm.getSleepTimerState()

    val context = LocalContext.current
    val appDb = LocalDatabase.current
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val episodeId = vm.metadataEpisodeId ?: ""

    val analysisStatus by autoMuteDb.dao().observeStatus(episodeId).collectAsState(initial = null)
    val muteEntities by autoMuteDb.dao().observeMuteSegmentsByEpisode(episodeId).collectAsState(initial = emptyList())
    val adEntities by autoMuteDb.dao().observeAdSegmentsByEpisode(episodeId).collectAsState(initial = emptyList())
    val pendingUndo by HushCastEngineBridge.pendingUndoEvent.collectAsState()
    val isCastingExternal by HushCastEngineBridge.isCastingExternal.collectAsState()

    var selectedTab by remember { mutableStateOf(MediaPlayerTab.PLAYER) }
    val currentEpisodeBundle by remember(episodeId) {
        if (episodeId.isNotBlank()) appDb.podcastEpisodes().get(episodeId)
        else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    var showMoreSheet by remember { mutableStateOf(false) }
    var showNotDownloadedDialog by remember { mutableStateOf(false) }
    var showHushCastSheet by remember { mutableStateOf(false) }
    var showSetupSheet by remember { mutableStateOf(false) }
    var pendingWordMute by remember { mutableStateOf<Pair<app.hushcast.automute.engine.Word, Boolean>?>(null) }
    var transcriptBlob by remember(episodeId) { mutableStateOf<WordIndexBlob?>(null) }
    var hadTranscript by remember(episodeId) { mutableStateOf(false) }

    fun triggerAnalyzeNow() {
        if (episodeId.isBlank()) return
        showNotDownloadedDialog = true
    }

    var episodeSummary by remember { mutableStateOf<String?>(null) }
    var episodeChapters by remember {
        mutableStateOf<List<app.hushcast.automute.desktop.DesktopChapterDto>>(emptyList())
    }

    LaunchedEffect(episodeId, analysisStatus?.badge, analysisStatus?.stageLabel) {
        if (episodeId.isBlank()) return@LaunchedEffect
        val idx = autoMuteDb.dao().getWordIndexByEpisode(episodeId)
        val insights = autoMuteDb.dao().getInsights(episodeId)
        val (decodedBlob, decodedChapters) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val blob = idx?.let { runCatching { BlobCodec.decompress(it.compressedBlob) }.getOrNull() }
            val chapters = insights?.chaptersJson?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<List<app.hushcast.automute.desktop.DesktopChapterDto>>(raw)
                        .sortedBy { it.startMs }
                }.getOrDefault(emptyList())
            } ?: emptyList()
            blob to chapters
        }
        if (decodedBlob != null && !hadTranscript && transcriptBlob == null && analysisStatus?.badge != null) {
            hadTranscript = true
        }
        transcriptBlob = decodedBlob
        episodeSummary = insights?.summary
        episodeChapters = decodedChapters
    }

    val visibleTabs = remember(episodeChapters.isNotEmpty(), transcriptBlob != null) {
        buildList {
            add(MediaPlayerTab.PLAYER)
            add(MediaPlayerTab.NOTES)
            if (episodeChapters.isNotEmpty()) add(MediaPlayerTab.CHAPTERS)
            if (transcriptBlob != null) add(MediaPlayerTab.TRANSCRIPT)
        }
    }
    LaunchedEffect(visibleTabs) {
        if (selectedTab !in visibleTabs) selectedTab = MediaPlayerTab.PLAYER
    }

    val chapterSkipModel by remember(episodeId) {
        if (episodeId.isNotBlank()) appDb.chapterSkips().observe(episodeId)
        else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val skippedChapterStarts = remember(chapterSkipModel) {
        chapterSkipModel?.skippedStartsSet() ?: emptySet()
    }

    fun toggleSkippedChapter(startMs: Long, skip: Boolean) {
        if (episodeId.isBlank()) return
        val next = if (skip) skippedChapterStarts + startMs else skippedChapterStarts - startMs
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (next.isEmpty()) {
                appDb.chapterSkips().delete(episodeId)
            } else {
                appDb.chapterSkips().upsert(
                    app.podiumpodcasts.podium.api.db.model.ChapterSkipModel(
                        episodeId = episodeId,
                        skippedStartsCsv = next.sorted().joinToString(",")
                    )
                )
            }
        }
    }

    LaunchedEffect(episodeId, episodeChapters, skippedChapterStarts, vm.currentDuration) {
        if (episodeId.isNotBlank()) {
            HushCastEngineBridge.updateSkippedChapterRanges(
                episodeId = episodeId,
                chapters = episodeChapters,
                skippedStarts = skippedChapterStarts,
                durationMs = vm.currentDuration
            )
        }
    }

    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) {
            delay(6000L)
            HushCastEngineBridge.clearPendingUndo()
        }
    }

    var showSleepTimerBottomSheet by remember { mutableStateOf(false) }
    var showSpeedBottomSheet by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    fun dismissGracefully(
        after: () -> Unit
    ) = scope.launch {
        sheetState.hide()

        after()
        onDismiss()
    }

    val setupCompleted by settingsRepository.hushCast.setupCompleted.collectAsState(initial = false)
    val shieldBadge = remember(analysisStatus?.badge) {
        runCatching { analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) } }.getOrNull()
            ?: ReadinessBadge.NOT_PROCESSED
    }
    val isFavorite by remember(episodeId) {
        if (episodeId.isNotBlank()) appDb.listItems().isFavorite(episodeId)
        else kotlinx.coroutines.flow.flowOf(false)
    }.collectAsState(initial = false)
    val isDownloaded = remember(episodeId, currentEpisodeBundle, currentEpisodeBundle?.download) {
        currentEpisodeBundle?.episode?.craftDownloadFile(context)?.exists() == true
    }

    var showRearrangeActionsSheet by remember { mutableStateOf(false) }
    val availablePlayerActions = remember {
        listOf(
            PlayerToolbarAction.HUSHCAST,
            PlayerToolbarAction.SPEED,
            PlayerToolbarAction.SLEEP,
            PlayerToolbarAction.FAVORITE,
            PlayerToolbarAction.DOWNLOAD,
            PlayerToolbarAction.GO_TO_PODCAST,
            PlayerToolbarAction.EPISODE_DETAILS,
            PlayerToolbarAction.MARK_PLAYED
        )
    }
    val configuredToolbarActions by settingsRepository.behavior.playerToolbarActions.collectAsState(
        initial = listOf(
            PlayerToolbarAction.HUSHCAST,
            PlayerToolbarAction.SPEED,
            PlayerToolbarAction.SLEEP,
            PlayerToolbarAction.FAVORITE
        )
    )
    val shortcutActions = remember(configuredToolbarActions) {
        configuredToolbarActions
            .map { if (it == PlayerToolbarAction.ANALYZE) PlayerToolbarAction.HUSHCAST else it }
            .distinct()
            .filter { it in availablePlayerActions }
            .take(4)
            .ifEmpty {
                listOf(
                    PlayerToolbarAction.HUSHCAST,
                    PlayerToolbarAction.SPEED,
                    PlayerToolbarAction.SLEEP,
                    PlayerToolbarAction.FAVORITE
                )
            }
    }

    fun performToolbarAction(action: PlayerToolbarAction) {
        when (action) {
            PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.ANALYZE -> {
                if (!setupCompleted) showSetupSheet = true
                else showHushCastSheet = true
            }
            PlayerToolbarAction.SPEED -> showSpeedBottomSheet = true
            PlayerToolbarAction.SLEEP -> showSleepTimerBottomSheet = true
            PlayerToolbarAction.FAVORITE -> {
                if (episodeId.isNotBlank()) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        appDb.lists().createFavorites()
                        if (isFavorite) {
                            appDb.listItems().get(-2, episodeId)?.let { item ->
                                appDb.listItems().deleteAndReindex(-2, item.id, item.position)
                            }
                        } else {
                            val pos = appDb.listItems().getNextPosition(-2) ?: 0
                            appDb.listItems().addListItemAndRefreshItemCount(
                                listId = -2,
                                contentId = episodeId,
                                isPodcast = false,
                                position = pos
                            )
                        }
                    }
                }
            }
            PlayerToolbarAction.DOWNLOAD -> {
                val ep = currentEpisodeBundle?.episode ?: return
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (isDownloaded) {
                        app.podiumpodcasts.podium.manager.DownloadManager.deleteEpisodeDownload(
                            context = context,
                            db = appDb,
                            episode = ep
                        )
                    } else {
                        app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                            context = context,
                            db = appDb,
                            episodeId = ep.id
                        )
                    }
                }
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
                    scope.launch {
                        appDb.podcastEpisodePlayStates().savePlayed(episodeId, true)
                    }
                    vm.removeEpisodeFromPlaybackAndQueue(episodeId)
                }
            }
        }
    }

    var isFullScreen by remember { mutableStateOf(true) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
    ) { visibleTabs.size }

    LaunchedEffect(pagerState.settledPage, visibleTabs) {
        val tab = visibleTabs.getOrNull(pagerState.settledPage)
        if (tab != null && tab != selectedTab) {
            selectedTab = tab
        }
    }
    LaunchedEffect(visibleTabs.size) {
        if (visibleTabs.isNotEmpty() && pagerState.currentPage >= visibleTabs.size) {
            pagerState.scrollToPage(visibleTabs.lastIndex)
        }
    }
    LaunchedEffect(selectedTab, visibleTabs) {
        val targetIdx = visibleTabs.indexOf(selectedTab)
        if (targetIdx >= 0 && pagerState.currentPage != targetIdx && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(targetIdx)
        }
    }

    SwitchableDynamicMaterialExpressiveTheme(
        enable = enableArtworkColors.value,
        seedColor = Color(vm.metadataImageSeedColor ?: 1)
    ) {
        ModalBottomSheet(
            sheetState = sheetState,
            sheetMaxWidth = if (isFullScreen) androidx.compose.ui.unit.Dp.Infinity else BottomSheetDefaults.SheetMaxWidth,
            onDismissRequest = onDismiss
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (isFullScreen) Modifier.fillMaxHeight() else Modifier)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(isFullScreen) {
                            val thresholdPx = 60.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalDrag = 0f
                                var triggered = false
                                var pointerId = down.id
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (!change.pressed) break
                                    val dy = change.position.y - change.previousPosition.y
                                    totalDrag += dy
                                    if (!triggered) {
                                        if (totalDrag < -thresholdPx) {
                                            triggered = true
                                            change.consume()
                                            if (!isFullScreen) isFullScreen = true
                                            else showQueueBottomSheet = true
                                        } else if (totalDrag > thresholdPx && isFullScreen) {
                                            triggered = true
                                            change.consume()
                                            isFullScreen = false
                                        } else if (dy < 0f || isFullScreen) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        modifier = Modifier.padding(start = 4.dp),
                        onClick = { dismissGracefully { } }
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Collapse player"
                        )
                    }

                    val activeTabIndex = pagerState.currentPage.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
                    androidx.compose.material3.PrimaryScrollableTabRow(
                        modifier = Modifier.weight(1f),
                        selectedTabIndex = activeTabIndex,
                        edgePadding = 0.dp,
                        minTabWidth = 0.dp,
                        containerColor = Color.Transparent,
                        divider = { }
                    ) {
                        visibleTabs.forEachIndexed { idx, tab ->
                            Tab(
                                selected = activeTabIndex == idx,
                                onClick = {
                                    selectedTab = tab
                                    scope.launch {
                                        pagerState.animateScrollToPage(idx)
                                    }
                                },
                                text = {
                                    Text(
                                        text = tab.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            )
                        }
                    }

                    IconButton(
                        modifier = Modifier.padding(end = 4.dp),
                        onClick = { showQueueBottomSheet = true }
                    ) {
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                if (vm.queue.isNotEmpty()) {
                                    androidx.compose.material3.Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text(if (vm.queue.size > 99) "99+" else vm.queue.size.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = stringResource(R.string.common_queue)
                            )
                        }
                    }
                }

                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isFullScreen) Modifier.weight(1f) else Modifier),
                    beyondViewportPageCount = 1
                ) { pageIndex ->
                val pageTab = visibleTabs.getOrNull(pageIndex) ?: MediaPlayerTab.PLAYER
                when (pageTab) {
                    MediaPlayerTab.CHAPTERS -> {
                        LiveChaptersTabContent(
                            vm = vm,
                            chapters = episodeChapters,
                            skippedChapterStarts = skippedChapterStarts,
                            onSeekToMs = { ms -> vm.seek(ms) },
                            onToggleSkipChapter = { ch, skip -> toggleSkippedChapter(ch.startMs, skip) },
                            onAnalyzeNowClick = { triggerAnalyzeNow() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isFullScreen) Modifier.fillMaxSize()
                                    else Modifier.heightIn(min = 320.dp, max = 520.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }

                    MediaPlayerTab.NOTES -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isFullScreen) Modifier.fillMaxSize()
                                    else Modifier.heightIn(min = 320.dp, max = 520.dp)
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Text(
                                text = currentEpisodeBundle?.episode?.title
                                    ?: vm.mediaMetadata?.title?.toString()
                                    ?: "",
                                style = Typography.titleLargeEmphasized
                            )
                            val sub = currentEpisodeBundle?.episode?.podcastTitle
                                ?: vm.mediaMetadata?.subtitle?.toString()
                                ?: ""
                            if (sub.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = sub,
                                    style = Typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            val desc = currentEpisodeBundle?.episode?.description.orEmpty()
                            if (desc.isBlank()) {
                                Text(
                                    text = "No show notes available for this episode.",
                                    style = Typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                val parsedNotes = remember(episodeId, desc) {
                                    AnnotatedString.fromHtml(
                                        htmlString = desc.replace("\n", "<br>")
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        text = parsedNotes,
                                        style = Typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    MediaPlayerTab.TRANSCRIPT -> {
                        val muteWindows = remember(muteEntities) { muteEntities.map { it.toDomain() } }
                        val adWindows = remember(adEntities) { adEntities.map { it.toDomain() } }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isFullScreen) Modifier.fillMaxSize()
                                    else Modifier.heightIn(min = 320.dp, max = 520.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (transcriptBlob == null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp, horizontal = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Subtitles,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Transcript not generated yet",
                                        style = Typography.titleMediumEmphasized
                                    )
                                    Text(
                                        text = "Analyze this episode with HushCast AI to view a synced, tappable transcript.",
                                        style = Typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Button(onClick = { triggerAnalyzeNow() }) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Analyze with HushCast AI")
                                    }
                                }
                            } else {
                                LiveSyncedTranscriptCard(
                                    modifier = Modifier.weight(1f),
                                    vm = vm,
                                    segments = transcriptBlob?.segments ?: emptyList(),
                                    words = transcriptBlob?.words ?: emptyList(),
                                    muteWindows = muteWindows,
                                    adWindows = adWindows,
                                    onSeekToMs = { ms -> vm.seek(ms) },
                                    summary = episodeSummary,
                                    chapters = episodeChapters,
                                    onPlayAdSegment = { ad, startMs ->
                                        HushCastEngineBridge.adSkipObserver.bypassAd(ad.startMs)
                                        vm.seek(startMs)
                                        if (!vm.isPlaying) vm.play()
                                    },
                                    onCopyReport = if (app.podiumpodcasts.podium.BuildConfig.DEBUG) {
                                        {
                                            val title = currentEpisodeBundle?.episode?.title
                                                ?: vm.mediaMetadata?.title?.toString()
                                                ?: episodeId
                                            val report = buildString {
                                                appendLine("HushCast Transcript & Ad Report")
                                                appendLine("Episode: $title")
                                                appendLine("Ad Windows (${adWindows.size}):")
                                                adWindows.forEach { ad ->
                                                    appendLine("  - ${formatPlayerTime(ad.startMs)}..${formatPlayerTime(ad.endMs)} [${ad.label}]")
                                                }
                                                appendLine("Mute Windows (${muteWindows.size}):")
                                                muteWindows.take(50).forEach { m ->
                                                    appendLine("  - ${formatPlayerTime(m.startMs)}..${formatPlayerTime(m.endMs)} (${m.word}, enabled=${m.enabled})")
                                                }
                                                appendLine("--- Transcript (${transcriptBlob?.segments?.size ?: 0} segments) ---")
                                                transcriptBlob?.segments?.forEach { seg ->
                                                    val ms = (seg.start * 1000.0).toLong()
                                                    appendLine("[${formatPlayerTime(ms)}] ${seg.text.trim()}")
                                                }
                                            }
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HushCast Transcript Report", report))
                                            android.widget.Toast.makeText(context, "Transcript & Ad Report copied", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } else null,
                                    onToggleWordMute = { word, mute ->
                                        val clean = word.text.trim().trim('.', ',', '!', '?', '"', '\'')
                                        if (clean.isNotEmpty() && episodeId.isNotBlank()) {
                                            pendingWordMute = word to mute
                                        }
                                    }
                                )
                            }
                        }
                    }

                    MediaPlayerTab.PLAYER -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    val thresholdPx = 72.dp.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var totalDrag = 0f
                                        var triggered = false
                                        val pointerId = down.id
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                            if (!change.pressed) break
                                            val dy = change.position.y - change.previousPosition.y
                                            totalDrag += dy
                                            if (!triggered && totalDrag < -thresholdPx) {
                                                triggered = true
                                                change.consume()
                                                showQueueBottomSheet = true
                                            }
                                        }
                                    }
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = true)
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = if (isFullScreen && !compactLayout) Arrangement.Center else Arrangement.Top
                                ) {
                                    Column(
                                        Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .clickable {
                                                dismissGracefully {
                                                    onOpenEpisode(
                                                        vm.metadataOrigin!!,
                                                        vm.metadataEpisodeId!!
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if(!compactLayout) {
                                            Image(
                                                mediaPlayerViewModel = vm,
                                                compactLayout = false,
                                                isFullScreen = isFullScreen
                                            )

                                            Spacer(Modifier.height(if (isFullScreen) 24.dp else 16.dp))
                                        }

                                        AnimatedContent(vm.mediaMetadata) { mediaMetadata ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if(compactLayout) {
                                                    Image(
                                                        mediaPlayerViewModel = vm,
                                                        compactLayout = true,
                                                        isFullScreen = isFullScreen
                                                    )

                                                    Spacer(Modifier.width(24.dp))
                                                }

                                                Column(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalAlignment = if(compactLayout) Alignment.Start else Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = mediaMetadata?.title.toString(),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = if(compactLayout) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
                                                        style = Typography.titleLargeEmphasized
                                                    )

                                                    Spacer(Modifier.height(4.dp))

                                                    Text(
                                                        text = mediaMetadata?.subtitle.toString(),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = if(compactLayout) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
                                                        color = if(onOpenPodcast != null && vm.metadataOrigin != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = Typography.bodyMedium,
                                                        modifier = if(onOpenPodcast != null && vm.metadataOrigin != null) {
                                                            Modifier.clickable {
                                                                dismissGracefully {
                                                                    onOpenPodcast(vm.metadataOrigin!!)
                                                                }
                                                            }
                                                        } else Modifier
                                                    )

                                                    if (episodeChapters.isNotEmpty()) {
                                                        Spacer(Modifier.height(10.dp))
                                                        ActiveChapterPill(
                                                            vm = vm,
                                                            chapters = episodeChapters,
                                                            onClick = { selectedTab = MediaPlayerTab.CHAPTERS }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Fixed bottom controls (never pushed into scrolling area by toasts/banners)
                                Column(
                                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val muteWindows = remember(muteEntities) { muteEntities.map { it.toDomain() } }
                                    val adWindows = remember(adEntities) { adEntities.map { it.toDomain() } }

                                    PlayerSeekBarSection(
                                        vm = vm,
                                        muteWindows = muteWindows,
                                        adWindows = adWindows
                                    )

                                    Spacer(Modifier.height(16.dp))

                                    ButtonGroup(
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(if (isFullScreen) 16.dp else 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            FilledTonalIconButton(
                                                modifier = Modifier
                                                    .size(IconButtonDefaults.largeContainerSize()),
                                                shapes = IconButtonDefaults.shapes(
                                                    shape = IconButtonDefaults.largeSquareShape,
                                                    pressedShape = IconButtonDefaults.largePressedShape
                                                ),
                                                onClick = {
                                                    vm.seekBack()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = when(vm.seekBackIncrement) {
                                                        5000L -> Icons.Rounded.Replay5
                                                        10000L -> Icons.Rounded.Replay10
                                                        30000L -> Icons.Rounded.Replay30
                                                        else -> Icons.Rounded.Replay
                                                    },
                                                    contentDescription = stringResource(R.string.common_action_seek_back),
                                                    modifier = Modifier.size(IconButtonDefaults.largeIconSize),
                                                )
                                            }

                                            val targetSizeWidth by animateDpAsState(
                                                targetValue = if(vm.isPlaying) {
                                                    IconButtonDefaults.largeContainerSize(IconButtonWidthOption.Uniform).width
                                                } else {
                                                    IconButtonDefaults.largeContainerSize(IconButtonWidthOption.Wide).width
                                                },
                                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
                                            )

                                            FilledIconButton(
                                                modifier = Modifier
                                                    .height(
                                                        IconButtonDefaults
                                                            .largeContainerSize(IconButtonWidthOption.Narrow)
                                                            .height
                                                    )
                                                    .width(targetSizeWidth),
                                                colors = IconButtonDefaults.filledIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                ),
                                                shapes = IconButtonDefaults.shapes(
                                                    shape = IconButtonDefaults.largeSquareShape,
                                                    pressedShape = IconButtonDefaults.largePressedShape
                                                ),
                                                onClick = {
                                                    if(vm.isPlaying) {
                                                        vm.pause()
                                                    } else {
                                                        vm.play()
                                                    }
                                                }
                                            ) {
                                                AnimatedContent(vm.isPlaying) {
                                                    Icon(
                                                        imageVector = when(it) {
                                                            true -> Icons.Rounded.Pause
                                                            false -> Icons.Rounded.PlayArrow
                                                        },
                                                        contentDescription = when(it) {
                                                            true -> stringResource(R.string.common_action_pause)
                                                            false -> stringResource(R.string.common_action_play)
                                                        },
                                                        modifier = Modifier.size(IconButtonDefaults.largeIconSize),
                                                    )
                                                }
                                            }

                                            FilledTonalIconButton(
                                                modifier = Modifier
                                                    .size(IconButtonDefaults.largeContainerSize()),
                                                shapes = IconButtonDefaults.shapes(
                                                    shape = IconButtonDefaults.largeSquareShape,
                                                    pressedShape = IconButtonDefaults.largePressedShape
                                                ),
                                                onClick = {
                                                    vm.seekForward()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = when(vm.seekForwardIncrement) {
                                                        5000L -> Icons.Rounded.Forward5
                                                        10000L -> Icons.Rounded.Forward10
                                                        30000L -> Icons.Rounded.Forward30
                                                        else -> Icons.Rounded.Forward
                                                    },
                                                    contentDescription = stringResource(R.string.common_action_seek_forward),
                                                    modifier = Modifier.size(IconButtonDefaults.largeIconSize),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Floating overlay for banners & toasts so play buttons never move
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isCastingExternal) {
                                    CastingBypassNoticeBanner()
                                }

                                if (vm.mediaSourceType is SourceTypeState.Streaming &&
                                    analysisStatus?.badge != ReadinessBadge.READY.name &&
                                    analysisStatus?.badge != ReadinessBadge.WORD_MUTE_READY.name &&
                                    episodeId.isNotBlank()
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shadowElevation = 4.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Download to enable HushCast muting & sponsor skipping",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            androidx.compose.material3.TextButton(
                                                onClick = { triggerAnalyzeNow() }
                                            ) {
                                                Text("Download")
                                            }
                                        }
                                    }
                                }

                                pendingUndo?.let { undoEvent ->
                                    OneTapUndoAdSkipBanner(
                                        event = undoEvent,
                                        onUndo = {
                                            HushCastEngineBridge.adSkipObserver.bypassAd(undoEvent.adWindow.startMs)
                                            vm.seek(undoEvent.jumpedFromMs)
                                            HushCastEngineBridge.clearPendingUndo()
                                        },
                                        onDismiss = { HushCastEngineBridge.clearPendingUndo() }
                                    )
                                }

                                if (pendingUndo == null && adEntities.isNotEmpty()) {
                                    val livePosMs = vm.rememberLivePositionMs()
                                    var dismissedAdStarts by remember(episodeId) {
                                        mutableStateOf(emptySet<Long>())
                                    }
                                    val activeAd = remember(adEntities, livePosMs, dismissedAdStarts) {
                                        adEntities.firstOrNull { seg ->
                                            livePosMs in seg.startMs until (seg.endMs - 400L) &&
                                                seg.startMs !in dismissedAdStarts
                                        }?.toDomain()
                                    }
                                    activeAd?.let { ad ->
                                        app.podiumpodcasts.podium.ui.component.media.ActiveAdSkipToastBanner(
                                            adWindow = ad,
                                            currentPositionMs = livePosMs,
                                            onSkip = {
                                                vm.seek(ad.endMs)
                                                HushCastEngineBridge.playSponsorSkipIndicator(context)
                                            },
                                            onDismiss = {
                                                dismissedAdStarts = dismissedAdStarts + ad.startMs
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }

                if (selectedTab != MediaPlayerTab.PLAYER) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val livePos = vm.rememberLivePositionMs()
                            val remainingMs = (vm.currentDuration - livePos).coerceAtLeast(0L)
                            Text(
                                text = "-${formatPlayerTime(remainingMs)}",
                                style = Typography.labelMediumEmphasized,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = { vm.seekBack() }) {
                                    Icon(
                                        imageVector = when(vm.seekBackIncrement) {
                                            5000L -> Icons.Rounded.Replay5
                                            10000L -> Icons.Rounded.Replay10
                                            30000L -> Icons.Rounded.Replay30
                                            else -> Icons.Rounded.Replay
                                        },
                                        contentDescription = stringResource(R.string.common_action_seek_back)
                                    )
                                }
                                FilledIconButton(
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shapes = IconButtonDefaults.shapes(),
                                    onClick = { if (vm.isPlaying) vm.pause() else vm.play() }
                                ) {
                                    Icon(
                                        imageVector = if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (vm.isPlaying) "Pause" else "Play"
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        shortcutActions.forEach { action ->
                            when (action) {
                                PlayerToolbarAction.HUSHCAST -> {
                                    IconButton(onClick = { performToolbarAction(action) }) {
                                        Box(contentAlignment = Alignment.Center) {
                                            when {
                                                !setupCompleted -> {
                                                    Icon(
                                                        imageVector = androidx.compose.material.icons.Icons.Outlined.Shield,
                                                        contentDescription = "Set up HushCast",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                shieldBadge == ReadinessBadge.ANALYZING -> {
                                                    androidx.compose.material3.CircularProgressIndicator(
                                                        progress = { (analysisStatus?.progressPercent ?: 0) / 100f },
                                                        modifier = Modifier.size(28.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Icon(
                                                        imageVector = androidx.compose.material.icons.Icons.Outlined.Shield,
                                                        contentDescription = "Processing with HushCast",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                shieldBadge == ReadinessBadge.READY || shieldBadge == ReadinessBadge.WORD_MUTE_READY -> {
                                                    Icon(
                                                        imageVector = androidx.compose.material.icons.Icons.Rounded.Shield,
                                                        contentDescription = "HushCast active",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                else -> {
                                                    androidx.compose.material3.BadgedBox(
                                                        badge = {
                                                            androidx.compose.material3.Badge(
                                                                containerColor = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = androidx.compose.material.icons.Icons.Outlined.Shield,
                                                            contentDescription = "Process episode with HushCast",
                                                            tint = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                PlayerToolbarAction.SPEED -> {
                                    androidx.compose.material3.TextButton(onClick = { performToolbarAction(action) }) {
                                        Text(
                                            text = String.format(Locale.getDefault(), "%.1f×", vm.speed),
                                            style = Typography.labelLargeEmphasized,
                                            color = if (vm.speed != 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                PlayerToolbarAction.SLEEP -> {
                                    IconButton(onClick = { performToolbarAction(action) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Bedtime,
                                            contentDescription = stringResource(R.string.common_sleep_timer),
                                            tint = if (sleepTimerState.value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                PlayerToolbarAction.FAVORITE -> {
                                    IconButton(onClick = { performToolbarAction(action) }) {
                                        Icon(
                                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                            contentDescription = "Favorite",
                                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                PlayerToolbarAction.DOWNLOAD -> {
                                    IconButton(onClick = { performToolbarAction(action) }) {
                                        Icon(
                                            imageVector = if (isDownloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download,
                                            contentDescription = if (isDownloaded) "Downloaded (tap to remove)" else "Download episode",
                                            tint = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                PlayerToolbarAction.GO_TO_PODCAST -> {
                                    IconButton(
                                        enabled = onOpenPodcast != null && vm.metadataOrigin != null,
                                        onClick = { performToolbarAction(action) }
                                    ) {
                                        Icon(Icons.Rounded.Podcasts, contentDescription = "Go to Podcast")
                                    }
                                }
                                PlayerToolbarAction.EPISODE_DETAILS -> {
                                    IconButton(onClick = { performToolbarAction(action) }) {
                                        Icon(Icons.Rounded.Info, contentDescription = "Episode Details & Notes")
                                    }
                                }
                                PlayerToolbarAction.MARK_PLAYED -> {
                                    IconButton(onClick = { performToolbarAction(action) }) {
                                        Icon(Icons.Rounded.Done, contentDescription = "Mark as Played")
                                    }
                                }
                                else -> Unit
                            }
                        }

                        // Pinned More button
                        IconButton(onClick = { showMoreSheet = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = "More"
                            )
                        }
                    }
                }
            }
        }


        if (showMoreSheet) {
            val overflowActions = availablePlayerActions.filter { action ->
                action !in shortcutActions &&
                    (action != PlayerToolbarAction.GO_TO_PODCAST || (onOpenPodcast != null && vm.metadataOrigin != null))
            }
            val totalRows = overflowActions.size + 1
            ModalBottomSheet(
                onDismissRequest = { showMoreSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    Text(
                        text = "Player Actions",
                        style = Typography.titleLargeEmphasized,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    overflowActions.forEachIndexed { idx, action ->
                        val label = when (action) {
                            PlayerToolbarAction.DOWNLOAD -> if (isDownloaded) "Remove Downloaded File" else "Download Episode"
                            PlayerToolbarAction.FAVORITE -> if (isFavorite) "Remove from Favorites" else "Star Episode"
                            PlayerToolbarAction.SPEED -> "Playback Speed (${String.format(Locale.getDefault(), "%.1f×", vm.speed)})"
                            else -> action.label
                        }
                        val description = when (action) {
                            PlayerToolbarAction.GO_TO_PODCAST -> vm.mediaMetadata?.subtitle?.toString() ?: ""
                            PlayerToolbarAction.HUSHCAST -> "Process episode, mute words, and skip sponsors"
                            else -> null
                        }
                        val iconVec = when (action) {
                            PlayerToolbarAction.HUSHCAST -> Icons.Rounded.Shield
                            PlayerToolbarAction.SPEED -> Icons.Filled.Speed
                            PlayerToolbarAction.SLEEP -> Icons.Filled.Bedtime
                            PlayerToolbarAction.FAVORITE -> if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder
                            PlayerToolbarAction.DOWNLOAD -> if (isDownloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download
                            PlayerToolbarAction.GO_TO_PODCAST -> Icons.Rounded.Podcasts
                            PlayerToolbarAction.EPISODE_DETAILS -> Icons.Rounded.Info
                            PlayerToolbarAction.MARK_PLAYED -> Icons.Rounded.Done
                            else -> Icons.Rounded.MoreHoriz
                        }
                        SettingsListItem(
                            icon = { Icon(iconVec, contentDescription = label) },
                            label = label,
                            description = description,
                            index = idx,
                            count = totalRows,
                            onClick = {
                                showMoreSheet = false
                                performToolbarAction(action)
                            }
                        )
                    }
                    SettingsListItem(
                        icon = { Icon(Icons.Rounded.Tune, contentDescription = "Rearrange Actions") },
                        label = "Rearrange Actions",
                        description = "Customize shortcuts on the player action bar",
                        index = overflowActions.size,
                        count = totalRows,
                        onClick = {
                            showMoreSheet = false
                            showRearrangeActionsSheet = true
                        }
                    )
                }
            }
        }

        if (showRearrangeActionsSheet) {
            val morePool = availablePlayerActions.filter { it !in shortcutActions }
            ModalBottomSheet(
                onDismissRequest = { showRearrangeActionsSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Rearrange Actions",
                        style = Typography.titleLargeEmphasized
                    )
                    Text(
                        text = "Shortcut on player (up to 4)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    shortcutActions.forEachIndexed { idx, action ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = action.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        enabled = idx > 0,
                                        onClick = {
                                            val updated = shortcutActions.toMutableList()
                                            val item = updated.removeAt(idx)
                                            updated.add(idx - 1, item)
                                            scope.launch {
                                                settingsRepository.behavior.setPlayerToolbarActions(updated)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        enabled = idx < shortcutActions.lastIndex,
                                        onClick = {
                                            val updated = shortcutActions.toMutableList()
                                            val item = updated.removeAt(idx)
                                            updated.add(idx + 1, item)
                                            scope.launch {
                                                settingsRepository.behavior.setPlayerToolbarActions(updated)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                                    }
                                    if (shortcutActions.size > 1) {
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                val updated = shortcutActions.toMutableList().apply { removeAt(idx) }
                                                scope.launch {
                                                    settingsRepository.behavior.setPlayerToolbarActions(updated)
                                                }
                                            }
                                        ) {
                                            Text("Hide")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "More actions",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    morePool.forEach { action ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = action.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        val updated = shortcutActions.toMutableList()
                                        if (updated.size >= 4) {
                                            updated[updated.lastIndex] = action
                                        } else {
                                            updated.add(action)
                                        }
                                        scope.launch {
                                            settingsRepository.behavior.setPlayerToolbarActions(updated)
                                        }
                                    }
                                ) {
                                    Text(if (shortcutActions.size >= 4) "Swap in" else "Add")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (showNotDownloadedDialog) {
            val isAlreadyDownloaded = remember(episodeId, currentEpisodeBundle) {
                currentEpisodeBundle?.episode?.craftDownloadFile(context)?.exists() == true
            }
            app.podiumpodcasts.podium.ui.component.media.HushCastAnalyzeDialog(
                episodeTitle = currentEpisodeBundle?.episode?.title
                    ?: vm.mediaMetadata?.title?.toString()
                    ?: "this episode",
                isDownloaded = isAlreadyDownloaded,
                onDismiss = { showNotDownloadedDialog = false },
                onAnalyzeDesktop = {
                    if (episodeId.isNotBlank()) {
                        scope.launch {
                            app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                context = context,
                                db = appDb,
                                episodeId = episodeId,
                                downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP,
                                forceReprocess = isAlreadyDownloaded
                            )
                        }
                    }
                },
                onAnalyzeOnDevice = {
                    if (episodeId.isNotBlank()) {
                        scope.launch {
                            app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                context = context,
                                db = appDb,
                                episodeId = episodeId,
                                downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE
                            )
                        }
                    }
                },
                onDownloadAudioOnly = {
                    if (episodeId.isNotBlank()) {
                        scope.launch {
                            app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                context = context,
                                db = appDb,
                                episodeId = episodeId,
                                downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS
                            )
                        }
                    }
                }
            )
        }

        if(showSleepTimerBottomSheet) MediaPlayerSleepTimerBottomSheet {
            showSleepTimerBottomSheet = false
        }

        if(showSpeedBottomSheet) MediaPlayerSpeedBottomSheet {
            showSpeedBottomSheet = false
        }

        if(showQueueBottomSheet) MediaPlayerQueueBottomSheet(
            onOpenEpisode = { origin, id ->
                dismissGracefully {
                    onOpenEpisode(origin, id)
                }
            }
        ) {
            showQueueBottomSheet = false
        }

        pendingWordMute?.let { (word, mute) ->
            val cleanWord = word.text.trim().trim('.', ',', '!', '?', '"', '\'')
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingWordMute = null },
                title = {
                    Text(if (mute) "Mute \"$cleanWord\"?" else "Unmute \"$cleanWord\"?")
                },
                text = {
                    Text(
                        if (mute) "Apply word muting to this episode only, or add \"$cleanWord\" to your global mute list for all episodes?"
                        else "Unmute \"$cleanWord\" in this episode only, or disable it across all episodes?"
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            pendingWordMute = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val targetWord = cleanWord.lowercase()
                                if (mute) {
                                    autoMuteDb.dao().upsertRuleIfNew(
                                        MuteRuleEntity(
                                            pattern = targetWord,
                                            isRegex = false,
                                            category = "CUSTOM",
                                            enabled = true
                                        )
                                    )
                                    val sha = analysisStatus?.fileSha256 ?: ""
                                    val matches = (transcriptBlob?.words ?: listOf(word)).filter {
                                        it.text.trim().trim('.', ',', '!', '?', '"', '\'').equals(targetWord, ignoreCase = true)
                                    }
                                    val newSegments = matches.map { w ->
                                        app.hushcast.automute.storage.MuteSegmentEntity(
                                            fileSha256 = sha,
                                            episodeId = episodeId,
                                            startMs = (w.start * 1000.0).toLong(),
                                            endMs = (w.end * 1000.0).toLong(),
                                            word = targetWord,
                                            ruleId = -1L,
                                            actionMode = HushCastEngineBridge.defaultActionMode.name,
                                            enabled = true
                                        )
                                    }
                                    if (newSegments.isNotEmpty()) {
                                        autoMuteDb.dao().insertMuteSegments(newSegments)
                                    }
                                } else {
                                    autoMuteDb.dao().getAllRules()
                                        .filter { it.pattern.equals(targetWord, ignoreCase = true) }
                                        .forEach { autoMuteDb.dao().setRuleEnabled(it.id, false) }
                                    muteEntities
                                        .filter { it.word.equals(targetWord, ignoreCase = true) }
                                        .forEach { autoMuteDb.dao().setMuteSegmentEnabled(it.id, false) }
                                }
                                val updatedMutes = autoMuteDb.dao().observeMuteSegmentsByEpisode(episodeId).first().map { it.toDomain() }
                                HushCastEngineBridge.autoMuteProcessor.updateWindows(updatedMutes)
                            }
                        }
                    ) {
                        Text("All episodes")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            pendingWordMute = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val targetWord = cleanWord.lowercase()
                                if (mute) {
                                    val sha = analysisStatus?.fileSha256 ?: ""
                                    val matches = (transcriptBlob?.words ?: listOf(word)).filter {
                                        it.text.trim().trim('.', ',', '!', '?', '"', '\'').equals(targetWord, ignoreCase = true)
                                    }
                                    val newSegments = matches.map { w ->
                                        app.hushcast.automute.storage.MuteSegmentEntity(
                                            fileSha256 = sha,
                                            episodeId = episodeId,
                                            startMs = (w.start * 1000.0).toLong(),
                                            endMs = (w.end * 1000.0).toLong(),
                                            word = targetWord,
                                            ruleId = -1L,
                                            actionMode = HushCastEngineBridge.defaultActionMode.name,
                                            enabled = true
                                        )
                                    }
                                    if (newSegments.isNotEmpty()) {
                                        autoMuteDb.dao().insertMuteSegments(newSegments)
                                    }
                                } else {
                                    muteEntities
                                        .filter { it.word.equals(targetWord, ignoreCase = true) }
                                        .forEach { autoMuteDb.dao().setMuteSegmentEnabled(it.id, false) }
                                }
                                val updatedMutes = autoMuteDb.dao().observeMuteSegmentsByEpisode(episodeId).first().map { it.toDomain() }
                                HushCastEngineBridge.autoMuteProcessor.updateWindows(updatedMutes)
                            }
                        }
                    ) {
                        Text("This episode")
                    }
                }
            )
        }

        if (showHushCastSheet) {
            val wordMutingEnabled by settingsRepository.hushCast.wordMutingEnabled.collectAsState(initial = true)
            val muteActionMode by settingsRepository.hushCast.muteActionMode.collectAsState(
                initial = app.hushcast.automute.dsp.MuteActionMode.HARD_SILENCE
            )
            val adPolicyMode by settingsRepository.hushCast.adPolicyMode.collectAsState(
                initial = app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP
            )
            val adAutoSkipScope by settingsRepository.hushCast.adAutoSkipScope.collectAsState(
                initial = app.hushcast.automute.dsp.AdAutoSkipScope.HIGH_CONFIDENCE_ONLY
            )
            val sponsorSkipSoundEnabled by settingsRepository.hushCast.sponsorSkipSoundEnabled.collectAsState(initial = true)
            val sheetBadge = runCatching {
                analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) }
            }.getOrNull() ?: ReadinessBadge.NOT_PROCESSED
            val isDownloaded = remember(episodeId, currentEpisodeBundle) {
                currentEpisodeBundle?.episode?.craftDownloadFile(context)?.exists() == true
            }

            ModalBottomSheet(onDismissRequest = { showHushCastSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "HushCast",
                                style = Typography.titleLargeEmphasized
                            )
                        }
                        val statusChipText = when (sheetBadge) {
                            ReadinessBadge.ANALYZING -> "Analyzing ${analysisStatus?.progressPercent ?: 0}%"
                            ReadinessBadge.READY -> "Ready"
                            ReadinessBadge.WORD_MUTE_READY -> "Word-Mute Ready"
                            else -> "Not Analyzed"
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = when (sheetBadge) {
                                ReadinessBadge.READY, ReadinessBadge.WORD_MUTE_READY -> MaterialTheme.colorScheme.primaryContainer
                                ReadinessBadge.ANALYZING -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ) {
                            Text(
                                text = statusChipText,
                                style = MaterialTheme.typography.labelMedium,
                                color = when (sheetBadge) {
                                    ReadinessBadge.READY, ReadinessBadge.WORD_MUTE_READY -> MaterialTheme.colorScheme.onPrimaryContainer
                                    ReadinessBadge.ANALYZING -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // 1. AI Analysis Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (sheetBadge) {
                                ReadinessBadge.ANALYZING -> {
                                    Text(
                                        text = analysisStatus?.stageLabel?.takeIf { it.isNotBlank() } ?: "Processing audio…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { (analysisStatus?.progressPercent ?: 0) / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = {
                                            if (episodeId.isNotBlank()) {
                                                EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                                                app.podiumpodcasts.podium.background.worker.PodcastEpisodeDownloadWorker.stopDownload(
                                                    context,
                                                    episodeId
                                                )
                                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    val existing = autoMuteDb.dao().getStatus(episodeId)
                                                    if (existing != null) {
                                                        autoMuteDb.dao().upsertStatus(
                                                            existing.copy(
                                                                badge = ReadinessBadge.NOT_PROCESSED.name,
                                                                progressPercent = 0,
                                                                stageLabel = "Cancelled"
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                            showHushCastSheet = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Cancel processing")
                                    }
                                }
                                ReadinessBadge.READY, ReadinessBadge.WORD_MUTE_READY -> {
                                    val savedSec = adEntities.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) } / 1000L
                                    val activeMuteCount = muteEntities.count { it.enabled }
                                    Text(
                                        text = "${adEntities.size} sponsor segments (${savedSec}s) · $activeMuteCount muted words",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    androidx.compose.material3.FilledTonalButton(
                                        onClick = {
                                            showHushCastSheet = false
                                            triggerAnalyzeNow()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Refresh AI Analysis")
                                    }
                                }
                                else -> {
                                    Text(
                                        text = if (isDownloaded) "This episode is downloaded and ready for HushCast AI analysis."
                                        else "Download and analyze this episode to mute words, skip sponsors, and generate chapters.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = {
                                            showHushCastSheet = false
                                            triggerAnalyzeNow()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (isDownloaded) "Analyze with HushCast AI" else "Download & Analyze with AI")
                                    }
                                }
                            }
                        }
                    }

                    // 2. Word Muting Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Mute words", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Silence or bleep flagged words in real time",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = wordMutingEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch { settingsRepository.hushCast.setWordMutingEnabled(enabled) }
                                    }
                                )
                            }
                            if (wordMutingEnabled) {
                                Text(
                                    text = "Mute style",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val styleOptions = listOf(
                                        app.hushcast.automute.dsp.MuteActionMode.HARD_SILENCE to "Silence",
                                        app.hushcast.automute.dsp.MuteActionMode.BEEP to "Bleep",
                                        app.hushcast.automute.dsp.MuteActionMode.VOLUME_DUCK to "Duck"
                                    )
                                    styleOptions.forEach { (mode, label) ->
                                        androidx.compose.material3.FilterChip(
                                            selected = muteActionMode == mode,
                                            onClick = {
                                                scope.launch {
                                                    settingsRepository.hushCast.setMuteActionMode(mode)
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        autoMuteDb.dao().updateAllRulesActionMode(mode.name)
                                                        autoMuteDb.dao().updateAllMuteSegmentsActionMode(mode.name)
                                                        val currentEpId = HushCastEngineBridge.currentEpisodeId
                                                        if (!currentEpId.isNullOrBlank()) {
                                                            val sha = autoMuteDb.dao().getStatus(currentEpId)?.fileSha256
                                                            if (!sha.isNullOrBlank()) {
                                                                val updated = autoMuteDb.dao().getMuteSegments(sha).map { it.toDomain() }
                                                                HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Sponsor Skipping Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Sponsor skipping",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val adModes = listOf(
                                    app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP to "Auto-Skip",
                                    app.hushcast.automute.dsp.AdPolicyMode.MARK_ONLY to "Show Only",
                                    app.hushcast.automute.dsp.AdPolicyMode.OFF to "Off"
                                )
                                adModes.forEach { (mode, label) ->
                                    androidx.compose.material3.FilterChip(
                                        selected = adPolicyMode == mode,
                                        onClick = {
                                            scope.launch { settingsRepository.hushCast.setAdPolicyMode(mode) }
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            if (adPolicyMode == app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP) {
                                Text(
                                    text = "Auto-skip confidence",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val scopes = listOf(
                                        app.hushcast.automute.dsp.AdAutoSkipScope.HIGH_CONFIDENCE_ONLY to "High (80%+)",
                                        app.hushcast.automute.dsp.AdAutoSkipScope.MODERATE_AND_HIGH to "Balanced",
                                        app.hushcast.automute.dsp.AdAutoSkipScope.ALL_DETECTED to "All Ads"
                                    )
                                    scopes.forEach { (sc, label) ->
                                        androidx.compose.material3.FilterChip(
                                            selected = adAutoSkipScope == sc,
                                            onClick = {
                                                scope.launch { settingsRepository.hushCast.setAdAutoSkipScope(sc) }
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Sponsor skip chime", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "Play a subtle tone when an ad is skipped",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = sponsorSkipSoundEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch { settingsRepository.hushCast.setSponsorSkipSoundEnabled(enabled) }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (showSetupSheet) {
            val wordMutingEnabled by settingsRepository.hushCast.wordMutingEnabled.collectAsState(initial = true)
            val adPolicyMode by settingsRepository.hushCast.adPolicyMode.collectAsState(initial = app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP)
            val autoDownloadUpNext by settingsRepository.hushCast.autoDownloadUpNext.collectAsState(initial = true)
            val context = androidx.compose.ui.platform.LocalContext.current
            var processTarget by remember {
                mutableStateOf(
                    if (app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(context)) "computer"
                    else "phone"
                )
            }

            ModalBottomSheet(onDismissRequest = { showSetupSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Set up HushCast",
                        style = Typography.titleLargeEmphasized
                    )
                    Text(
                        text = "Choose how HushCast cleans up your podcasts automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("1. Mute unwanted words", style = MaterialTheme.typography.bodyLarge)
                            Text("Silence or bleep profanity and custom words", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = wordMutingEnabled,
                            onCheckedChange = { scope.launch { settingsRepository.hushCast.setWordMutingEnabled(it) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("2. Skip sponsor reads", style = MaterialTheme.typography.bodyLarge)
                            Text("Automatically jump over detected ad segments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = adPolicyMode == app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP,
                            onCheckedChange = { skip ->
                                scope.launch {
                                    settingsRepository.hushCast.setAdPolicyMode(
                                        if (skip) app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP
                                        else app.hushcast.automute.dsp.AdPolicyMode.OFF
                                    )
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("3. Prepare Up Next", style = MaterialTheme.typography.bodyLarge)
                            Text("Auto-download & process queued episodes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = autoDownloadUpNext,
                            onCheckedChange = { scope.launch { settingsRepository.hushCast.setAutoDownloadUpNext(it) } }
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("4. Where to process", style = MaterialTheme.typography.bodyLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "phone" to "This phone",
                                "computer" to "My computer",
                                "off" to "Off"
                            ).forEach { (key, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = processTarget == key,
                                    onClick = { processTarget = key },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Text(
                            text = when (processTarget) {
                                "phone" -> "Downloads on-device AI models (~455 MB) over Wi-Fi."
                                "computer" -> "Pair your desktop worker in Settings → HushCast."
                                else -> "AI processing disabled; you can turn it on anytime."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                when (processTarget) {
                                    "phone" -> {
                                        app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerEnabled(context, false)
                                        val mm = app.hushcast.automute.engine.ModelManager(context)
                                        if (!mm.areWordMuteModelsReady() || !mm.areAdModelsReady()) {
                                            app.hushcast.automute.worker.ModelDownloadWorker.enqueue(
                                                context = context,
                                                includeAdModels = true,
                                                allowMetered = false
                                            )
                                        }
                                    }
                                    "computer" -> {
                                        app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerEnabled(context, true)
                                    }
                                    "off" -> {
                                        settingsRepository.hushCast.setWordMutingEnabled(false)
                                        settingsRepository.hushCast.setAdPolicyMode(app.hushcast.automute.dsp.AdPolicyMode.OFF)
                                    }
                                }
                                settingsRepository.hushCast.setSetupCompleted(true)
                                showSetupSheet = false
                                showHushCastSheet = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (processTarget == "phone") "Download AI & Get started" else "Get started")
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Image(
    mediaPlayerViewModel: MediaPlayerViewModel,
    compactLayout: Boolean,
    isFullScreen: Boolean = false
) {
    val size = when {
        compactLayout -> 96.dp
        isFullScreen -> 260.dp
        else -> 196.dp
    }
    val cornerRadius = if (isFullScreen && !compactLayout) 24.dp else 16.dp

    AnimatedContent(mediaPlayerViewModel.isLoading) { isLoading ->
        when(isLoading) {
            true -> {
                Box(
                    Modifier.size(size),
                    contentAlignment = Alignment.Center
                ) {
                    ContainedLoadingIndicator(
                        Modifier.size(size / 2)
                    )
                }
            }

                    false -> {
                AnimatedContent(mediaPlayerViewModel.mediaMetadata) { mediaMetadata ->
                    ShimmerAsyncImage(
                        modifier = Modifier
                            .size(size)
                            .clip(RoundedCornerShape(cornerRadius)),

                        model = mediaMetadata?.artworkUri,
                        contentDescription = null,

                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerSeekBarSection(
    vm: MediaPlayerViewModel,
    muteWindows: List<app.hushcast.automute.dsp.MuteWindow>,
    adWindows: List<app.hushcast.automute.dsp.AdWindow>
) {
    val scope = rememberCoroutineScope()
    var blockSliderUpdate by remember { mutableStateOf(false) }
    var dragSliderState by remember { mutableFloatStateOf(0f) }

    val progressState = vm.getProgressState()
    LaunchedEffect(blockSliderUpdate, progressState.value) {
        if (blockSliderUpdate && kotlin.math.abs(progressState.value - dragSliderState) < 0.02f) {
            blockSliderUpdate = false
        }
    }
    val displayValue = if (blockSliderUpdate) dragSliderState else progressState.value

    app.podiumpodcasts.podium.ui.component.media.AnnotatedPlaybackSlider(
        value = displayValue,
        durationMs = vm.currentDuration,
        muteWindows = muteWindows,
        adWindows = adWindows,
        onValueChange = {
            dragSliderState = it
            blockSliderUpdate = true
        },
        onValueChangeFinished = {
            blockSliderUpdate = true
            vm.seek((dragSliderState * vm.currentDuration).toLong())
            scope.launch {
                delay(2000L)
                blockSliderUpdate = false
            }
        }
    )

    val context = LocalContext.current
    val settingsRepository = remember(context) { app.podiumpodcasts.podium.SettingsRepository(context) }
    val remainingMode by settingsRepository.behavior.remainingTimeDisplay.collectAsState(
        initial = app.podiumpodcasts.podium.RemainingTimeDisplayMode.BOTH
    )

    val currentPosMs = (displayValue * vm.currentDuration).toLong().coerceIn(0L, vm.currentDuration.coerceAtLeast(0L))
    val remainingRawMs = (vm.currentDuration - currentPosMs).coerceAtLeast(0L)
    val adjustedRemainingMs = remember(currentPosMs, vm.currentDuration, vm.speed, adWindows) {
        computeAdjustedRemainingMs(
            currentPosMs = currentPosMs,
            durationMs = vm.currentDuration,
            speed = vm.speed,
            skipRanges = HushCastEngineBridge.getActiveSkipRanges()
        )
    }

    val remainingText = when (remainingMode) {
        app.podiumpodcasts.podium.RemainingTimeDisplayMode.RAW -> "-${formatPlayerTime(remainingRawMs)}"
        app.podiumpodcasts.podium.RemainingTimeDisplayMode.ADJUSTED -> "-${formatPlayerTime(adjustedRemainingMs)}"
        app.podiumpodcasts.podium.RemainingTimeDisplayMode.BOTH -> {
            if (kotlin.math.abs(adjustedRemainingMs - remainingRawMs) >= 1000L) {
                "-${formatPlayerTime(remainingRawMs)} (-${formatPlayerTime(adjustedRemainingMs)})"
            } else {
                "-${formatPlayerTime(remainingRawMs)}"
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatPlayerTime(
                time = currentPosMs,
                duration = vm.currentDuration
            ),
            style = Typography.labelMediumEmphasized
        )

        Text(
            text = remainingText,
            style = Typography.labelMediumEmphasized
        )
    }
}

private fun computeAdjustedRemainingMs(
    currentPosMs: Long,
    durationMs: Long,
    speed: Float,
    skipRanges: List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange>
): Long {
    val remainingRaw = (durationMs - currentPosMs).coerceAtLeast(0L)
    if (remainingRaw == 0L) return 0L

    val clipped = skipRanges
        .mapNotNull { r ->
            val s = maxOf(r.startMs, currentPosMs)
            val e = minOf(r.endMs, durationMs)
            if (e > s) s to e else null
        }
        .sortedBy { it.first }

    var skippedMs = 0L
    var curStart = -1L
    var curEnd = -1L
    for ((s, e) in clipped) {
        if (curStart < 0L) {
            curStart = s
            curEnd = e
        } else if (s <= curEnd) {
            curEnd = maxOf(curEnd, e)
        } else {
            skippedMs += (curEnd - curStart)
            curStart = s
            curEnd = e
        }
    }
    if (curStart >= 0L) {
        skippedMs += (curEnd - curStart)
    }

    val effectiveRemaining = (remainingRaw - skippedMs).coerceAtLeast(0L)
    return (effectiveRemaining / speed.coerceAtLeast(0.25f)).toLong()
}

@Composable
private fun LiveSyncedTranscriptCard(
    vm: MediaPlayerViewModel,
    segments: List<app.hushcast.automute.engine.Segment>,
    words: List<app.hushcast.automute.engine.Word>,
    muteWindows: List<app.hushcast.automute.dsp.MuteWindow>,
    adWindows: List<app.hushcast.automute.dsp.AdWindow>,
    onSeekToMs: (Long) -> Unit,
    onToggleWordMute: (app.hushcast.automute.engine.Word, Boolean) -> Unit,
    onPlayAdSegment: ((app.hushcast.automute.dsp.AdWindow, Long) -> Unit)? = null,
    onCopyReport: (() -> Unit)? = null,
    summary: String? = null,
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto> = emptyList(),
    modifier: Modifier = Modifier
) {
    SyncedTranscriptCard(
        modifier = modifier,
        segments = segments,
        words = words,
        muteWindows = muteWindows,
        adWindows = adWindows,
        currentPositionMs = vm.rememberLivePositionMs(100L),
        onSeekToMs = onSeekToMs,
        onToggleWordMute = onToggleWordMute,
        onPlayAdSegment = onPlayAdSegment,
        onCopyReport = onCopyReport,
        summary = summary,
        chapters = chapters
    )
}

@Composable
private fun LiveChaptersTabContent(
    vm: MediaPlayerViewModel,
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto>,
    skippedChapterStarts: Set<Long>,
    onSeekToMs: (Long) -> Unit,
    onToggleSkipChapter: (app.hushcast.automute.desktop.DesktopChapterDto, Boolean) -> Unit,
    onAnalyzeNowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    app.podiumpodcasts.podium.ui.component.media.ChaptersTabContent(
        chapters = chapters,
        currentPositionMs = vm.rememberLivePositionMs(500L),
        episodeDurationMs = vm.currentDuration,
        skippedChapterStarts = skippedChapterStarts,
        onSeekToMs = onSeekToMs,
        onToggleSkipChapter = onToggleSkipChapter,
        onAnalyzeNowClick = onAnalyzeNowClick,
        modifier = modifier
    )
}

@Composable
private fun ActiveChapterPill(
    vm: MediaPlayerViewModel,
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto>,
    onClick: () -> Unit
) {
    val posMs = vm.rememberLivePositionMs(500L)
    val activeChapter = remember(chapters, posMs) {
        chapters.lastOrNull { it.startMs <= posMs } ?: chapters.firstOrNull()
    } ?: return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = "Ch: ${activeChapter.title}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}