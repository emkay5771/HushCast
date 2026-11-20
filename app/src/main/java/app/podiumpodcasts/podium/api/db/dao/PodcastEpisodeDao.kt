package app.podiumpodcasts.podium.api.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadModel
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodePlayStateModel
import kotlinx.coroutines.flow.Flow

enum class PodcastEpisodesOrder(
    val value: String
) {
    ASCENDING("ASC"),
    DESCENDING("DESC")
}

enum class PodcastEpisodesOrderBy(
    val value: String
) {
    DATE("e.pubDate"),
    TITLE("e.title"),
    DURATION("e.duration")
}

enum class PodcastEpisodesFilter(
    val condition: String
) {
    PLAYED("COALESCE(p.played, 0) = 1"),
    PAUSED("COALESCE(p.played, 0) = 0 AND COALESCE(p.state, 0) > 0"),
    NEW("e.new = 1"),
    FAVORITE("EXISTS(SELECT 1 FROM listItem WHERE contentId = e.id AND listId = -2)"),
    DOWNLOADED("COALESCE(d.state, 0) = ${PodcastEpisodeDownloadState.DOWNLOADED.value}")
}

@Dao
interface PodcastEpisodeDao {
    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE origin=:origin ORDER BY pubDate DESC")
    fun all(origin: String): Flow<List<PodcastEpisodeBundle>>

    @RawQuery(
        observedEntities = [
            PodcastEpisodeModel::class,
            PodcastEpisodePlayStateModel::class,
            PodcastEpisodeDownloadModel::class
        ]
    )
    fun queryPaged(query: SupportSQLiteQuery): PagingSource<Int, PodcastEpisodeBundle>

    @RawQuery(
        observedEntities = [
            PodcastEpisodeModel::class,
            PodcastEpisodePlayStateModel::class,
            PodcastEpisodeDownloadModel::class,
            app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionModel::class
        ]
    )
    fun queryNewEpisodesPaged(query: SupportSQLiteQuery): PagingSource<Int, PodcastEpisodeBundle>

    fun buildNewEpisodesQuery(
        excludePlayed: Boolean,
        onlyInProgress: Boolean,
        onlyDownloaded: Boolean
    ): SimpleSQLiteQuery {
        val builder = StringBuilder(
            """
            SELECT e.* FROM podcastEpisode AS e
            INNER JOIN podcastSubscription AS s ON e.origin = s.origin
            LEFT JOIN podcastEpisodePlayState AS ps ON e.id = ps.episodeId
            LEFT JOIN podcastEpisodeDownload AS d ON e.id = d.episodeId
            WHERE 1=1
            """.trimIndent()
        )
        if (excludePlayed) {
            builder.append(" AND COALESCE(ps.played, 0) = 0")
        }
        if (onlyInProgress) {
            builder.append(" AND COALESCE(ps.played, 0) = 0 AND COALESCE(ps.state, 0) > 0")
        }
        if (onlyDownloaded) {
            builder.append(" AND COALESCE(d.state, 0) = ${PodcastEpisodeDownloadState.DOWNLOADED.value}")
        }
        builder.append(" ORDER BY e.pubDate DESC")
        return SimpleSQLiteQuery(builder.toString())
    }

    @Query("SELECT EXISTS(SELECT 1 FROM podcastEpisode WHERE origin = :origin AND season IS NOT NULL)")
    fun hasSeasons(origin: String): Flow<Boolean>

    /**
     * The feed's current metadata for an episode already stored.  None of these fields can be
     * edited in the app, so the feed always wins (a corrected season, a guest added later, an
     * episode no longer marked explicit).  Rows already up to date aren't written at all.
     */
    @Query(
        """
        UPDATE podcastEpisode
        SET season = :season,
            episodeNumber = :episodeNumber,
            episodeType = :episodeType,
            explicit = :explicit,
            commentsUrl = :commentsUrl,
            personsJson = :personsJson,
            soundbitesJson = :soundbitesJson
        WHERE id = :id AND (
            season IS NOT :season OR
            episodeNumber IS NOT :episodeNumber OR
            episodeType != :episodeType OR
            explicit != :explicit OR
            commentsUrl != :commentsUrl OR
            personsJson != :personsJson OR
            soundbitesJson != :soundbitesJson
        )
        """
    )
    suspend fun updateMetadataFromFeed(
        id: String,
        season: Int?,
        episodeNumber: Int?,
        episodeType: String,
        explicit: Boolean,
        commentsUrl: String,
        personsJson: String,
        soundbitesJson: String
    )

