package app.podiumpodcasts.podium.ui.component.model.episode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.AutoMuteDatabase
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.download.DownloadTracker
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.formatPubDate
import kotlinx.coroutines.launch
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastEpisodeListItem(
    modifier: Modifier = Modifier,
    bundle: PodcastEpisodeBundle,
    overlineText: String = LocalContext.current.let { ctx ->
        remember(bundle.episode.pubDate) {
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
    showArtwork: Boolean = true,
    inQueue: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val db = app.podiumpodcasts.podium.ui.helper.LocalDatabase.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playerVm = viewModel<MediaPlayerViewModel>()
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val analysisStatus by remember(bundle.episode.id, autoMuteDb) {
        autoMuteDb.statusMapFlow
            .map { it[bundle.episode.id] }
            .distinctUntilChanged()
    }.collectAsState(initial = null)
    val downloadProgress by remember(bundle.episode.id) {
        DownloadTracker.progress
            .map { it[bundle.episode.id] }
            .distinctUntilChanged()
    }.collectAsState(initial = null)
    val readinessBadge = remember(analysisStatus?.badge) {
        runCatching {
            analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) }
        }.getOrNull() ?: ReadinessBadge.NOT_PROCESSED
    }

    val isPlayed = bundle.playState?.played == true
    val durationSec = bundle.episode.duration
    val progressSec = bundle.playState?.state ?: 0
    val hasProgress = !isPlayed && durationSec > 0 && progressSec > 0
    val progressFraction = if (hasProgress) {
        (progressSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val formattedDuration = remember(durationSec, progressSec, isPlayed) {
        formatPocketCastsDuration(
            durationSec = durationSec,
            progressSec = progressSec,
            isPlayed = isPlayed
        )
    }

    val isDownloaded = bundle.download?.state == PodcastEpisodeDownloadState.DOWNLOADED.value
    val isDownloading = bundle.download?.state == PodcastEpisodeDownloadState.DOWNLOADING.value
    val isFailed = bundle.download?.state == PodcastEpisodeDownloadState.FAILED.value
    val isProcessed = readinessBadge == ReadinessBadge.READY || readinessBadge == ReadinessBadge.WORD_MUTE_READY
    val isAnalyzing = readinessBadge == ReadinessBadge.ANALYZING
    val isInQueue = inQueue ?: playerVm.queue.contains(bundle.episode.id)
    val isCurrentlyEpisode = playerVm.metadataEpisodeId == bundle.episode.id
    val isActivelyPlaying = isCurrentlyEpisode && playerVm.isPlaying

    val shape = if (count == 1) {
        RoundedCornerShape(16.dp)
    } else {
        ListItemDefaults.segmentedShapes(index = index, count = count).shape
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        colors.containerColor
    }

    val clickModifier = if (onLongClick != null) {
        Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Select episode") {
                        onLongClick()
                        true
                    }
                )
            }
    } else {
        Modifier.combinedClickable(
            onClick = onClick
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(clickModifier),
        shape = shape,
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else if (leadingContent != null) {
                leadingContent()
            } else if (showArtwork) {
                ShimmerAsyncImage(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    model = bundle.episode.imageUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = stringResource(R.string.common_cover_image),
                    targetSizePx = 128
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (isPlayed) 0.6f else 1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (bundle.episode.new && !isPlayed) {
                        Text(
                            text = stringResource(R.string.common_new_uppercase),
                            style = Typography.labelSmallEmphasized,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = overlineText.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Text(
                    text = titleText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isInQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = "In Up Next",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    when {
                        isDownloading -> {
                            val pct = downloadProgress?.percent
                            Text(
                                text = if (pct != null && pct >= 0) "Downloading $pct%" else "Downloading…",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        isAnalyzing -> {
                            val pct = analysisStatus?.progressPercent ?: 0
                            Text(
                                text = "Processing $pct%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        isFailed -> {
                            Surface(
                                onClick = {
                                    scope.launch {
                                        DownloadManager.downloadEpisode(context, db, bundle.episode.id)
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.common_action_retry),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        else -> {
                            if (isDownloaded) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Downloaded",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (isProcessed) {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = "HushCast ready",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    if (formattedDuration.isNotEmpty()) {
                        Text(
                            text = formattedDuration,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    if (isPlayed) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Played",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    trailingContent()
                }

                supportingContent?.invoke()
            }

            // Right: circular play/pause/replay button with progress ring
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasProgress && !isActivelyPlaying) {
                    CircularProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.size(38.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Surface(
                    onClick = {
                        if (!isCurrentlyEpisode) {
                            playerVm.play(context, bundle)
                        } else if (playerVm.isPlaying) {
                            playerVm.pause()
                        } else if (playerVm.canResumeCurrentEpisode) {
                            playerVm.play()
                        } else {
                            playerVm.play(context, bundle)
                        }
                    },
                    shape = if (isActivelyPlaying) RoundedCornerShape(10.dp) else CircleShape,
                    color = when {
                        isActivelyPlaying -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    border = if (!hasProgress || isActivelyPlaying) {
                        BorderStroke(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else null,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                isActivelyPlaying -> Icons.Rounded.Pause
                                isPlayed -> Icons.Rounded.Replay
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = when {
                                isActivelyPlaying -> "Pause"
                                isPlayed -> "Replay"
                                else -> "Play"
                            },
                            tint = when {
                                isActivelyPlaying -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatPocketCastsDuration(
    durationSec: Int,
    progressSec: Int,
    isPlayed: Boolean
): String {
    if (durationSec <= 0) return ""
    val remainingSec = if (!isPlayed && progressSec > 0) {
        (durationSec - progressSec).coerceAtLeast(60)
    } else {
        durationSec
    }
    val totalMin = (remainingSec / 60).coerceAtLeast(1)
    val hours = totalMin / 60
    val mins = totalMin % 60
    val base = when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
    return if (!isPlayed && progressSec > 0) "$base left" else base
}