package app.podiumpodcasts.podium.ui.component.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.podiumpodcasts.podium.background.worker.PeriodicPodcastUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The parts of a WorkInfo the pull-to-refresh UI cares about (plain data, so it can be tested). */
enum class RefreshJobState { ENQUEUED, BLOCKED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class RefreshJob(
    val id: String,
    val state: RefreshJobState,
    /** The worker's output-data "error" reason, when it failed and gave one. */
    val error: String? = null,
    /** RUNNING, but every feed is stored and only chapter downloads remain. */
    val finishing: Boolean = false,
    /** SUCCEEDED, but this many feeds (of [totalFeeds]) couldn't be read. */
    val failedFeeds: Int = 0,
    val totalFeeds: Int = 0
)

/**
 * Item 1: the pull-to-refresh spinner shows only while the refresh work is RUNNING (plus a short
 * grace right after the pull, before WorkManager starts it), never while it sits ENQUEUED waiting
 * for a network.
 */
fun refreshIndicatorVisible(jobs: List<RefreshJob>, awaitingStart: Boolean): Boolean {
    // Stops once the feeds are stored; chapter downloads finish quietly in the background.
    if (jobs.any { it.state == RefreshJobState.RUNNING && !it.finishing }) return true
    return awaitingStart && jobs.none { it.state.isFinished() || it.finishing }
}

fun RefreshJobState.isFinished(): Boolean =
    this == RefreshJobState.SUCCEEDED || this == RefreshJobState.FAILED || this == RefreshJobState.CANCELLED

/**
 * Remembers which refresh jobs this screen saw unfinished, so it reports a failure only when it
 * watched that job end, not when it opens and finds an old FAILED entry in WorkManager's history.
 */
class RefreshFailureTracker {
    private val seenUnfinished = HashSet<String>()
    private val reported = HashSet<String>()

    /**
     * Feed every new list of jobs.  Returns one message per job this screen watched end badly:
     * FAILED (with its reason, if any), or SUCCEEDED with some feeds unreadable.
     */
    fun onUpdate(jobs: List<RefreshJob>): List<String> {
        val messages = ArrayList<String>()
        for (job in jobs) {
            if (!job.state.isFinished()) {
                seenUnfinished += job.id
            } else if (job.id in seenUnfinished && job.id !in reported) {
                val message = when {
                    job.state == RefreshJobState.FAILED -> refreshFailedMessage(job.error)
                    job.state == RefreshJobState.SUCCEEDED && job.failedFeeds > 0 ->
                        refreshPartlyFailedMessage(job.failedFeeds, job.totalFeeds)
                    else -> null
                }
                if (message != null) {
                    reported += job.id
                    messages += message
                }
            }
        }
        return messages
    }
}

fun refreshFailedMessage(reason: String?): String =
    if (reason.isNullOrBlank()) REFRESH_FAILED_MESSAGE else "$REFRESH_FAILED_MESSAGE: $reason"

fun refreshPartlyFailedMessage(failedFeeds: Int, totalFeeds: Int): String =
    if (totalFeeds > 0) "Couldn't refresh $failedFeeds of $totalFeeds podcasts"
    else if (failedFeeds == 1) "Couldn't refresh 1 podcast" else "Couldn't refresh $failedFeeds podcasts"

const val REFRESH_FAILED_MESSAGE = "Refresh failed"
const val REFRESH_OFFLINE_MESSAGE = "You're offline. Pull to refresh when you're back online."

/** Output-data key the update worker sets with a reason when it fails. */
const val REFRESH_ERROR_KEY = PeriodicPodcastUpdateWorker.KEY_ERROR

/**
 * Online the way WorkManager's CONNECTED constraint means it: a network with internet access that
 * the system has validated.  Without it the refresh work would just sit ENQUEUED.
 */
fun isNetworkConnected(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun WorkInfo.toRefreshJob(): RefreshJob = RefreshJob(
    id = id.toString(),
    state = when (state) {
        WorkInfo.State.ENQUEUED -> RefreshJobState.ENQUEUED
        WorkInfo.State.BLOCKED -> RefreshJobState.BLOCKED
        WorkInfo.State.RUNNING -> RefreshJobState.RUNNING
        WorkInfo.State.SUCCEEDED -> RefreshJobState.SUCCEEDED
        WorkInfo.State.FAILED -> RefreshJobState.FAILED
        WorkInfo.State.CANCELLED -> RefreshJobState.CANCELLED
    },
    error = outputData.getString(REFRESH_ERROR_KEY),
    finishing = state == WorkInfo.State.RUNNING &&
        progress.getString(PeriodicPodcastUpdateWorker.KEY_STAGE) == PeriodicPodcastUpdateWorker.STAGE_FINISHING,
    failedFeeds = outputData.getInt(PeriodicPodcastUpdateWorker.KEY_FAILED_FEEDS, 0),
    totalFeeds = outputData.getInt(PeriodicPodcastUpdateWorker.KEY_TOTAL_FEEDS, 0)
)

@Stable
class PodcastRefreshState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState
) {
    internal var jobs by mutableStateOf<List<RefreshJob>>(emptyList())
    internal var awaitingStart by mutableStateOf(false)

    val isRefreshing: Boolean
        get() = refreshIndicatorVisible(jobs, awaitingStart)

    /** Call from PullToRefreshBox's onRefresh. */
    fun refresh() {
        if (isRefreshing) return
        if (!isNetworkConnected(context)) {
            // Don't enqueue: the work would wait for a network and the spinner would never stop.
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(REFRESH_OFFLINE_MESSAGE)
            }
            return
        }
        awaitingStart = true
        PeriodicPodcastUpdateWorker.enqueueNow(context)
    }
}

/**
 * Pull-to-refresh for the podcast feeds (Home, New episodes): runs the instant update work and
 * reports offline and failed refreshes in [snackbarHostState].
 */
@Composable
fun rememberPodcastRefreshState(snackbarHostState: SnackbarHostState): PodcastRefreshState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(context, snackbarHostState) {
        PodcastRefreshState(context.applicationContext, scope, snackbarHostState)
    }
    val tracker = remember { RefreshFailureTracker() }

    val workInfos by remember(context) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(PeriodicPodcastUpdateWorker.INSTANT_UNIQUE_NAME)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(workInfos) {
        val jobs = workInfos.map { it.toRefreshJob() }
        state.jobs = jobs
        if (jobs.any { it.state == RefreshJobState.RUNNING || it.state.isFinished() }) {
            state.awaitingStart = false
        }
        val problems = tracker.onUpdate(jobs)
        if (problems.isNotEmpty()) {
            // In the screen's scope, so the next WorkInfo update doesn't cancel the message.
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(problems.last())
            }
        }
    }

    LaunchedEffect(state.awaitingStart) {
        if (state.awaitingStart) {
            // Grace for WorkManager to start the job; after that only RUNNING keeps the spinner.
            delay(REFRESH_START_GRACE_MS)
            state.awaitingStart = false
        }
    }

    return state
}

private const val REFRESH_START_GRACE_MS = 2_000L
