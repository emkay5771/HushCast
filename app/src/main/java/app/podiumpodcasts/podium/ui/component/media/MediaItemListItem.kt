package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.utils.getEpisodeId
import coil3.compose.AsyncImage

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
            Text(
                text = mediaItem.mediaMetadata.artist.toString(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        vm.dequeue(mediaItem.getEpisodeId())
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