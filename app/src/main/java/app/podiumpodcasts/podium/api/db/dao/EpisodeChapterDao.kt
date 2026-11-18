package app.podiumpodcasts.podium.api.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeChapterDao {
    @Query("SELECT * FROM episodeChapter WHERE episodeId = :episodeId ORDER BY startMs ASC")
    fun observeByEpisode(episodeId: String): Flow<List<EpisodeChapterModel>>

    @Query("SELECT * FROM episodeChapter WHERE episodeId = :episodeId ORDER BY startMs ASC")
    suspend fun getByEpisodeSync(episodeId: String): List<EpisodeChapterModel>

    @Query("DELETE FROM episodeChapter WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("DELETE FROM chapterSkip WHERE episodeId = :episodeId")
    suspend fun clearChapterSkips(episodeId: String)

    @Insert
    suspend fun insertAll(chapters: List<EpisodeChapterModel>)

    @Transaction
    suspend fun replaceByEpisode(episodeId: String, chapters: List<EpisodeChapterModel>) {
        val prevSource = getByEpisodeSync(episodeId).firstOrNull()?.source
        val nextSource = chapters.firstOrNull()?.source
        if (prevSource != null && nextSource != prevSource) {
            clearChapterSkips(episodeId)
        }
        deleteByEpisode(episodeId)
        if (chapters.isNotEmpty()) {
            insertAll(chapters)
        }
    }

    @Transaction
    suspend fun upsertPublisherChapters(
        episodeId: String,
        source: String,
        chapters: List<EpisodeChapterModel>
    ) {
        if (chapters.isEmpty()) return
        val existing = getByEpisodeSync(episodeId)
        if (existing.any { it.source == EpisodeChapterModel.SOURCE_ID3 } &&
            source != EpisodeChapterModel.SOURCE_ID3
        ) {
            return
        }
        if ((source == EpisodeChapterModel.SOURCE_JSON || source == EpisodeChapterModel.SOURCE_PODLOVE) &&
            existing.any { it.source == EpisodeChapterModel.SOURCE_JSON || it.source == EpisodeChapterModel.SOURCE_PODLOVE } &&
            existing.size > chapters.size
        ) {
            return
        }
        replaceByEpisode(episodeId, chapters)
    }

    @Transaction
    suspend fun upsertGeneratedChaptersIfNoPublisher(
        episodeId: String,
        chapters: List<EpisodeChapterModel>
    ) {
        if (chapters.isEmpty()) return
        val existing = getByEpisodeSync(episodeId)
        if (existing.any { it.isPublisher }) {
            return
        }
        replaceByEpisode(episodeId, chapters)
    }
}
