package app.podiumpodcasts.podium.ui.component.model.episode

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = bundle.episode.new && !isPlayed,
                    enter = fadeIn() + slideInHorizontally { -it },
                    exit = fadeOut() + slideOutHorizontally { -it }
                ) {
                    Text(
                        modifier = Modifier.padding(end = 8.dp),
                        text = stringResource(R.string.common_new_uppercase),
                        style = Typography.labelSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = formattedOverline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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