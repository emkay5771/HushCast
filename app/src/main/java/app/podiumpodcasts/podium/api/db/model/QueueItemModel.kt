package app.podiumpodcasts.podium.api.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playbackQueue")
data class QueueItemModel(
    @PrimaryKey val episodeId: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
)
