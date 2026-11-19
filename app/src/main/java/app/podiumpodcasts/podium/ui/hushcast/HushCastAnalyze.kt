package app.podiumpodcasts.podium.ui.hushcast

import android.content.Context
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.DownloadAnalysisMethod
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.manager.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a user tap on "Process episode", "Re-process episode" or an Up Next row's analyze
 * button does.  One place, so every entry point routes the same way:
 *  - an active worker (switched on, with an address) gets the job, forced to re-process;
 *  - otherwise a downloaded episode is analyzed on the phone, replacing any queued job;
 *  - otherwise the caller has to offer a download first.
 */
object HushCastAnalyze {

    enum class Outcome {
        /** Sent to the desktop or cloud worker. */
        SENT_TO_WORKER,
        /** Running on the phone now. */
        STARTED_ON_DEVICE,
        /** Queued on the phone; "Analyze only while charging" is on and the phone isn't charging. */
        WAITING_FOR_CHARGER,
        /** Not downloaded and no worker: offer to download first. */
        NEEDS_DOWNLOAD
    }

    suspend fun start(
        context: Context,
        db: AppDatabase,
        episodeId: String,
        actionMode: MuteActionMode,
        reprocessStage: String? = null
    ): Outcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        // The worker is used only when it's switched on and has an address (same rule everywhere).
        if (HushCastPrefs.isWorkerActive(appContext)) {
            DownloadManager.downloadEpisode(
                context = appContext,
                db = db,
                episodeId = episodeId,
                downloadMethod = DownloadAnalysisMethod.DESKTOP,
                forceReprocess = true,
                reprocessStage = reprocessStage
            )
            return@withContext Outcome.SENT_TO_WORKER
        }

        val bundle = runCatching { db.podcastEpisodes().getSyncNullable(episodeId) }.getOrNull()
        val file = bundle?.episode?.craftDownloadFile(appContext)
        if (bundle == null || file == null || !file.exists() || file.length() <= 0L) {
            return@withContext Outcome.NEEDS_DOWNLOAD
        }

        val waitingForCharger = HushCastPrefs.isAnalyzeOnlyWhileCharging(appContext) && !isCharging(appContext)
        if (!waitingForCharger) {
            // Only claim "Analyzing" when the work can actually start now.
            AutoMuteDatabase.getInstance(appContext).dao().upsertStatus(
                EpisodeAnalysisStatusEntity(
                    episodeId = episodeId,
                    badge = ReadinessBadge.ANALYZING.name,
                    progressPercent = 0,
                    stageLabel = "Starting analysis…"
                )
            )
        }
        // The publisher's transcript, when the feed has one, saves a full Whisper pass.  Bounded so
        // the tap's confirmation never waits long on a slow server.
        val feedTranscript = kotlinx.coroutines.withTimeoutOrNull(FEED_TRANSCRIPT_TIMEOUT_MS) {
            app.podiumpodcasts.podium.api.download.FeedTranscriptFetcher.fetchBestForAnalysis(bundle.episode)
        }
        EpisodeAnalysisWorker.enqueueAnalyzeNow(
            context = appContext,
            episodeId = episodeId,
            audioPath = file.absolutePath,
            actionMode = actionMode,
            episodeTitle = bundle.episode.title,
            feedTranscriptRaw = feedTranscript?.raw,
            feedTranscriptMime = feedTranscript?.mime ?: bundle.episode.transcriptType,
            // A different queued job (e.g. auto-download's charging-only one) is replaced; an identical
            // one that is already running is kept, so a second tap doesn't restart the analysis.
            force = false
        )
        if (waitingForCharger) Outcome.WAITING_FOR_CHARGER else Outcome.STARTED_ON_DEVICE
    }

    /**
     * "Cancel processing": stops on-device analysis and the worker job (the cloud job used to keep
     * running, and billing, after a cancel), then puts the badge back to what's usable.
     */
    suspend fun cancel(context: Context, episodeId: String) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        EpisodeAnalysisWorker.cancelEpisode(appContext, episodeId)
        // Cancels the offload work and the job it's tracking in memory.
        app.podiumpodcasts.podium.background.worker.PodcastEpisodeDownloadWorker.stopDownload(appContext, episodeId)
        // After an app restart that in-memory reference is gone, so cancel the saved job id too.
        HushCastPrefs.getWorkerJobId(appContext, episodeId)?.let { jobId ->
            val url = HushCastPrefs.getDesktopWorkerUrl(appContext)
            if (url.isNotBlank()) {
                runCatching {
                    app.hushcast.automute.desktop.DesktopWorkerClient.cancelJob(
                        url,
                        HushCastPrefs.getDesktopWorkerToken(appContext),
                        jobId
                    )
                }
            }
            HushCastPrefs.setWorkerJobId(appContext, episodeId, null)
        }
        val dao = AutoMuteDatabase.getInstance(appContext).dao()
        val status = dao.getStatus(episodeId) ?: return@withContext
        val hasTranscript = status.fileSha256.isNotBlank() && dao.getWordIndex(status.fileSha256) != null
        dao.upsertStatus(
            status.copy(
                badge = if (hasTranscript) ReadinessBadge.READY.name else ReadinessBadge.NOT_PROCESSED.name,
                progressPercent = if (hasTranscript) 100 else 0,
                stageLabel = "Cancelled"
            )
        )
    }

    /** A short confirmation to show after [start]. */
    fun message(outcome: Outcome): String = when (outcome) {
        Outcome.SENT_TO_WORKER -> "Sent to your HushCast worker"
        Outcome.STARTED_ON_DEVICE -> "Analyzing on this phone"
        Outcome.WAITING_FOR_CHARGER -> "Will analyze when the phone is charging"
        Outcome.NEEDS_DOWNLOAD -> "Download the episode first"
    }

    private const val FEED_TRANSCRIPT_TIMEOUT_MS = 6_000L

    private fun isCharging(context: Context): Boolean =
        context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
}
