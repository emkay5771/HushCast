package app.podiumpodcasts.podium.background.work

import android.content.Context
import android.util.Log
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.download.DownloadTracker
import app.podiumpodcasts.podium.api.download.HttpDownloadClient
import app.podiumpodcasts.podium.api.download.HttpDownloadClientResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DownloadWorkOutcome {
    data object Success : DownloadWorkOutcome
    data object RetryableFailure : DownloadWorkOutcome
    data object PermanentFailure : DownloadWorkOutcome
}

class PodcastEpisodeDownloadWork(
    val context: Context,
    val db: AppDatabase,
    val httpDownloadClient: HttpDownloadClient = HttpDownloadClient()
) {
    companion object {
        private val transferSemaphore = Semaphore(2)
        private const val PROGRESS_THROTTLE_MS = 250L
    }

    private var lastProgressUpdate = 0L

    suspend fun doWork(
        episodeId: String,
        downloadMethod: app.hushcast.automute.engine.DownloadAnalysisMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO,
        isAuto: Boolean = false,
        forceReprocess: Boolean = false,
        onProgressUpdate: ((title: String, sourceLabel: String, percent: Int) -> Unit)? = null
    ): DownloadWorkOutcome {
        val bundle = db.podcastEpisodes().getSyncNullable(episodeId)
            ?: return DownloadWorkOutcome.PermanentFailure
        val episode = bundle.episode

        if (bundle.download == null) {
            return DownloadWorkOutcome.PermanentFailure
        }

        val file = episode.craftDownloadFile(context)
        val wasAlreadyDownloaded =
            bundle.download.state == PodcastEpisodeDownloadState.DOWNLOADED.value && file.exists()

        var outcome: DownloadWorkOutcome = DownloadWorkOutcome.PermanentFailure
        var stateHandled = false

        try {
            return transferSemaphore.withPermit {
                onProgressUpdate?.invoke(episode.title, "Starting up…", 0)
                DownloadTracker.update(episodeId, 0L, -1L, "Starting up…")

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
                        forceReprocess = forceReprocess,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (offloaded) {
                        outcome = DownloadWorkOutcome.Success
                        stateHandled = true
                        return@withPermit outcome
                    }
                    if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP) {
                        db.podcastEpisodeDownloads().setState(
                            episodeId,
                            if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                            else PodcastEpisodeDownloadState.FAILED.value
                        )
                        outcome = DownloadWorkOutcome.RetryableFailure
                        stateHandled = true
                        return@withPermit outcome
                    }
                    Log.w("HushCastDesktop", "Desktop worker unavailable or failed (auto=$isAuto); falling back to on-device download")
                } else if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP) {
                    Log.w("HushCastDesktop", "Desktop method requested but no Desktop Worker URL is configured")
                    db.podcastEpisodeDownloads().setState(
                        episodeId,
                        if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                        else PodcastEpisodeDownloadState.FAILED.value
                    )
                    outcome = DownloadWorkOutcome.PermanentFailure
                    stateHandled = true
                    return@withPermit outcome
                }

                val tmpFile = File(file.parentFile, "${file.name}.part")
                val result = withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    httpDownloadClient.download(
                        url = episode.audioUrl,
                        output = tmpFile,
                        onProgress = { progress, total ->
                            if (total <= 0L) return@download

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgressUpdate < PROGRESS_THROTTLE_MS) return@download

                            lastProgressUpdate = currentTime
                            DownloadTracker.update(episodeId, progress, total, "Downloading audio")

                            val pct = ((progress * 100L) / total).toInt().coerceIn(0, 100)
                            onProgressUpdate?.invoke(episode.title, "Downloading audio…", pct)
                        }
                    )
                }

                when (result) {
                    is HttpDownloadClientResult.Success -> {
                        val finalSize = withContext(Dispatchers.IO) {
                            if (file.exists()) file.delete()
                            if (!tmpFile.renameTo(file)) {
                                tmpFile.copyTo(file, overwrite = true)
                                tmpFile.delete()
                            }
                            file.length()
                        }
                        db.podcastEpisodeDownloads()
                            .registerDownload(episodeId, file.absolutePath, finalSize)

                        app.podiumpodcasts.podium.background.PlaybackService.notifyEpisodeDownloaded(episodeId)

                        val canAnalyzeOnDevice = app.hushcast.automute.engine.ModelManager(context).areWordMuteModelsReady()
                        if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS || !canAnalyzeOnDevice) {
                            app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                        } else {
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
                        outcome = DownloadWorkOutcome.Success
                        stateHandled = true
                        outcome
                    }

                    is HttpDownloadClientResult.Failure -> {
                        if (!result.isRetryable) {
                            tmpFile.delete()
                            db.podcastEpisodeDownloads().setState(
                                episodeId,
                                if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                                else PodcastEpisodeDownloadState.FAILED.value
                            )
                            outcome = DownloadWorkOutcome.PermanentFailure
                        } else {
                            // Keep .part file for Range resume on retry
                            db.podcastEpisodeDownloads().setState(
                                episodeId,
                                if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                                else PodcastEpisodeDownloadState.FAILED.value
                            )
                            outcome = DownloadWorkOutcome.RetryableFailure
                        }
                        stateHandled = true
                        outcome
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } finally {
            DownloadTracker.clear(episodeId)
            if (!stateHandled && outcome != DownloadWorkOutcome.Success) {
                runCatching {
                    db.podcastEpisodeDownloads().setState(
                        episodeId,
                        if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                        else PodcastEpisodeDownloadState.NOT_DOWNLOADED.value
                    )
                }
            }
        }
    }

    private fun friendlyWorkerStage(rawStage: String): String {
        val lower = rawStage.lowercase()
        return when {
            "cached" in lower || "matched existing" in lower -> "Ready"
            "queued" in lower || "reprocessing" in lower || "submitting" in lower -> "Starting analysis…"
            "downloading" in lower -> "Downloading audio…"
            "decoding" in lower || "acoustic" in lower || "loading whisper" in lower -> "Preparing audio…"
            "transcrib" in lower -> "Transcribing speech…"
            "speaker" in lower -> "Detecting speakers…"
            "embedding" in lower || "ad" in lower || "sponsor" in lower || "classif" in lower -> "Scanning for ads…"
            "summar" in lower || "chapter" in lower -> "Creating summary & chapters…"
            "transfer" in lower -> "Saving to phone…"
            "finaliz" in lower -> "Finishing up…"
            "ready" in lower -> "Ready"
            else -> "Analyzing episode…"
        }
    }

    private suspend fun tryDownloadFromDesktopWorker(
        episodeId: String,
        audioUrl: String,
        title: String,
        destFile: File,
        workerUrl: String,
        token: String,
        forceReprocess: Boolean = false,
        onProgressUpdate: ((title: String, sourceLabel: String, percent: Int) -> Unit)?
    ): Boolean = coroutineScope {
        try {
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
            onProgressUpdate?.invoke(title, "Starting analysis…", 2)
            reportDesktopStatus(5, "Starting analysis…")
            val job = app.hushcast.automute.desktop.DesktopWorkerClient.submitJob(
                baseUrl = workerUrl,
                token = token,
                request = app.hushcast.automute.desktop.DesktopJobRequest(
                    episodeId = episodeId,
                    audioUrl = audioUrl,
                    title = title,
                    force = forceReprocess
                )
            )
            var lastPollNotifyMs = 0L
            var lastPollNotifyPct = -1
            var lastFriendlyStage = ""
            val ready = app.hushcast.automute.desktop.DesktopWorkerClient.pollJobUntilReady(
                baseUrl = workerUrl,
                token = token,
                jobId = job.jobId
            ) { status ->
                val pct = 10 + ((status.progressPercent * 60) / 100)
                val friendlyStage = friendlyWorkerStage(status.stageLabel.ifBlank { status.state })
                val fgPct = (status.progressPercent / 2).coerceIn(1, 50)
                val now = System.currentTimeMillis()
                if ((fgPct != lastPollNotifyPct || friendlyStage != lastFriendlyStage) &&
                    (now - lastPollNotifyMs >= 450L || fgPct >= 50 || friendlyStage != lastFriendlyStage)
                ) {
                    lastPollNotifyMs = now
                    lastPollNotifyPct = fgPct
                    lastFriendlyStage = friendlyStage
                    onProgressUpdate?.invoke(title, friendlyStage, fgPct)
                    DownloadTracker.update(episodeId, fgPct.toLong(), 100L, friendlyStage)
                    launch {
                        runCatching { reportDesktopStatus(pct, friendlyStage) }
                    }
                }
            }
            val sha256 = ready.fileSha256 ?: return@coroutineScope false

            reportDesktopStatus(72, "Saving to phone…")
            onProgressUpdate?.invoke(title, "Saving to phone…", 50)
            var lastStatusUpdateMs = 0L
            var lastTransferNotifyMs = 0L
            var lastTransferNotifyPct = 50
            app.hushcast.automute.desktop.DesktopWorkerClient.downloadAudioAndVerifySha256(
                baseUrl = workerUrl,
                token = token,
                expectedSha256 = sha256,
                destFile = destFile
            ) { received, total ->
                if (total > 0L) {
                    val now = System.currentTimeMillis()
                    val pct = (50 + ((received * 50L) / total).toInt()).coerceIn(50, 100)
                    if (pct != lastTransferNotifyPct && (now - lastTransferNotifyMs >= 250L || pct >= 100)) {
                        lastTransferNotifyMs = now
                        lastTransferNotifyPct = pct
                        DownloadTracker.update(episodeId, received, total, "Saving to phone…")
                        onProgressUpdate?.invoke(title, "Saving to phone…", pct)
                    }
                    if (now - lastStatusUpdateMs >= 450L) {
                        lastStatusUpdateMs = now
                        val statusPct = (72 + ((received * 25L) / total).toInt()).coerceIn(72, 97)
                        launch {
                            runCatching {
                                reportDesktopStatus(
                                    statusPct,
                                    "Saving to phone (${(received * 100L / total).toInt()}%)"
                                )
                            }
                        }
                    }
                }
            }

            reportDesktopStatus(98, "Finishing up…")
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

            db.podcastEpisodeDownloads().registerDownload(episodeId, destFile.absolutePath, destFile.length())
            app.podiumpodcasts.podium.background.PlaybackService.notifyEpisodeDownloaded(episodeId)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val errMsg = t.message ?: t.javaClass.simpleName
            Log.w("HushCastDesktop", "Desktop worker offload failed for $episodeId: $errMsg", t)
            runCatching {
                val autoMuteDb = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
                autoMuteDb.dao().upsertStatus(
                    app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        badge = app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name,
                        progressPercent = 0,
                        stageLabel = "Desktop/Cloud failed",
                        errorMessage = errMsg
                    )
                )
            }
            false
        }
    }
}