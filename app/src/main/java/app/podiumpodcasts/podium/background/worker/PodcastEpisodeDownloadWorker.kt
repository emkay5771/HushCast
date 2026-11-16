package app.podiumpodcasts.podium.background.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.background.work.PodcastEpisodeDownloadWork
import app.podiumpodcasts.podium.manager.DatabaseManager
import app.podiumpodcasts.podium.ui.route.settings.SettingsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

const val KEY_EPISODE_ID = "KEY_EPISODE_ID"
const val KEY_DOWNLOAD_METHOD = "KEY_DOWNLOAD_METHOD"
const val KEY_IS_AUTO = "KEY_IS_AUTO"
const val KEY_FORCE_REPROCESS = "KEY_FORCE_REPROCESS"

class PodcastEpisodeDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(
    context, params
) {
    val db by lazy {
        DatabaseManager.build(context)
    }

    override suspend fun doWork(): Result {
        return try {
            val episodeId = inputData.getString(KEY_EPISODE_ID)
                ?: throw NullPointerException()
            val method = inputData.getString(KEY_DOWNLOAD_METHOD)?.let { raw ->
                runCatching { app.hushcast.automute.engine.DownloadAnalysisMethod.valueOf(raw) }.getOrNull()
            } ?: app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO
            val isAuto = inputData.getBoolean(KEY_IS_AUTO, false)
            val forceReprocess = inputData.getBoolean(KEY_FORCE_REPROCESS, false)

            runCatching {
                setForeground(createDownloadForegroundInfo("Downloading episode…", "Preparing download…", 0))
            }

            var lastTitle = ""
            val worker = PodcastEpisodeDownloadWork(applicationContext, db)
            val result = worker.doWork(
                episodeId = episodeId,
                downloadMethod = method,
                isAuto = isAuto,
                forceReprocess = forceReprocess
            ) { title, sourceLabel, pct ->
                if (title.isNotBlank()) lastTitle = title
                runCatching {
                    setForegroundAsync(createDownloadForegroundInfo(title, sourceLabel, pct))
                }
            }

            if(result) {
                runCatching {
                    val resolvedTitle = lastTitle.ifBlank {
                        db.podcastEpisodes().getSync(episodeId).episode.title
                    }
                    postDownloadCompleteNotification(episodeId, resolvedTitle, method)
                }
                Result.success()
            } else {
                Result.failure()
            }
        } catch(e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun createDownloadForegroundInfo(
        title: String,
        sourceLabel: String = "Downloading",
        percent: Int
    ): androidx.work.ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Episode Downloads",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val clamped = percent.coerceIn(0, 100)
        val chipText = if (clamped > 0) "$clamped%" else "…"
        val builder = androidx.core.app.NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Downloading episode" })
            .setContentText(if (clamped > 0) "$sourceLabel • $clamped%" else sourceLabel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chipText)
            .setProgress(100, clamped, clamped == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (android.os.Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                androidx.core.app.NotificationCompat.ProgressStyle()
                    .setProgress(clamped)
                    .setProgressIndeterminate(clamped == 0)
            )
        }

        val notification = builder.build()

        val notificationId = 41002
        return if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            androidx.work.ForegroundInfo(notificationId, notification)
        }
    }

    private fun postDownloadCompleteNotification(
        episodeId: String,
        title: String,
        method: app.hushcast.automute.engine.DownloadAnalysisMethod
    ) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                DOWNLOAD_COMPLETE_CHANNEL_ID,
                "Completed Episode Downloads",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val statusText = when (method) {
            app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS -> "Download complete"
            else -> {
                val stage = kotlinx.coroutines.runBlocking {
                    runCatching {
                        app.hushcast.automute.storage.AutoMuteDatabase.getInstance(applicationContext)
                            .dao()
                            .getStatus(episodeId)
                            ?.stageLabel
                    }.getOrNull()
                }
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
            android.app.PendingIntent.getActivity(
                applicationContext,
                episodeId.hashCode(),
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = androidx.core.app.NotificationCompat.Builder(applicationContext, DOWNLOAD_COMPLETE_CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Episode ready" })
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        val notifId = 41100 + (episodeId.hashCode() and 0x7FFF)
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
            forceReprocess: Boolean = false
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
                KEY_FORCE_REPROCESS to forceReprocess
            )

            val networkType = when(downloadMetered) {
                false -> NetworkType.UNMETERED
                else -> when(downloadInRoaming) {
                    true -> NetworkType.CONNECTED
                    false -> NetworkType.NOT_ROAMING
                }
            }

            val downloadRequest = OneTimeWorkRequestBuilder<PodcastEpisodeDownloadWorker>()
                .setInputData(podcastData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            networkType = if(auto && !applySettingsForAutoDownloads)
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
                    ExistingWorkPolicy.REPLACE,
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
        }
    }

}