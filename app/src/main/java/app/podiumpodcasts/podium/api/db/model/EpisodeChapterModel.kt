package app.podiumpodcasts.podium.api.db.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodeChapter",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEpisodeModel::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["episodeId"])
    ]
)
data class EpisodeChapterModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val episodeId: String,
    val source: String,
    val startMs: Long,
    val endMs: Long? = null,
    val title: String,
    val imageUrl: String? = null,
    val linkUrl: String? = null
) {
    val isPublisher: Boolean
        get() = source != SOURCE_GENERATED

    val needsDaiOffsetMap: Boolean
        get() = source == SOURCE_JSON || source == SOURCE_PODLOVE

    companion object {
        const val SOURCE_ID3 = "id3"
        const val SOURCE_JSON = "json"
        const val SOURCE_PODLOVE = "podlove"
        const val SOURCE_GENERATED = "generated"
    }
}