    fun buildQuery(
        origin: String,
        searchQuery: String?,
        orderBy: PodcastEpisodesOrderBy,
        order: PodcastEpisodesOrder,
        filter: Set<PodcastEpisodesFilter>,
        filterNot: Set<PodcastEpisodesFilter>,
        groupBySeason: Boolean = false
    ): SimpleSQLiteQuery {
        val builder = StringBuilder(
            """
            SELECT e.* FROM podcastEpisode AS e
            LEFT JOIN podcastEpisodePlayState AS p ON e.id = p.episodeId
            LEFT JOIN podcastEpisodeDownload AS d ON e.id = d.episodeId
            WHERE e.origin = ?
        """.trimIndent()
        )

        val bindArgs = mutableListOf<Any>(origin)

        if(!searchQuery.isNullOrBlank()) {
            builder.append(" AND (e.title LIKE ? OR e.description LIKE ?)")

            val wildcard = "%$searchQuery%"
            bindArgs.add(wildcard)
            bindArgs.add(wildcard)
        }

        filter.forEach { builder.append(" AND ${it.condition}") }
        filterNot.forEach { builder.append(" AND NOT( ${it.condition} )") }

        if (groupBySeason) {
            builder.append(
                " ORDER BY CASE WHEN e.season IS NULL THEN 1 ELSE 0 END ASC, e.season ${order.value}, ${orderBy.value} ${order.value}"
            )
        } else {
            builder.append(" ORDER BY ${orderBy.value} ${order.value}")
        }
        return SimpleSQLiteQuery(builder.toString(), bindArgs.toTypedArray())
    }

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE origin=:origin ORDER BY pubDate DESC")
    suspend fun allSync(origin: String): List<PodcastEpisodeBundle>

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE origin=:origin ORDER BY pubDate DESC LIMIT :limit OFFSET :offset")
    suspend fun get(origin: String, limit: Int, offset: Int): List<PodcastEpisodeBundle>

    @Transaction
    @Query(
        """
        SELECT e.* FROM podcastEpisode e
        INNER JOIN podcastSubscription s ON e.origin = s.origin
        LEFT JOIN podcastEpisodePlayState ps ON e.id = ps.episodeId
        WHERE COALESCE(ps.played, 0) = 0
        ORDER BY e.pubDate DESC
        """
    )
    fun allNew(): PagingSource<Int, PodcastEpisodeBundle>

