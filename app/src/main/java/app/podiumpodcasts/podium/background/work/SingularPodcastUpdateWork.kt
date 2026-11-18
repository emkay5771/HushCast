package app.podiumpodcasts.podium.background.work

import android.content.Context
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.api.rss.FetchPodcastClient
import app.podiumpodcasts.podium.api.rss.FetchPodcastClientResult
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.utils.rss.computeEpisodeId
import app.podiumpodcasts.podium.utils.rss.toPodcast
import app.podiumpodcasts.podium.utils.rss.toPodcastEpisode
import java.util.concurrent.CancellationException

class SingularPodcastUpdateWork(
    val context: Context,
    val db: AppDatabase
) {

    private val fetchPodcastClient = FetchPodcastClient()

    suspend fun doWork(
        oldPodcast: PodcastModel
    ): Result<Int> {
        return try {
            val episodeIds = db.podcastEpisodes().getEpisodeIds(oldPodcast.origin).toHashSet()
            val response = fetchPodcastClient.fetchNoCache(oldPodcast.origin)

            if (response !is FetchPodcastClientResult.Success) {
                val err = (response as? FetchPodcastClientResult.Failure)?.e
                    ?: Exception("Feed refresh failed")
                return Result.failure(err)
            }

            val podcast =
                response.rssChannel.toPodcast(oldPodcast.origin, response.fileSize, oldPodcast, response.extras.language)
            db.podcasts().update(podcast)

            val candidateEpisodes = response.rssChannel.items
                .filter { !episodeIds.contains(it.computeEpisodeId(podcast.origin)) }
                .map { it.toPodcastEpisode(podcast = podcast, new = true, extras = response.extras) }

            val inserted = db.podcastEpisodes()
                .insertAllAndUpdateNewEpisodesCount(
                    podcast.origin, *candidateEpisodes.toTypedArray()
                )

            app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.applyFeedExtrasOnRefresh(
                db = db,
                origin = podcast.origin,
                items = response.rssChannel.items,
                extras = response.extras
            )

            val subscription = db.podcastSubscriptions().getSync(podcast.origin)
            if (subscription?.enableAutoDownload == true && inserted.isNotEmpty()) {
                for (episode in inserted) {
                    try {
                        DownloadManager.downloadEpisode(context, db, episode.id)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            db.podcastSubscriptions().logUpdate(origin = podcast.origin)
            Result.success(inserted.size)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}