package app.podiumpodcasts.podium.api.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.podiumpodcasts.podium.manager.MAX_APP_DB_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class AppDatabaseMigration21Test {

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

    private fun schemaDir(): File =
        listOf(
            File("schemas/app.podiumpodcasts.podium.api.db.AppDatabase"),
            File("app/schemas/app.podiumpodcasts.podium.api.db.AppDatabase")
        ).firstOrNull { it.isDirectory } ?: error("AppDatabase schema directory not found")

    private fun fields(version: Int, table: String): Map<String, JsonObject> {
        val root = Json.parseToJsonElement(File(schemaDir(), "$version.json").readText()).jsonObject
        val entity = root["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == table }
        return entity["fields"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["columnName"]!!.jsonPrimitive.content }
    }

    @Test
    fun `migration 20 to 21 is registered and is the last step`() {
        assertEquals(20, MIGRATION_20_21.startVersion)
        assertEquals(21, MIGRATION_20_21.endVersion)
        assertTrue(ALL_MIGRATIONS.contains(MIGRATION_20_21))
        // A contiguous chain 1 -> 21, so every older install can upgrade in place.
        ALL_MIGRATIONS.toList().zipWithNext().forEach { (a, b) -> assertEquals(a.endVersion, b.startVersion) }
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(21, ALL_MIGRATIONS.last().endVersion)
        assertEquals(21, MAX_APP_DB_VERSION)
    }

    @Test
    fun `latest exported schema is version 21`() {
        val latest = schemaDir().listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals(21, latest)
    }

    @Test
    fun `migration 20 to 21 adds exactly the columns the version 21 schema expects`() {
        val executed = mutableListOf<String>()
        MIGRATION_20_21.migrate(recordingDb(executed))
        // Four added columns, then the one-time history clean-up (no schema change).
        assertEquals(5, executed.size)
        assertEquals(HISTORY_DEDUPE_SQL, executed.last())
        executed.removeAt(executed.lastIndex)

        val addColumn = Regex(
            "ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)` (TEXT|INTEGER) NOT NULL DEFAULT ('[^']*'|\\d+)"
        )
        for (sql in executed) {
            val m = addColumn.matchEntire(sql.trim())
            assertNotNull("Unexpected SQL: $sql", m)
            val (table, column, type, default) = m!!.destructured
            val before = fields(20, table)
            val after = fields(21, table)
            assertFalse("$table.$column already existed in v20", before.containsKey(column))
            val field = after[column]
            assertNotNull("$table.$column missing from 21.json", field)
            assertEquals(type, field!!["affinity"]!!.jsonPrimitive.content)
            assertTrue(field["notNull"]!!.jsonPrimitive.boolean)
            assertEquals(default, field["defaultValue"]!!.jsonPrimitive.content)
        }

        // And nothing else changed in those tables.
        for (table in listOf("podcastEpisode", "podcastSubscription")) {
            val added = fields(21, table).keys - fields(20, table).keys
            val migrated = executed.mapNotNull { addColumn.matchEntire(it.trim())?.groupValues }
                .filter { it[1] == table }.map { it[2] }.toSet()
            assertEquals(migrated, added)
        }
    }
}
