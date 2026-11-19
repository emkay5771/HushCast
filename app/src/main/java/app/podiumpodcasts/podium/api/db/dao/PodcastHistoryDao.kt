package app.podiumpodcasts.podium.api.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.podiumpodcasts.podium.api.db.model.PodcastHistoryBundle
import app.podiumpodcasts.podium.api.db.model.PodcastHistoryModel
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastHistoryDao {

    @Transaction
    @Query("SELECT * FROM podcastHistory ORDER BY timestamp DESC")
    fun all(): PagingSource<Int, PodcastHistoryBundle>

    @Transaction
    @Query("SELECT * FROM podcastHistory WHERE timestamp < :from AND timestamp > :to ORDER BY timestamp DESC")
    fun allIn(from: Long, to: Long): PagingSource<Int, PodcastHistoryBundle>

    @Transaction
    @Query("SELECT * FROM podcastHistory WHERE timestamp > :after ORDER BY timestamp DESC")
    fun allAfter(after: Long): PagingSource<Int, PodcastHistoryBundle>

    @Transaction
    @Query("SELECT * FROM podcastHistory WHERE timestamp < :before ORDER BY timestamp DESC")
    fun allBefore(before: Long): PagingSource<Int, PodcastHistoryBundle>

    @Transaction
    @Query("SELECT * FROM podcastHistory ORDER BY timestamp DESC")
    fun get(): Flow<List<PodcastHistoryBundle>>

    @Transaction
    @Query("SELECT * FROM podcastHistory ORDER BY timestamp DESC LIMIT 1")
    fun getLast(): Flow<PodcastHistoryBundle?>

    /**
     * Bumps one history entry.  By id, not episode: an episode played again days later has an
     * older entry too, and updating by episode moved every one of them to "now", so History
     * showed the episode several times.
     */
    @Query("UPDATE podcastHistory SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Int, timestamp: Long)

    @Query("INSERT INTO podcastHistory (origin,episodeId,timestamp) VALUES (:origin,:episodeId,:timestamp)")
    suspend fun insert(
        origin: String,
        episodeId: String,
        timestamp: Long = System.currentTimeMillis()
    )

    @Insert
    suspend fun insert(item: PodcastHistoryModel)

    @Delete
    suspend fun delete(item: PodcastHistoryModel)

}