package app.podiumpodcasts.podium.api.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.podiumpodcasts.podium.api.db.model.ChapterSkipModel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterSkipDao {
    @Query("SELECT * FROM chapterSkip WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<ChapterSkipModel?>

    @Query("SELECT * FROM chapterSkip WHERE episodeId = :episodeId")
    suspend fun getSync(episodeId: String): ChapterSkipModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ChapterSkipModel)

    @Query("DELETE FROM chapterSkip WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)
}
