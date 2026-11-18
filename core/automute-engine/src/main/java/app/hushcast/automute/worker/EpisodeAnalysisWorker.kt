package app.hushcast.automute.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

/**
 * WorkManager worker for on-device episode analysis.
 * Uses per-episode unique work + a process-wide Semaphore(1) so one episode's failure or
 * cancellation never aborts other queued episodes (E6).
 */
class EpisodeAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        val audioPath = inputData.getString(KEY_AUDIO_PATH) ?: return Result.failure()
        val episodeTitle = inputData.getString(KEY_EPISODE_TITLE).orEmpty()
        val knownSha256 = inputData.getString(KEY_KNOWN_SHA256)
        val quickMode = inputData.getBoolean(KEY_QUICK_MODE, false)
        val feedTranscriptRaw = inputData.getString(KEY_FEED_TRANSCRIPT_RAW)
        val feedTranscriptMime = inputData.getString(KEY_FEED_TRANSCRIPT_MIME).orEmpty()
        val actionMode = runCatching {
            MuteActionMode.valueOf(inputData.getString(KEY_ACTION_MODE) ?: MuteActionMode.HARD_SILENCE.name)
        }.getOrDefault(MuteActionMode.HARD_SILENCE)

        val db = AutoMuteDatabase.getInstance(applicationContext)
        val audioFile = File(audioPath)
        val displayTitle = episodeTitle.ifBlank { "Episode analysis" }
        val notificationId = NOTIFICATION_ID_BASE + (episodeId.hashCode() and 0x0FFF)

        if (!audioFile.exists()) {
            db.dao().upsertStatus(
                EpisodeAnalysisStatusEntity(
                    episodeId = episodeId,
                    fileSha256 = knownSha256 ?: "",
                    badge = ReadinessBadge.NOT_PROCESSED.name,
                    progressPercent = 0,
                    stageLabel = "Audio not downloaded",
                    errorMessage = null
                )
            )
            return Result.failure()
        }

        return analysisSemaphore.withPermit {
            runCatching {
                setForeground(createForegroundInfo(notificationId, displayTitle, "Analyzing episode…", 0))
            }

            val analyzer = AutoMuteAnalyzer(applicationContext, db)
            android.util.Log.i("HushCastPerf", "Worker start: ep=$episodeId quick=$quickMode bytes=${audioFile.length()}")

            var lastNotifPct = 0
            try {
                analyzer.analyzeEpisode(
                    episodeId = episodeId,
                    audioFile = audioFile,
                    knownSha256 = knownSha256,
                    quickWordMuteOnly = quickMode,
                    feedTranscriptRaw = feedTranscriptRaw,
                    feedTranscriptMime = feedTranscriptMime,
                    defaultActionMode = actionMode,
                    onProgress = { p ->
                        val clampedPct = maxOf(lastNotifPct, p.percent.coerceIn(0, 100))
                        lastNotifPct = clampedPct
                        runCatching {
                            setForegroundAsync(createForegroundInfo(notificationId, displayTitle, p.stage, clampedPct))
                        }
                    }
                )
                android.util.Log.i("HushCastPerf", "Worker success: ep=$episodeId")
                analysisCompletedEvents.tryEmit(episodeId)
                Result.success()
            } catch (c: CancellationException) {
                val reason = if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_NOT_STOPPED
                val isPausedBySystem = reason == WorkInfo.STOP_REASON_CONSTRAINT_CHARGING ||
                    reason == WorkInfo.STOP_REASON_TIMEOUT ||
                    reason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
                    reason == WorkInfo.STOP_REASON_QUOTA
                android.util.Log.w("HushCastPerf", "Worker stopped: ep=$episodeId reason=$reason paused=$isPausedBySystem")
                withContext(NonCancellable) {
                    val existing = runCatching { db.dao().getStatus(episodeId) }.getOrNull()
                    val preservedWordMute = existing?.badge == ReadinessBadge.WORD_MUTE_READY.name
                    val label = when {
                        isPausedBySystem && preservedWordMute -> "Paused (Word-Mute Ready)"
                        isPausedBySystem -> "Paused (waiting to resume)"
                        preservedWordMute -> "Cancelled (Word-Mute Ready)"
                        else -> "Cancelled"
                    }
                    db.dao().upsertStatus(
                        EpisodeAnalysisStatusEntity(
                            episodeId = episodeId,
                            fileSha256 = existing?.fileSha256?.ifBlank { knownSha256 ?: "" } ?: (knownSha256 ?: ""),
                            badge = if (preservedWordMute) ReadinessBadge.WORD_MUTE_READY.name else ReadinessBadge.NOT_PROCESSED.name,
                            progressPercent = if (preservedWordMute) 100 else (existing?.progressPercent ?: 0),
                            stageLabel = label,
                            peakRssMb = existing?.peakRssMb ?: 0,
                            errorMessage = null
                        )
                    )
                }
                throw c
            } catch (t: Throwable) {
                android.util.Log.e("HushCastPerf", "Worker failed: ep=$episodeId ${t.javaClass.simpleName}: ${t.message}", t)
                val existing = runCatching { db.dao().getStatus(episodeId) }.getOrNull()
                val preservedWordMute = existing?.badge == ReadinessBadge.WORD_MUTE_READY.name
                db.dao().upsertStatus(
                    EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        fileSha256 = existing?.fileSha256?.ifBlank { knownSha256 ?: "" } ?: (knownSha256 ?: ""),
                        badge = if (preservedWordMute) ReadinessBadge.WORD_MUTE_READY.name else ReadinessBadge.NOT_PROCESSED.name,
                        progressPercent = if (preservedWordMute) 100 else 0,
                        stageLabel = if (preservedWordMute) "Ad detection failed (Word-Mute Ready)" else "Analysis failed",
                        peakRssMb = existing?.peakRssMb ?: 0,
                        errorMessage = t.message ?: t.javaClass.simpleName
                    )
                )
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        episodeTitle: String,
        stage: String,
        progress: Int
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HushCast On-Device Analysis",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val clamped = progress.coerceIn(0, 100)
        val titleText = episodeTitle.ifBlank { "HushCast Audio Analysis" }
        val chipText = if (clamped > 0) "$clamped%" else "AI"

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chipText)
            .setProgress(100, clamped, clamped == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(clamped)
                    .setProgressIndeterminate(clamped == 0)
            )
        }

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= 35) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "hushcast_analysis"
        const val KEY_EPISODE_ID = "episode_id"
        const val KEY_AUDIO_PATH = "audio_path"
        const val KEY_EPISODE_TITLE = "episode_title"
        const val KEY_KNOWN_SHA256 = "known_sha256"
        const val KEY_QUICK_MODE = "quick_mode"
        const val KEY_ACTION_MODE = "action_mode"
        const val KEY_FEED_TRANSCRIPT_RAW = "feed_transcript_raw"
        const val KEY_FEED_TRANSCRIPT_PATH = "feed_transcript_path"
        const val KEY_FEED_TRANSCRIPT_MIME = "feed_transcript_mime"

        const val ANALYSIS_QUEUE_NAME = "hushcast_analysis_queue"
        private const val NOTIFICATION_ID_BASE = 41001
        private val analysisSemaphore = Semaphore(1)

        private fun uniqueWorkName(episodeId: String) = "hushcast_analysis_$episodeId"

        private fun stageTranscriptFile(context: Context, episodeId: String, raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
                val safeName = episodeId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
                val file = File(dir, "$safeName.txt")
                file.writeText(raw)
                file.absolutePath
            }.getOrNull()
        }

        fun enqueueBackgroundCharging(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String? = null,
            quickMode: Boolean = false,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
            episodeTitle: String? = null,
            feedTranscriptRaw: String? = null,
            feedTranscriptMime: String = ""
        ) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .build()
            val transcriptPath = stageTranscriptFile(context, episodeId, feedTranscriptRaw)

            val request = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .addTag(ANALYSIS_QUEUE_NAME)
                .addTag("episode:$episodeId")
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_EPISODE_TITLE to episodeTitle,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to quickMode,
                        KEY_ACTION_MODE to actionMode.name,
                        KEY_FEED_TRANSCRIPT_PATH to transcriptPath,
                        KEY_FEED_TRANSCRIPT_MIME to feedTranscriptMime
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(episodeId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueAnalyzeNow(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String? = null,
            quickMode: Boolean = false,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
            episodeTitle: String? = null,
            feedTranscriptRaw: String? = null,
            feedTranscriptMime: String = ""
        ) {
            val chargingOnly = HushCastPrefs.isAnalyzeOnlyWhileCharging(context)
            val transcriptPath = stageTranscriptFile(context, episodeId, feedTranscriptRaw)
            val builder = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .addTag(ANALYSIS_QUEUE_NAME)
                .addTag("episode:$episodeId")
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_EPISODE_TITLE to episodeTitle,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to quickMode,
                        KEY_ACTION_MODE to actionMode.name,
                        KEY_FEED_TRANSCRIPT_PATH to transcriptPath,
                        KEY_FEED_TRANSCRIPT_MIME to feedTranscriptMime
                    )
                )
            if (chargingOnly) {
                builder.setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .build()
                )
            } else {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(episodeId),
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }

        fun cancelEpisode(context: Context, episodeId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(episodeId))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ANALYSIS_QUEUE_NAME)
        }

        val analysisCompletedEvents = MutableSharedFlow<String>(
            extraBufferCapacity = 16
        )
    }
}
