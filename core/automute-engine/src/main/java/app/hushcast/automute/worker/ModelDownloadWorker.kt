package app.hushcast.automute.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hushcast.automute.engine.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Dedicated WorkManager job for downloading on-device AI models with NetworkType.UNMETERED
 * by default, Range-based resume, and visible progress (E3, U2).
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val includeAdModels = inputData.getBoolean(KEY_INCLUDE_AD_MODELS, true)
        val job = currentCoroutineContext()[Job]
        val shouldAbort: () -> Boolean = { job?.isActive == false }
        val manager = ModelManager(applicationContext)

        runCatching {
            setForeground(createForegroundInfo("Preparing model download…", 0))
        }

        return try {
            withContext(Dispatchers.IO) {
                manager.ensureAllModels(
                    shouldAbort = shouldAbort,
                    includeAdModels = includeAdModels
                ) { p ->
                    val pct = if (p.totalBytes > 0L) {
                        ((p.bytesDownloaded * 100L) / p.totalBytes).toInt().coerceIn(0, 100)
                    } else 0
                    runCatching {
                        setForegroundAsync(createForegroundInfo(p.modelName, pct))
                    }
                }
            }
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.e("HushCastPerf", "Model download failed: ${t.message}", t)
            Result.retry()
        }
    }

    private fun createForegroundInfo(label: String, progress: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "HushCast Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val clamped = progress.coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading HushCast AI Models")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, clamped, clamped == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        const val UNIQUE_NAME = "hushcast_model_download"
        const val CHANNEL_ID = "hushcast_model_downloads"
        const val NOTIFICATION_ID = 41010
        const val KEY_INCLUDE_AD_MODELS = "include_ad_models"

        fun enqueue(
            context: Context,
            includeAdModels: Boolean = true,
            allowMetered: Boolean = false
        ) {
            val networkType = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .addTag(UNIQUE_NAME)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_INCLUDE_AD_MODELS to includeAdModels))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
