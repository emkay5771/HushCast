package app.podiumpodcasts.podium.api.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodePlayStateBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodePlayStateModel
import app.podiumpodcasts.podium.api.db.model.PodcastPlayStateBundle

@Dao
interface PodcastEpisodePlayStateDao {

    @Transaction
    @Query("SELECT * FROM podcastEpisodePlayState WHERE state > 0 OR played != 0")
    suspend fun allSync(): List<PodcastEpisodePlayStateBundle>

    @Query("INSERT INTO podcastEpisodePlayState (episodeId, state, played, lastUpdate) VALUES (:episodeId, 0, 0, 0)")
    suspend fun initState(episodeId: String)

    @Query("UPDATE podcastEpisodePlayState SET state = :state, lastUpdate = :lastUpdate WHERE episodeId=:episodeId")
    suspend fun saveState(
        episodeId: String,
        state: Int,
        lastUpdate: Long = System.currentTimeMillis() / 1000L
    )

    @Query("INSERT OR IGNORE INTO podcastEpisodePlayState (episodeId, state, played, lastUpdate) VALUES (:episodeId, 0, 0, 0)")
    suspend fun initStateIgnore(episodeId: String)

    @Query("UPDATE podcastEpisodePlayState SET played = :played, lastUpdate = :lastUpdate WHERE episodeId=:episodeId")
    suspend fun savePlayed(
        episodeId: String,
        played: Boolean,
        lastUpdate: Long = System.currentTimeMillis() / 1000L
    )

    @Transaction
    suspend fun upsertPlayed(
        episodeId: String,
        played: Boolean,
        lastUpdate: Long = System.currentTimeMillis() / 1000L
    ) {
        initStateIgnore(episodeId)
        savePlayed(episodeId, played, lastUpdate)
    }

    @Query("INSERT OR IGNORE INTO podcastEpisodePlayState (episodeId, state, played, lastUpdate) SELECT id, 0, 1, :lastUpdate FROM podcastEpisode WHERE origin=:origin")
    suspend fun _insertMissingPlayedForOrigin(origin: String, lastUpdate: Long)

    @Query("UPDATE podcastEpisodePlayState SET played = 1, lastUpdate = :lastUpdate WHERE episodeId IN (SELECT id FROM podcastEpisode WHERE origin=:origin)")
    suspend fun _updatePlayedForOrigin(origin: String, lastUpdate: Long)

    @Transaction
    suspend fun markAllPlayedForOrigin(
        origin: String,
        lastUpdate: Long = System.currentTimeMillis() / 1000L
    ) {
        _insertMissingPlayedForOrigin(origin, lastUpdate)
        _updatePlayedForOrigin(origin, lastUpdate)
    }

    @Query("INSERT OR IGNORE INTO podcastEpisodePlayState (episodeId, state, played, lastUpdate) SELECT e.id, 0, 1, :lastUpdate FROM podcastEpisode e INNER JOIN podcastSubscription s ON e.origin = s.origin")
    suspend fun _insertMissingPlayedForSubscribed(lastUpdate: Long)

    @Query("UPDATE podcastEpisodePlayState SET played = 1, lastUpdate = :lastUpdate WHERE episodeId IN (SELECT e.id FROM podcastEpisode e INNER JOIN podcastSubscription s ON e.origin = s.origin)")
    suspend fun _updatePlayedForSubscribed(lastUpdate: Long)

    @Transaction
    suspend fun markAllSubscribedPlayed(
        lastUpdate: Long = System.currentTimeMillis() / 1000L
    ) {
        _insertMissingPlayedForSubscribed(lastUpdate)
        _updatePlayedForSubscribed(lastUpdate)
    }

    @Query("UPDATE podcastEpisodePlayState SET state = :state, played = :played, lastUpdate = :lastUpdate WHERE episodeId=:episodeId")
    suspend fun set(
        episodeId: String,
        state: Int,
        played: Boolean,
        lastUpdate: Long = System.currentTimeMillis() / 1000L
    )

    @Transaction
    @Query("SELECT * FROM podcastEpisodePlayState WHERE played=0 AND state>0 ORDER BY lastUpdate DESC")
    fun allContinuePlaying(): PagingSource<Int, PodcastPlayStateBundle>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM podcastEpisodePlayState, podcastEpisode e WHERE played=0 AND state>0 AND episodeId=e.id ORDER BY pubDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getContinuePlayingSync(limit: Int, offset: Int): List<PodcastPlayStateBundle>

    @Query("SELECT * FROM podcastEpisodePlayState WHERE episodeId=:episodeId")
    suspend fun get(episodeId: String): PodcastEpisodePlayStateModel

}