package app.podiumpodcasts.podium.api.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.podiumpodcasts.podium.api.db.model.QueueItemModel
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM playbackQueue ORDER BY position ASC")
    fun observeQueue(): Flow<List<QueueItemModel>>

    @Query("SELECT * FROM playbackQueue ORDER BY position ASC")
    suspend fun getQueueSync(): List<QueueItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemModel>)

    @Query("DELETE FROM playbackQueue WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: String)

    @Query("DELETE FROM playbackQueue")
    suspend fun clear()

    @Transaction
    suspend fun replaceQueue(episodeIds: List<String>) {
        clear()
        val now = System.currentTimeMillis()
        insertAll(episodeIds.mapIndexed { idx, id ->
            QueueItemModel(episodeId = id, position = idx, addedAt = now)
        })
    }
}
