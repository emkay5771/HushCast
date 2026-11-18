package app.podiumpodcasts.podium.manager

import android.content.Context
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.background.worker.PodcastEpisodeDownloadWorker
import java.io.File

class DownloadManager {
    companion object {
        suspend fun requeueDownloads(
            context: Context,
            db: AppDatabase,
            settingsRepository: SettingsRepository = SettingsRepository(context)
        ) {
            db.podcastEpisodeDownloads().allNotDownloadedSync().forEach { download ->
                PodcastEpisodeDownloadWorker.enqueueDownload(
                    context = context,
                    episodeId = download.episodeId,
                    settingsRepository = settingsRepository
                )
            }
        }

        suspend fun downloadEpisode(
            context: Context,
            db: AppDatabase,
            episodeId: String,
            auto: Boolean = false,
            downloadMethod: app.hushcast.automute.engine.DownloadAnalysisMethod? = null,
            forceReprocess: Boolean = false,
            reprocessStage: String? = null
        ) {
            val existing = db.podcastEpisodeDownloads().getSync(episodeId)
            if (existing == null) {
                db.podcastEpisodeDownloads().add(episodeId)
            } else if (existing.state == app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState.FAILED.value) {
                db.podcastEpisodeDownloads().setState(
                    episodeId,
                    app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState.NOT_DOWNLOADED.value
                )
            }

            PodcastEpisodeDownloadWorker.enqueueDownload(
                context = context,
                episodeId = episodeId,
                auto = auto,
                downloadMethod = downloadMethod,
                forceReprocess = forceReprocess,
                reprocessStage = reprocessStage
            )
        }

        suspend fun deleteEpisodeDownload(
            context: Context,
            db: AppDatabase,
            episode: PodcastEpisodeModel
        ) {
            val downloadFile = episode.craftDownloadFile(context)
            if (downloadFile.exists()) downloadFile.delete()
            val partFile = File(downloadFile.parentFile, "${downloadFile.name}.part")
            if (partFile.exists()) partFile.delete()
            val metaFile = File(downloadFile.parentFile, "${partFile.name}.meta")
            if (metaFile.exists()) metaFile.delete()

            PodcastEpisodeDownloadWorker.stopDownload(
                context = context,
                episodeId = episode.id
            )

            db.podcastEpisodeDownloads()
                .delete(episode.id)

            runCatching {
                app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
                    .dao()
                    .resetEpisodeStatusOnly(episode.id)
            }
        }

        fun getDownloadsDirectory(context: Context): File {
            val downloadsDir = File(context.noBackupFilesDir, "downloads")
            downloadsDir.mkdirs()
            return downloadsDir
        }
    }
}