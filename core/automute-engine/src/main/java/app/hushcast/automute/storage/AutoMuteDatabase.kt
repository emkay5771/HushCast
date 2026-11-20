package app.hushcast.automute.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.BuiltInFilters
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.transcript.ApproxSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class WordIndexBlob(
    val words: List<Word>,
    val segments: List<Segment>,
    /**
     * SRT/VTT cues whose words were spread evenly over the cue, not heard (see CueRealigner).
     * Stored inside the blob, so older blobs simply decode with none.
     */
    val approxSpans: List<ApproxSpan> = emptyList()
)

@Entity(tableName = "episode_word_index")
data class EpisodeWordIndexEntity(
    @PrimaryKey val fileSha256: String,
    val episodeId: String,
    val durationSec: Double,
    val wordCount: Int,
    val compressedBlob: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
    val encoderDelayMs: Int = 0
)

data class WordIndexRef(
    val fileSha256: String,
    val episodeId: String
)

@Entity(
    tableName = "mute_segments",
    indices = [Index("fileSha256"), Index("episodeId")]
)
data class MuteSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileSha256: String,
    val episodeId: String,
    val startMs: Long,
    val endMs: Long,
    val word: String,
    val ruleId: Long,
    val actionMode: String,
    val enabled: Boolean = true
)

@Entity(
    tableName = "ad_segments",
    indices = [Index("fileSha256"), Index("episodeId")]
)
data class AdSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileSha256: String,
    val episodeId: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val isHighConfidence: Boolean,
    val label: String,
    val adClass: String = "sponsor_ad"
)

@Entity(tableName = "mute_rules")
data class MuteRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val pattern: String,
    val isRegex: Boolean = false,
    val matchPlurals: Boolean = true,
    /** A [MuteActionMode] name, or "DEFAULT" to follow the global mute mode (the normal case). */
    val actionMode: String = DEFAULT_RULE_MODE,
    val enabled: Boolean = true,
    val category: String = "Custom"
)

/** Stored rule mode meaning "follow the global mute mode". */
const val DEFAULT_RULE_MODE = "DEFAULT"

@Entity(tableName = "podcast_ad_policy")
data class PodcastAdPolicyEntity(
    @PrimaryKey val podcastOrigin: String,
    val policyMode: String = AdPolicyMode.AUTO_SKIP.name
)

@Entity(tableName = "episode_analysis_status")
data class EpisodeAnalysisStatusEntity(
    @PrimaryKey val episodeId: String,
    val fileSha256: String = "",
    val badge: String = ReadinessBadge.NOT_PROCESSED.name,
    val progressPercent: Int = 0,
    val stageLabel: String = "",
    val errorMessage: String? = null,
    val peakRssMb: Int = 0,
    val analysisDurationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val encoderDelayMs: Int = 0,
    val pipelineVersion: Int = 1,
    val rulesVersion: Int = 0
)

@Entity(
    tableName = "episode_chunk_checkpoints",
    primaryKeys = ["fileSha256", "chunkIndex"]
)
data class EpisodeChunkCheckpointEntity(
    val fileSha256: String,
    val chunkIndex: Int,
    val chunkStartSec: Double,
    val wordsJson: String,
    val segmentsJson: String
)

@Entity(
    tableName = "episode_offset_map",
    indices = [Index("episodeId")]
)
data class EpisodeOffsetMapEntity(
    @PrimaryKey val fileSha256: String,
    val episodeId: String,
    val stretchesJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "episode_insights")
