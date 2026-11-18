package app.podiumpodcasts.podium.api.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AppDatabaseMigrationTest {
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
    fun `migration 15 to 16 creates playbackQueue table`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(15, MIGRATION_15_16.startVersion)
        assertEquals(16, MIGRATION_15_16.endVersion)

        MIGRATION_15_16.migrate(db)

        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `playbackQueue`") })
    }

    @Test
    fun `migration 16 to 17 creates performance indices and cascade foreign keys`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(16, MIGRATION_16_17.startVersion)
        assertEquals(17, MIGRATION_16_17.endVersion)

        MIGRATION_16_17.migrate(db)

        assertTrue(executed.any { it.contains("index_podcastEpisode_origin_pubDate") })
        assertTrue(executed.any { it.contains("index_podcastEpisode_pubDate") })
        assertTrue(executed.any { it.contains("index_listItem_listId") })
        assertTrue(executed.any { it.contains("index_podcastHistory_timestamp") })
        assertTrue(executed.any { it.contains("index_podcastEpisodePlayState_played_lastUpdate") })
        assertTrue(executed.any { it.contains("podcastEpisodeDownload_new") && it.contains("ON DELETE CASCADE") })
        assertTrue(executed.any { it.contains("ALTER TABLE `podcastEpisodeDownload_new` RENAME TO `podcastEpisodeDownload`") })
    }

    @Test
    fun `migration 18 to 19 adds transcript and chapters columns to podcastEpisode`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(18, MIGRATION_18_19.startVersion)
        assertEquals(19, MIGRATION_18_19.endVersion)

        MIGRATION_18_19.migrate(db)

        assertTrue(executed.any { it.contains("ADD COLUMN `transcriptUrl`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `transcriptType`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `chaptersUrl`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `chaptersType`") })
    }

    @Test
    fun `migration 19 to 20 creates episodeChapter table`() {
        val executed = mutableListOf<String>()
        val db = recordingDb(executed)

        assertEquals(19, MIGRATION_19_20.startVersion)
        assertEquals(20, MIGRATION_19_20.endVersion)

        MIGRATION_19_20.migrate(db)

        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `episodeChapter`") })
        assertTrue(executed.any { it.contains("index_episodeChapter_episodeId") })
    }
}
