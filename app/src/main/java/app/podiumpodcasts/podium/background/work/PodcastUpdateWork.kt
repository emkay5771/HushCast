package app.podiumpodcasts.podium.background.work

import android.content.Context
import android.util.Log
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.chapters.ChapterFetchPolicy
import app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.FEED_EXTRAS_VERSION
import app.podiumpodcasts.podium.api.rss.FetchPodcastClient
import app.podiumpodcasts.podium.api.rss.FetchPodcastClientResult
import app.podiumpodcasts.podium.api.rss.withFeedExtras
import app.podiumpodcasts.podium.background.notification.DebugUpdateNotification
import app.podiumpodcasts.podium.background.notification.NewPodcastEpisodeNotification
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.utils.rss.computeEpisodeId
import app.podiumpodcasts.podium.utils.rss.toPodcastEpisode
import coil3.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prof18.rssparser.model.RssChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

const val HEADER_OVERHEAD = 1024L

class PodcastUpdateWork(
    val context: Context,
    val db: AppDatabase,
    val settingsRepository: SettingsRepository = SettingsRepository(context)
) {

    val fetchPodcastClient = FetchPodcastClient()
    private val imageBitmapMap = ConcurrentHashMap<String, Bitmap>()

    /**
     * Refreshes every subscription, then (after notifications and auto-downloads) queues stale
     * re-runs and fetches new chapter URLs.
     *
     * @param chapterBudgetMs total time the chapter fetches may take at the end.
     * @param onFeedsDone called once every feed is read and stored, before the slower extras.
     */
    suspend fun doWork(
        chapterBudgetMs: Long = ChapterFetchPolicy.PERIODIC_BUDGET_MS,
        onFeedsDone: suspend (RefreshSummary) -> Unit = {}
    ): RefreshSummary {
        val enableDebugUpdateNotification =
            settingsRepository.debug.enableUpdateNotification.first()

        val subscriptions = db.podcastSubscriptions().allSortedByLastUpdate()
        val semaphore = Semaphore(4)
        val cachedPodcasts = AtomicInteger(0)
        val updatedPodcasts = AtomicInteger(0)
        val failedPodcasts = AtomicInteger(0)
        val newEpisodes = AtomicInteger(0)
        val firstError = AtomicReference<Throwable?>(null)
        val dataUsage = AtomicLong(0L)
        val chapterCandidates = ConcurrentLinkedQueue<EpisodeChapterHelper.ChapterFetchCandidate>()

        coroutineScope {
            subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        val origin = subscription.podcast.origin
                        try {
                            // Feeds that keep answering 304 never hand over their extras.  Once per
                            // podcast (per extras version) skip the conditional headers.
                            val fullFetch = subscription.subscription.extrasVersion < FEED_EXTRAS_VERSION
                            val response = fetchPodcastClient.fetch(
                                origin = subscription.podcast.fetchFeedUrl(),
                                lastModified = if (fullFetch) "" else subscription.subscription.cacheLastModified,
                                eTag = if (fullFetch) "" else subscription.subscription.cacheETag,
                                contentLength = subscription.subscription.cacheContentLength
                            )

                            if (enableDebugUpdateNotification) {
                                DebugUpdateNotification(subscription, response).send(context)
                            }

                            when (response) {
                                is FetchPodcastClientResult.Success -> {
                                    dataUsage.addAndGet(response.fileSize + HEADER_OVERHEAD)
                                    updatedPodcasts.incrementAndGet()

                                    val episodeIds = db.podcastEpisodes().getEpisodeIds(origin).toHashSet()
                                    val podcast = response.rssChannel.toPodcast(
                                        origin = origin,
                                        fileSize = response.fileSize,
                                        oldPodcast = subscription.podcast,
                                        languageCode = response.extras.language,
                                        extras = response.extras
                                    )
                                    db.podcasts().update(podcast)

                                    val candidateEpisodes = response.rssChannel.items
                                        .filter { !episodeIds.contains(it.computeEpisodeId(origin)) }
                                        .map { item ->
                                            item.toPodcastEpisode(podcast = podcast, new = true, extras = response.extras)
                                                .withFeedExtras(response.extras.lookup(item))
                                        }

                                    val inserted = db.podcastEpisodes()
                                        .insertAllAndUpdateNewEpisodesCount(
                                            podcast.origin, *candidateEpisodes.toTypedArray()
                                        )
                                    newEpisodes.addAndGet(inserted.size)

                                    response.rssChannel.items.forEach { item ->
                                        val id = item.computeEpisodeId(origin)
                                        if (episodeIds.contains(id)) {
                                            val mapped = item.toPodcastEpisode(podcast = podcast, new = false, extras = response.extras)
                                            if (mapped.season != null || mapped.episodeNumber != null ||
                                                mapped.episodeType.isNotEmpty() || mapped.explicit ||
                                                mapped.commentsUrl.isNotEmpty() || mapped.personsJson.isNotEmpty() ||
                                                mapped.soundbitesJson.isNotEmpty()
                                            ) {
                                                db.podcastEpisodes().updateMetadataFromFeed(
                                                    id = id,
                                                    season = mapped.season,
                                                    episodeNumber = mapped.episodeNumber,
                                                    episodeType = mapped.episodeType,
                                                    explicit = mapped.explicit,
                                                    commentsUrl = mapped.commentsUrl,
                                                    personsJson = mapped.personsJson,
                                                    soundbitesJson = mapped.soundbitesJson
                                                )
                                            }
                                        }
                                    }

                                    // DB writes only (one transaction); the chapter URLs to fetch are
                                    // collected and fetched at the very end.
                                    runCatching {
                                        EpisodeChapterHelper.applyFeedExtrasOnRefresh(
                                            db = db,
                                            origin = origin,
                                            items = response.rssChannel.items,
                                            extras = response.extras,
                                            newEpisodeIds = inserted.mapTo(HashSet()) { it.id }
                                        )
                                    }.onSuccess { chapterCandidates.addAll(it) }
                                        .onFailure { if (it is CancellationException) throw it else it.printStackTrace() }

                                    if (subscription.subscription.enableNotifications && inserted.isNotEmpty()) {
                                        val bmp = loadBitmap(response.rssChannel)
                                        for (episode in inserted) {
                                            NewPodcastEpisodeNotification(
                                                podcastTitle = podcast.fetchTitle(),
                                                episode = episode,
                                                imageBitmap = bmp
                                            ).send(context)
                                        }
                                    }

                                    if (subscription.subscription.enableAutoDownload && inserted.isNotEmpty()) {
                                        for (episode in inserted) {
                                            try {
                                                DownloadManager.downloadEpisode(
                                                    context = context,
                                                    db = db,
                                                    episodeId = episode.id,
                                                    auto = true
                                                )
                                            } catch (c: CancellationException) {
                                                throw c
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    }

                                    db.podcastSubscriptions().logUpdate(origin = origin)
                                    db.podcastSubscriptions().storeCacheValues(
                                        origin = origin,
                                        lastModified = response.lastModified,
                                        eTag = response.eTag,
                                        contentLength = response.contentLength
                                    )
                                    if (subscription.subscription.extrasVersion != FEED_EXTRAS_VERSION) {
                                        db.podcastSubscriptions().setExtrasVersion(origin, FEED_EXTRAS_VERSION)
                                    }
                                }

                                is FetchPodcastClientResult.Unchanged -> {
                                    dataUsage.addAndGet(HEADER_OVERHEAD)
                                    cachedPodcasts.incrementAndGet()
                                    db.podcastSubscriptions().logUpdate(origin = origin)
                                }

                                is FetchPodcastClientResult.Failure -> {
                                    dataUsage.addAndGet(HEADER_OVERHEAD)
                                    failedPodcasts.incrementAndGet()
                                    firstError.compareAndSet(null, response.e)
                                }
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            e.printStackTrace()
                            failedPodcasts.incrementAndGet()
                            firstError.compareAndSet(null, e)
                        }
                    }
                }
            }.awaitAll()
        }

        if (cachedPodcasts.get() >= 2) {
            db.statisticsUpdatePodcastRun().log(dataUsage.get())
        }

        val summary = RefreshSummary(
            totalFeeds = subscriptions.size,
            updatedFeeds = updatedPodcasts.get(),
            unchangedFeeds = cachedPodcasts.get(),
            failedFeeds = failedPodcasts.get(),
            newEpisodes = newEpisodes.get(),
            firstError = firstError.get()
        )
        runCatching { onFeedsDone(summary) }.onFailure { if (it is CancellationException) throw it }

        runCatching { queueStalePipelineReruns() }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Stale re-runs failed", it) }

        // Last: chapter URLs of new (or changed) episodes, a few at a time, within a time budget.
        val candidates = chapterCandidates.toList()
        if (candidates.isNotEmpty()) {
            val stored = runCatching {
                EpisodeChapterHelper.fetchCandidateChapters(db, candidates, chapterBudgetMs)
            }.onFailure { if (it is CancellationException) throw it }.getOrDefault(0)
            Log.i(TAG, "Chapter fetch: ${candidates.size} candidates, $stored stored")
        }
        return summary
    }

    private suspend fun queueStalePipelineReruns() {
        val workerActive = app.hushcast.automute.engine.HushCastPrefs.isWorkerActive(context)
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val isCharging = bm?.isCharging == true
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val isUnmeteredWifi = caps != null &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)

        if (!app.hushcast.automute.worker.EpisodeAnalysisWorker.isRerunEligible(
                isCharging = isCharging,
                isUnmeteredWifi = isUnmeteredWifi,
                isWorkerConfigured = workerActive
            )
        ) {
            return
        }

        val autoMuteDb = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
        val staleStatuses = app.hushcast.automute.worker.EpisodeAnalysisWorker.findStaleEpisodesForRerun(
            autoMuteDb.dao().getAllStatuses()
        )
        if (staleStatuses.isEmpty()) return

        // 3 per refresh; up to 10 while charging on unmetered Wi-Fi (a version bump catches up faster).
        val cap = RerunPlanner.capFor(isCharging = isCharging, isUnmeteredWifi = isUnmeteredWifi)
        val workManager = androidx.work.WorkManager.getInstance(context)
        val bundles = HashMap<String, app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle>()

        val picked = RerunPlanner.pick(staleStatuses, cap) { status ->
            val epBundle = db.podcastEpisodes().getSyncNullable(status.episodeId) ?: return@pick false
            val localFile = epBundle.episode.craftDownloadFile(context)
            if (!localFile.exists() || localFile.length() <= 0L) return@pick false
            if (isRerunPending(workManager, status.episodeId)) return@pick false
            bundles[status.episodeId] = epBundle
            true
        }

        for (status in picked) {
            val epBundle = bundles[status.episodeId] ?: continue
            val localFile = epBundle.episode.craftDownloadFile(context)
            if (workerActive) {
                // Re-process on the worker, never on the phone's battery or mobile data.
                DownloadManager.downloadEpisode(
                    context = context,
                    db = db,
                    episodeId = status.episodeId,
                    auto = true,
                    downloadMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP,
                    forceReprocess = true
                )
            } else {
                // The publisher's transcript (with fall-through), so the re-run doesn't need a
                // full Whisper pass.
                val feedTranscript = app.podiumpodcasts.podium.api.download.FeedTranscriptFetcher
                    .fetchBestForAnalysis(epBundle.episode)
                app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueBackgroundRerun(
                    context = context,
                    episodeId = status.episodeId,
                    audioPath = localFile.absolutePath,
                    knownSha256 = status.fileSha256,
                    actionMode = app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode,
                    episodeTitle = epBundle.episode.title,
                    feedTranscriptRaw = feedTranscript?.raw,
                    feedTranscriptMime = feedTranscript?.mime ?: epBundle.episode.transcriptType
                )
            }
        }
        if (picked.isNotEmpty()) {
            Log.i(TAG, "Queued ${picked.size} stale re-run(s) (cap=$cap, worker=$workerActive, of ${staleStatuses.size} stale)")
        }
    }

    /** True while the episode already has a download/offload or an on-device analysis queued or running. */
    private suspend fun isRerunPending(workManager: androidx.work.WorkManager, episodeId: String): Boolean {
        fun List<androidx.work.WorkInfo>.anyUnfinished() = any { !it.state.isFinished }
        return runCatching {
            workManager.getWorkInfosForUniqueWorkFlow(
                app.podiumpodcasts.podium.background.worker.PodcastEpisodeDownloadWorker.generateUniqueWorkName(episodeId)
            ).first().anyUnfinished() ||
                workManager.getWorkInfosByTagFlow("episode:$episodeId").first().anyUnfinished()
        }.getOrDefault(false)
    }

    suspend fun loadBitmap(rssChannel: RssChannel): Bitmap? {
        val url = rssChannel.image?.url ?: return null
        imageBitmapMap[url]?.let { return it }

        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(256)
            .allowHardware(false)
            .build()

        val result = loader.execute(request)
        if (result is ErrorResult) {
            result.throwable.printStackTrace()
            return null
        } else if (result is SuccessResult) {
            val bitmap = result.image.toBitmap()
            imageBitmapMap[url] = bitmap
            return bitmap
        }

        return null
    }

    private companion object {
        const val TAG = "PodcastUpdateWork"
    }
}
