package app.podiumpodcasts.podium.background.work

import android.content.Context
import app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.FEED_EXTRAS_VERSION
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.api.rss.FetchPodcastClient
import app.podiumpodcasts.podium.api.rss.FetchPodcastClientResult
import app.podiumpodcasts.podium.api.rss.withFeedExtras
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
            val response = fetchPodcastClient.fetchNoCache(oldPodcast.fetchFeedUrl())

            if (response !is FetchPodcastClientResult.Success) {
                val err = (response as? FetchPodcastClientResult.Failure)?.e
                    ?: Exception("Feed refresh failed")
                return Result.failure(err)
            }

            val podcast =
                response.rssChannel.toPodcast(oldPodcast.origin, response.fileSize, oldPodcast, response.extras.language, response.extras)
            db.podcasts().update(podcast)

            val candidateEpisodes = response.rssChannel.items
                .filter { !episodeIds.contains(it.computeEpisodeId(podcast.origin)) }
                .map { item ->
                    item.toPodcastEpisode(podcast = podcast, new = true, extras = response.extras)
                        .withFeedExtras(response.extras.lookup(item))
                }

            val inserted = db.podcastEpisodes()
                .insertAllAndUpdateNewEpisodesCount(
                    podcast.origin, *candidateEpisodes.toTypedArray()
                )

            response.rssChannel.items.forEach { item ->
                val id = item.computeEpisodeId(podcast.origin)
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

            // DB writes only; the chapter URLs worth fetching come back for the end.
            val chapterCandidates = runCatching {
                EpisodeChapterHelper.applyFeedExtrasOnRefresh(
                    db = db,
                    origin = podcast.origin,
                    items = response.rssChannel.items,
                    extras = response.extras,
                    newEpisodeIds = inserted.mapTo(HashSet()) { it.id }
                )
            }.onFailure { if (it is CancellationException) throw it else it.printStackTrace() }
                .getOrDefault(emptyList())

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
            // A no-cache fetch: the extras are current, so the periodic refresh can skip its
            // one-off unconditional fetch for this podcast.
            if (subscription != null && subscription.extrasVersion != FEED_EXTRAS_VERSION) {
                db.podcastSubscriptions().setExtrasVersion(podcast.origin, FEED_EXTRAS_VERSION)
            }
            // Chapters last, without holding up the caller (the podcast screen's refresh).
            EpisodeChapterHelper.launchCandidateChapterFetch(db, chapterCandidates)
            Result.success(inserted.size)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}