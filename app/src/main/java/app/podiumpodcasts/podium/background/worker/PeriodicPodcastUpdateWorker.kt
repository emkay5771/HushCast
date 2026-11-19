package app.podiumpodcasts.podium.background.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.background.work.PodcastUpdateWork
import app.podiumpodcasts.podium.background.work.RefreshSummary
import kotlinx.coroutines.CancellationException
import app.podiumpodcasts.podium.manager.DatabaseManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class PeriodicPodcastUpdateWorker(
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
            runCatching { setProgress(workDataOf(KEY_STAGE to STAGE_FEEDS)) }
            val manual = tags.contains(TAG_MANUAL)
            val worker = PodcastUpdateWork(applicationContext, db)
            val summary = worker.doWork(
                chapterBudgetMs = if (manual) {
                    app.podiumpodcasts.podium.api.chapters.ChapterFetchPolicy.MANUAL_BUDGET_MS
                } else {
                    app.podiumpodcasts.podium.api.chapters.ChapterFetchPolicy.PERIODIC_BUDGET_MS
                }
            ) { feeds ->
                // S10: repair the "new episodes" counts here, in the background, instead of at
                // app start.
                runCatching { db.podcastSubscriptions().recalculateAllNewEpisodesCounts() }
                    .onFailure { if (it is CancellationException) throw it }
                runCatching {
                    setProgress(
                        workDataOf(
                            KEY_STAGE to STAGE_FINISHING,
                            KEY_NEW_EPISODES to feeds.newEpisodes,
                            KEY_FAILED_FEEDS to feeds.failedFeeds,
                            KEY_TOTAL_FEEDS to feeds.totalFeeds
                        )
                    )
                }.onFailure { if (it is CancellationException) throw it }
            }

            val error = summary.errorMessage()
            if (error != null) {
                Result.failure(
                    workDataOf(
                        KEY_ERROR to error,
                        KEY_FAILED_FEEDS to summary.failedFeeds,
                        KEY_TOTAL_FEEDS to summary.totalFeeds
                    )
                )
            } else {
                Result.success(
                    workDataOf(
                        KEY_NEW_EPISODES to summary.newEpisodes,
                        KEY_FAILED_FEEDS to summary.failedFeeds,
                        KEY_TOTAL_FEEDS to summary.totalFeeds
                    )
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(workDataOf(KEY_ERROR to RefreshSummary.shortReason(e)))
        }
    }

    companion object {
        /**
         * Unique work name of the manual refresh (pull to refresh).  Observe it with
         * `WorkManager.getWorkInfosForUniqueWorkFlow(INSTANT_UNIQUE_NAME)`:
         *  - RUNNING while feeds are read; [KEY_STAGE] in `progress` says how far
         *    ([STAGE_FEEDS], then [STAGE_FINISHING] once every feed is stored and only extras
         *    such as chapter downloads remain; the UI may stop its spinner there).
         *  - SUCCEEDED: `outputData` has [KEY_NEW_EPISODES], [KEY_FAILED_FEEDS], [KEY_TOTAL_FEEDS]
         *    (some feeds may have failed; the refresh as a whole worked).
         *  - FAILED: `outputData[KEY_ERROR]` is a short, user-facing reason (every feed failed,
         *    or the refresh itself crashed).
         */
        const val INSTANT_UNIQUE_NAME = "PeriodicPodcastUpdateWorkerInstant"

        /** Output (FAILED): short reason to show the user. */
        const val KEY_ERROR = "error"
        /** Output (SUCCEEDED) and progress (finishing): episodes added by this refresh. */
        const val KEY_NEW_EPISODES = "newEpisodes"
        /** Output and progress: feeds that couldn't be read. */
        const val KEY_FAILED_FEEDS = "failedFeeds"
        /** Output and progress: feeds this refresh looked at. */
        const val KEY_TOTAL_FEEDS = "totalFeeds"
        /** Progress: [STAGE_FEEDS] or [STAGE_FINISHING]. */
        const val KEY_STAGE = "stage"
        const val STAGE_FEEDS = "feeds"
        const val STAGE_FINISHING = "finishing"

        /** Tag on the manual refresh (shorter chapter-fetch budget). */
        const val TAG_MANUAL = "PeriodicPodcastUpdateWorkerManual"

        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                INSTANT_UNIQUE_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<PeriodicPodcastUpdateWorker>()
                    .setConstraints(
                        Constraints(requiredNetworkType = NetworkType.CONNECTED)
                    )
                    .addTag(TAG_MANUAL)
                    .build()
            )
        }

        suspend fun enqueueWorker(
            context: Context,
            settingsRepository: SettingsRepository,
            replace: Boolean = false,
            delay: Boolean = false
        ) {
            val intervalMinutes = settingsRepository.behavior.updatePodcastsIntervalMinutes.first()
            val inRoaming = settingsRepository.behavior.updatePodcastsInRoaming.first()

            enqueueWorker(
                context = context,
                repeatIntervalMinutes = intervalMinutes.toLong(),
                inRoaming = inRoaming,
                replace = replace,
                delay = delay
            )
        }

        fun enqueueWorker(
            context: Context,
            repeatIntervalMinutes: Long,
            inRoaming: Boolean,
            replace: Boolean = false,
            delay: Boolean = false
        ) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    uniqueWorkName = "PeriodicPodcastUpdateWorker",
                    existingPeriodicWorkPolicy = when(replace) {
                        true -> ExistingPeriodicWorkPolicy.REPLACE
                        false -> ExistingPeriodicWorkPolicy.KEEP
                    },
                    request = PeriodicWorkRequestBuilder<PeriodicPodcastUpdateWorker>(
                        repeatIntervalMinutes,
                        TimeUnit.MINUTES
                    )
                        .setConstraints(
                            Constraints(
                                requiredNetworkType = when(inRoaming) {
                                    true -> NetworkType.CONNECTED
                                    false -> NetworkType.NOT_ROAMING
                                },
                                requiresBatteryNotLow = true
                            )
                        ).let {
                            if(delay) {
                                it.setInitialDelay(5L, TimeUnit.MINUTES)
                            } else {
                                it
                            }
                        }
                        .build()
                )
        }
    }

}