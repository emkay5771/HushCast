package app.hushcast.automute.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AutoMuteMigrationTest {
    private fun recordingDb(executed: MutableList<String>): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executed.add(args[0] as String)
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
    }

    @Test
    fun `migration 2 to 3 adds adClass and encoderDelayMs columns`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(2, AutoMuteDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AutoMuteDatabase.MIGRATION_2_3.endVersion)

        AutoMuteDatabase.MIGRATION_2_3.migrate(db)

        assertTrue(executed.any { it.contains("ALTER TABLE `ad_segments` ADD COLUMN `adClass`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `episode_word_index` ADD COLUMN `encoderDelayMs`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `episode_analysis_status` ADD COLUMN `encoderDelayMs`") })
    }

    @Test
    fun `migration 3 to 4 adds pipelineVersion, checkpoints, and offset map`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(3, AutoMuteDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AutoMuteDatabase.MIGRATION_3_4.endVersion)

        AutoMuteDatabase.MIGRATION_3_4.migrate(db)

        assertTrue(executed.any { it.contains("ALTER TABLE `episode_analysis_status` ADD COLUMN `pipelineVersion`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `episode_analysis_status` ADD COLUMN `rulesVersion`") })
        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `episode_chunk_checkpoints`") })
        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `episode_offset_map`") })
    }

    @Test
    fun `migration 4 to 5 adds episode_fingerprints and reclaimed_time tables`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(4, AutoMuteDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AutoMuteDatabase.MIGRATION_4_5.endVersion)

        AutoMuteDatabase.MIGRATION_4_5.migrate(db)

        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `episode_fingerprints`") })
        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `reclaimed_time`") })
    }
}
