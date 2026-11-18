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
import dev.chrisbanes.haze.hazeEffect

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

            val detailPlayerVm = androidx.lifecycle.viewmodel.compose.viewModel<app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.PlayerScreenViewModel>()
            val detailMediaVm = androidx.lifecycle.viewmodel.compose.viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
            androidx.compose.runtime.LaunchedEffect(episode.id, episode.duration) {
                detailPlayerVm.bindEpisode(episode.id, episode.duration * 1000L)
            }
            val detailChapters by detailPlayerVm.resolvedChapters.collectAsState(initial = emptyList())
            val detailTranscriptState by detailPlayerVm.transcriptState.collectAsState(
                initial = app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.TranscriptLoadState.Loading
            )
            val detailMutes by detailPlayerVm.muteSegments.collectAsState(initial = emptyList())

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
                        colors = ToggleButtonDefaults.toggleButtonColors(
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
                        app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(downloadContext).isNotBlank()
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
                                text = { Text("Play Next") },
                                onClick = {
                                    showMoreMenu = false
                                    detailMediaVm.playNext(downloadContext, bundle)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Play Last") },
                                onClick = {
                                    showMoreMenu = false
                                    detailMediaVm.enqueue(downloadContext, bundle)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save to List") },
                                onClick = {
                                    showMoreMenu = false
                                    showSaveToList = true
                                }
                            )
                            if (detailChapters.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Generate chapters") },
                                    onClick = {
                                        showMoreMenu = false
                                        downloadScope.launch {
                                            val localFile = episode.craftDownloadFile(downloadContext)
                                            if (isAlreadyDownloaded && !hasDesktopUrl && localFile.exists()) {
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
                            DropdownMenuItem(
                                text = { Text("Process with HushCast") },
                                onClick = {
                                    showMoreMenu = false
                                    downloadScope.launch {
                                        val localFile = episode.craftDownloadFile(downloadContext)
                                        if (isAlreadyDownloaded && !hasDesktopUrl && localFile.exists()) {
                                            app.hushcast.automute.storage.AutoMuteDatabase.getInstance(downloadContext)
                                                .dao()
                                                .upsertStatus(
                                                    app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                                                        episodeId = episode.id,
                                                        badge = app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name,
                                                        progressPercent = 0,
                                                        stageLabel = "Queued for AI analysis…"
                                                    )
                                                )
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
                            if (hasDesktopUrl) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isAlreadyDownloaded) "Refresh on Desktop / Cloud"
                                            else "Download via Desktop / Cloud"
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
                                        if (isAlreadyDownloaded) "Re-download & Analyze On-Device"
                                        else "Download & Analyze On-Device"
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
                                        if (isAlreadyDownloaded) "Re-download Audio Only (Skip Analysis)"
                                        else "Download Audio Only (Skip Analysis)"
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
                                            detailMediaVm.play(context, bundle)
                                            detailMediaVm.seek(ch.startMs)
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
                        app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.TranscriptOverlay(
                            state = detailTranscriptState,
                            episodeTitle = episode.title,
                            currentPositionMs = if (detailMediaVm.metadataEpisodeId == episode.id) detailMediaVm.rememberLivePositionMs(100L) else 0L,
                            isPlaying = detailMediaVm.metadataEpisodeId == episode.id && detailMediaVm.isPlaying,
                            muteWindows = detailMutes.map { it.toDomain() },
                            onClose = { showDetailTranscriptSheet = false },
                            onSeekToMs = { posMs ->
                                if (detailMediaVm.metadataEpisodeId != episode.id) {
                                    detailMediaVm.play(context, bundle)
                                }
                                detailMediaVm.seek(posMs)
                            },
                            onMuteWord = { word ->
                                detailPlayerVm.addMuteWordRule(word.text, app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode)
                            },
                            onShareTxt = { lines ->
                                detailPlayerVm.shareTranscriptTxt(context, episode.title, lines)
                            },
                            onRetry = { detailPlayerVm.retryPublisherTranscript() },
                            onProcessNow = { showDetailTranscriptSheet = false },
                            onDownloadNow = { showDetailTranscriptSheet = false },
                            bottomControlsPadding = 24.dp
                        )
                    }
                }
            }

            // Show notes expanded by default (Pocket Casts style)
            Column(Modifier.padding(16.dp)) {
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

                            Spacer(Modifier.height(16.dp))

                            DetailsList(
                                items = listOf(
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
                                    DetailsListItemModel(
                                        icon = Icons.Rounded.AudioFile,
                                        label = R.string.common_audio_file,
                                        value = episode.audioUrl,
                                        onClick = {
                                            uriHandler.openUri(episode.audioUrl)
                                        }
                                    ),
                                    DetailsListItemModel(
                                        icon = Icons.AutoMirrored.Rounded.Label,
                                        label = R.string.common_guid,
                                        value = episode.guid
                                    )
                                )
                            )
            }

            FloatingMediaPlayerSpacer()
            Spacer(Modifier.height(24.dp))
        }
    }
}