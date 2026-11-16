package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.ui.component.common.SwitchableDynamicMaterialExpressiveTheme
import app.podiumpodcasts.podium.ui.component.media.MediaItemListItem
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.utils.getEpisodeId
import app.podiumpodcasts.podium.utils.getOrigin
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPlayerQueueBottomSheet(
    onOpenEpisode: (
        origin: String,
        id: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val settingsRepository = LocalSettingsRepository.current
    val vm = viewModel<MediaPlayerViewModel>()

    val enableArtworkColors = settingsRepository.appearance.enableArtworkColors.collectAsState(true)
    val tapUpNextToPlay = settingsRepository.behavior.tapUpNextToPlay.collectAsState(true)

    var showClearDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val headerCount = if(vm.mediaMetadata != null) 2 else 1

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState
    ) { from, to ->
        if(from.index >= headerCount && to.index >= headerCount) {
            vm.moveQueueItem(from.index - headerCount, to.index - headerCount)
        }
    }

    if(showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Up Next?") },
            text = { Text("Remove all ${vm.queueIndex.size} queued episodes? The currently playing episode will keep playing.") },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        vm.clearQueue()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear")
                }
            }
        )
    }

    SwitchableDynamicMaterialExpressiveTheme(
        enable = enableArtworkColors.value,
        seedColor = Color(vm.metadataImageSeedColor ?: 1)
    ) {
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = onDismiss
        ) {
            LazyColumn(
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                contentPadding = PaddingValues(16.dp)
            ) {
                item(key = "HEADER") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Up Next",
                                style = Typography.headlineMediumEmphasized
                            )
                            Text(
                                text = if(vm.queueIndex.isEmpty()) "Queue is empty"
                                else "${vm.queueIndex.size} ${if(vm.queueIndex.size == 1) "episode" else "episodes"} queued",
                                style = Typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if(vm.queueIndex.isNotEmpty()) {
                            TextButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.ClearAll,
                                    contentDescription = "Clear queue",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Clear")
                            }
                        }
                    }
                }

                if(vm.mediaMetadata != null) {
                    item(key = "NOW_PLAYING") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ShimmerAsyncImage(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    model = vm.mediaMetadata?.artworkUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "NOW PLAYING",
                                        style = Typography.labelSmallEmphasized
                                    )
                                    Text(
                                        text = vm.mediaMetadata?.title?.toString() ?: "",
                                        style = Typography.titleMediumEmphasized,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = vm.mediaMetadata?.subtitle?.toString() ?: "",
                                        style = Typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                FilledIconButton(
                                    onClick = {
                                        if(vm.isPlaying) vm.pause() else vm.play()
                                    }
                                ) {
                                    Icon(
                                        imageVector = if(vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if(vm.isPlaying) "Pause" else "Play"
                                    )
                                }
                            }
                        }
                    }
                }

                if(vm.queueIndex.isEmpty()) {
                    item(key = "EMPTY") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Nothing up next",
                                    style = Typography.titleMediumEmphasized
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Add episodes from any podcast or listing to build your queue.",
                                    style = Typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(
                        count = vm.queueIndex.size,
                        key = { queueIndexIndex ->
                            vm.queue.getOrNull(queueIndexIndex) ?: vm.queueIndex[queueIndexIndex]
                        }
                    ) { queueIndexIndex ->
                        val index = vm.queueIndex[queueIndexIndex]
                        val itemKey = vm.queue.getOrNull(queueIndexIndex) ?: index
                        val mediaItem = vm.rememberMediaItemAt(index)

                        ReorderableItem(
                            state = reorderableLazyListState,
                            key = itemKey
                        ) {
                            mediaItem?.let {
                                val dismissState = rememberSwipeToDismissBoxState()
                                if(dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                                    vm.dequeue(mediaItem.getEpisodeId())
                                }

                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(MaterialTheme.colorScheme.errorContainer)
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "Remove from queue",
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                ) {
                                    MediaItemListItem(
                                        modifier = Modifier
                                            .animateItem()
                                            .longPressDraggableHandle(),
                                        dragModifier = Modifier.draggableHandle(),
                                        mediaItem = mediaItem,
                                        index = queueIndexIndex,
                                        count = vm.queueIndex.size,
                                        onMoveToTop = if(queueIndexIndex > 0) {
                                            { vm.moveToTopOfQueue(queueIndexIndex) }
                                        } else null,
                                        onOpenDetails = {
                                            scope.launch {
                                                sheetState.hide()
                                                onDismiss()
                                                onOpenEpisode(
                                                    mediaItem.getOrigin(),
                                                    mediaItem.getEpisodeId()
                                                )
                                            }
                                        },
                                        onClick = {
                                            if(tapUpNextToPlay.value) {
                                                vm.playFromQueue(queueIndexIndex)
                                                scope.launch {
                                                    sheetState.hide()
                                                    onDismiss()
                                                }
                                            } else {
                                                scope.launch {
                                                    sheetState.hide()
                                                    onDismiss()
                                                    onOpenEpisode(
                                                        mediaItem.getOrigin(),
                                                        mediaItem.getEpisodeId()
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}