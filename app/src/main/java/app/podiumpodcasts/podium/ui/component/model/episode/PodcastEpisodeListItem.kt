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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.AutoMuteDatabase
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.formatPubDate
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
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
    onLongClick: (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val playerVm = viewModel<MediaPlayerViewModel>()
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

    val formattedDuration = remember(durationSec, progressSec, isPlayed) {
        formatPocketCastsDuration(
            durationSec = durationSec,
            progressSec = progressSec,
            isPlayed = isPlayed
        )
    }

    val isDownloaded = bundle.download?.state == PodcastEpisodeDownloadState.DOWNLOADED.value
    val isDownloading = bundle.download?.state == PodcastEpisodeDownloadState.DOWNLOADING.value
    val inQueue = playerVm.queue.contains(bundle.episode.id)
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
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
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
            // Left: artwork or selection check
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

            // Middle: date added + AI badge, title, status + duration
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

                    val badgeLabel = when (readinessBadge) {
                        ReadinessBadge.READY -> {
                            val stage = analysisStatus?.stageLabel.orEmpty()
                            when {
                                stage.contains("Cloud", ignoreCase = true) || stage.contains("Modal", ignoreCase = true) -> "Cloud AI"
                                stage.contains("Desktop", ignoreCase = true) -> "Desktop AI"
                                else -> "On-Device AI"
                            }
                        }
                        ReadinessBadge.WORD_MUTE_READY -> "Word-Mute"
                        ReadinessBadge.ANALYZING -> "${analysisStatus?.progressPercent ?: 0}%"
                        ReadinessBadge.NOT_PROCESSED -> null
                    }
                    if (badgeLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (readinessBadge) {
                                ReadinessBadge.READY -> MaterialTheme.colorScheme.primaryContainer
                                ReadinessBadge.WORD_MUTE_READY -> MaterialTheme.colorScheme.secondaryContainer
                                ReadinessBadge.ANALYZING -> MaterialTheme.colorScheme.tertiaryContainer
                                ReadinessBadge.NOT_PROCESSED -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = badgeLabel,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
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
                    if (inQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = "In Up Next",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                    } else if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp
                        )
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
                        Text(
                            text = "• Played",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    trailingContent()
                }

                if (hasProgress) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { (progressSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }

                supportingContent?.invoke()
            }

            // Right: Pocket Casts-style circular play/pause button
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
                shape = CircleShape,
                color = if (isActivelyPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                border = BorderStroke(
                    width = 1.75.dp,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isActivelyPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isActivelyPlaying) "Pause" else "Play",
                        tint = if (isActivelyPlaying) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
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