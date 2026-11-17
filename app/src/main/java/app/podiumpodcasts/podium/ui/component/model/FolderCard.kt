package app.podiumpodcasts.podium.ui.component.model

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.PodcastFolder
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.theme.Typography

private val folderPalette = listOf(
    Color(0xFF4A6572),
    Color(0xFF7A4E48),
    Color(0xFF4F6D5A),
    Color(0xFF5D5378),
    Color(0xFF7B6340),
    Color(0xFF3E6375)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderCard(
    modifier: Modifier = Modifier,
    folder: PodcastFolder,
    items: List<PodcastSubscriptionBundle>,
    badgeMode: SubscriptionsBadgeMode = SubscriptionsBadgeMode.UNPLAYED_COUNT,
    unplayedCounts: Map<String, Int> = emptyMap(),
    showTitles: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = folderPalette[folder.colorIndex.mod(folderPalette.size)]
    val totalUnread = items.sumOf { unplayedCounts[it.podcast.origin] ?: it.subscription.newEpisodes }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick() }
                    .padding(if (compact) 2.dp else 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(if (compact) 8.dp else 10.dp))
                        .background(bgColor.copy(alpha = 0.85f))
                        .padding(if (compact) 4.dp else 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (items.isEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = folder.name,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxSize(0.45f)
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                        ) {
                            for (rowIdx in 0..1) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                                ) {
                                    for (colIdx in 0..1) {
                                        val idx = rowIdx * 2 + colIdx
                                        val item = items.getOrNull(idx)
                                        if (item != null) {
                                            ShimmerAsyncImage(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(4.dp)),
                                                model = item.podcast.imageUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                targetSizePx = 128
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.White.copy(alpha = 0.12f))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showTitles && !compact) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = folder.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = Typography.titleSmallEmphasized
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${items.size} ${if (items.size == 1) "podcast" else "podcasts"}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = Typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (totalUnread > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    when (badgeMode) {
                        SubscriptionsBadgeMode.UNPLAYED_COUNT -> Badge(
                            Modifier.offset((-4).dp, 4.dp)
                        ) {
                            Text(totalUnread.toString())
                        }
                        SubscriptionsBadgeMode.LATEST_ONLY -> Badge(
                            Modifier
                                .offset((-6).dp, 6.dp)
                                .size(10.dp)
                        )
                        SubscriptionsBadgeMode.OFF -> {}
                    }
                }
            }
        }
    }
}