data class EpisodeInsightsEntity(
    @PrimaryKey val episodeId: String,
    val fileSha256: String = "",
    val summary: String? = null,
    val chaptersJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface AutoMuteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWordIndex(entity: EpisodeWordIndexEntity)

    @Query("SELECT * FROM episode_word_index WHERE fileSha256 = :sha256 LIMIT 1")
    suspend fun getWordIndex(sha256: String): EpisodeWordIndexEntity?

    @Query("SELECT * FROM episode_word_index WHERE episodeId = :episodeId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getWordIndexByEpisode(episodeId: String): EpisodeWordIndexEntity?

    @Query("SELECT * FROM episode_word_index")
    suspend fun getAllWordIndices(): List<EpisodeWordIndexEntity>

    @Query("SELECT fileSha256, episodeId FROM episode_word_index")
    suspend fun getAllWordIndexRefs(): List<WordIndexRef>

    @Query("DELETE FROM mute_segments WHERE fileSha256 = :sha256")
    suspend fun deleteMuteSegments(sha256: String)

    @Insert
    suspend fun insertMuteSegments(segments: List<MuteSegmentEntity>)

    @Query("SELECT * FROM mute_segments WHERE fileSha256 = :sha256 ORDER BY startMs ASC")
    suspend fun getMuteSegments(sha256: String): List<MuteSegmentEntity>

    @androidx.room.Transaction
    suspend fun replaceMuteSegments(sha256: String, segments: List<MuteSegmentEntity>) {
        val existingDisabled = getMuteSegments(sha256)
            .filter { !it.enabled }
            .map { it.ruleId to it.startMs }
            .toHashSet()
        deleteMuteSegments(sha256)
        if (segments.isNotEmpty()) {
            val preserved = if (existingDisabled.isEmpty()) {
                segments
            } else {
                segments.map { seg ->
                    if ((seg.ruleId to seg.startMs) in existingDisabled) {
                        seg.copy(enabled = false)
                    } else {
                        seg
                    }
                }
            }
            insertMuteSegments(preserved)
        }
    }

    @Query("SELECT * FROM mute_segments WHERE episodeId = :episodeId ORDER BY startMs ASC")
    fun observeMuteSegmentsByEpisode(episodeId: String): Flow<List<MuteSegmentEntity>>

    @Query("UPDATE mute_segments SET enabled = :enabled WHERE id = :segmentId")
    suspend fun setMuteSegmentEnabled(segmentId: Long, enabled: Boolean)

    @Query("DELETE FROM ad_segments WHERE fileSha256 = :sha256")
    suspend fun deleteAdSegments(sha256: String)

    @Insert
    suspend fun insertAdSegments(segments: List<AdSegmentEntity>)

    @androidx.room.Transaction
    suspend fun replaceAdSegments(sha256: String, segments: List<AdSegmentEntity>) {
        deleteAdSegments(sha256)
        if (segments.isNotEmpty()) {
            insertAdSegments(segments)
        }
    }

    @Query("SELECT * FROM ad_segments WHERE fileSha256 = :sha256 ORDER BY startMs ASC")
    suspend fun getAdSegments(sha256: String): List<AdSegmentEntity>

    @Query("SELECT * FROM ad_segments WHERE episodeId = :episodeId ORDER BY startMs ASC")
    fun observeAdSegmentsByEpisode(episodeId: String): Flow<List<AdSegmentEntity>>

    @Query("SELECT * FROM mute_rules ORDER BY category ASC, pattern ASC")
    fun observeRules(): Flow<List<MuteRuleEntity>>

    @Query("SELECT * FROM mute_rules")
    suspend fun getAllRules(): List<MuteRuleEntity>

    @Query("SELECT * FROM mute_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<MuteRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: MuteRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(rules: List<MuteRuleEntity>)

    @Query("DELETE FROM mute_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("UPDATE mute_rules SET category = :category WHERE id = :ruleId")
    suspend fun setRuleCategory(ruleId: Long, category: String)

    /** Points stored mute segments at another rule (keeps their on/off state through a re-match). */
    @Query("UPDATE mute_segments SET ruleId = :newRuleId WHERE ruleId = :oldRuleId")
    suspend fun remapSegmentRule(oldRuleId: Long, newRuleId: Long)

    @Query("DELETE FROM mute_rules")
    suspend fun deleteAllRules()

    @androidx.room.Transaction
    suspend fun upsertRuleIfNew(rule: MuteRuleEntity): Boolean {
        return upsertRulesIfNew(listOf(rule)) > 0
    }

    @androidx.room.Transaction
    suspend fun upsertRulesIfNew(newRules: List<MuteRuleEntity>): Int {
        val existing = getAllRules()
        val seen = HashSet<Pair<String, Boolean>>(existing.size)
        for (r in existing) {
            val key = r.pattern.trim().lowercase() to r.isRegex
            if (!seen.add(key)) {
                deleteRule(r.id)
            }
        }
        val toInsert = ArrayList<MuteRuleEntity>()
        for (r in newRules) {
            val trimmed = r.pattern.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase() to r.isRegex
            if (seen.add(key)) {
                toInsert.add(r.copy(pattern = trimmed))
            }
        }
        if (toInsert.isNotEmpty()) {
            upsertRules(toInsert)
        }
        return toInsert.size
    }

    @Query("UPDATE mute_rules SET enabled = :enabled WHERE id = :ruleId")
    suspend fun setRuleEnabled(ruleId: Long, enabled: Boolean)

    @Query("UPDATE mute_rules SET actionMode = :actionMode")
    suspend fun updateAllRulesActionMode(actionMode: String)

    @Query("UPDATE mute_segments SET actionMode = :actionMode")
    suspend fun updateAllMuteSegmentsActionMode(actionMode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPodcastAdPolicy(policy: PodcastAdPolicyEntity)

    @Query("SELECT * FROM podcast_ad_policy WHERE podcastOrigin = :origin LIMIT 1")
    suspend fun getPodcastAdPolicy(origin: String): PodcastAdPolicyEntity?

    @Query("SELECT * FROM podcast_ad_policy WHERE podcastOrigin = :origin LIMIT 1")
    fun observePodcastAdPolicy(origin: String): Flow<PodcastAdPolicyEntity?>

    @Query("DELETE FROM podcast_ad_policy WHERE podcastOrigin = :origin")
    suspend fun deletePodcastAdPolicy(origin: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatus(status: EpisodeAnalysisStatusEntity)

    @Query("SELECT * FROM episode_analysis_status WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getStatus(episodeId: String): EpisodeAnalysisStatusEntity?

    @Query("SELECT * FROM episode_analysis_status WHERE episodeId = :episodeId LIMIT 1")
    fun observeStatus(episodeId: String): Flow<EpisodeAnalysisStatusEntity?>

    @Query("SELECT * FROM episode_analysis_status")
    fun observeAllStatuses(): Flow<List<EpisodeAnalysisStatusEntity>>

    @Query("SELECT * FROM episode_analysis_status")
    suspend fun getAllStatuses(): List<EpisodeAnalysisStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsights(insights: EpisodeInsightsEntity)

    @Query("SELECT * FROM episode_insights WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getInsights(episodeId: String): EpisodeInsightsEntity?

    @Query("SELECT * FROM episode_insights WHERE episodeId = :episodeId LIMIT 1")
    fun observeInsights(episodeId: String): Flow<EpisodeInsightsEntity?>

    @Query("DELETE FROM episode_analysis_status WHERE episodeId = :episodeId")
    suspend fun deleteStatusByEpisode(episodeId: String)

    @Query("DELETE FROM episode_word_index WHERE episodeId = :episodeId")
    suspend fun deleteWordIndexByEpisode(episodeId: String)

    @Query("DELETE FROM episode_insights WHERE episodeId = :episodeId")
    suspend fun deleteInsightsByEpisode(episodeId: String)

    @Query("DELETE FROM mute_segments WHERE episodeId = :episodeId")
    suspend fun deleteMuteSegmentsByEpisode(episodeId: String)

    @Query("DELETE FROM ad_segments WHERE episodeId = :episodeId")
    suspend fun deleteAdSegmentsByEpisode(episodeId: String)

    @Query("DELETE FROM mute_segments WHERE episodeId = :episodeId AND fileSha256 != :keepSha256")
    suspend fun deleteMuteSegmentsForOtherHashes(episodeId: String, keepSha256: String)

    @Query("DELETE FROM ad_segments WHERE episodeId = :episodeId AND fileSha256 != :keepSha256")
    suspend fun deleteAdSegmentsForOtherHashes(episodeId: String, keepSha256: String)

    /** Removes the episode's mute segments, ad segments and offset maps for every other file hash. */
    @androidx.room.Transaction
    suspend fun clearAnalysisForOtherHashes(episodeId: String, keepSha256: String) {
        deleteMuteSegmentsForOtherHashes(episodeId, keepSha256)
        deleteAdSegmentsForOtherHashes(episodeId, keepSha256)
        deleteOtherOffsetMaps(episodeId, keepSha256)
    }

    /**
     * A5: writes the new hash's mute segments and removes every other hash's results for the
     * episode in one transaction.  Runs before ad detection, so a failed or stopped ad stage
     * never leaves old-hash results behind.
     */
    @androidx.room.Transaction
    suspend fun replaceMuteSegmentsAndClearOtherHashes(
        episodeId: String,
        sha256: String,
        segments: List<MuteSegmentEntity>
    ) {
        replaceMuteSegments(sha256, segments)
        clearAnalysisForOtherHashes(episodeId, sha256)
    }

    /** A5: the ad segments and the old-hash cleanup in one transaction. */
    @androidx.room.Transaction
    suspend fun replaceAdSegmentsAndClearOtherHashes(
        episodeId: String,
        sha256: String,
        segments: List<AdSegmentEntity>
    ) {
        replaceAdSegments(sha256, segments)
        clearAnalysisForOtherHashes(episodeId, sha256)
    }

    /** Re-aligned words and the mute segments matched on them, together. */
    @androidx.room.Transaction
    suspend fun saveWordIndexAndMuteSegments(
        index: EpisodeWordIndexEntity,
        episodeId: String,
        segments: List<MuteSegmentEntity>
    ) {
        upsertWordIndex(index)
        replaceMuteSegmentsAndClearOtherHashes(episodeId, index.fileSha256, segments)
    }

    @Query("SELECT * FROM episode_chunk_checkpoints WHERE fileSha256 = :sha256 ORDER BY chunkIndex ASC")
    suspend fun getCheckpoints(sha256: String): List<EpisodeChunkCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(entity: EpisodeChunkCheckpointEntity)

    @Query("DELETE FROM episode_chunk_checkpoints WHERE fileSha256 = :sha256")
    suspend fun deleteCheckpoints(sha256: String)

    /**
     * Every checkpoint row of a file: keys "sha:..." (per model and pipeline version) and the
     * bare legacy key.  substr instead of LIKE so nothing in the key acts as a wildcard.
     */
    @Query(
        """
        DELETE FROM episode_chunk_checkpoints
        WHERE fileSha256 = :sha256 OR substr(fileSha256, 1, length(:sha256) + 1) = :sha256 || ':'
        """
    )
    suspend fun deleteAllCheckpointsForSha(sha256: String): Int

    /**
     * Checkpoints left behind by earlier versions, which wrote "sha:model" keys but deleted by
     * bare sha.  A row goes when its file finished (READY) more than [cutoffMs] ago, or when the
     * file has no word index and no status touched since [cutoffMs] (nothing is resuming it).
     * The age comes from the status row: the checkpoint table has no timestamp.
     */
    @Query(
        """
        DELETE FROM episode_chunk_checkpoints
        WHERE (CASE WHEN instr(fileSha256, ':') > 0 THEN substr(fileSha256, 1, instr(fileSha256, ':') - 1) ELSE fileSha256 END)
            IN (SELECT fileSha256 FROM episode_analysis_status WHERE badge = 'READY' AND updatedAt < :cutoffMs)
        OR (
            (CASE WHEN instr(fileSha256, ':') > 0 THEN substr(fileSha256, 1, instr(fileSha256, ':') - 1) ELSE fileSha256 END)
                NOT IN (SELECT fileSha256 FROM episode_word_index)
            AND (CASE WHEN instr(fileSha256, ':') > 0 THEN substr(fileSha256, 1, instr(fileSha256, ':') - 1) ELSE fileSha256 END)
                NOT IN (SELECT fileSha256 FROM episode_analysis_status WHERE updatedAt >= :cutoffMs)
        )
        """
    )
    suspend fun pruneOrphanCheckpoints(cutoffMs: Long): Int

    @Query("SELECT * FROM episode_offset_map WHERE fileSha256 = :sha256 LIMIT 1")
    suspend fun getOffsetMap(sha256: String): EpisodeOffsetMapEntity?

    @Query("SELECT * FROM episode_offset_map WHERE fileSha256 = :sha256 LIMIT 1")
    fun observeOffsetMap(sha256: String): Flow<EpisodeOffsetMapEntity?>

    @Query("SELECT * FROM episode_offset_map WHERE episodeId = :episodeId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getOffsetMapByEpisode(episodeId: String): EpisodeOffsetMapEntity?

    @Query("SELECT * FROM episode_offset_map WHERE episodeId = :episodeId ORDER BY createdAt DESC LIMIT 1")
    fun observeOffsetMapByEpisode(episodeId: String): Flow<EpisodeOffsetMapEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOffsetMap(entity: EpisodeOffsetMapEntity)

    @Query("DELETE FROM episode_offset_map WHERE episodeId = :episodeId")
    suspend fun deleteOffsetMapsByEpisode(episodeId: String)

    @Query("DELETE FROM episode_offset_map WHERE episodeId = :episodeId AND fileSha256 != :keepSha256")
    suspend fun deleteOtherOffsetMaps(episodeId: String, keepSha256: String)

    @androidx.room.Transaction
    suspend fun clearAnalysisForEpisode(episodeId: String) {
        deleteStatusByEpisode(episodeId)
        deleteWordIndexByEpisode(episodeId)
        deleteInsightsByEpisode(episodeId)
        deleteMuteSegmentsByEpisode(episodeId)
        deleteAdSegmentsByEpisode(episodeId)
        deleteOffsetMapsByEpisode(episodeId)
    }

    @Query("DELETE FROM episode_analysis_status WHERE episodeId = :episodeId")
    suspend fun resetEpisodeStatusOnly(episodeId: String)

    @Query(
        """
        DELETE FROM episode_word_index
        WHERE fileSha256 NOT IN (
            SELECT fileSha256 FROM episode_word_index ORDER BY createdAt DESC LIMIT :keepCount
        )
        """
    )
    suspend fun pruneOldWordIndices(keepCount: Int = 500)

    @Query(
        """
        DELETE FROM mute_segments
        WHERE fileSha256 NOT IN (SELECT fileSha256 FROM episode_word_index)
        """
    )
    suspend fun pruneOrphanMuteSegments()

    @Query(
        """
        DELETE FROM ad_segments
        WHERE fileSha256 NOT IN (SELECT fileSha256 FROM episode_word_index)
        """
    )
    suspend fun pruneOrphanAdSegments()

    @androidx.room.Transaction
    suspend fun pruneHashCache(keepCount: Int = 500) {
        pruneOldWordIndices(keepCount)
        pruneOrphanMuteSegments()
        pruneOrphanAdSegments()
    }
}

@Database(
    entities = [
        EpisodeWordIndexEntity::class,
        MuteSegmentEntity::class,
        AdSegmentEntity::class,
        MuteRuleEntity::class,
        PodcastAdPolicyEntity::class,
        EpisodeAnalysisStatusEntity::class,
        EpisodeInsightsEntity::class,
        EpisodeChunkCheckpointEntity::class,
        EpisodeOffsetMapEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AutoMuteDatabase : RoomDatabase() {
    abstract fun dao(): AutoMuteDao

    val statusMapFlow: StateFlow<Map<String, EpisodeAnalysisStatusEntity>> by lazy {
        dao().observeAllStatuses()
            .map { list -> list.associateBy { it.episodeId } }
            .stateIn(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                SharingStarted.WhileSubscribed(5_000L),
                emptyMap()
            )
    }

    companion object {
        @Volatile
        private var INSTANCE: AutoMuteDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episode_insights` (
                        `episodeId` TEXT NOT NULL,
                        `fileSha256` TEXT NOT NULL,
                        `summary` TEXT,
                        `chaptersJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`episodeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ad_segments` ADD COLUMN `adClass` TEXT NOT NULL DEFAULT 'sponsor_ad'")
                db.execSQL("UPDATE `ad_segments` SET `isHighConfidence` = 0")
                db.execSQL("ALTER TABLE `episode_word_index` ADD COLUMN `encoderDelayMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `episode_analysis_status` ADD COLUMN `encoderDelayMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `episode_analysis_status` ADD COLUMN `pipelineVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `episode_analysis_status` ADD COLUMN `rulesVersion` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episode_chunk_checkpoints` (
                        `fileSha256` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `chunkStartSec` REAL NOT NULL,
                        `wordsJson` TEXT NOT NULL,
                        `segmentsJson` TEXT NOT NULL,
                        PRIMARY KEY(`fileSha256`, `chunkIndex`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episode_offset_map` (
                        `fileSha256` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `stretchesJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fileSha256`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_offset_map_episodeId` ON `episode_offset_map` (`episodeId`)")
            }
        }

        fun getInstance(context: Context): AutoMuteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoMuteDatabase::class.java,
                    "hushcast_automute.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}

object BlobCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun compress(blob: WordIndexBlob): ByteArray {
        val raw = json.encodeToString(blob).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    fun decompress(bytes: ByteArray): WordIndexBlob {
        val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        return json.decodeFromString(raw.toString(Charsets.UTF_8))
    }
}

fun MuteRuleEntity.toDomain() = MuteRule(
    id = id,
    pattern = pattern,
    isRegex = isRegex,
    matchPlurals = matchPlurals,
    actionMode = runCatching { MuteActionMode.valueOf(actionMode) }.getOrDefault(MuteActionMode.HARD_SILENCE),
    enabled = enabled,
    category = category,
    useDefaultMode = actionMode.equals("DEFAULT", ignoreCase = true)
)

fun MuteSegmentEntity.toDomain(defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE) = MuteWindow(
    startMs = startMs,
    endMs = endMs,
    word = word,
    ruleId = ruleId,
    actionMode = if (actionMode.equals("DEFAULT", ignoreCase = true)) {
        defaultActionMode
    } else {
        runCatching { MuteActionMode.valueOf(actionMode) }.getOrDefault(defaultActionMode)
    },
    enabled = enabled
)

/**
 * Resolves each segment's mute mode: a rule (or segment) stored as "DEFAULT" follows
 * [defaultActionMode]; otherwise the rule's own mode wins, then the segment's.  This is the one
 * place modes are resolved, so the player sheet, Settings and reloads always agree.
 */
fun resolveMuteWindows(
    segments: List<MuteSegmentEntity>,
    rulesById: Map<Long, MuteRuleEntity>,
    defaultActionMode: MuteActionMode
): List<MuteWindow> = segments.map { seg ->
    val rule = rulesById[seg.ruleId]
    // Built-in filter words always follow the global mode.
    val useDefault = seg.actionMode.equals(DEFAULT_RULE_MODE, ignoreCase = true) ||
        BuiltInFilters.isBuiltInId(seg.ruleId) ||
        (rule != null && rule.actionMode.equals(DEFAULT_RULE_MODE, ignoreCase = true))
    val resolvedMode = when {
        useDefault -> defaultActionMode
        rule != null -> runCatching { MuteActionMode.valueOf(rule.actionMode) }.getOrDefault(defaultActionMode)
        else -> runCatching { MuteActionMode.valueOf(seg.actionMode) }.getOrDefault(defaultActionMode)
    }
    seg.toDomain(defaultActionMode).copy(actionMode = resolvedMode)
}

suspend fun AutoMuteDao.loadResolvedMuteWindows(
    fileSha256: String,
    defaultActionMode: MuteActionMode
): List<MuteWindow> =
    resolveMuteWindows(getMuteSegments(fileSha256), getAllRules().associateBy { it.id }, defaultActionMode)

suspend fun AutoMuteDao.loadResolvedMuteWindowsByEpisode(
    episodeId: String,
    defaultActionMode: MuteActionMode
): List<MuteWindow> {
    val sha = getStatus(episodeId)?.fileSha256?.takeIf { it.isNotBlank() }
    if (sha != null) {
        return loadResolvedMuteWindows(sha, defaultActionMode)
    }
    return resolveMuteWindows(
        observeMuteSegmentsByEpisode(episodeId).first(),
        getAllRules().associateBy { it.id },
        defaultActionMode
    )
}

/**
 * One-time fix for installs from 1.0.30 and earlier: rules were saved with the global mode's
 * name, so changing the mode never reached them.  There is no per-rule mode UI, so every rule
 * is switched to follow the global mode.  Safe to call on every start.
 */
suspend fun AutoMuteDao.migrateRulesToDefaultModeOnce(context: android.content.Context) {
    if (app.hushcast.automute.engine.HushCastPrefs.isRuleModeMigrated(context)) return
    updateAllRulesActionMode(DEFAULT_RULE_MODE)
    app.hushcast.automute.engine.HushCastPrefs.setRuleModeMigrated(context)
}

/**
 * The rules to match one episode with: the user's own words, plus the built-in filter words its
 * settings turn on (the global ones, or its podcast's own profanity level).
 */
suspend fun AutoMuteDao.effectiveRules(context: Context, episodeId: String): List<MuteRule> =
    getEnabledRules().map { it.toDomain() } +
        BuiltInFilters.rulesFor(app.hushcast.automute.engine.HushCastPrefs.effectiveFilterSettings(context, episodeId))

/**
 * One-time move (1.0.45) from presets copied into the word list to the built-in filters.  Nothing
 * that was muted becomes unmuted: the profanity level is the strictest one whose earlier preset
 * words were all in the list and on, Explicit content is on if its whole preset was, and preset
 * words beyond those stay as the user's own words.  Segments are pointed at the built-in ids, so
 * mutes switched off in a transcript stay off.  Returns whether anything moved.  Safe to call on
 * every start.
 */
suspend fun AutoMuteDao.migratePresetsToBuiltInFiltersOnce(context: Context): Boolean {
    val prefs = app.hushcast.automute.engine.HushCastPrefs
    if (prefs.isFilterMigrationDone(context)) return false
    val rows = getAllRules().filter { !it.isRegex }
    val enabled = rows.filter { it.enabled }.map { it.pattern.trim().lowercase() }.toSet()
    val plan = BuiltInFilters.presetMigrationFor(enabled)

    var moved = false
    for (row in rows) {
        val pattern = row.pattern.trim().lowercase()
        val category = when (pattern) {
            in plan.absorbedProfanity -> BuiltInFilters.CATEGORY_PROFANITY
            in plan.absorbedExplicit -> BuiltInFilters.CATEGORY_EXPLICIT
            else -> null
        }
        val builtInId = category?.let { BuiltInFilters.idFor(pattern, it) }
        if (builtInId != null && row.enabled) {
            remapSegmentRule(row.id, builtInId)
            deleteRule(row.id)
            moved = true
        } else if (row.category == BuiltInFilters.CATEGORY_PROFANITY || row.category == BuiltInFilters.CATEGORY_EXPLICIT) {
            // A preset word the filters don't cover at this level: it stays the user's own word.
            setRuleCategory(row.id, "Custom")
        }
    }
    prefs.setFilterSettings(
        context,
        prefs.getFilterSettings(context).copy(profanity = plan.level, explicit = plan.explicit)
    )
    prefs.setFilterMigrationDone(context)
    return moved
}

fun AdSegmentEntity.toDomain() = AdWindow(
    startMs = startMs,
    endMs = endMs,
    confidence = confidence,
    isHighConfidence = isHighConfidence,
    label = label,
    adClass = adClass
)