    @Transaction
    @Query(
        """
        SELECT e.* FROM podcastEpisode e
        INNER JOIN podcastSubscription s ON e.origin = s.origin
        LEFT JOIN podcastEpisodePlayState ps ON e.id = ps.episodeId
        WHERE COALESCE(ps.played, 0) = 0
        ORDER BY e.pubDate DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getNew(limit: Int, offset: Int): List<PodcastEpisodeBundle>

    @Query("SELECT id FROM podcastEpisode WHERE origin=:origin")
    suspend fun getEpisodeIds(origin: String): List<String>

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PodcastEpisodeBundle>

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE id=:id")
    fun get(id: String): Flow<PodcastEpisodeBundle>

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE id=:id")
    fun getNullable(id: String): Flow<PodcastEpisodeBundle?>

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE id=:id")
    suspend fun getSync(id: String): PodcastEpisodeBundle

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE id=:id")
    suspend fun getSyncNullable(id: String): PodcastEpisodeBundle?

    @Transaction
    @Query("SELECT * FROM podcastEpisode WHERE origin=:origin AND audioUrl=:audioUrl")
    suspend fun getSyncByOriginAndAudioUrl(origin: String, audioUrl: String): PodcastEpisodeBundle?

    @Transaction
    @Query(
        """
        SELECT * FROM podcastEpisode 
        WHERE title LIKE '%' || :query || '%' 
           OR description LIKE '%' || :query || '%'
        ORDER BY pubDate DESC
    """
    )
    fun search(query: String): PagingSource<Int, PodcastEpisodeBundle>

    @Transaction
    suspend fun newAndUpdateNewEpisodesCount(
        origin: String, episodeId: String
    ) {
        _new(episodeId)
        _updateNewEpisodesCount(origin)
    }

    @Transaction
    suspend fun unnewAndUpdateNewEpisodesCount(
        origin: String, episodeId: String
    ) {
        _unnew(episodeId)
        _updateNewEpisodesCount(origin)
    }

    @Transaction
    suspend fun insertAllAndUpdateNewEpisodesCount(
        origin: String,
        vararg episodes: PodcastEpisodeModel
    ): List<PodcastEpisodeModel> {
        val unique = episodes.distinctBy { it.id }
        if (unique.isEmpty()) return emptyList()
        val rowIds = _insertAll(*unique.toTypedArray())
        val inserted = unique.filterIndexed { index, _ -> rowIds.getOrElse(index) { -1L } != -1L }
        if (inserted.isNotEmpty()) {
            _initPlayStates(inserted.map { PodcastEpisodePlayStateModel(episodeId = it.id) })
            _updateNewEpisodesCount(origin)
        }
        return inserted
    }

    @Update
    suspend fun update(episode: PodcastEpisodeModel)

    @Query("UPDATE podcastEpisode SET duration = :durationSec WHERE id = :id AND (duration <= 0 OR duration != :durationSec)")
    suspend fun updateDuration(id: String, durationSec: Int)

    /**
     * Applies a feed item's transcript/chapters extras.  Blank values keep what's stored.  A new
     * chapters URL clears the chapter-fetch backoff (SQLite evaluates every SET against the old row).
     */
    @Query(
        """
        UPDATE podcastEpisode
        SET transcriptUrl = CASE WHEN :transcriptUrl != '' THEN :transcriptUrl ELSE transcriptUrl END,
            transcriptType = CASE WHEN :transcriptUrl != '' THEN :transcriptType ELSE transcriptType END,
            transcriptAlternatesJson = CASE WHEN :transcriptUrl != '' THEN :transcriptAlternatesJson ELSE transcriptAlternatesJson END,
            chaptersFetchFailures = CASE WHEN :chaptersUrl != '' AND :chaptersUrl != chaptersUrl THEN 0 ELSE chaptersFetchFailures END,
            chaptersRetryAfter = CASE WHEN :chaptersUrl != '' AND :chaptersUrl != chaptersUrl THEN 0 ELSE chaptersRetryAfter END,
            chaptersUrl = CASE WHEN :chaptersUrl != '' THEN :chaptersUrl ELSE chaptersUrl END,
            chaptersType = CASE WHEN :chaptersUrl != '' THEN :chaptersType ELSE chaptersType END
        WHERE id = :id
        """
    )
    suspend fun updateFeedExtras(
        id: String,
        transcriptUrl: String,
        transcriptType: String,
        transcriptAlternatesJson: String,
        chaptersUrl: String,
        chaptersType: String
    )

    @Query(
        """
        SELECT id, transcriptUrl, transcriptType, transcriptAlternatesJson, chaptersUrl, chaptersType,
               chaptersFetchFailures, chaptersRetryAfter
        FROM podcastEpisode WHERE origin = :origin
        """
    )
    suspend fun getFeedExtrasStates(origin: String): List<app.podiumpodcasts.podium.api.db.model.EpisodeFeedExtrasState>

    @Query("UPDATE podcastEpisode SET chaptersFetchFailures = :failures, chaptersRetryAfter = :retryAfter WHERE id = :id")
    suspend fun setChaptersFetchBackoff(id: String, failures: Int, retryAfter: Long)

    @Query("DELETE FROM podcastEpisode WHERE id=:id")
    suspend fun delete(id: String)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun _insertAll(vararg episodes: PodcastEpisodeModel): List<Long>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun _initPlayStates(states: List<PodcastEpisodePlayStateModel>)

    @Query("UPDATE podcastEpisode SET new=1 WHERE id=:id")
    suspend fun _new(id: String)

    @Query("UPDATE podcastEpisode SET new=0 WHERE id=:id")
    suspend fun _unnew(id: String)

    @Query("UPDATE podcastEpisode SET new=0 WHERE origin=:origin")
    suspend fun markAllSeenByOrigin(origin: String)

    @Query("UPDATE podcastEpisode SET new=0")
    suspend fun markAllSeen()

    @Query(
        """
        UPDATE podcastSubscription 
        SET newEpisodes = (
            SELECT COUNT(*) 
            FROM podcastEpisode 
            WHERE podcastEpisode.origin = podcastSubscription.origin 
            AND podcastEpisode.new = 1
        ) WHERE origin=:origin
    """
    )
    suspend fun _updateNewEpisodesCount(origin: String)
}