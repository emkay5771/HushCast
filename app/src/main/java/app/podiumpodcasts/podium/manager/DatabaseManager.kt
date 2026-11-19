package app.podiumpodcasts.podium.manager

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.work.WorkManager
import app.hushcast.automute.engine.DownloadAnalysisMethod
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.storage.AutoMuteDatabase
import app.podiumpodcasts.podium.api.db.ALL_MIGRATIONS
import app.podiumpodcasts.podium.api.db.AppDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val DATABASE_NAME = "podiumDB"
const val AUTOMUTE_DATABASE_NAME = "hushcast_automute.db"
const val SETTINGS_BACKUP_NAME = "settings.preferences_pb"
const val HUSHCAST_CONFIG_BACKUP_NAME = "hushcast_config.json"
const val DATABASE_EXTENSION = "podiumdb"

internal const val MAX_APP_DB_VERSION = 21
internal const val MAX_AUTOMUTE_DB_VERSION = 4

val DATABASE_BACKUP_FILES = arrayOf(
    DATABASE_NAME,
    "$DATABASE_NAME-shm",
    "$DATABASE_NAME-wal",
    AUTOMUTE_DATABASE_NAME,
    "$AUTOMUTE_DATABASE_NAME-shm",
    "$AUTOMUTE_DATABASE_NAME-wal"
)

@Serializable
data class HushCastBackupConfig(
    val fastModelEnabled: Boolean = false,
    val fastAttentionEnabled: Boolean = false,
    val analyzeOnlyWhileCharging: Boolean = false,
    val desktopWorkerEnabled: Boolean = false,
    val desktopWorkerUrl: String = "",
    val desktopWorkerToken: String = "",
    val desktopWorkerFingerprint: String = "",
    val autoDownloadMethod: String = DownloadAnalysisMethod.AUTO.name,
    val lastPlayingEpisodeId: String? = null
)

class DatabaseManager {
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val backupJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun build(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room
                    .databaseBuilder(
                        context = context.applicationContext,
                        klass = AppDatabase::class.java,
                        name = DATABASE_NAME
                    )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun getDatabaseFile(context: Context): File? {
            return context.getDatabasePath(DATABASE_NAME)
        }

        fun getBackupFile(context: Context): File {
            val backupDir = File(context.cacheDir, "backup")
            backupDir.deleteRecursively()
            backupDir.mkdirs()

            val timestamp = DateFormat.format("yyyy-MM-dd_HHmm", Date()).toString()
            return File(backupDir, "Backup_$timestamp.$DATABASE_EXTENSION")
        }

        fun writeBackupFile(context: Context, file: File): File {
            val appCtx = context.applicationContext
            val tmpDir = File(appCtx.cacheDir, "backup_snapshot").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val appSnap = File(tmpDir, DATABASE_NAME)
                val db = build(appCtx)
                db.openHelper.writableDatabase.execSQL(
                    "VACUUM INTO '${appSnap.absolutePath.replace("'", "''")}'"
                )

                val autoMuteSnap = File(tmpDir, AUTOMUTE_DATABASE_NAME)
                val autoMuteDbFile = appCtx.getDatabasePath(AUTOMUTE_DATABASE_NAME)
                if (autoMuteDbFile.exists()) {
                    val amDb = AutoMuteDatabase.getInstance(appCtx)
                    amDb.openHelper.writableDatabase.execSQL(
                        "VACUUM INTO '${autoMuteSnap.absolutePath.replace("'", "''")}'"
                    )
                }

                val dataStoreFile = File(appCtx.filesDir, "datastore/settings.preferences_pb")
                val playbackPrefs = appCtx.getSharedPreferences("hushcast_playback_state", Context.MODE_PRIVATE)
                val hushCastConfig = HushCastBackupConfig(
                    fastModelEnabled = HushCastPrefs.isFastModelEnabled(appCtx),
                    fastAttentionEnabled = HushCastPrefs.isFastAttentionEnabled(appCtx),
                    analyzeOnlyWhileCharging = HushCastPrefs.isAnalyzeOnlyWhileCharging(appCtx),
                    desktopWorkerEnabled = HushCastPrefs.isDesktopWorkerEnabled(appCtx),
                    desktopWorkerUrl = HushCastPrefs.getDesktopWorkerUrl(appCtx),
                    desktopWorkerToken = "",
                    desktopWorkerFingerprint = HushCastPrefs.getDesktopWorkerFingerprint(appCtx),
                    autoDownloadMethod = HushCastPrefs.getAutoDownloadMethod(appCtx).name,
                    lastPlayingEpisodeId = playbackPrefs.getString("last_playing_episode_id", null)
                )
                val configBytes = backupJson.encodeToString(hushCastConfig).toByteArray(Charsets.UTF_8)

                FileOutputStream(file).use { outputStream ->
                    ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                        listOf(appSnap, autoMuteSnap).forEach { snap ->
                            if (snap.exists()) {
                                zipOut.putNextEntry(ZipEntry(snap.name))
                                snap.inputStream().use { it.copyTo(zipOut) }
                                zipOut.closeEntry()
                            }
                        }
                        if (dataStoreFile.exists()) {
                            zipOut.putNextEntry(ZipEntry(SETTINGS_BACKUP_NAME))
                            dataStoreFile.inputStream().use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                        zipOut.putNextEntry(ZipEntry(HUSHCAST_CONFIG_BACKUP_NAME))
                        zipOut.write(configBytes)
                        zipOut.closeEntry()
                    }
                }
            } finally {
                tmpDir.deleteRecursively()
            }

            return file
        }

