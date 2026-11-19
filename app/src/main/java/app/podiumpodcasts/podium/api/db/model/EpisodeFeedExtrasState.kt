package app.podiumpodcasts.podium.api.db.model

import androidx.room.ColumnInfo

/** What a feed refresh needs to know about a stored episode's feed extras. */
data class EpisodeFeedExtrasState(
    @ColumnInfo("id") val id: String,
    @ColumnInfo("transcriptUrl") val transcriptUrl: String,
    @ColumnInfo("transcriptType") val transcriptType: String,
    @ColumnInfo("transcriptAlternatesJson") val transcriptAlternatesJson: String,
    @ColumnInfo("chaptersUrl") val chaptersUrl: String,
    @ColumnInfo("chaptersType") val chaptersType: String,
    @ColumnInfo("chaptersFetchFailures") val chaptersFetchFailures: Int,
    @ColumnInfo("chaptersRetryAfter") val chaptersRetryAfter: Long
)

/** Stored chapter counts for one episode. */
data class EpisodeChapterCounts(
    @ColumnInfo("episodeId") val episodeId: String,
    /** Chapters from the feed (JSON or Podlove). */
    @ColumnInfo("feedCount") val feedCount: Int,
    /** Any publisher chapters (feed or ID3), i.e. everything but generated ones. */
    @ColumnInfo("publisherCount") val publisherCount: Int
)
