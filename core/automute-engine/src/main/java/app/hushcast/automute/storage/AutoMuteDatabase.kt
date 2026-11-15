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
import app.hushcast.automute.matcher.MuteRule
import kotlinx.coroutines.flow.Flow
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
    val segments: List<Segment>
)

@Entity(tableName = "episode_word_index")
data class EpisodeWordIndexEntity(
    @PrimaryKey val fileSha256: String,
    val episodeId: String,
    val durationSec: Double,
    val wordCount: Int,
    val compressedBlob: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
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
    val label: String
)

@Entity(tableName = "mute_rules")
data class MuteRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val pattern: String,
    val isRegex: Boolean = false,
    val matchPlurals: Boolean = true,
    val actionMode: String = MuteActionMode.HARD_SILENCE.name,
    val enabled: Boolean = true,
    val category: String = "Custom"
)

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

    @Query("DELETE FROM mute_segments WHERE fileSha256 = :sha256")
    suspend fun deleteMuteSegments(sha256: String)

    @Insert
    suspend fun insertMuteSegments(segments: List<MuteSegmentEntity>)

    @Query("SELECT * FROM mute_segments WHERE fileSha256 = :sha256 ORDER BY startMs ASC")
    suspend fun getMuteSegments(sha256: String): List<MuteSegmentEntity>

    @Query("SELECT * FROM mute_segments WHERE episodeId = :episodeId ORDER BY startMs ASC")
    fun observeMuteSegmentsByEpisode(episodeId: String): Flow<List<MuteSegmentEntity>>

    @Query("UPDATE mute_segments SET enabled = :enabled WHERE id = :segmentId")
    suspend fun setMuteSegmentEnabled(segmentId: Long, enabled: Boolean)

    @Query("DELETE FROM ad_segments WHERE fileSha256 = :sha256")
    suspend fun deleteAdSegments(sha256: String)

    @Insert
    suspend fun insertAdSegments(segments: List<AdSegmentEntity>)

    @Query("SELECT * FROM ad_segments WHERE fileSha256 = :sha256 ORDER BY startMs ASC")
    suspend fun getAdSegments(sha256: String): List<AdSegmentEntity>

    @Query("SELECT * FROM ad_segments WHERE episodeId = :episodeId ORDER BY startMs ASC")
    fun observeAdSegmentsByEpisode(episodeId: String): Flow<List<AdSegmentEntity>>

    @Query("SELECT * FROM mute_rules ORDER BY category ASC, pattern ASC")
    fun observeRules(): Flow<List<MuteRuleEntity>>

    @Query("SELECT * FROM mute_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<MuteRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: MuteRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(rules: List<MuteRuleEntity>)

    @Query("DELETE FROM mute_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("UPDATE mute_rules SET enabled = :enabled WHERE id = :ruleId")
    suspend fun setRuleEnabled(ruleId: Long, enabled: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPodcastAdPolicy(policy: PodcastAdPolicyEntity)

    @Query("SELECT * FROM podcast_ad_policy WHERE podcastOrigin = :origin LIMIT 1")
    suspend fun getPodcastAdPolicy(origin: String): PodcastAdPolicyEntity?

    @Query("SELECT * FROM podcast_ad_policy WHERE podcastOrigin = :origin LIMIT 1")
    fun observePodcastAdPolicy(origin: String): Flow<PodcastAdPolicyEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatus(status: EpisodeAnalysisStatusEntity)

    @Query("SELECT * FROM episode_analysis_status WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getStatus(episodeId: String): EpisodeAnalysisStatusEntity?

    @Query("SELECT * FROM episode_analysis_status WHERE episodeId = :episodeId LIMIT 1")
    fun observeStatus(episodeId: String): Flow<EpisodeAnalysisStatusEntity?>

    @Query("SELECT * FROM episode_analysis_status")
    fun observeAllStatuses(): Flow<List<EpisodeAnalysisStatusEntity>>
}

@Database(
    entities = [
        EpisodeWordIndexEntity::class,
        MuteSegmentEntity::class,
        AdSegmentEntity::class,
        MuteRuleEntity::class,
        PodcastAdPolicyEntity::class,
        EpisodeAnalysisStatusEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AutoMuteDatabase : RoomDatabase() {
    abstract fun dao(): AutoMuteDao

    companion object {
        @Volatile
        private var INSTANCE: AutoMuteDatabase? = null

        fun getInstance(context: Context): AutoMuteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoMuteDatabase::class.java,
                    "hushcast_automute.db"
                ).build().also { INSTANCE = it }
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
    category = category
)

fun MuteSegmentEntity.toDomain() = MuteWindow(
    startMs = startMs,
    endMs = endMs,
    word = word,
    ruleId = ruleId,
    actionMode = runCatching { MuteActionMode.valueOf(actionMode) }.getOrDefault(MuteActionMode.HARD_SILENCE),
    enabled = enabled
)

fun AdSegmentEntity.toDomain() = AdWindow(
    startMs = startMs,
    endMs = endMs,
    confidence = confidence,
    isHighConfidence = isHighConfidence,
    label = label
)
