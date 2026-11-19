package app.podiumpodcasts.podium.ui.component.common.swipeable

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val onUndo: suspend () -> Unit,
    /** False for plain notices (nothing to undo): the snackbar shows no Undo button. */
    val showUndo: Boolean = true,
    /**
     * Runs when the snackbar goes away without Undo: timed out, swiped away, replaced by another
     * message, or the screen closed.  Used to make deferred actions (item 2) final.
     */
    val onTimeout: (suspend () -> Unit)? = null
)

abstract class SwipeableItemAction(
    val style: @Composable () -> SwipeableItemActionStyle,
    val onActionHandler: @Composable () -> (suspend () -> SwipeableItemActionResult),
    /** M11: the action's name, for TalkBack's actions menu ("Play next", "Remove download"). */
    val label: @Composable () -> String = { "" },
    val shortLabel: @Composable () -> String = { "" }
)

interface SwipeableItemActions {
    class DeleteAction(
        label: String = "Delete",
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
        },
        label = { label },
        shortLabel = { "Delete" }
    )

    class CheckAction(
        isChecked: Boolean = false,
        label: String? = null,
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
        },
        label = { label ?: if (isChecked) "Mark as unplayed" else "Mark as played" },
        shortLabel = { if (isChecked) "Unplayed" else "Played" }
    )

    class ResetAction(
        label: String = "Reset progress",
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
        },
        label = { label },
        shortLabel = { "Reset" }
    )

    /**
     * S7: [inQueue] comes from the list's one Set of queued ids.  Without it (callers that don't
     * pass one) the queue is checked here.
     */
    class QueueAction(
        bundle: app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle,
        playNext: Boolean,
        playerVm: app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel,
        inQueue: Boolean? = null
    ) : SwipeableItemAction(
        style = {
            val queued = inQueue ?: playerVm.queue.contains(bundle.episode.id)
            SwipeableItemActionStyle(
                icon = when {
                    queued -> Icons.Rounded.PlaylistRemove
                    playNext -> Icons.Rounded.VerticalAlignTop
                    else -> Icons.Rounded.VerticalAlignBottom
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
                val queued = withContext(Dispatchers.Main) {
                    playerVm.queue.contains(bundle.episode.id)
                }
                if (queued) {
                    withContext(Dispatchers.Main) {
                        playerVm.dequeue(bundle.episode.id)
                    }
                    SwipeableItemActionResult(
                        isDismissed = false,
                        message = "Removed from Up Next",
                        onUndo = {
                            withContext(Dispatchers.Main) {
                                if (playNext) playerVm.playNext(context, bundle)
                                else playerVm.enqueue(context, bundle)
                            }
                        }
                    )
                } else {
                    withContext(Dispatchers.Main) {
                        if (playNext) playerVm.playNext(context, bundle)
                        else playerVm.enqueue(context, bundle)
                    }
                    SwipeableItemActionResult(
                        isDismissed = false,
                        message = if (playNext) "Will play next" else "Added to the end of Up Next",
                        onUndo = {
                            withContext(Dispatchers.Main) {
                                playerVm.dequeue(bundle.episode.id)
                            }
                        }
                    )
                }
            }
        },
        label = {
            val queued = inQueue ?: playerVm.queue.contains(bundle.episode.id)
            when {
                queued -> "Remove from Up Next"
                playNext -> "Play next"
                else -> "Play last"
            }
        },
        shortLabel = {
            val queued = inQueue ?: playerVm.queue.contains(bundle.episode.id)
            when {
                queued -> "Remove"
                playNext -> "Next"
                else -> "End"
            }
        }
    )

    /**
     * Item 2: removing a download is deferred until the Undo snackbar times out
     * ([PendingDownloadDeletes]), and the episode that's in the player is never removed.
     * [playerVm] tells which episode that is; without it the check can't run, so pass it.
     */
    class DownloadAction(
        bundle: app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle,
        playerVm: app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel? = null
    ) : SwipeableItemAction(
        style = {
            // From the database row, not File.exists(): this runs for every row while composing.
            val showsDownloaded = bundle.isShownAsDownloaded()
            SwipeableItemActionStyle(
                icon = if (showsDownloaded) Icons.Rounded.Delete else Icons.Rounded.Download,
                backgroundColor = if (showsDownloaded) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                iconTint = if (showsDownloaded) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                activeBackgroundColor = if (showsDownloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                activeIconTint = if (showsDownloaded) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary,
            )
        },
        onActionHandler = actionHandler@{
            val context = androidx.compose.ui.platform.LocalContext.current
            val db = LocalDatabase.current
            val tracker = PendingDownloadDeletes.tracker
            return@actionHandler {
                val id = bundle.episode.id
                val fileExists = withContext(Dispatchers.IO) {
                    bundle.episode.craftDownloadFile(context).exists()
                }
                val isCurrent = withContext(Dispatchers.Main) { playerVm?.metadataEpisodeId == id }
                when (decideDownloadSwipe(fileExists, tracker.isPending(id), isCurrent)) {
                    DownloadSwipeDecision.BLOCKED_PLAYING -> SwipeableItemActionResult(
                        isDismissed = false,
                        message = CANT_REMOVE_PLAYING_MESSAGE,
                        onUndo = { },
                        showUndo = false
                    )

                    DownloadSwipeDecision.RESTORE_PENDING -> {
                        tracker.undo(id)
                        SwipeableItemActionResult(
                            isDismissed = false,
                            message = DOWNLOAD_KEPT_MESSAGE,
                            onUndo = { },
                            showUndo = false
                        )
                    }

                    DownloadSwipeDecision.MARK_PENDING -> {
                        tracker.mark(id)
                        SwipeableItemActionResult(
                            isDismissed = false,
                            message = DOWNLOAD_REMOVED_MESSAGE,
                            // Instant: the file was never touched.
                            onUndo = { tracker.undo(id) },
                            onTimeout = {
                                val stillCurrent = withContext(Dispatchers.Main) {
                                    playerVm?.metadataEpisodeId == id
                                }
                                if (tracker.commit(id, isCurrentEpisode = stillCurrent)) {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            app.podiumpodcasts.podium.manager.DownloadManager.deleteEpisodeDownload(
                                                context = context,
                                                db = db,
                                                episode = bundle.episode
                                            )
                                        }
                                    } finally {
                                        tracker.finish(id)
                                    }
                                }
                            }
                        )
                    }

                    DownloadSwipeDecision.DOWNLOAD -> {
                        app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(
                            context = context,
                            db = db,
                            episodeId = id
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
        },
        label = {
            if (bundle.isShownAsDownloaded()) "Remove download" else "Download"
        }
    )
}

/** Downloaded per the database, and not waiting to be removed (item 2). */
fun app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle.isShownAsDownloaded(): Boolean =
    download?.state == PodcastEpisodeDownloadState.DOWNLOADED.value &&
        episode.id !in PendingDownloadDeletes.hiddenIds

@Composable
fun buildEpisodeSwipeAction(
    actionType: app.podiumpodcasts.podium.EpisodeSwipeAction,
    bundle: app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle,
    playerVm: app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel,
    /** S7: from the list's Set of queued ids; null checks the queue here. */
    inQueue: Boolean? = null,
    onMarkPlayed: suspend (Boolean) -> Unit
): SwipeableItemAction? {
    val isCurrentlyPlayed = bundle.playState?.played == true
    val markedAsPlayed = stringResource(R.string.snackbar_marked_as_played)
    val markedAsUnplayed = stringResource(R.string.snackbar_marked_as_unplayed)

    return when (actionType) {
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT -> SwipeableItemActions.QueueAction(
            bundle = bundle,
            playNext = true,
            playerVm = playerVm,
            inQueue = inQueue
        )
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST -> SwipeableItemActions.QueueAction(
            bundle = bundle,
            playNext = false,
            playerVm = playerVm,
            inQueue = inQueue
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
            bundle = bundle,
            playerVm = playerVm
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
