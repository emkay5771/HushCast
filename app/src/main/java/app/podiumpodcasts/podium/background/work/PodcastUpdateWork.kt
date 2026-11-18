package app.podiumpodcasts.podium.background.work

import android.content.Context
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.rss.FetchPodcastClient
import app.podiumpodcasts.podium.api.rss.FetchPodcastClientResult
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val HEADER_OVERHEAD = 1024L

class PodcastUpdateWork(
    val context: Context,
    val db: AppDatabase,
    val settingsRepository: SettingsRepository = SettingsRepository(context)
) {

    val fetchPodcastClient = FetchPodcastClient()
    private val imageBitmapMap = ConcurrentHashMap<String, Bitmap>()

    suspend fun doWork() {
        val enableDebugUpdateNotification =
            settingsRepository.debug.enableUpdateNotification.first()

        val subscriptions = db.podcastSubscriptions().allSortedByLastUpdate()
        val semaphore = Semaphore(4)
        val cachedPodcasts = AtomicInteger(0)
        val dataUsage = AtomicLong(0L)

        coroutineScope {
            subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        val origin = subscription.podcast.origin
                        try {
                            val response = fetchPodcastClient.fetch(
                                origin = origin,
                                lastModified = subscription.subscription.cacheLastModified,
                                eTag = subscription.subscription.cacheETag,
                                contentLength = subscription.subscription.cacheContentLength
                            )

                            if (enableDebugUpdateNotification) {
                                DebugUpdateNotification(subscription, response).send(context)
                            }

                            when (response) {
                                is FetchPodcastClientResult.Success -> {
                                    dataUsage.addAndGet(response.fileSize + HEADER_OVERHEAD)

                                    val episodeIds = db.podcastEpisodes().getEpisodeIds(origin).toHashSet()
                                    val podcast = subscription.podcast

                                    val candidateEpisodes = response.rssChannel.items
                                        .filter { !episodeIds.contains(it.computeEpisodeId(origin)) }
                                        .map { it.toPodcastEpisode(podcast = podcast, new = true, extras = response.extras) }

                                    val inserted = db.podcastEpisodes()
                                        .insertAllAndUpdateNewEpisodesCount(
                                            podcast.origin, *candidateEpisodes.toTypedArray()
                                        )

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
                                }

                                is FetchPodcastClientResult.Unchanged -> {
                                    dataUsage.addAndGet(HEADER_OVERHEAD)
                                    cachedPodcasts.incrementAndGet()
                                    db.podcastSubscriptions().logUpdate(origin = origin)
                                }

                                is FetchPodcastClientResult.Failure -> {
                                    dataUsage.addAndGet(HEADER_OVERHEAD)
                                }
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }.awaitAll()
        }

        if (cachedPodcasts.get() >= 2) {
            db.statisticsUpdatePodcastRun().log(dataUsage.get())
        }
    }

    suspend fun loadBitmap(rssChannel: RssChannel): Bitmap? {
        val url = rssChannel.image?.url ?: return null
        imageBitmapMap[url]?.let { return it }

        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(url)
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
}