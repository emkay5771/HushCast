package app.podiumpodcasts.podium.ui.component.model.episode

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.formatPubDate
import app.podiumpodcasts.podium.ui.theme.Typography
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastEpisodeListItem(
    modifier: Modifier = Modifier,
    bundle: PodcastEpisodeBundle,
    overlineText: String = LocalContext.current.let { ctx ->
        androidx.compose.runtime.remember(bundle.episode.pubDate) {
            formatPubDate(ctx, bundle.episode.pubDate)
        }
    },
    titleText: String = bundle.episode.title,
    descriptionText: String = bundle.episode.description,
    leadingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable () -> Unit = { },
    index: Int = 0,
    count: Int = 0,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val statusMap by autoMuteDb.statusMapFlow.collectAsState()
    val analysisStatus = statusMap[bundle.episode.id]
    val readinessBadge = remember(analysisStatus?.badge) {
        runCatching {
            analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) }
        }.getOrNull() ?: ReadinessBadge.NOT_PROCESSED
    }

    val isPlayed = bundle.playState?.played == true
    val durationSec = bundle.episode.duration
    val progressSec = bundle.playState?.state ?: 0
    val hasProgress = !isPlayed && durationSec > 0 && progressSec > 0

    val formattedOverline = if(durationSec > 0) {
        val remainingMin = if(hasProgress) {
            ((durationSec - progressSec).coerceAtLeast(0) / 60).coerceAtLeast(1)
        } else {
            (durationSec / 60).coerceAtLeast(1)
        }
        if(hasProgress) "$overlineText • ${remainingMin}m left" else "$overlineText • ${remainingMin}m"
    } else {
        overlineText
    }

    val effectiveColors = if(isSelected) {
        ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    } else {
        colors
    }

    val baseModifier = (if(count == 1) modifier.clip(RoundedCornerShape(16.dp)) else modifier)
        .let { m ->
            if(onLongClick != null) {
                m.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
            } else m
        }

    SegmentedListItem(
        modifier = baseModifier,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(
            index = index,
            count = count
        ),
        colors = effectiveColors,
        leadingContent = if(isSelected) {
            {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else leadingContent,
        supportingContent = supportingContent,
        overlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnimatedVisibility(
                    visible = bundle.episode.new && !isPlayed,
                    enter = fadeIn() + slideInHorizontally { -it },
                    exit = fadeOut() + slideOutHorizontally { -it }
                ) {
                    Text(
                        modifier = Modifier.padding(end = 2.dp),
                        text = stringResource(R.string.common_new_uppercase),
                        style = Typography.labelSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = formattedOverline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val badgeLabel = when (readinessBadge) {
                    ReadinessBadge.READY -> "AI Ready"
                    ReadinessBadge.WORD_MUTE_READY -> "Word-Mute"
                    ReadinessBadge.ANALYZING -> "${analysisStatus?.progressPercent ?: 0}%"
                    ReadinessBadge.NOT_PROCESSED -> null
                }
                if (badgeLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (readinessBadge) {
                            ReadinessBadge.READY -> MaterialTheme.colorScheme.primaryContainer
                            ReadinessBadge.WORD_MUTE_READY -> MaterialTheme.colorScheme.secondaryContainer
                            ReadinessBadge.ANALYZING -> MaterialTheme.colorScheme.tertiaryContainer
                            ReadinessBadge.NOT_PROCESSED -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = badgeLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = Typography.labelSmall,
                            color = when (readinessBadge) {
                                ReadinessBadge.READY -> MaterialTheme.colorScheme.onPrimaryContainer
                                ReadinessBadge.WORD_MUTE_READY -> MaterialTheme.colorScheme.onSecondaryContainer
                                ReadinessBadge.ANALYZING -> MaterialTheme.colorScheme.onTertiaryContainer
                                ReadinessBadge.NOT_PROCESSED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.alpha(if(isPlayed) 0.62f else 1f)) {
            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    Modifier.weight(1f)
                ) {
                    Text(
                        text = titleText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = Typography.titleMediumEmphasized
                    )

                    Text(
                        text = AnnotatedString.fromHtml(
                            htmlString = descriptionText
                                .replace("\n", "<br>")
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        model = bundle.episode.imageUrl,
                        contentScale = ContentScale.Crop,
                        contentDescription = stringResource(R.string.common_cover_image),
                        targetSizePx = 128
                    )

                    trailingContent()
                }
            }

            if(hasProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (progressSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }

            Spacer(Modifier.height(6.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodcastEpisodePlayButton(
                            bundle = bundle
                        )

                        PodcastEpisodeMarkAsPlayedButton(
                            bundle = bundle
                        )
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (readinessBadge) {
                            ReadinessBadge.ANALYZING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp)
                                        .size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            ReadinessBadge.READY -> {
                                val desktopConnected = remember(context) {
                                    app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(context) &&
                                        app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()
                                }
                                if (desktopConnected) {
                                    val refreshScope = rememberCoroutineScope()
                                    val appDb = app.podiumpodcasts.podium.ui.helper.LocalDatabase.current
                                    var showRefreshDialog by remember { mutableStateOf(false) }
                                    if (showRefreshDialog) {
                                        app.podiumpodcasts.podium.ui.component.media.HushCastAnalyzeDialog(
                                            episodeTitle = bundle.episode.title,
                                            isDownloaded = true,
                                            onDismiss = { showRefreshDialog = false },
                                            onAnalyzeDesktop = {
                                                refreshScope.launch {
                                                    app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                        context = context,
                                                        db = appDb,
                                                        episodeId = bundle.episode.id,
                                                        downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP
                                                    )
                                                }
                                            },
                                            onAnalyzeOnDevice = {
                                                val audioFile = bundle.episode.craftDownloadFile(context)
                                                if (audioFile.exists()) {
                                                    EpisodeAnalysisWorker.enqueueAnalyzeNow(
                                                        context = context,
                                                        episodeId = bundle.episode.id,
                                                        audioPath = audioFile.absolutePath,
                                                        actionMode = HushCastEngineBridge.defaultActionMode,
                                                        episodeTitle = bundle.episode.title
                                                    )
                                                }
                                            },
                                            onDownloadAudioOnly = {}
                                        )
                                    }
                                    IconButton(onClick = { showRefreshDialog = true }) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Rounded.Refresh,
                                            contentDescription = "Refresh on Desktop / Cloud"
                                        )
                                    }
                                }
                            }
                            ReadinessBadge.WORD_MUTE_READY,
                            ReadinessBadge.NOT_PROCESSED -> {
                                val sparkleScope = rememberCoroutineScope()
                                val appDb = app.podiumpodcasts.podium.ui.helper.LocalDatabase.current
                                var showAnalyzeDialog by remember { mutableStateOf(false) }

                                if (showAnalyzeDialog) {
                                    val audioExists = remember(bundle.episode.id) {
                                        bundle.episode.craftDownloadFile(context).exists()
                                    }
                                    app.podiumpodcasts.podium.ui.component.media.HushCastAnalyzeDialog(
                                        episodeTitle = bundle.episode.title,
                                        isDownloaded = audioExists,
                                        onDismiss = { showAnalyzeDialog = false },
                                        onAnalyzeDesktop = {
                                            sparkleScope.launch {
                                                app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                    context = context,
                                                    db = appDb,
                                                    episodeId = bundle.episode.id,
                                                    downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP
                                                )
                                            }
                                        },
                                        onAnalyzeOnDevice = {
                                            val audioFile = bundle.episode.craftDownloadFile(context)
                                            if (audioFile.exists()) {
                                                sparkleScope.launch {
                                                    autoMuteDb.dao().upsertStatus(
                                                        app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                                                            episodeId = bundle.episode.id,
                                                            badge = ReadinessBadge.ANALYZING.name,
                                                            progressPercent = 0,
                                                            stageLabel = "Queued for AI analysis…"
                                                        )
                                                    )
                                                }
                                                EpisodeAnalysisWorker.enqueueAnalyzeNow(
                                                    context = context,
                                                    episodeId = bundle.episode.id,
                                                    audioPath = audioFile.absolutePath,
                                                    actionMode = HushCastEngineBridge.defaultActionMode,
                                                    episodeTitle = bundle.episode.title
                                                )
                                                Toast.makeText(
                                                    context,
                                                    "Queued for HushCast AI analysis",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                sparkleScope.launch {
                                                    app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                        context = context,
                                                        db = appDb,
                                                        episodeId = bundle.episode.id,
                                                        downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE
                                                    )
                                                }
                                            }
                                        },
                                        onDownloadAudioOnly = {
                                            sparkleScope.launch {
                                                app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                                    context = context,
                                                    db = appDb,
                                                    episodeId = bundle.episode.id,
                                                    downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.AUDIO_ONLY
                                                )
                                            }
                                        }
                                    )
                                }

                                IconButton(onClick = { showAnalyzeDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = "Analyze with HushCast AI"
                                    )
                                }
                            }
                        }

                        PodcastEpisodeDownloadButton(
                            icon = true,
                            bundle = bundle
                        )

                        PodcastEpisodeQueueButton(
                            bundle = bundle
                        )
                    }
                }
            }
        }
    }
}