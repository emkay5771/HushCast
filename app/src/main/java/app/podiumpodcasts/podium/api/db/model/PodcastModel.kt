package app.podiumpodcasts.podium.api.db.model

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.podiumpodcasts.podium.api.opml.model.OpmlOutline

@Entity(tableName = "podcast")
data class PodcastModel(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo("origin")
    val origin: String,
    @ColumnInfo("link")
    val link: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("description")
    val description: String,
    @ColumnInfo("author")
    val author: String,
    @ColumnInfo("imageUrl")
    val imageUrl: String,
    @ColumnInfo("imageSeedColor")
    var imageSeedColor: Int,
    @ColumnInfo("languageCode")
    val languageCode: String,
    @ColumnInfo("fileSize")
    val fileSize: Long,
    @ColumnInfo("overrideTitle")
    val overrideTitle: String = "",
    @ColumnInfo("skipBeginning")
    val skipBeginning: Int = 0,
    @ColumnInfo("skipEnding")
    val skipEnding: Int = 0,
    @ColumnInfo("feedUrl", defaultValue = "")
    val feedUrl: String = "",
    @ColumnInfo("itunesType", defaultValue = "")
    val itunesType: String = "",
    @ColumnInfo("categoriesJson", defaultValue = "")
    val categoriesJson: String = "",
    @ColumnInfo("explicit", defaultValue = "0")
    val explicit: Boolean = false,
    @ColumnInfo("fundingJson", defaultValue = "")
    val fundingJson: String = "",
    @ColumnInfo("personsJson", defaultValue = "")
    val personsJson: String = "",
    @ColumnInfo("trailerUrl", defaultValue = "")
    val trailerUrl: String = "",
    @ColumnInfo("trailerTitle", defaultValue = "")
    val trailerTitle: String = ""
) {
    fun createMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId("podcast:${origin}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDescription(description)
                    .setArtist(author)
                    .setArtworkUri(Uri.parse(imageUrl))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun fetchTitle(): String {
        return overrideTitle.ifBlank { title }
    }

    fun fetchFeedUrl(): String {
        return feedUrl.ifBlank { origin }
    }

    fun toOpmlOutline(): OpmlOutline {
        return OpmlOutline(
            title = title,
            text = title,
            type = "rss",
            xmlUrl = fetchFeedUrl(),
            htmlUrl = link
        )
    }
}