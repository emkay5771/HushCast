package app.podiumpodcasts.podium.ui.component.common.swipeable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Item 2: swipe-to-delete of a download is deferred.  The row is marked pending and looks removed
 * at once; the file is deleted only when the "Download removed · Undo" snackbar goes away without
 * Undo.  Undo just forgets the pending mark, so nothing is re-downloaded or re-analysed.
 *
 * States per episode: (none) → PENDING → COMMITTING → (none), or PENDING → (none) on Undo.
 * Pending and committing episodes are both reported in [onChange] so rows keep looking removed
 * until the delete has finished.
 */
class PendingDeleteTracker(
    private val onChange: (Set<String>) -> Unit = { }
) {
    private val pending = LinkedHashSet<String>()
    private val committing = LinkedHashSet<String>()

    /** Episodes whose rows should look removed. */
    @get:Synchronized
    val hidden: Set<String>
        get() = pending + committing

    @Synchronized
    fun isPending(id: String): Boolean = id in pending

    @Synchronized
    fun isHidden(id: String): Boolean = id in pending || id in committing

    /** Marks a download for removal.  False when it already is pending or being deleted. */
    @Synchronized
    fun mark(id: String): Boolean {
        if (id in pending || id in committing) return false
        pending += id
        publish()
        return true
    }

    /** Undo: restores the row.  False when it wasn't pending (already committed, or never marked). */
    @Synchronized
    fun undo(id: String): Boolean {
        if (!pending.remove(id)) return false
        publish()
        return true
    }

    /**
     * The snackbar went away without Undo.  Returns true when the caller should delete the file
     * now (then call [finish]).  Returns false, and restores the row, when the episode became the
     * one that's playing in the meantime; nothing happens if it wasn't pending any more.
     */
    @Synchronized
    fun commit(id: String, isCurrentEpisode: Boolean): Boolean {
        if (!pending.remove(id)) return false
        if (isCurrentEpisode) {
            publish()
            return false
        }
        committing += id
        publish()
        return true
    }

    /** The delete started by [commit] is done (or failed). */
    @Synchronized
    fun finish(id: String) {
        if (committing.remove(id)) publish()
    }

    private fun publish() = onChange(pending + committing)
}

/** What a swipe on the download action does. */
enum class DownloadSwipeDecision {
    /** It's the episode in the player: never remove its file. */
    BLOCKED_PLAYING,

    /** The row is already pending removal: swiping again keeps the download. */
    RESTORE_PENDING,

    /** Downloaded: mark pending and offer Undo. */
    MARK_PENDING,

    /** Not downloaded: start the download. */
    DOWNLOAD
}

fun decideDownloadSwipe(
    isDownloaded: Boolean,
    isPending: Boolean,
    isCurrentEpisode: Boolean
): DownloadSwipeDecision = when {
    isPending -> DownloadSwipeDecision.RESTORE_PENDING
    isDownloaded && isCurrentEpisode -> DownloadSwipeDecision.BLOCKED_PLAYING
    isDownloaded -> DownloadSwipeDecision.MARK_PENDING
    else -> DownloadSwipeDecision.DOWNLOAD
}

const val DOWNLOAD_REMOVED_MESSAGE = "Download removed"
const val DOWNLOAD_KEPT_MESSAGE = "Download kept"
const val CANT_REMOVE_PLAYING_MESSAGE = "Can't remove the episode that's playing"

/** The app-wide tracker.  [hiddenIds] is snapshot state, so rows read it without a collector. */
object PendingDownloadDeletes {
    var hiddenIds by mutableStateOf<Set<String>>(emptySet())
        private set

    val tracker = PendingDeleteTracker { hiddenIds = it }
}
