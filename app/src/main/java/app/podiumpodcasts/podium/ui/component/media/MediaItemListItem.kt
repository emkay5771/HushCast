package app.podiumpodcasts.podium.ui.component.media

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.utils.getEpisodeId
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaItemListItem(
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    mediaItem: MediaItem,
    index: Int = 0,
    count: Int = 0,
    onMoveToTop: (() -> Unit)? = null,
    onOpenDetails: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val vm = viewModel<MediaPlayerViewModel>()
    val context = LocalContext.current
    val appDb = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val episodeId = remember(mediaItem) { mediaItem.getEpisodeId() }
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val statusMap by autoMuteDb.statusMapFlow.collectAsState()
    val analysisStatus = statusMap[episodeId]
    val readinessBadge = remember(analysisStatus?.badge) {
        runCatching {
            analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) }
        }.getOrNull() ?: ReadinessBadge.NOT_PROCESSED
    }

    SegmentedListItem(
        modifier = if(count == 1)
            modifier.clip(RoundedCornerShape(16.dp))
        else
            modifier,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(
            index = index,
            count = count
        ),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragModifier.size(24.dp)
                )
                AsyncImage(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    model = mediaItem.mediaMetadata.artworkUri ?: mediaItem.mediaMetadata.artworkData,
                    contentDescription = ""
                )
            }
        },
        overlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = mediaItem.mediaMetadata.artist.toString(),
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
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (readinessBadge) {
                    ReadinessBadge.ANALYZING -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    ReadinessBadge.READY -> Unit
                    ReadinessBadge.WORD_MUTE_READY,
                    ReadinessBadge.NOT_PROCESSED -> {
                        var showAnalyzeDialog by remember { mutableStateOf(false) }

                        if (showAnalyzeDialog) {
                            HushCastAnalyzeDialog(
                                episodeTitle = mediaItem.mediaMetadata.title?.toString() ?: "this episode",
                                isDownloaded = false,
                                onDismiss = { showAnalyzeDialog = false },
                                onAnalyzeDesktop = {
                                    scope.launch {
                                        app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                            context = context,
                                            db = appDb,
                                            episodeId = episodeId,
                                            downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP
                                        )
                                    }
                                },
                                onAnalyzeOnDevice = {
                                    scope.launch {
                                        app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                            context = context,
                                            db = appDb,
                                            episodeId = episodeId,
                                            downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE
                                        )
                                    }
                                },
                                onDownloadAudioOnly = {
                                    scope.launch {
                                        app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                                            context = context,
                                            db = appDb,
                                            episodeId = episodeId,
                                            downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.AUDIO_ONLY
                                        )
                                    }
                                }
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    val bundle = withContext(Dispatchers.IO) {
                                        runCatching { appDb.podcastEpisodes().getSync(episodeId) }.getOrNull()
                                    }
                                    val audioFile = bundle?.episode?.craftDownloadFile(context)
                                    if (audioFile != null && audioFile.exists()) {
                                        autoMuteDb.dao().upsertStatus(
                                            app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                                                episodeId = episodeId,
                                                badge = ReadinessBadge.ANALYZING.name,
                                                progressPercent = 0,
                                                stageLabel = "Queued for AI analysis…"
                                            )
                                        )
                                        EpisodeAnalysisWorker.enqueueAnalyzeNow(
                                            context = context,
                                            episodeId = episodeId,
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
                                        showAnalyzeDialog = true
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Analyze with HushCast AI"
                            )
                        }
                    }
                }

                onMoveToTop?.let { moveToTop ->
                    IconButton(onClick = moveToTop) {
                        Icon(
                            imageVector = Icons.Rounded.VerticalAlignTop,
                            contentDescription = "Move to top"
                        )
                    }
                }
                onOpenDetails?.let { openDetails ->
                    IconButton(onClick = openDetails) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = stringResource(R.string.common_info)
                        )
                    }
                }
                FilledIconButton(
                    onClick = {
                        vm.dequeue(episodeId)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlaylistRemove,
                        contentDescription = stringResource(R.string.common_action_remove_from_queue)
                    )
                }
            }
        }
    ) {
        Text(
            text = mediaItem.mediaMetadata.title.toString(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}