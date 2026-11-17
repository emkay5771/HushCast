package app.podiumpodcasts.podium.api.db.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapterSkip",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEpisodeModel::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChapterSkipModel(
    @PrimaryKey val episodeId: String,
    val skippedStartsCsv: String
) {
    fun skippedStartsSet(): Set<Long> =
        skippedStartsCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
}
