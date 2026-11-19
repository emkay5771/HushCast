package app.podiumpodcasts.podium.api.db.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcastSubscription",
    foreignKeys = [ForeignKey(
        entity = PodcastModel::class,
        parentColumns = arrayOf("origin"),
        childColumns = arrayOf("origin"),
        onDelete = CASCADE
    )]
)
data class PodcastSubscriptionModel(
    @PrimaryKey
    @ColumnInfo("origin")
    val origin: String,
    @ColumnInfo("enableNotifications")
    val enableNotifications: Boolean,
    @ColumnInfo("enableAutoDownload")
    val enableAutoDownload: Boolean,
    @ColumnInfo("lastUpdate")
    val lastUpdate: Long = 0,
    @ColumnInfo("newEpisodes")
    val newEpisodes: Int = 0,
    @ColumnInfo("cacheETag")
    val cacheETag: String = "",
    @ColumnInfo("cacheLastModified")
    val cacheLastModified: String = "",
    @ColumnInfo("cacheContentLength")
    val cacheContentLength: String = "",
    /**
     * Which version of the feed extras (transcripts, chapters) was last applied from a full,
     * unconditional fetch.  Below [FEED_EXTRAS_VERSION] the next refresh skips the ETag /
     * Last-Modified headers once, so feeds that keep answering 304 still get their extras.
     */
    @ColumnInfo("extrasVersion", defaultValue = "0")
    val extrasVersion: Int = 0
)

/** Bump to make every podcast re-read its feed extras once (e.g. after a parser change). */
const val FEED_EXTRAS_VERSION = 1