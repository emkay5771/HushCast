package app.podiumpodcasts.podium.background.work

import androidx.room.withTransaction
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.api.rss.RssPodcastExtras
import app.podiumpodcasts.podium.utils.rss.computeEpisodeId
import app.podiumpodcasts.podium.utils.rss.toPodcastEpisode
import com.prof18.rssparser.model.RssItem

/**
 * Gives the episodes already stored the feed's current season, episode number, type, explicit
 * flag, comments link, people and soundbites (new episodes get them when they're inserted).
 * One transaction; rows already up to date aren't written.
 */
internal suspend fun AppDatabase.updateKnownEpisodeMetadata(
    podcast: PodcastModel,
    items: List<RssItem>,
    extras: RssPodcastExtras,
    knownEpisodeIds: Set<String>
) {
    if (knownEpisodeIds.isEmpty()) return
    withTransaction {
        for (item in items) {
            val id = item.computeEpisodeId(podcast.origin)
            if (id !in knownEpisodeIds) continue
            val mapped = item.toPodcastEpisode(podcast = podcast, new = false, extras = extras)
            podcastEpisodes().updateMetadataFromFeed(
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

/** The ETag / Last-Modified / Content-Length to keep after a successful fetch. */
internal data class FeedCacheValues(
    val lastModified: String,
    val eTag: String,
    val contentLength: String
)

/**
 * After a fetch that found `itunes:new-feed-url`, the response's validators belong to the old
 * URL.  Sent to the new host they can be read as "not modified" (a date older than the new
 * feed), and the new feed is never read; so a move starts the new URL without them.
 */
internal fun feedCacheValuesAfterFetch(
    movedFeed: Boolean,
    lastModified: String,
    eTag: String,
    contentLength: String
): FeedCacheValues =
    if (movedFeed) FeedCacheValues("", "", "") else FeedCacheValues(lastModified, eTag, contentLength)