        fun shareBackupFile(
            context: Context,
            file: File,
            title: String
        ): Boolean {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    title
                )
            )

            return true
        }

        private fun isRawSqliteBackup(context: Context, uri: Uri): Boolean {
            val header = ByteArray(16)
            val read = context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: 0
            return read == 16 && String(header, Charsets.US_ASCII) == "SQLite format 3\u0000"
        }

        fun isRestoreFileValid(context: Context, uri: Uri): Boolean {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (entry.name == DATABASE_NAME) return true
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }

            return isRawSqliteBackup(context, uri)
        }

        private fun validateSqliteFile(file: File, minVersion: Int, maxVersion: Int) {
            require(file.exists() && file.length() > 16) {
                "Backup database ${file.name} is missing or empty"
            }
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "Backup database ${file.name} failed integrity check"
                    }
                }
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    check(cursor.moveToFirst()) {
                        "Could not read schema version of ${file.name}"
                    }
                    val version = cursor.getInt(0)
                    check(version in minVersion..maxVersion) {
                        "Unsupported ${file.name} schema version $version (expected $minVersion..$maxVersion)"
                    }
                }
            }
        }

        fun restoreFromBackup(context: Context, uri: Uri) {
            val appCtx = context.applicationContext
            val stagingDir = File(appCtx.cacheDir, "restore_staging").apply {
                deleteRecursively()
                mkdirs()
            }
            val stagedDbDir = File(stagingDir, "db").apply { mkdirs() }
            var stagedSettingsFile: File? = null
            var stagedHushCastConfigRaw: String? = null
            var restoredAnyZipEntry = false

            try {
                appCtx.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipInputStream ->
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            restoredAnyZipEntry = true
                            when (entry.name) {
                                in DATABASE_BACKUP_FILES -> {
                                    val destination = File(stagedDbDir, entry.name)
                                    destination.outputStream().use { outputStream ->
                                        zipInputStream.copyTo(outputStream)
                                    }
                                }
                                SETTINGS_BACKUP_NAME -> {
                                    val destination = File(stagingDir, SETTINGS_BACKUP_NAME)
                                    destination.outputStream().use { outputStream ->
                                        zipInputStream.copyTo(outputStream)
                                    }
                                    stagedSettingsFile = destination
                                }
                                HUSHCAST_CONFIG_BACKUP_NAME -> {
                                    stagedHushCastConfigRaw = zipInputStream.readBytes().toString(Charsets.UTF_8)
                                }
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                    }
                }

                if (!restoredAnyZipEntry && isRawSqliteBackup(appCtx, uri)) {
                    appCtx.contentResolver.openInputStream(uri)?.use { input ->
                        File(stagedDbDir, DATABASE_NAME).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                // Validate staged databases BEFORE touching any live files
                validateSqliteFile(
                    File(stagedDbDir, DATABASE_NAME),
                    minVersion = 1,
                    maxVersion = MAX_APP_DB_VERSION
                )
                val stagedAutoMute = File(stagedDbDir, AUTOMUTE_DATABASE_NAME)
                if (stagedAutoMute.exists()) {
                    validateSqliteFile(
                        stagedAutoMute,
                        minVersion = 1,
                        maxVersion = MAX_AUTOMUTE_DB_VERSION
                    )
                }

                // Cancel background workers before closing Room instances so they don't touch closed DBs
                runCatching { WorkManager.getInstance(appCtx).cancelAllWork() }
                closeInstance()
                AutoMuteDatabase.closeInstance()

                val dbDir = getDatabaseFile(appCtx)!!.parentFile!!
                DATABASE_BACKUP_FILES.forEach { name ->
                    File(dbDir, name).delete()
                }
                stagedDbDir.listFiles()?.forEach { staged ->
                    staged.copyTo(File(dbDir, staged.name), overwrite = true)
                }

                stagedSettingsFile?.let { stagedSettings ->
                    val dsDir = File(appCtx.filesDir, "datastore").apply { mkdirs() }
                    stagedSettings.copyTo(File(dsDir, SETTINGS_BACKUP_NAME), overwrite = true)
                }

                stagedHushCastConfigRaw?.let { raw ->
                    runCatching {
                        val cfg = backupJson.decodeFromString<HushCastBackupConfig>(raw)
                        HushCastPrefs.setFastModelEnabled(appCtx, cfg.fastModelEnabled)
                        HushCastPrefs.setFastAttentionEnabled(appCtx, cfg.fastAttentionEnabled)
                        HushCastPrefs.setAnalyzeOnlyWhileCharging(appCtx, cfg.analyzeOnlyWhileCharging)
                        HushCastPrefs.setDesktopWorkerEnabled(appCtx, cfg.desktopWorkerEnabled)
                        HushCastPrefs.setDesktopWorkerUrl(appCtx, cfg.desktopWorkerUrl)
                        if (cfg.desktopWorkerToken.isNotBlank()) {
                            HushCastPrefs.setDesktopWorkerToken(appCtx, cfg.desktopWorkerToken)
                        }
                        HushCastPrefs.setDesktopWorkerFingerprint(appCtx, cfg.desktopWorkerFingerprint)
                        runCatching {
                            DownloadAnalysisMethod.valueOf(cfg.autoDownloadMethod)
                        }.getOrNull()?.let { method ->
                            HushCastPrefs.setAutoDownloadMethod(appCtx, method)
                        }
                        val playbackPrefs = appCtx.getSharedPreferences("hushcast_playback_state", Context.MODE_PRIVATE)
                        if (cfg.lastPlayingEpisodeId != null) {
                            playbackPrefs.edit().putString("last_playing_episode_id", cfg.lastPlayingEpisodeId).apply()
                        } else {
                            playbackPrefs.edit().remove("last_playing_episode_id").apply()
                        }
                    }
                }
            } finally {
                stagingDir.deleteRecursively()
            }
        }
    }
}