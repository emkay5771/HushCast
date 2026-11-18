package app.podiumpodcasts.podium.background.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.background.work.DownloadWorkOutcome
import app.podiumpodcasts.podium.background.work.PodcastEpisodeDownloadWork
import app.podiumpodcasts.podium.manager.DatabaseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

const val KEY_EPISODE_ID = "KEY_EPISODE_ID"
const val KEY_DOWNLOAD_METHOD = "KEY_DOWNLOAD_METHOD"
const val KEY_IS_AUTO = "KEY_IS_AUTO"
const val KEY_FORCE_REPROCESS = "KEY_FORCE_REPROCESS"
const val KEY_REPROCESS_STAGE = "KEY_REPROCESS_STAGE"

class PodcastEpisodeDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db by lazy { DatabaseManager.build(context) }

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        val method = inputData.getString(KEY_DOWNLOAD_METHOD)?.let { raw ->
            runCatching { app.hushcast.automute.engine.DownloadAnalysisMethod.valueOf(raw) }.getOrNull()
        } ?: app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO
        val isAuto = inputData.getBoolean(KEY_IS_AUTO, false)
        val forceReprocess = inputData.getBoolean(KEY_FORCE_REPROCESS, false)
        val reprocessStage = inputData.getString(KEY_REPROCESS_STAGE)
        android.util.Log.i(
            "HushCastDesktop",
            "Worker start ep=$episodeId attempt=$runAttemptCount method=$method auto=$isAuto force=$forceReprocess stage=$reprocessStage"
        )

        return try {
            runCatching {
                setForeground(
                    createDownloadForegroundInfo(
                        episodeId = episodeId,
                        title = "Downloading episode…",
                        sourceLabel = "Preparing download…",
                        percent = 0
                    )
                )
            }

            var lastTitle = ""
            var lastNotifPct = 0
            var lastNotifMs = 0L
            val worker = PodcastEpisodeDownloadWork(applicationContext, db)
            val isFinalAttempt = runAttemptCount >= 4
            val outcome = worker.doWork(
                episodeId = episodeId,
                downloadMethod = method,
                isAuto = isAuto,
                forceReprocess = forceReprocess,
                reprocessStage = reprocessStage,
                isFinalAttempt = isFinalAttempt,
                isRetry = runAttemptCount > 0
            ) { title, sourceLabel, pct ->
                if (title.isNotBlank()) lastTitle = title
                val clampedPct = maxOf(lastNotifPct, pct.coerceIn(0, 100))
                val now = System.currentTimeMillis()
                if (now - lastNotifMs < 500L && clampedPct < 100 && clampedPct == lastNotifPct) {
                    return@doWork
                }
                if (now - lastNotifMs < 500L && clampedPct < 100) {
                    return@doWork
                }
                lastNotifMs = now
                lastNotifPct = clampedPct
                runCatching {
                    setForegroundAsync(
                        createDownloadForegroundInfo(
                            episodeId = episodeId,
                            title = title,
                            sourceLabel = sourceLabel,
                            percent = clampedPct,
                            isAnalysis = method != app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS
                        )
                    )
                }
            }

            when (outcome) {
                DownloadWorkOutcome.Success -> {
                    android.util.Log.i("HushCastDesktop", "Worker success ep=$episodeId attempt=$runAttemptCount")
                    runCatching {
                        val resolvedTitle = lastTitle.ifBlank {
                            db.podcastEpisodes().getSyncNullable(episodeId)?.episode?.title.orEmpty()
                        }
                        postDownloadCompleteNotification(episodeId, resolvedTitle, method)
                    }
                    Result.success()
                }

                DownloadWorkOutcome.RetryableFailure -> {
                    if (!isFinalAttempt) {
                        android.util.Log.w("HushCastDesktop", "Worker retrying ep=$episodeId attempt=$runAttemptCount")
                        Result.retry()
                    } else {
                        android.util.Log.e("HushCastDesktop", "Worker failed (max attempts) ep=$episodeId")
                        db.podcastEpisodeDownloads().setState(episodeId, PodcastEpisodeDownloadState.FAILED.value)
                        Result.failure()
                    }
                }

                DownloadWorkOutcome.PermanentFailure -> {
                    android.util.Log.e("HushCastDesktop", "Worker permanent failure ep=$episodeId")
                    Result.failure()
                }
            }
        } catch (c: CancellationException) {
            val reason = if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED
            android.util.Log.w("HushCastDesktop", "Worker cancelled ep=$episodeId attempt=$runAttemptCount stopReason=$reason")
            val isPausedBySystem = reason == androidx.work.WorkInfo.STOP_REASON_TIMEOUT ||
                reason == androidx.work.WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
                reason == androidx.work.WorkInfo.STOP_REASON_QUOTA ||
                reason == androidx.work.WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY ||
                reason == androidx.work.WorkInfo.STOP_REASON_CONSTRAINT_CHARGING ||
                reason == androidx.work.WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW ||
                reason == androidx.work.WorkInfo.STOP_REASON_DEVICE_STATE
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    val autoMuteDao = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(applicationContext).dao()
                    val existing = autoMuteDao.getStatus(episodeId)
                    if (existing != null && existing.badge == app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name) {
                        if (isPausedBySystem) {
                            autoMuteDao.upsertStatus(
                                existing.copy(
                                    stageLabel = "Paused (waiting to resume)"
                                )
                            )
                        } else {
                            autoMuteDao.upsertStatus(
                                existing.copy(
                                    badge = app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name,
                                    progressPercent = 0,
                                    stageLabel = "Cancelled"
                                )
                            )
                        }
                    }
                }
            }
            throw c
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 4) {
                Result.retry()
            } else {
                runCatching {
                    db.podcastEpisodeDownloads().setState(episodeId, PodcastEpisodeDownloadState.FAILED.value)
                    val autoMuteDao = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(applicationContext).dao()
                    val st = autoMuteDao.getStatus(episodeId)
                    if (st?.badge == app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name) {
                        autoMuteDao.upsertStatus(
                            st.copy(
                                badge = app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name,
                                progressPercent = 0,
                                stageLabel = "Failed",
                                errorMessage = e.message ?: e.javaClass.simpleName
                            )
                        )
                    }
                }
                Result.failure()
            }
        }
    }

    private fun createDownloadForegroundInfo(
        episodeId: String,
        title: String,
        sourceLabel: String = "Downloading",
        percent: Int,
        isAnalysis: Boolean = true
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Episode Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val clamped = percent.coerceIn(0, 100)
        val chipText = if (clamped > 0) "$clamped%" else "…"
        val builder = NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Downloading episode" })
            .setContentText(if (clamped > 0) "$sourceLabel • $clamped%" else sourceLabel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chipText)
            .setProgress(100, clamped, clamped == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (android.os.Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(clamped)
                    .setProgressIndeterminate(clamped == 0)
            )
        }

        val notification = builder.build()
        val notificationId = 41000 + (episodeId.hashCode() and 0x3FFF)
        val serviceType = if (isAnalysis && android.os.Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(
            notificationId,
            notification,
            serviceType
        )
    }

    private suspend fun postDownloadCompleteNotification(
        episodeId: String,
        title: String,
        method: app.hushcast.automute.engine.DownloadAnalysisMethod
    ) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            DOWNLOAD_COMPLETE_CHANNEL_ID,
            "Completed Episode Downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val statusText = when (method) {
            app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS -> "Download complete"
            else -> {
                val stage = runCatching {
                    app.hushcast.automute.storage.AutoMuteDatabase.getInstance(applicationContext)
                        .dao()
                        .getStatus(episodeId)
                        ?.stageLabel
                }.getOrNull()
                if (!stage.isNullOrBlank() && stage.startsWith("Ready", ignoreCase = true)) {
                    "Download & AI analysis complete • $stage"
                } else {
                    "Download complete"
                }
            }
        }

        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                episodeId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(applicationContext, DOWNLOAD_COMPLETE_CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Episode ready" })
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        val notifId = 58000 + (episodeId.hashCode() and 0x3FFF)
        manager.notify(notifId, builder.build())
    }

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "episode_downloads"
        private const val DOWNLOAD_COMPLETE_CHANNEL_ID = "episode_downloads_complete"

        fun generateUniqueWorkName(episodeId: String): String {
            return "app.podiumpodcasts.podium.background.worker.PodcastEpisodeDownloadWorker:$episodeId"
        }

        suspend fun enqueueDownload(
            context: Context,
            episodeId: String,
            settingsRepository: SettingsRepository = SettingsRepository(context),
            auto: Boolean = false,
            downloadMethod: app.hushcast.automute.engine.DownloadAnalysisMethod? = null,
            forceReprocess: Boolean = false,
            reprocessStage: String? = null
        ) {
            val downloadMetered = settingsRepository.behavior.downloadMetered.first()
            val downloadInRoaming = settingsRepository.behavior.downloadInRoaming.first()
            val applySettingsForAutoDownloads =
                settingsRepository.behavior.applySettingsForAutoDownloads.first()

            val effectiveMethod = downloadMethod
                ?: if (auto) app.hushcast.automute.engine.HushCastPrefs.getAutoDownloadMethod(context)
                else app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO

            val podcastData = workDataOf(
                KEY_EPISODE_ID to episodeId,
                KEY_DOWNLOAD_METHOD to effectiveMethod.name,
                KEY_IS_AUTO to auto,
                KEY_FORCE_REPROCESS to forceReprocess,
                KEY_REPROCESS_STAGE to reprocessStage
            )

            val networkType = when (downloadMetered) {
                false -> NetworkType.UNMETERED
                else -> when (downloadInRoaming) {
                    true -> NetworkType.CONNECTED
                    false -> NetworkType.NOT_ROAMING
                }
            }

            val downloadRequest = OneTimeWorkRequestBuilder<PodcastEpisodeDownloadWorker>()
                .setInputData(podcastData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            networkType = if (auto && !applySettingsForAutoDownloads)
                                NetworkType.UNMETERED
                            else
                                networkType
                        )
                        .build()
                ).build()

            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    generateUniqueWorkName(episodeId),
                    if (forceReprocess) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    downloadRequest
                )
        }

        fun stopDownload(
            context: Context,
            episodeId: String
        ) {
            WorkManager
                .getInstance(context)
                .cancelUniqueWork(generateUniqueWorkName(episodeId))
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                app.hushcast.automute.desktop.DesktopWorkerClient.cancelActiveJobForEpisode(episodeId)
            }
        }
    }
}