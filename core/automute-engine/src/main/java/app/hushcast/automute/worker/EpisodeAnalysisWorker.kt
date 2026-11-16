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
        val knownSha256 = inputData.getString(KEY_KNOWN_SHA256)
        val quickMode = inputData.getBoolean(KEY_QUICK_MODE, false)
        val actionMode = runCatching {
            MuteActionMode.valueOf(inputData.getString(KEY_ACTION_MODE) ?: MuteActionMode.HARD_SILENCE.name)
        }.getOrDefault(MuteActionMode.HARD_SILENCE)

        val audioFile = File(audioPath)
        if (!audioFile.exists()) return Result.failure()

        runCatching {
            setForeground(createForegroundInfo("Analyzing episode…", 0))
        }

        val db = AutoMuteDatabase.getInstance(applicationContext)
        val analyzer = AutoMuteAnalyzer(applicationContext, db)

        return try {
            analyzer.analyzeEpisode(
                episodeId = episodeId,
                audioFile = audioFile,
                knownSha256 = knownSha256,
                quickWordMuteOnly = quickMode,
                defaultActionMode = actionMode,
                onProgress = { p ->
                    runCatching {
                        setForegroundAsync(createForegroundInfo(p.stage, p.percent))
                    }
                }
            )
            Result.success()
        } catch (t: Throwable) {
            db.dao().upsertStatus(
                EpisodeAnalysisStatusEntity(
                    episodeId = episodeId,
                    fileSha256 = knownSha256 ?: "",
                    badge = ReadinessBadge.NOT_PROCESSED.name,
                    progressPercent = 0,
                    stageLabel = "Analysis failed",
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
            )
            Result.failure()
        }
    }

    private fun createForegroundInfo(stage: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HushCast On-Device Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val clamped = progress.coerceIn(0, 100)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("HushCast Audio Analysis")
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(if (clamped > 0) "$clamped%" else "AI")
            .setProgress(100, clamped, clamped == 0)

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
        const val KEY_KNOWN_SHA256 = "known_sha256"
        const val KEY_QUICK_MODE = "quick_mode"
        const val KEY_ACTION_MODE = "action_mode"

        fun enqueueBackgroundCharging(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String? = null,
            quickMode: Boolean = false,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
        ) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .build()

            val request = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to quickMode,
                        KEY_ACTION_MODE to actionMode.name
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "hushcast_analyze_$episodeId",
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
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
        ) {
            // Expedited jobs on Android only allow network and storage constraints
            // (setting battery-not-low throws IllegalArgumentException in WorkRequest.Builder.build).
            val request = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to quickMode,
                        KEY_ACTION_MODE to actionMode.name
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "hushcast_analyze_$episodeId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
