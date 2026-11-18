package app.podiumpodcasts.podium.ui.component.common.swipeable

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.SystemLists
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import kotlinx.coroutines.launch

data class SwipeableItemActionStyle(
    val icon: ImageVector,
    val backgroundColor: Color,
    val activeBackgroundColor: Color,
    val iconTint: Color,
    val activeIconTint: Color
)

data class SwipeableItemActionResult(
    val isDismissed: Boolean,
    val message: String,
    val onUndo: suspend () -> Unit
)

abstract class SwipeableItemAction(
    val style: @Composable () -> SwipeableItemActionStyle,
    val onActionHandler: @Composable () -> (suspend () -> SwipeableItemActionResult)
)

interface SwipeableItemActions {
    class DeleteAction(
        onAction: suspend () -> SwipeableItemActionResult
    ) : SwipeableItemAction(
        style = {
            SwipeableItemActionStyle(
                icon = Icons.Rounded.Delete,
                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                activeBackgroundColor = MaterialTheme.colorScheme.error,
                activeIconTint = MaterialTheme.colorScheme.onError,
            )
        },
        onActionHandler = {
            onAction
        }
    )

    class CheckAction(
        isChecked: Boolean = false,
        onAction: suspend () -> SwipeableItemActionResult
    ) : SwipeableItemAction(
        style = {
            SwipeableItemActionStyle(
                icon = if (isChecked) Icons.Rounded.RemoveDone else Icons.Rounded.Check,
                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                activeBackgroundColor = MaterialTheme.colorScheme.tertiary,
                activeIconTint = MaterialTheme.colorScheme.onTertiary,
            )
        },
        onActionHandler = {
            onAction
        }
    )

    class ResetAction(
        onAction: suspend () -> SwipeableItemActionResult
    ) : SwipeableItemAction(
        style = {
            SwipeableItemActionStyle(
                icon = Icons.Rounded.RestartAlt,
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                activeBackgroundColor = MaterialTheme.colorScheme.secondary,
                activeIconTint = MaterialTheme.colorScheme.onSecondary,
            )
        },
        onActionHandler = {
            onAction
        }
    )

