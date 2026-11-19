package app.podiumpodcasts.podium.api.rss

import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel

/**
 * Fills in what `RssItem.toPodcastEpisode` doesn't copy from the feed extras (the transcript
 * alternates), so new episodes are stored complete.
 */
fun PodcastEpisodeModel.withFeedExtras(extra: RssEpisodeExtras?): PodcastEpisodeModel {
    if (extra == null || transcriptAlternatesJson.isNotEmpty()) return this
    val alternates = extra.transcriptAlternatesJson
    return if (alternates.isEmpty() || transcriptUrl != extra.transcriptUrl) this
    else copy(transcriptAlternatesJson = alternates)
}
