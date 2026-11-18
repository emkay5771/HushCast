package app.podiumpodcasts.podium.manager

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DatabaseManagerVersionTest {
    @Test
    fun `MAX_AUTOMUTE_DB_VERSION matches latest AutoMuteDatabase exported schema`() {
        val schemaDir = listOf(
            File("core/automute-engine/schemas/app.hushcast.automute.storage.AutoMuteDatabase"),
            File("../core/automute-engine/schemas/app.hushcast.automute.storage.AutoMuteDatabase")
        ).firstOrNull { it.isDirectory }
            ?: error("Could not locate AutoMuteDatabase schemas directory")

        val latestVersion = schemaDir.listFiles()
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
            ?: error("No schema JSON files found in ${schemaDir.absolutePath}")

        assertEquals(latestVersion, MAX_AUTOMUTE_DB_VERSION)
    }
}
