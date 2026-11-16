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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import java.io.File

/**
 * WorkManager worker for on-device episode analysis with 3-tier ForegroundService compatibility:
 * - Android 15+ (API 35+): FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
 * - Android 10-14 (API 29-34): FOREGROUND_SERVICE_TYPE_DATA_SYNC
 * - Android 8-9 (API 26-28): Legacy untyped ForegroundInfo
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
        val actionMode = runCatching {
            MuteActionMode.valueOf(inputData.getString(KEY_ACTION_MODE) ?: MuteActionMode.HARD_SILENCE.name)
        }.getOrDefault(MuteActionMode.HARD_SILENCE)

        val db = AutoMuteDatabase.getInstance(applicationContext)
        val audioFile = File(audioPath)
        val displayTitle = episodeTitle.ifBlank { audioFile.nameWithoutExtension }

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
            onEpisodeFinished(applicationContext, id)
            return Result.failure()
        }

        val (currentIndex, totalCount) = beginEpisodeProgress(applicationContext)

        runCatching {
            setForeground(createForegroundInfo(displayTitle, currentIndex, totalCount, "Analyzing episode…", 0))
        }

        val analyzer = AutoMuteAnalyzer(applicationContext, db)
        android.util.Log.i("HushCastPerf", "Worker start ($currentIndex/$totalCount): ep=$episodeId title=\"$displayTitle\" quick=$quickMode file=${audioFile.name} (${audioFile.length() / 1024} KB)")

        return try {
            analyzer.analyzeEpisode(
                episodeId = episodeId,
                audioFile = audioFile,
                knownSha256 = knownSha256,
                quickWordMuteOnly = quickMode,
                defaultActionMode = actionMode,
                onProgress = { p ->
                    runCatching {
                        setForegroundAsync(createForegroundInfo(displayTitle, currentIndex, totalCount, p.stage, p.percent))
                    }
                }
            )
            android.util.Log.i("HushCastPerf", "Worker success ($currentIndex/$totalCount): ep=$episodeId")
            Result.success()
        } catch (c: kotlinx.coroutines.CancellationException) {
            android.util.Log.w("HushCastPerf", "Worker cancelled ($currentIndex/$totalCount): ep=$episodeId")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val existing = runCatching { db.dao().getStatus(episodeId) }.getOrNull()
                val preservedWordMute = existing?.badge == ReadinessBadge.WORD_MUTE_READY.name
                db.dao().upsertStatus(
                    EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        fileSha256 = existing?.fileSha256?.ifBlank { knownSha256 ?: "" } ?: (knownSha256 ?: ""),
                        badge = if (preservedWordMute) ReadinessBadge.WORD_MUTE_READY.name else ReadinessBadge.NOT_PROCESSED.name,
                        progressPercent = if (preservedWordMute) 100 else 0,
                        stageLabel = if (preservedWordMute) "Cancelled (Word-Mute Ready)" else "Cancelled",
                        peakRssMb = existing?.peakRssMb ?: 0,
                        errorMessage = null
                    )
                )
            }
            throw c
        } catch (t: Throwable) {
            android.util.Log.e("HushCastPerf", "Worker failed ($currentIndex/$totalCount): ep=$episodeId ${t.javaClass.simpleName}: ${t.message}", t)
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
        } finally {
            onEpisodeFinished(applicationContext, id)
        }
    }

    private fun createForegroundInfo(
        episodeTitle: String,
        currentIndex: Int,
        totalCount: Int,
        stage: String,
        progress: Int
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HushCast On-Device Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val clamped = progress.coerceIn(0, 100)
        val titleText = episodeTitle.ifBlank { "HushCast Audio Analysis" }
        val countLabel = "$currentIndex of $totalCount"
        val chipText = if (clamped > 0) "$currentIndex/$totalCount $clamped%" else "$currentIndex/$totalCount"

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(titleText)
            .setSubText(countLabel)
            .setContentText("$countLabel • $stage")
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

        val notificationId = 41001
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> ForegroundInfo(notificationId, notification)
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

        private const val BATCH_PREFS = "hushcast_analysis_batch"
        private const val KEY_BATCH_TOTAL = "batch_total"
        private const val KEY_BATCH_COMPLETED = "batch_completed"
        private val batchLock = Any()

        private fun recordEnqueued(context: Context) {
            synchronized(batchLock) {
                val prefs = context.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
                val completed = prefs.getInt(KEY_BATCH_COMPLETED, 0)
                val total = prefs.getInt(KEY_BATCH_TOTAL, 0)
                val (newCompleted, newTotal) = if (total <= 0 || completed >= total) {
                    0 to 1
                } else {
                    completed to (total + 1)
                }
                prefs.edit()
                    .putInt(KEY_BATCH_COMPLETED, newCompleted)
                    .putInt(KEY_BATCH_TOTAL, newTotal)
                    .apply()
            }
        }

        private fun beginEpisodeProgress(context: Context): Pair<Int, Int> {
            val activeCount = runCatching {
                val infos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(ANALYSIS_QUEUE_NAME)
                    .get()
                infos.count {
                    it.state == androidx.work.WorkInfo.State.RUNNING ||
                        it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        it.state == androidx.work.WorkInfo.State.BLOCKED
                }
            }.getOrDefault(1).coerceAtLeast(1)

            synchronized(batchLock) {
                val prefs = context.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
                val completed = prefs.getInt(KEY_BATCH_COMPLETED, 0)
                val storedTotal = prefs.getInt(KEY_BATCH_TOTAL, 0)
                val currentIndex = (completed + 1).coerceAtLeast(1)
                val total = maxOf(storedTotal, currentIndex + activeCount - 1)
                if (total != storedTotal) {
                    prefs.edit().putInt(KEY_BATCH_TOTAL, total).apply()
                }
                return currentIndex to total
            }
        }

        private fun onEpisodeFinished(context: Context, currentWorkId: java.util.UUID) {
            val hasMorePending = runCatching {
                val infos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(ANALYSIS_QUEUE_NAME)
                    .get()
                infos.any {
                    it.id != currentWorkId && (
                        it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                            it.state == androidx.work.WorkInfo.State.BLOCKED ||
                            it.state == androidx.work.WorkInfo.State.RUNNING
                        )
                }
            }.getOrDefault(false)

            synchronized(batchLock) {
                val prefs = context.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
                if (!hasMorePending) {
                    prefs.edit()
                        .putInt(KEY_BATCH_COMPLETED, 0)
                        .putInt(KEY_BATCH_TOTAL, 0)
                        .apply()
                } else {
                    val completed = prefs.getInt(KEY_BATCH_COMPLETED, 0) + 1
                    prefs.edit().putInt(KEY_BATCH_COMPLETED, completed).apply()
                }
            }
        }

        private fun resetBatch(context: Context) {
            synchronized(batchLock) {
                context.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_BATCH_COMPLETED, 0)
                    .putInt(KEY_BATCH_TOTAL, 0)
                    .apply()
            }
        }

        fun enqueueBackgroundCharging(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String? = null,
            quickMode: Boolean = false,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
            episodeTitle: String? = null
        ) {
            recordEnqueued(context)
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .build()

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
                        KEY_ACTION_MODE to actionMode.name
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ANALYSIS_QUEUE_NAME,
                ExistingWorkPolicy.APPEND,
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
            episodeTitle: String? = null
        ) {
            recordEnqueued(context)
            val chargingOnly = app.hushcast.automute.engine.HushCastPrefs.isAnalyzeOnlyWhileCharging(context)
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
                        KEY_ACTION_MODE to actionMode.name
                    )
                )
            if (chargingOnly) {
                builder.setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .build()
                )
            } else {
                // Expedited jobs on Android only allow network and storage constraints
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                ANALYSIS_QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                builder.build()
            )
        }

        fun cancelEpisode(context: Context, episodeId: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag("episode:$episodeId")
        }

        fun cancelAll(context: Context) {
            resetBatch(context)
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(ANALYSIS_QUEUE_NAME)
            wm.cancelAllWorkByTag(ANALYSIS_QUEUE_NAME)
        }

        const val ANALYSIS_QUEUE_NAME = "hushcast_analysis_queue"
    }
}