    class QueueAction(
        bundle: app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle,
        playNext: Boolean,
        playerVm: app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
    ) : SwipeableItemAction(
        style = {
            val inQueue = playerVm.queue.contains(bundle.episode.id)
            SwipeableItemActionStyle(
                icon = when {
                    inQueue -> Icons.Rounded.Delete
                    playNext -> Icons.Rounded.RestartAlt
                    else -> Icons.Rounded.Check
                }.let {
                    if (inQueue) androidx.compose.material.icons.Icons.Rounded.PlaylistRemove
                    else if (playNext) androidx.compose.material.icons.Icons.Rounded.VerticalAlignTop
                    else androidx.compose.material.icons.Icons.Rounded.VerticalAlignBottom
                },
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                activeBackgroundColor = MaterialTheme.colorScheme.primary,
                activeIconTint = MaterialTheme.colorScheme.onPrimary,
            )
        },
        onActionHandler = actionHandler@{
            val context = androidx.compose.ui.platform.LocalContext.current
            return@actionHandler {
                val inQueue = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    playerVm.queue.contains(bundle.episode.id)
                }
                if (inQueue) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        playerVm.dequeue(bundle.episode.id)
                    }
                    SwipeableItemActionResult(
                        isDismissed = false,
                        message = "Removed from Up Next",
                        onUndo = {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (playNext) playerVm.playNext(context, bundle)
                                else playerVm.enqueue(context, bundle)
                            }
                        }
                    )
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (playNext) playerVm.playNext(context, bundle)
                        else playerVm.enqueue(context, bundle)
                    }
                    SwipeableItemActionResult(
                        isDismissed = false,
                        message = if (playNext) "Added to Up Next (Play Next)" else "Added to Up Next (Play Last)",
                        onUndo = {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                playerVm.dequeue(bundle.episode.id)
                            }
                        }
                    )
                }
            }
        }
    )

    class DownloadAction(
        bundle: app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
    ) : SwipeableItemAction(
        style = {
            val context = androidx.compose.ui.platform.LocalContext.current
            val isDownloaded = remember(bundle.episode.id, bundle.download?.state) {
                bundle.episode.craftDownloadFile(context).exists()
            }
            SwipeableItemActionStyle(
                icon = if (isDownloaded) Icons.Rounded.Delete else androidx.compose.material.icons.Icons.Rounded.Download,
                backgroundColor = if (isDownloaded) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                iconTint = if (isDownloaded) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                activeBackgroundColor = if (isDownloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                activeIconTint = if (isDownloaded) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary,
            )
        },
        onActionHandler = actionHandler@{
            val context = androidx.compose.ui.platform.LocalContext.current
            val db = LocalDatabase.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var showDeleteConfirm by androidx.compose.runtime.remember(bundle.episode.id) {
                androidx.compose.runtime.mutableStateOf(false)
            }
            if (showDeleteConfirm) {
                app.podiumpodcasts.podium.ui.dialog.DeleteConfirmationDialog(
                    onDismiss = { showDeleteConfirm = false },
                    itemName = bundle.episode.title,
                    additionalText = "This will delete the downloaded audio file from your device.",
                    onConfirm = {
                        scope.launch {
                            app.podiumpodcasts.podium.manager.DownloadManager.deleteEpisodeDownload(
                                context = context,
                                db = db,
                                episode = bundle.episode
                            )
                        }
                    }
                )
            }
            return@actionHandler {
                val isDownloaded = bundle.episode.craftDownloadFile(context).exists()
                if (isDownloaded) {
                    showDeleteConfirm = true
                    SwipeableItemActionResult(
                        isDismissed = false
                    )
                } else {
                    app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                        context = context,
                        db = db,
                        episodeId = bundle.episode.id
                    )
                    SwipeableItemActionResult(
                        isDismissed = false,
                        message = "Downloading episode",
                        onUndo = {
                            app.podiumpodcasts.podium.manager.DownloadManager.deleteEpisodeDownload(
                                context = context,
                                db = db,
                                episode = bundle.episode
                            )
                        }
                    )
                }
            }
        }
    )
}

@Composable
fun buildEpisodeSwipeAction(
    actionType: app.podiumpodcasts.podium.EpisodeSwipeAction,
    bundle: app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle,
    playerVm: app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel,
    onMarkPlayed: suspend (Boolean) -> Unit
): SwipeableItemAction? {
    val isCurrentlyPlayed = bundle.playState?.played == true
    val markedAsPlayed = stringResource(R.string.snackbar_marked_as_played)
    val markedAsUnplayed = stringResource(R.string.snackbar_marked_as_unplayed)

    return when (actionType) {
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT -> SwipeableItemActions.QueueAction(
            bundle = bundle,
            playNext = true,
            playerVm = playerVm
        )
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST -> SwipeableItemActions.QueueAction(
            bundle = bundle,
            playNext = false,
            playerVm = playerVm
        )
        app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED -> SwipeableItemActions.CheckAction(
            isChecked = isCurrentlyPlayed,
            onAction = {
                val markPlayed = !isCurrentlyPlayed
                onMarkPlayed(markPlayed)
                SwipeableItemActionResult(
                    isDismissed = false,
                    message = if (markPlayed) markedAsPlayed else markedAsUnplayed,
                    onUndo = { onMarkPlayed(!markPlayed) }
                )
            }
        )
        app.podiumpodcasts.podium.EpisodeSwipeAction.DOWNLOAD -> SwipeableItemActions.DownloadAction(
            bundle = bundle
        )
        app.podiumpodcasts.podium.EpisodeSwipeAction.NONE -> null
    }
}

fun consolidateSecondarySwipeAction(
    primary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    secondary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    inQueue: Boolean
): app.podiumpodcasts.podium.EpisodeSwipeAction {
    val isPrimaryQueue = primary == app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT ||
        primary == app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST
    val isSecondaryQueue = secondary == app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT ||
        secondary == app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST
    return if (inQueue && isPrimaryQueue && isSecondaryQueue) {
        app.podiumpodcasts.podium.EpisodeSwipeAction.NONE
    } else {
        secondary
    }
}