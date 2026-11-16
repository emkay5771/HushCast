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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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

    var blockSliderUpdate by remember { mutableStateOf(false) }

    var sliderState by remember { mutableFloatStateOf(0f) }

    val animateSliderState = remember { Animatable(0f) }
    LaunchedEffect(animateSliderState.value) {
        if(blockSliderUpdate) return@LaunchedEffect
        sliderState = animateSliderState.value
    }

    val progressState = vm.getProgressState()
    LaunchedEffect(progressState.value) {
        if(blockSliderUpdate) return@LaunchedEffect
        animateSliderState.snapTo(sliderState)
        animateSliderState.animateTo(progressState.value, tween(100))
    }

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
    var currentEpisodeBundle by remember(episodeId) { mutableStateOf<PodcastEpisodeBundle?>(null) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showNotDownloadedDialog by remember { mutableStateOf(false) }
    var transcriptBlob by remember(episodeId) { mutableStateOf<WordIndexBlob?>(null) }

    LaunchedEffect(episodeId) {
        currentEpisodeBundle = if (episodeId.isNotBlank()) {
            runCatching { appDb.podcastEpisodes().getSync(episodeId) }.getOrNull()
        } else null
    }

    fun triggerAnalyzeNow() {
        if (episodeId.isBlank()) return
        scope.launch {
            val bundle = currentEpisodeBundle ?: runCatching { appDb.podcastEpisodes().getSync(episodeId) }.getOrNull()
            val audioPath = bundle?.episode?.craftDownloadFile(context)?.absolutePath
            if (audioPath != null && java.io.File(audioPath).exists()) {
                EpisodeAnalysisWorker.enqueueAnalyzeNow(
                    context = context,
                    episodeId = episodeId,
                    audioPath = audioPath,
                    actionMode = HushCastEngineBridge.defaultActionMode,
                    episodeTitle = bundle.episode.title
                )
                android.widget.Toast.makeText(
                    context,
                    "Starting HushCast AI analysis…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                showNotDownloadedDialog = true
            }
        }
    }

    LaunchedEffect(episodeId, analysisStatus?.badge) {
        if (episodeId.isBlank()) return@LaunchedEffect
        val idx = autoMuteDb.dao().getWordIndexByEpisode(episodeId)
        transcriptBlob = idx?.let { runCatching { BlobCodec.decompress(it.compressedBlob) }.getOrNull() }
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

    SwitchableDynamicMaterialExpressiveTheme(
        enable = enableArtworkColors.value,
        seedColor = Color(vm.metadataImageSeedColor ?: 1)
    ) {
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = onDismiss
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                totalDrag += dragAmount
                                if(totalDrag < -60f) {
                                    totalDrag = 0f
                                    showQueueBottomSheet = true
                                }
                            }
                        )
                    }
            ) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color.Transparent
                ) {
                    MediaPlayerTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) }
                        )
                    }
                }

                when (selectedTab) {
                    MediaPlayerTab.NOTES -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp, max = 520.dp)
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
                                SelectionContainer {
                                    Text(
                                        text = AnnotatedString.fromHtml(
                                            htmlString = desc.replace("\n", "<br>")
                                        ),
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
                                .heightIn(min = 320.dp, max = 520.dp)
                                .verticalScroll(rememberScrollState())
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
                                SyncedTranscriptCard(
                                    segments = transcriptBlob?.segments ?: emptyList(),
                                    words = transcriptBlob?.words ?: emptyList(),
                                    muteWindows = muteWindows,
                                    adWindows = adWindows,
                                    currentPositionMs = vm.currentPosition,
                                    onSeekToMs = { ms -> vm.seek(ms) },
                                    onToggleWordMute = { word, mute ->
                                        val clean = word.text.trim().trim('.', ',', '!', '?', '"', '\'')
                                        if (clean.isNotEmpty() && episodeId.isNotBlank()) {
                                            scope.launch {
                                                if (mute) {
                                                    autoMuteDb.dao().upsertRule(
                                                        MuteRuleEntity(
                                                            pattern = clean,
                                                            actionMode = HushCastEngineBridge.defaultActionMode.name,
                                                            category = "Transcript"
                                                        )
                                                    )
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
                                .verticalScroll(
                                    state = rememberScrollState(),
                                    flingBehavior = null
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                    AnimatedContent(
                        targetState = vm.mediaSourceType
                    ) {
                        when(it) {
                            is SourceTypeState.Downloaded -> {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(12.dp),
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = stringResource(R.string.common_downloaded)
                                        )

                                        Spacer(Modifier.width(8.dp))

                                        Text(
                                            text = stringResource(R.string.common_downloaded),
                                            style = Typography.labelMedium
                                        )
                                    }
                                }
                            }

                            is SourceTypeState.Streaming -> {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(12.dp),
                                            imageVector = Icons.Rounded.Podcasts,
                                            contentDescription = stringResource(R.string.common_info)
                                        )

                                        Spacer(Modifier.width(8.dp))

                                        Text(
                                            text = stringResource(R.string.common_streaming),
                                            style = Typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

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

                                    Spacer(Modifier.height(8.dp))

                                    val badge = runCatching {
                                        analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) }
                                    }.getOrNull() ?: ReadinessBadge.NOT_PROCESSED

                                    EpisodeReadinessBadge(
                                        badge = badge,
                                        progressPercent = analysisStatus?.progressPercent ?: 0,
                                        onAnalyzeNowClick = if (episodeId.isNotBlank()) {
                                            { triggerAnalyzeNow() }
                                        } else null
                                    )
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

                        AnnotatedScrubberOverlay(
                            durationMs = vm.currentDuration,
                            muteWindows = muteWindows,
                            adWindows = adWindows
                        )

                        Slider(
                            modifier = Modifier.fillMaxWidth(),
                            value = sliderState,
                            onValueChange = {
                                sliderState = it
                                blockSliderUpdate = true
                            },
                            onValueChangeFinished = {
                                blockSliderUpdate = true
                                vm.seek((sliderState * vm.currentDuration).toLong())

                                // don't animate slider after seeking
                                scope.launch {
                                    delay(500)
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
                                    time = (sliderState * vm.currentDuration).toLong(),
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

                        Spacer(Modifier.height(16.dp))

                        ButtonGroup(
                            overflowIndicator = {},
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
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showNotDownloadedDialog = false },
                title = { Text("Episode not downloaded") },
                text = {
                    Text("HushCast AI analyzes episodes locally on your device, so the audio file needs to be downloaded first.")
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showNotDownloadedDialog = false
                            if (episodeId.isNotBlank()) {
                                scope.launch {
                                    app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                        context,
                                        appDb,
                                        episodeId
                                    )
                                    android.widget.Toast.makeText(
                                        context,
                                        "Downloading episode…",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Text("Download")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showNotDownloadedDialog = false }
                    ) {
                        Text("Cancel")
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