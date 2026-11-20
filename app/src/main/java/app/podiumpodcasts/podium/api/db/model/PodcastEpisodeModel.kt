package app.podiumpodcasts.podium.api.db.model

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.legacy.MediaDescriptionCompat
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.utils.sha256
import java.io.File

enum class MediaMetadataExtra {
    ORIGIN,
    EPISODE_ID,
    AUDIO_URL,
    IMAGE_SEED_COLOR,
    RESUME_AT,
    IS_DOWNLOAD,
    SKIP_BEGINNING,
    SKIP_ENDING
}

@Entity(
    tableName = "podcastEpisode",
    foreignKeys = [ForeignKey(
        entity = PodcastModel::class,
        parentColumns = arrayOf("origin"),
        childColumns = arrayOf("origin"),
        onDelete = CASCADE
    )],
    indices = [
        Index(value = ["origin", "pubDate"]),
        Index(value = ["pubDate"])
    ]
)
data class PodcastEpisodeModel(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("guid")
    val guid: String,
    @ColumnInfo("origin")
    val origin: String,
    @ColumnInfo("link")
    val link: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("description")
    val description: String,
    @ColumnInfo("imageUrl")
    var imageUrl: String?,
    @ColumnInfo("author")
    val author: String,
    @ColumnInfo("pubDate")
    val pubDate: Long,
    @ColumnInfo("duration")
    val duration: Int,
    @ColumnInfo("audioUrl")
    val audioUrl: String,
    @ColumnInfo("podcastTitle")
    val podcastTitle: String,
    @ColumnInfo("imageSeedColor")
    var imageSeedColor: Int,
    @ColumnInfo("new")
    val new: Boolean = false,
    @ColumnInfo("transcriptUrl", defaultValue = "")
    val transcriptUrl: String = "",
    @ColumnInfo("transcriptType", defaultValue = "")
    val transcriptType: String = "",
    @ColumnInfo("chaptersUrl", defaultValue = "")
    val chaptersUrl: String = "",
    @ColumnInfo("chaptersType", defaultValue = "")
    val chaptersType: String = "",
    /** Every other feed transcript, best first: a JSON array of `{url, type}` (T5). */
    @ColumnInfo("transcriptAlternatesJson", defaultValue = "")
    val transcriptAlternatesJson: String = "",
    /** Failed or empty chapter-URL fetches during feed refresh, for backoff. */
    @ColumnInfo("chaptersFetchFailures", defaultValue = "0")
    val chaptersFetchFailures: Int = 0,
    /** Epoch ms before which a refresh doesn't retry the chapters URL. */
    @ColumnInfo("chaptersRetryAfter", defaultValue = "0")
    val chaptersRetryAfter: Long = 0L,
    @ColumnInfo("season")
    val season: Int? = null,
    @ColumnInfo("episodeNumber")
    val episodeNumber: Int? = null,
    @ColumnInfo("episodeType", defaultValue = "")
    val episodeType: String = "",
    @ColumnInfo("explicit", defaultValue = "0")
    val explicit: Boolean = false,
    @ColumnInfo("commentsUrl", defaultValue = "")
    val commentsUrl: String = "",
    @ColumnInfo("personsJson", defaultValue = "")
    val personsJson: String = "",
    @ColumnInfo("soundbitesJson", defaultValue = "")
    val soundbitesJson: String = ""
) {
    @android.annotation.SuppressLint("RestrictedApi")
    fun createMediaItem(
        context: Context,
        playState: PodcastEpisodePlayStateModel? = null
    ): MediaItem {
        val downloadFile = craftDownloadFile(context, createDir = false)
        val isDownload = downloadFile.exists()

        return MediaItem.Builder()
            .setMediaId("episode:$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDescription(description.take(512))
                    .setArtist(podcastTitle)
                    .setSubtitle(podcastTitle)
                    .setDisplayTitle(title)
                    .setArtworkUri((imageUrl ?: "").toUri())
                    .setExtras(
                        Bundle().apply {
                            putString(MediaMetadataExtra.ORIGIN.name, origin)
                            putString(MediaMetadataExtra.EPISODE_ID.name, id)
                            putString(MediaMetadataExtra.AUDIO_URL.name, audioUrl)
                            putInt(MediaMetadataExtra.IMAGE_SEED_COLOR.name, imageSeedColor)
                            putBoolean(MediaMetadataExtra.IS_DOWNLOAD.name, isDownload)
                            if(playState != null) putLong(
                                MediaMetadataExtra.RESUME_AT.name,
                                playState.state * 1000L
                            )

                            putLong(
                                MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS, when(isDownload) {
                                    true -> MediaDescriptionCompat.STATUS_DOWNLOADED
                                    false -> MediaDescriptionCompat.STATUS_NOT_DOWNLOADED
                                }
                            )
                        }
                    )
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .setUri(
                when(isDownload) {
                    true -> Uri.fromFile(downloadFile)
                    false -> Uri.parse(audioUrl)
                }
            ).build()
    }

    fun craftDownloadFile(
        context: Context,
        createDir: Boolean = true
    ): File {
        val podcastDownloadsDir =
            File(DownloadManager.getDownloadsDirectory(context), origin.sha256())
        if (createDir) podcastDownloadsDir.mkdirs()
        return File(podcastDownloadsDir, audioUrl.sha256())
    }
}

/**
 * The feed transcripts to try, in order: the primary one, then the alternates (T5).
 * Each pair is (url, type); the type can be blank.
 */
fun PodcastEpisodeModel.transcriptCandidates(): List<Pair<String, String>> =
    app.podiumpodcasts.podium.api.rss.TranscriptRanking.candidates(
        primaryUrl = transcriptUrl,
        primaryType = transcriptType,
        alternatesJson = transcriptAlternatesJson
    )
