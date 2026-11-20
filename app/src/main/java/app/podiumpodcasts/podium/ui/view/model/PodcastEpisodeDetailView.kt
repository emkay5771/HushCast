package app.podiumpodcasts.podium.ui.view.model

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.hushcast.automute.storage.toDomain
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.ui.component.DetailsList
import app.podiumpodcasts.podium.ui.component.DetailsListItemModel
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.common.ExpandableText
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.model.ContentFavoriteButton
import app.podiumpodcasts.podium.ui.component.model.ContentSaveToListButton
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeDownloadButton
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeMarkAsPlayedButton
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodePlayButton
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.formatEpisodePlayTime
import app.podiumpodcasts.podium.ui.formatPubDate
import app.podiumpodcasts.podium.ui.theme.Typography

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodeDetailView(
    bundle: PodcastEpisodeBundle,
    parent: PodcastModel?,
    showParentLink: Boolean = false,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onShowParent: () -> Unit = { },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val episode = bundle.episode

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton {
                        onBack()
                    }
                },
                title = {
                    if(parent != null) {
                        Text(
                            text = parent.fetchTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                            style = Typography.titleMedium,
                            modifier = if(showParentLink) Modifier.clickable { onShowParent() } else Modifier
                        )
                    }
                },
                actions = {
                    if(showParentLink && parent != null) {
                        FilledIconButton(
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            onClick = { onShowParent() }
                        ) {
                            ShimmerAsyncImage(
                                modifier = Modifier
                                    .size(IconButtonDefaults.mediumIconSize)
                                    .clip(CircleShape),
                                model = parent.imageUrl,
                                contentDescription = stringResource(R.string.common_cover_image)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor,
                    scrolledContainerColor = backgroundColor
                )
            )
        },
        containerColor = backgroundColor
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .verticalScroll(rememberScrollState())
        ) {
            // Pocket Casts-style compact episode header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                ShimmerAsyncImage(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = showParentLink) { onShowParent() },
                    model = episode.imageUrl ?: parent?.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    SelectionContainer {
                        Text(
                            text = episode.title,
                            style = Typography.titleLargeEmphasized,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if(bundle.episode.new) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    text = stringResource(R.string.common_new_uppercase),
                                    style = Typography.labelSmallEmphasized
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }

                        Text(
                            text = buildString {
                                append(formatPubDate(context, episode.pubDate))
                                if(episode.duration > 0) {
                                    append(" • ")
                                    append(formatEpisodePlayTime(context, episode.duration))
                                }
                            },
                            style = Typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val detailPlayerVm = androidx.lifecycle.viewmodel.compose.viewModel<app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.PlayerScreenViewModel>(
                key = "episode_detail_shared"
            )
            val detailMediaVm = androidx.lifecycle.viewmodel.compose.viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
            LaunchedEffect(episode.id, episode.duration) {
                detailPlayerVm.bindEpisode(episode.id, episode.duration * 1000L)
            }
            val chaptersScoped by detailPlayerVm.resolvedChaptersScoped.collectAsState()
            val transcriptScoped by detailPlayerVm.transcriptStateScoped.collectAsState()
            val mutesScoped by detailPlayerVm.muteSegmentsScoped.collectAsState()
            val adsScoped by detailPlayerVm.adSegmentsScoped.collectAsState()
            val detailChapters = chaptersScoped.valueFor(episode.id) ?: emptyList()
            val detailTranscriptState = transcriptScoped.valueFor(episode.id)
                ?: app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.TranscriptLoadState.Loading
            val detailMutes = mutesScoped.valueFor(episode.id) ?: emptyList()
            val detailAds = adsScoped.valueFor(episode.id) ?: emptyList()
            val devMode by app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
                .hushCast.devMode.collectAsState(initial = false)
            var pendingWordMute by remember { mutableStateOf<app.hushcast.automute.engine.Word?>(null) }
            var showNeedsDownloadDialog by remember { mutableStateOf(false) }
            val pageScope = rememberCoroutineScope()
            val pageDb = app.podiumpodcasts.podium.ui.helper.LocalDatabase.current

            // Pocket Casts-style balanced action row
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PodcastEpisodePlayButton(
                        bundle = bundle,
                        colors = ToggleButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )

                    Spacer(Modifier.weight(1f))

                    PodcastEpisodeDownloadButton(
                        icon = true,
                        bundle = bundle
                    )

                    app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeQueueButton(
                        bundle = bundle
                    )

                    PodcastEpisodeMarkAsPlayedButton(
                        bundle = bundle,
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )

                    ContentFavoriteButton(
                        contentId = episode.id,
                        isPodcast = false
                    )

                    val downloadScope = rememberCoroutineScope()
                    val downloadDb = app.podiumpodcasts.podium.ui.helper.LocalDatabase.current
                    val downloadContext = LocalContext.current
                    var showMoreMenu by remember { mutableStateOf(false) }
                    var showSaveToList by remember { mutableStateOf(false) }
                    val isAlreadyDownloaded = bundle.download?.state ==
                        app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState.DOWNLOADED.value
                    val hasDesktopUrl = remember(downloadContext) {
                        app.hushcast.automute.engine.HushCastPrefs.isWorkerActive(downloadContext)
                    }
                    if (showSaveToList) {
                        app.podiumpodcasts.podium.ui.dialog.bottomsheet.SaveToListBottomSheet(
                            contentId = episode.id,
                            isPodcast = false
                        ) {
                            showSaveToList = false
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                contentDescription = "More episode actions"
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play next") },
                                onClick = {
                                    showMoreMenu = false
                                    detailMediaVm.playNext(downloadContext, bundle)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Play last") },
                                onClick = {
                                    showMoreMenu = false
                                    detailMediaVm.enqueue(downloadContext, bundle)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save to list") },
                                onClick = {
                                    showMoreMenu = false
                                    showSaveToList = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    showMoreMenu = false
                                    val link = episode.link.ifBlank { episode.audioUrl }
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, episode.title)
                                        putExtra(
                                            android.content.Intent.EXTRA_TEXT,
                                            if (link.isBlank()) episode.title else "${episode.title}\n$link"
                                        )
                                    }
                                    runCatching {
                                        downloadContext.startActivity(
                                            android.content.Intent.createChooser(shareIntent, "Share episode")
                                        )
                                    }
                                }
                            )
                            // I13: one entry, routed like every other "Process" button.
                            DropdownMenuItem(
                                text = { Text("Process episode") },
                                onClick = {
                                    showMoreMenu = false
                                    downloadScope.launch {
                                        val outcome = app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.start(
                                            context = downloadContext,
                                            db = downloadDb,
                                            episodeId = episode.id,
                                            actionMode = app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode
                                        )
                                        if (outcome == app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.Outcome.NEEDS_DOWNLOAD) {
                                            showNeedsDownloadDialog = true
                                        } else {
                                            android.widget.Toast.makeText(
                                                downloadContext,
                                                app.podiumpodcasts.podium.ui.hushcast.HushCastAnalyze.message(outcome),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                            // Stage-specific variants, for testing only.
                            if (devMode) {
                                androidx.compose.material3.HorizontalDivider()
                                if (detailChapters.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Generate chapters (developer)") },
                                        onClick = {
                                            showMoreMenu = false
                                            downloadScope.launch {
                                                val localFile = episode.craftDownloadFile(downloadContext)
                                                val hasFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { localFile.exists() }
                                                if (isAlreadyDownloaded && !hasDesktopUrl && hasFile) {
                                                    app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueAnalyzeNow(
                                                        context = downloadContext,
                                                        episodeId = episode.id,
                                                        audioPath = localFile.absolutePath,
                                                        actionMode = app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode,
                                                        episodeTitle = episode.title
                                                    )
                                                } else {
                                                    app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                        context = downloadContext,
                                                        db = downloadDb,
                                                        episodeId = episode.id,
                                                        downloadMethod = if (hasDesktopUrl) {
                                                            app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP
                                                        } else {
                                                            app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE
                                                        },
                                                        forceReprocess = isAlreadyDownloaded
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                                if (hasDesktopUrl) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isAlreadyDownloaded) "Refresh on worker (developer)"
                                                else "Download via worker (developer)"
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            downloadScope.launch {
                                                app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                    context = downloadContext,
                                                    db = downloadDb,
                                                    episodeId = episode.id,
                                                    downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP,
                                                    forceReprocess = isAlreadyDownloaded
                                                )
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isAlreadyDownloaded) "Re-download and analyze on phone (developer)"
                                            else "Download and analyze on phone (developer)"
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        downloadScope.launch {
                                            app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                context = downloadContext,
                                                db = downloadDb,
                                                episodeId = episode.id,
                                                downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE
                                            )
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isAlreadyDownloaded) "Re-download audio only (developer)"
                                            else "Download audio only (developer)"
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        downloadScope.launch {
                                            app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                context = downloadContext,
                                                db = downloadDb,
                                                episodeId = episode.id,
                                                downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (showNeedsDownloadDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showNeedsDownloadDialog = false },
                    title = { Text("Download and process this episode?") },
                    text = { Text("HushCast needs the episode on your phone to transcribe it and find ads.") },
                    confirmButton = {
                        androidx.compose.material3.Button(
                            onClick = {
                                showNeedsDownloadDialog = false
                                pageScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val method = if (app.hushcast.automute.engine.HushCastPrefs.isWorkerActive(context)) {
                                        app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO
                                    } else {
                                        app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE
                                    }
                                    app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                        context = context,
                                        db = pageDb,
                                        episodeId = episode.id,
                                        downloadMethod = method
                                    )
                                }
                            }
                        ) {
                            Text("Download & process")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showNeedsDownloadDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // T1 & C6: Transcript & Chapters on Episode Detail page
            var showDetailTranscriptSheet by remember { mutableStateOf(false) }

            if (detailTranscriptState is app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.TranscriptLoadState.Ready) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { showDetailTranscriptSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View transcript")
                    }
                }
            }

            if (detailChapters.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "Chapters (${detailChapters.size})",
                                style = Typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            detailChapters.forEach { ch ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            detailMediaVm.play(context, bundle, startPositionMs = ch.startMs)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ch.title,
                                        style = Typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = android.text.format.DateUtils.formatElapsedTime(ch.startMs / 1000L),
                                        style = Typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showDetailTranscriptSheet) {
                androidx.compose.material3.ModalBottomSheet(
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    sheetMaxWidth = androidx.compose.ui.unit.Dp.Infinity,
                    onDismissRequest = { showDetailTranscriptSheet = false }
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(640.dp)) {
                        val isCurrentEpisode = detailMediaVm.metadataEpisodeId == episode.id
                        val livePos = detailMediaVm.rememberLivePositionMsState(100L)
                        val notPlayingPos = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                        val detailAdWindows = remember(detailAds) { detailAds.map { it.toDomain() } }
                        val autoSkipStarts = remember(detailAdWindows, isCurrentEpisode) {
                            if (!isCurrentEpisode) emptySet()
                            else app.podiumpodcasts.podium.background.HushCastEngineBridge.adSkipObserver
                                .getActiveAutoSkipRanges().map { it.startMs }.toSet()
                        }
                        app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.TranscriptOverlay(
                            state = detailTranscriptState,
                            episodeTitle = episode.title,
                            positionMsState = if (isCurrentEpisode) livePos else notPlayingPos,
                            isPlaying = isCurrentEpisode && detailMediaVm.isPlaying,
                            muteWindows = remember(detailMutes) { detailMutes.map { it.toDomain() } },
                            adWindows = detailAdWindows,
                            autoSkipAdStarts = autoSkipStarts,
                            // T3: only for the episode that's playing (the skip state lives in the player).
                            onPlayAnyway = if (isCurrentEpisode) {
                                { ad ->
                                    app.podiumpodcasts.podium.background.HushCastEngineBridge.adSkipObserver.bypassAd(ad.startMs)
                                    detailMediaVm.seek(ad.startMs)
                                    if (!detailMediaVm.isPlaying) detailMediaVm.play()
                                }
                            } else null,
                            onClose = { showDetailTranscriptSheet = false },
                            onSeekToMs = { posMs ->
                                if (detailMediaVm.metadataEpisodeId != episode.id) {
                                    detailMediaVm.play(context, bundle, startPositionMs = posMs)
                                } else {
                                    detailMediaVm.seek(posMs)
                                }
                            },
                            // Item 5: same as the player: unmute turns this spot's segment(s) off,
                            // mute adds the word to the list.
                            onMuteWord = { word -> pendingWordMute = word },
                            onShareTxt = { lines ->
                                detailPlayerVm.shareTranscriptTxt(context, episode.title, lines)
                            },
                            onRetry = { detailPlayerVm.retryPublisherTranscript() },
                            onProcessNow = null,
                            onDownloadNow = null,
                            bottomControlsPadding = 24.dp
                        )
                    }
                }
            }

            pendingWordMute?.let { word ->
                app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.WordMuteDialog(
                    word = word,
                    muteSegments = detailMutes,
                    onUnmuteSegments = { ids -> detailPlayerVm.setMuteSegmentsEnabled(ids, false) },
                    onAddMuteWord = { clean ->
                        detailPlayerVm.addMuteWordRule(clean, app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode)
                    },
                    onDismiss = { pendingWordMute = null }
                )
            }

            // Show notes expanded by default (Pocket Casts style)
            Column(Modifier.padding(16.dp)) {
                val soundbites = remember(episode.soundbitesJson) {
                    app.podiumpodcasts.podium.api.rss.PodcastMetadataJson.decodeSoundbites(episode.soundbitesJson)
                }
                val episodePersons = remember(episode.personsJson) {
                    app.podiumpodcasts.podium.api.rss.PodcastMetadataJson.decodePersons(episode.personsJson)
                }

                if (soundbites.isNotEmpty()) {
                    val soundbiteContext = LocalContext.current
                    app.podiumpodcasts.podium.ui.component.model.podcast.EpisodeSoundbitesRow(
                        soundbites = soundbites,
                        onPlaySoundbite = { sb ->
                            detailMediaVm.playSoundbite(soundbiteContext, bundle, sb)
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    val parsedDesc = remember(episode.id, episode.description) {
                        AnnotatedString.fromHtml(
                            htmlString = episode.description.replace("\n", "<br>")
                        )
                    }
                    SelectionContainer(Modifier.padding(16.dp)) {
                        Text(
                            text = parsedDesc,
                            style = Typography.bodyMedium
                        )
                    }
                }

                if (episodePersons.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    app.podiumpodcasts.podium.ui.component.model.podcast.PodcastPersonsRow(
                        persons = episodePersons,
                        onClickUrl = { uriHandler.openUri(it) }
                    )
                }

                            Spacer(Modifier.height(16.dp))

                            DetailsList(
                                items = listOfNotNull(
                                    DetailsListItemModel(
                                        icon = Icons.Rounded.Person,
                                        label = R.string.common_author,
                                        value = episode.author
                                    ),
                                    DetailsListItemModel(
                                        icon = Icons.Rounded.AccessTime,
                                        label = R.string.common_duration,
                                        value = formatEpisodePlayTime(
                                            context = LocalContext.current,
                                            duration = episode.duration
                                        )
                                    ),
                                    if (episode.commentsUrl.isNotBlank()) DetailsListItemModel(
                                        icon = Icons.Rounded.Link,
                                        label = R.string.common_comments,
                                        value = episode.commentsUrl,
                                        onClick = {
                                            uriHandler.openUri(episode.commentsUrl)
                                        }
                                    ) else null,
                                    DetailsListItemModel(
                                        icon = Icons.Rounded.Link,
                                        label = R.string.common_source,
                                        value = episode.link,
                                        onClick = {
                                            uriHandler.openUri(episode.link)
                                        }
                                    ),
                                    DetailsListItemModel(
                                        icon = Icons.Rounded.RssFeed,
                                        label = R.string.common_rss_feed,
                                        value = episode.origin
                                    ),
                                    // I13: the audio URL and GUID are for debugging; developer mode only.
                                    if (devMode) DetailsListItemModel(
                                        icon = Icons.Rounded.AudioFile,
                                        label = R.string.common_audio_file,
                                        value = episode.audioUrl,
                                        onClick = {
                                            uriHandler.openUri(episode.audioUrl)
                                        }
                                    ) else null,
                                    if (devMode) DetailsListItemModel(
                                        icon = Icons.AutoMirrored.Rounded.Label,
                                        label = R.string.common_guid,
                                        value = episode.guid
                                    ) else null
                                )
                            )
            }

            FloatingMediaPlayerSpacer()
            Spacer(Modifier.height(24.dp))
        }
    }
}