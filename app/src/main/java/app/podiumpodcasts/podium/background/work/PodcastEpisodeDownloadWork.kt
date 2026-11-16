package app.podiumpodcasts.podium.background.work

import android.content.Context
import android.util.Log
import androidx.compose.runtime.currentRecomposeScope
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.download.HttpDownloadClient
import app.podiumpodcasts.podium.api.download.HttpDownloadClientResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class PodcastEpisodeDownloadWork(
    val context: Context,
    val db: AppDatabase,
    val httpDownloadClient: HttpDownloadClient = HttpDownloadClient()
) {

    val progressUpdateDelay = 250L
    var lastProgressUpdate = 0L

    suspend fun doWork(
        episodeId: String,
        downloadMethod: app.hushcast.automute.engine.DownloadAnalysisMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO,
        isAuto: Boolean = false,
        onProgressUpdate: ((title: String, percent: Int) -> Unit)? = null
    ): Boolean {
        val bundle = db.podcastEpisodes().getSync(episodeId)
        val episode = bundle.episode

        if(bundle.download == null)
            return false

        val file = episode.craftDownloadFile(context)
        val wasAlreadyDownloaded =
            bundle.download.state == PodcastEpisodeDownloadState.DOWNLOADED.value && file.exists()

        onProgressUpdate?.invoke(episode.title, 0)
        delay(500)

        db.podcastEpisodeDownloads()
            .setState(episodeId, PodcastEpisodeDownloadState.DOWNLOADING.value)

        val workerUrl = app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context)
        val shouldTryDesktop = when (downloadMethod) {
            app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP -> workerUrl.isNotBlank()
            app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO ->
                app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(context) && workerUrl.isNotBlank()
            app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE,
            app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS -> false
        }

        if (shouldTryDesktop) {
            val offloaded = tryDownloadFromDesktopWorker(
                episodeId = episodeId,
                audioUrl = episode.audioUrl,
                title = episode.title,
                destFile = file,
                workerUrl = workerUrl,
                token = app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerToken(context),
                onProgressUpdate = onProgressUpdate
            )
            if (offloaded) {
                return true
            }
            if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP) {
                db.podcastEpisodeDownloads().setState(
                    episodeId,
                    if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                    else PodcastEpisodeDownloadState.NOT_DOWNLOADED.value
                )
                return false
            }
            Log.w("HushCastDesktop", "Desktop worker unavailable or failed (auto=$isAuto); falling back to on-device download/analysis")
        } else if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP) {
            Log.w("HushCastDesktop", "Desktop method requested but no Desktop Worker URL is configured")
            db.podcastEpisodeDownloads().setState(
                episodeId,
                if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                else PodcastEpisodeDownloadState.NOT_DOWNLOADED.value
            )
            return false
        }

        val tmpFile = java.io.File(file.parentFile, "${file.name}.part")
        val result = withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            httpDownloadClient.download(
                url = episode.audioUrl,
                output = tmpFile,
                onProgress = { progress, total ->
                    if(total == 0L) return@download

                    val currentTime = System.currentTimeMillis()
                    if(currentTime - lastProgressUpdate < progressUpdateDelay) return@download

                    lastProgressUpdate = currentTime

                    db.podcastEpisodeDownloads()
                        .setProgress(episodeId, progress, total)

                    val pct = ((progress * 100L) / total).toInt().coerceIn(0, 100)
                    onProgressUpdate?.invoke(episode.title, pct)
                }
            )
        }

        when(result) {
            is HttpDownloadClientResult.Success -> {
                withContext(Dispatchers.IO) {
                    if (file.exists()) file.delete()
                    if (!tmpFile.renameTo(file)) {
                        tmpFile.copyTo(file, overwrite = true)
                        tmpFile.delete()
                    }
                }
                db.podcastEpisodeDownloads()
                    .registerDownload(episodeId, file.absolutePath)

                app.podiumpodcasts.podium.background.PlaybackService.notifyEpisodeDownloaded(episodeId)

                if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS) {
                    app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                } else {
                    // Compute SHA-256 and enqueue background charging analysis (DAI-resilient)
                    val sha256 = withContext(Dispatchers.IO) {
                        app.hushcast.automute.hash.StreamingSha256.hashFile(file)
                    }
                    app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueBackgroundCharging(
                        context = context,
                        episodeId = episodeId,
                        audioPath = file.absolutePath,
                        knownSha256 = sha256,
                        episodeTitle = episode.title
                    )
                }
                return true
            }

            is HttpDownloadClientResult.Failure -> {
                tmpFile.delete()
                db.podcastEpisodeDownloads().setState(
                    episodeId,
                    if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                    else PodcastEpisodeDownloadState.NOT_DOWNLOADED.value
                )
                return false
            }
        }

        return false
    }

    private suspend fun tryDownloadFromDesktopWorker(
        episodeId: String,
        audioUrl: String,
        title: String,
        destFile: java.io.File,
        workerUrl: String,
        token: String,
        onProgressUpdate: ((title: String, percent: Int) -> Unit)?
    ): Boolean {
        return try {
            val autoMuteDb = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
            suspend fun reportDesktopStatus(pct: Int, stage: String) {
                autoMuteDb.dao().upsertStatus(
                    app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        badge = app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name,
                        progressPercent = pct.coerceIn(0, 99),
                        stageLabel = stage
                    )
                )
            }
            reportDesktopStatus(5, "Desktop: Submitting job…")
            val job = app.hushcast.automute.desktop.DesktopWorkerClient.submitJob(
                baseUrl = workerUrl,
                token = token,
                request = app.hushcast.automute.desktop.DesktopJobRequest(
                    episodeId = episodeId,
                    audioUrl = audioUrl,
                    title = title
                )
            )
            val ready = app.hushcast.automute.desktop.DesktopWorkerClient.pollJobUntilReady(
                baseUrl = workerUrl,
                token = token,
                jobId = job.jobId
            ) { status ->
                val pct = 10 + ((status.progressPercent * 60) / 100)
                onProgressUpdate?.invoke(title, (status.progressPercent / 2).coerceIn(0, 50))
                kotlinx.coroutines.runBlocking {
                    reportDesktopStatus(pct, "Desktop: ${status.stageLabel}")
                }
            }
            val sha256 = ready.fileSha256 ?: return false

            reportDesktopStatus(72, "Desktop: Transferring audio…")
            var lastStatusUpdateMs = 0L
            app.hushcast.automute.desktop.DesktopWorkerClient.downloadAudioAndVerifySha256(
                baseUrl = workerUrl,
                token = token,
                expectedSha256 = sha256,
                destFile = destFile
            ) { received, total ->
                if (total > 0L) {
                    db.podcastEpisodeDownloads().setProgress(episodeId, received, total)
                    val pct = (50 + ((received * 50L) / total).toInt()).coerceIn(50, 100)
                    onProgressUpdate?.invoke(title, pct)
                    val now = System.currentTimeMillis()
                    if (now - lastStatusUpdateMs >= 400L) {
                        lastStatusUpdateMs = now
                        val statusPct = (72 + ((received * 25L) / total).toInt()).coerceIn(72, 97)
                        kotlinx.coroutines.runBlocking {
                            reportDesktopStatus(statusPct, "Desktop: Transferring audio (${(received * 100L / total).toInt()}%)")
                        }
                    }
                }
            }

            reportDesktopStatus(98, "Desktop: Finalizing…")
            val bundle = app.hushcast.automute.desktop.DesktopWorkerClient.fetchBundle(
                baseUrl = workerUrl,
                token = token,
                sha256 = sha256
            )
            app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
            app.hushcast.automute.desktop.DesktopWorkerClient.ingestBundleIntoDb(
                db = autoMuteDb,
                episodeId = episodeId,
                bundle = bundle,
                defaultActionMode = app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode
            )

            db.podcastEpisodeDownloads().registerDownload(episodeId, destFile.absolutePath)
            app.podiumpodcasts.podium.background.PlaybackService.notifyEpisodeDownloaded(episodeId)
            true
        } catch (t: Throwable) {
            Log.w("HushCastDesktop", "Desktop worker offload failed for $episodeId: ${t.message}")
            runCatching {
                val autoMuteDb = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
                autoMuteDb.dao().upsertStatus(
                    app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        badge = app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name,
                        progressPercent = 0,
                        stageLabel = "Not Processed"
                    )
                )
            }
            false
        }
    }

}