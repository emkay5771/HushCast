package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
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
    PLAYER("Player"),
    NOTES("Notes"),
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
    var transcriptBlob by remember(episodeId) { mutableStateOf<WordIndexBlob?>(null) }

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
                }.getOrDefault(emptyList())
            } ?: emptyList()
            blob to chapters
        }
        transcriptBlob = decodedBlob
        episodeSummary = insights?.summary
        episodeChapters = decodedChapters
    }

    val chapterSkipPrefs = remember(context) {
        context.getSharedPreferences("hushcast_chapter_skips", android.content.Context.MODE_PRIVATE)
    }
    var skippedChapterStarts by remember(episodeId) {
        val saved = if (episodeId.isNotBlank()) {
            chapterSkipPrefs.getStringSet(episodeId, emptySet())
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet()
                ?: emptySet()
        } else {
            emptySet()
        }
        mutableStateOf(saved)
    }
    fun toggleSkippedChapter(startMs: Long, skip: Boolean) {
        val updated = if (skip) skippedChapterStarts + startMs else skippedChapterStarts - startMs
        skippedChapterStarts = updated
        if (episodeId.isNotBlank()) {
            chapterSkipPrefs.edit()
                .putStringSet(episodeId, updated.map { it.toString() }.toSet())
                .apply()
        }
    }

    var lastAutoSkippedChapterStart by remember(episodeId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(episodeId, vm.currentPosition, episodeChapters, skippedChapterStarts) {
        if (episodeId.isBlank() || episodeChapters.isEmpty() || skippedChapterStarts.isEmpty()) return@LaunchedEffect
        val posMs = vm.currentPosition
        val activeIdx = episodeChapters.indexOfLast { it.startMs <= posMs }
        if (activeIdx < 0) return@LaunchedEffect
        val active = episodeChapters[activeIdx]
        if (active.startMs in skippedChapterStarts) {
            if (lastAutoSkippedChapterStart == active.startMs) return@LaunchedEffect
            val nextEnabled = episodeChapters
                .drop(activeIdx + 1)
                .firstOrNull { it.startMs !in skippedChapterStarts }
            val targetMs = nextEnabled?.startMs ?: vm.currentDuration
            if (targetMs > posMs + 300L) {
                lastAutoSkippedChapterStart = active.startMs
                vm.seek(targetMs)
            }
        } else if (lastAutoSkippedChapterStart != null) {
            lastAutoSkippedChapterStart = null
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

    var isFullScreen by remember { mutableStateOf(false) }

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
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onVerticalDrag = { _, dragAmount ->
                                    totalDrag += dragAmount
                                    if (totalDrag < -60f && !isFullScreen) {
                                        totalDrag = 0f
                                        isFullScreen = true
                                    } else if (totalDrag > 60f && isFullScreen) {
                                        totalDrag = 0f
                                        isFullScreen = false
                                    }
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PrimaryTabRow(
                        modifier = Modifier.weight(1f),
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = Color.Transparent
                    ) {
                        MediaPlayerTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = {
                                    Text(
                                        text = tab.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    IconButton(
                        modifier = Modifier.padding(end = 4.dp),
                        onClick = { isFullScreen = !isFullScreen }
                    ) {
                        Icon(
                            imageVector = if (isFullScreen) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                            contentDescription = if (isFullScreen) "Collapse player" else "Expand player"
                        )
                    }
                }

                when (selectedTab) {
                    MediaPlayerTab.CHAPTERS -> {
                        app.podiumpodcasts.podium.ui.component.media.ChaptersTabContent(
                            chapters = episodeChapters,
                            currentPositionMs = vm.currentPosition,
                            episodeDurationMs = vm.currentDuration,
                            skippedChapterStarts = skippedChapterStarts,
                            onSeekToMs = { ms -> vm.seek(ms) },
                            onToggleSkipChapter = { ch, skip -> toggleSkippedChapter(ch.startMs, skip) },
                            onAnalyzeNowClick = { triggerAnalyzeNow() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isFullScreen) Modifier.weight(1f)
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
                                    if (isFullScreen) Modifier.weight(1f)
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
                                    if (isFullScreen) Modifier.weight(1f)
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
                                    onCopyReport = {
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
                                    },
                                    onToggleWordMute = { word, mute ->
                                        val clean = word.text.trim().trim('.', ',', '!', '?', '"', '\'')
                                        if (clean.isNotEmpty() && episodeId.isNotBlank()) {
                                            scope.launch {
                                                if (mute) {
                                                    autoMuteDb.dao().upsertRuleIfNew(
                                                        MuteRuleEntity(
                                                            pattern = clean,
                                                            actionMode = HushCastEngineBridge.defaultActionMode.name,
                                                            category = "Transcript"
                                                        )
                                                    )
                                                    android.widget.Toast.makeText(context, "Muted \"$clean\" (long-press again to undo)", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val matching = autoMuteDb.dao().getAllRules().filter {
                                                        it.pattern.equals(clean, ignoreCase = true)
                                                    }
                                                    matching.forEach { autoMuteDb.dao().deleteRule(it.id) }
                                                    android.widget.Toast.makeText(context, "Unmuted \"$clean\"", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                val sha = analysisStatus?.fileSha256?.takeIf { it.isNotBlank() }
                                                if (sha != null) {
                                                    app.hushcast.automute.pipeline.AutoMuteAnalyzer(context, autoMuteDb)
                                                        .reevaluateMuteRules(sha, episodeId, defaultActionMode = HushCastEngineBridge.defaultActionMode)
                                                    val updated = autoMuteDb.dao().getMuteSegments(sha).map { it.toDomain() }
                                                    HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    MediaPlayerTab.PLAYER -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                dismissGracefully {
                                    onOpenEpisode(
                                        vm.metadataOrigin!!,
                                        vm.metadataEpisodeId!!
                                    )
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if(!compactLayout) {
                            Image(
                                mediaPlayerViewModel = vm,
                                compactLayout = false
                            )

                            Spacer(Modifier.height(16.dp))
                        }

                        AnimatedContent(vm.mediaMetadata) { mediaMetadata ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if(compactLayout) {
                                    Image(
                                        mediaPlayerViewModel = vm,
                                        compactLayout = true
                                    )

                                    Spacer(Modifier.width(32.dp))
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

                                    Spacer(Modifier.height(10.dp))

                                    val badge = runCatching {
                                        analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) }
                                    }.getOrNull() ?: ReadinessBadge.NOT_PROCESSED

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(
                                            8.dp,
                                            if (compactLayout) Alignment.Start else Alignment.CenterHorizontally
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        when (vm.mediaSourceType) {
                                            is SourceTypeState.Downloaded -> {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                ) {
                                                    Row(
                                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            modifier = Modifier.size(14.dp),
                                                            imageVector = Icons.Rounded.Download,
                                                            contentDescription = stringResource(R.string.common_downloaded)
                                                        )
                                                        Text(
                                                            text = stringResource(R.string.common_downloaded),
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                }
                                            }

                                            is SourceTypeState.Streaming -> {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                ) {
                                                    Row(
                                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            modifier = Modifier.size(14.dp),
                                                            imageVector = Icons.Rounded.Podcasts,
                                                            contentDescription = stringResource(R.string.common_streaming)
                                                        )
                                                        Text(
                                                            text = stringResource(R.string.common_streaming),
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        EpisodeReadinessBadge(
                                            badge = badge,
                                            progressPercent = analysisStatus?.progressPercent ?: 0,
                                            onAnalyzeNowClick = if (episodeId.isNotBlank()) {
                                                { triggerAnalyzeNow() }
                                            } else null
                                        )

                                        if (episodeChapters.isNotEmpty()) {
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

                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isCastingExternal) {
                            CastingBypassNoticeBanner()
                            Spacer(Modifier.height(8.dp))
                        }

                        pendingUndo?.let { undoEvent ->
                            OneTapUndoAdSkipBanner(
                                event = undoEvent,
                                onUndo = {
                                    vm.seek(undoEvent.jumpedFromMs)
                                    HushCastEngineBridge.clearPendingUndo()
                                },
                                onDismiss = { HushCastEngineBridge.clearPendingUndo() }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        androidx.compose.material3.TextButton(onClick = { showSpeedBottomSheet = true }) {
                            Text(
                                text = String.format(Locale.getDefault(), "%.1fx", vm.speed),
                                style = Typography.labelLargeEmphasized,
                                color = if(vm.speed != 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        androidx.compose.material3.IconButton(onClick = { showSleepTimerBottomSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.Bedtime,
                                contentDescription = stringResource(R.string.common_sleep_timer),
                                tint = if(sleepTimerState.value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        androidx.compose.material3.IconButton(onClick = { showQueueBottomSheet = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = stringResource(R.string.common_queue),
                                tint = if(vm.queue.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        androidx.compose.material3.IconButton(
                            onClick = {
                                selectedTab = if (selectedTab == MediaPlayerTab.TRANSCRIPT) {
                                    MediaPlayerTab.PLAYER
                                } else {
                                    MediaPlayerTab.TRANSCRIPT
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Subtitles,
                                contentDescription = "Transcript",
                                tint = if(selectedTab == MediaPlayerTab.TRANSCRIPT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        androidx.compose.material3.IconButton(onClick = { showMoreSheet = true }) {
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
            ModalBottomSheet(
                onDismissRequest = { showMoreSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    Text(
                        text = "Player Actions",
                        style = Typography.titleLargeEmphasized,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (onOpenPodcast != null && vm.metadataOrigin != null) {
                        SettingsListItem(
                            icon = { Icon(Icons.Rounded.Podcasts, "Go to Podcast") },
                            label = "Go to Podcast",
                            description = vm.mediaMetadata?.subtitle?.toString() ?: "",
                            index = 0,
                            count = 4,
                            onClick = {
                                showMoreSheet = false
                                dismissGracefully { onOpenPodcast(vm.metadataOrigin!!) }
                            }
                        )
                    }
                    SettingsListItem(
                        icon = { Icon(Icons.Rounded.Info, "Episode Details") },
                        label = "Episode Details & Notes",
                        index = if (onOpenPodcast != null && vm.metadataOrigin != null) 1 else 0,
                        count = if (onOpenPodcast != null && vm.metadataOrigin != null) 4 else 3,
                        onClick = {
                            showMoreSheet = false
                            val origin = vm.metadataOrigin
                            val id = vm.metadataEpisodeId
                            if (origin != null && id != null) {
                                dismissGracefully { onOpenEpisode(origin, id) }
                            }
                        }
                    )
                    SettingsListItem(
                        icon = { Icon(Icons.Rounded.Done, "Mark as Played") },
                        label = "Mark as Played",
                        index = if (onOpenPodcast != null && vm.metadataOrigin != null) 2 else 1,
                        count = if (onOpenPodcast != null && vm.metadataOrigin != null) 4 else 3,
                        onClick = {
                            showMoreSheet = false
                            if (episodeId.isNotBlank()) {
                                scope.launch {
                                    appDb.podcastEpisodePlayStates().savePlayed(episodeId, true)
                                }
                            }
                        }
                    )
                    SettingsListItem(
                        icon = { Icon(Icons.Rounded.AutoAwesome, "Re-analyze Audio") },
                        label = "Analyze with HushCast AI",
                        description = "Scan for muted words and sponsor segments",
                        index = if (onOpenPodcast != null && vm.metadataOrigin != null) 3 else 2,
                        count = if (onOpenPodcast != null && vm.metadataOrigin != null) 4 else 3,
                        onClick = {
                            showMoreSheet = false
                            triggerAnalyzeNow()
                        }
                    )
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
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Image(
    mediaPlayerViewModel: MediaPlayerViewModel,
    compactLayout: Boolean
) {
    val size = when(compactLayout) {
        true -> 96.dp
        false -> 196.dp
    }

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
                            .clip(RoundedCornerShape(16.dp)),

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
                delay(400)
                blockSliderUpdate = false
            }
        }
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatPlayerTime(
                time = (displayValue * vm.currentDuration).toLong(),
                duration = vm.currentDuration
            ),
            style = Typography.labelMediumEmphasized
        )

        Text(
            text = formatPlayerTime(
                time = vm.currentDuration
            ),
            style = Typography.labelMediumEmphasized
        )
    }
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
        currentPositionMs = vm.currentPosition,
        onSeekToMs = onSeekToMs,
        onToggleWordMute = onToggleWordMute,
        onPlayAdSegment = onPlayAdSegment,
        onCopyReport = onCopyReport,
        summary = summary,
        chapters = chapters
    )
}

@Composable
private fun ActiveChapterPill(
    vm: MediaPlayerViewModel,
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto>,
    onClick: () -> Unit
) {
    val posMs = vm.currentPosition
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