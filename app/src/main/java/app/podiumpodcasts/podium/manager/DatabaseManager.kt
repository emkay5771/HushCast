package app.podiumpodcasts.podium.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.content.FileProvider
import androidx.room.Room
import app.hushcast.automute.storage.AutoMuteDatabase
import app.podiumpodcasts.podium.api.db.ALL_MIGRATIONS
import app.podiumpodcasts.podium.api.db.AppDatabase
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
const val DATABASE_EXTENSION = "podiumdb"

val DATABASE_BACKUP_FILES = arrayOf(
    DATABASE_NAME,
    "$DATABASE_NAME-shm",
    "$DATABASE_NAME-wal",
    AUTOMUTE_DATABASE_NAME,
    "$AUTOMUTE_DATABASE_NAME-shm",
    "$AUTOMUTE_DATABASE_NAME-wal"
)

class DatabaseManager {
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

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

            return false
        }

        fun restoreFromBackup(context: Context, uri: Uri) {
            val appCtx = context.applicationContext
            // F10: Close all open Room instances and clear WAL/SHM before replacing database files
            closeInstance()
            AutoMuteDatabase.closeInstance()

            val dbDir = getDatabaseFile(appCtx)!!.parentFile!!
            listOf(
                DATABASE_NAME,
                "$DATABASE_NAME-shm",
                "$DATABASE_NAME-wal",
                AUTOMUTE_DATABASE_NAME,
                "$AUTOMUTE_DATABASE_NAME-shm",
                "$AUTOMUTE_DATABASE_NAME-wal"
            ).forEach { name ->
                File(dbDir, name).delete()
            }

            val dsDir = File(appCtx.filesDir, "datastore").apply { mkdirs() }

            appCtx.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            in DATABASE_BACKUP_FILES -> {
                                val destination = File(dbDir, entry.name)
                                destination.outputStream().use { outputStream ->
                                    zipInputStream.copyTo(outputStream)
                                }
                            }
                            SETTINGS_BACKUP_NAME -> {
                                val destination = File(dsDir, SETTINGS_BACKUP_NAME)
                                destination.outputStream().use { outputStream ->
                                    zipInputStream.copyTo(outputStream)
                                }
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        }
    }
}