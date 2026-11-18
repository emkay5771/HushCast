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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
        private val desktopOffloadSemaphore = Semaphore(4)
        private const val PROGRESS_THROTTLE_MS = 500L
    }

    private var lastProgressUpdate = 0L

    private suspend fun fetchFeedTranscriptRaw(url: String): String? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val bytes = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > 5 * 1024 * 1024) return@runCatching null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    bytes.decodeToString()
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }

    suspend fun doWork(
        episodeId: String,
        downloadMethod: app.hushcast.automute.engine.DownloadAnalysisMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO,
        isAuto: Boolean = false,
        forceReprocess: Boolean = false,
        reprocessStage: String? = null,
        isFinalAttempt: Boolean = true,
        isRetry: Boolean = false,
        onProgressUpdate: ((title: String, sourceLabel: String, percent: Int) -> Unit)? = null
    ): DownloadWorkOutcome {
        val bundle = db.podcastEpisodes().getSyncNullable(episodeId)
            ?: return DownloadWorkOutcome.PermanentFailure
        val episode = bundle.episode
        val podcastLanguage = runCatching { db.podcasts().getSync(episode.origin)?.languageCode }.getOrNull().orEmpty()

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
                val existingStatus = runCatching {
                    app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
                        .dao()
                        .getStatus(episodeId)
                }.getOrNull()
                val resumeAnalyzing = !forceReprocess &&
                    existingStatus?.badge == app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name &&
                    existingStatus.progressPercent > 0
                val initialPct = if (resumeAnalyzing) existingStatus!!.progressPercent.coerceIn(0, 99) else 0
                val initialStage = if (resumeAnalyzing && existingStatus!!.stageLabel.isNotBlank()) {
                    existingStatus.stageLabel
                } else {
                    "Starting up…"
                }
                onProgressUpdate?.invoke(episode.title, initialStage, initialPct)
                if (resumeAnalyzing) {
                    DownloadTracker.update(episodeId, initialPct.toLong(), 100L, initialStage)
                } else {
                    DownloadTracker.update(episodeId, 0L, -1L, initialStage)
                }

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
                    app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                    val offloaded = tryDownloadFromDesktopWorker(
                        episodeId = episodeId,
                        audioUrl = episode.audioUrl,
                        title = episode.title,
                        destFile = file,
                        workerUrl = workerUrl,
                        token = app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerToken(context),
                        feedTranscriptUrl = episode.transcriptUrl.ifBlank { null },
                        feedTranscriptMime = episode.transcriptType,
                        feedChaptersUrl = episode.chaptersUrl.ifBlank { null },
                        feedChaptersType = episode.chaptersType.ifBlank { null },
                        language = podcastLanguage.ifBlank { null },
                        forceReprocess = forceReprocess && !isRetry,
                        reprocessStage = reprocessStage,
                        resetStatusOnError = downloadMethod != app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP || isFinalAttempt,
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
                        runCatching {
                            app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.extractAndStoreId3FromFile(
                                context = context,
                                db = db,
                                episodeId = episodeId,
                                uri = android.net.Uri.fromFile(file),
                                durationMs = episode.duration * 1000L
                            )
                        }

                        app.podiumpodcasts.podium.background.PlaybackService.notifyEpisodeDownloaded(episodeId)

                        val canAnalyzeOnDevice = app.hushcast.automute.engine.ModelManager(context).areWordMuteModelsReady()
                        if (downloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS || !canAnalyzeOnDevice) {
                            app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                        } else {
                            val sha256 = withContext(Dispatchers.IO) {
                                app.hushcast.automute.hash.StreamingSha256.hashFile(file)
                            }
                            val existingForHash = runCatching {
                                app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
                                    .dao()
                                    .getStatus(episodeId)
                            }.getOrNull()
                            val alreadyAnalyzedAtCurrentVersion = !forceReprocess &&
                                existingForHash != null &&
                                existingForHash.fileSha256 == sha256 &&
                                existingForHash.pipelineVersion >= app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION &&
                                existingForHash.badge == app.hushcast.automute.dsp.ReadinessBadge.READY.name
                            if (!alreadyAnalyzedAtCurrentVersion) {
                                val feedTranscriptRaw = if (episode.transcriptUrl.isNotBlank()) {
                                    fetchFeedTranscriptRaw(episode.transcriptUrl)
                                } else null
                                val chargingOnly = app.hushcast.automute.engine.HushCastPrefs.isAnalyzeOnlyWhileCharging(context)
                                if (isAuto && chargingOnly) {
                                    app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueBackgroundCharging(
                                        context = context,
                                        episodeId = episodeId,
                                        audioPath = file.absolutePath,
                                        knownSha256 = sha256,
                                        episodeTitle = episode.title,
                                        feedTranscriptRaw = feedTranscriptRaw,
                                        feedTranscriptMime = episode.transcriptType
                                    )
                                } else {
                                    app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueAnalyzeNow(
                                        context = context,
                                        episodeId = episodeId,
                                        audioPath = file.absolutePath,
                                        knownSha256 = sha256,
                                        episodeTitle = episode.title,
                                        feedTranscriptRaw = feedTranscriptRaw,
                                        feedTranscriptMime = episode.transcriptType
                                    )
                                }
                            }
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
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        db.podcastEpisodeDownloads().setState(
                            episodeId,
                            if (wasAlreadyDownloaded) PodcastEpisodeDownloadState.DOWNLOADED.value
                            else PodcastEpisodeDownloadState.NOT_DOWNLOADED.value
                        )
                    }
                }
            }
        }
    }

    private fun friendlyWorkerStage(rawStage: String, stage: String? = null): String {
        when (stage?.uppercase()) {
            "QUEUED" -> return "Queued on worker…"
            "DOWNLOADING" -> return "Downloading audio…"
            "DECODING" -> return "Preparing audio…"
            "TRANSCRIBING" -> return "Transcribing speech…"
            "ALIGNING" -> return "Aligning transcript…"
            "SPEAKER_ID" -> return "Detecting speakers…"
            "EMBEDDING" -> return "Analyzing transcript…"
            "TAGGING" -> return "Scanning for ads…"
            "SUMMARIZING" -> return "Creating summary & chapters…"
            "READY" -> return "Ready"
            "CANCELLED" -> return "Cancelled"
            "FAILED" -> return "Worker failed"
        }
        val lower = rawStage.lowercase()
        return when {
            "cached" in lower || "matched existing" in lower -> "Ready"
            "queued" in lower -> "Queued on worker…"
            "reprocessing" in lower || "submitting" in lower -> "Starting analysis…"
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
        feedTranscriptUrl: String? = null,
        feedTranscriptMime: String = "",
        feedChaptersUrl: String? = null,
        feedChaptersType: String? = null,
        language: String? = null,
        forceReprocess: Boolean = false,
        reprocessStage: String? = null,
        resetStatusOnError: Boolean = true,
        onProgressUpdate: ((title: String, sourceLabel: String, percent: Int) -> Unit)?
    ): Boolean = desktopOffloadSemaphore.withPermit {
        coroutineScope {
            val autoMuteDb = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context)
            var highestWrittenPct = 0
            suspend fun reportDesktopStatus(pct: Int, stage: String) {
                val clamped = maxOf(highestWrittenPct, pct).coerceIn(0, 99)
                highestWrittenPct = clamped
                autoMuteDb.dao().upsertStatus(
                    app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        badge = app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name,
                        progressPercent = clamped,
                        stageLabel = stage
                    )
                )
            }
            val statusChannel = kotlinx.coroutines.channels.Channel<Pair<Int, String>>(
                kotlinx.coroutines.channels.Channel.CONFLATED
            )
            val statusWriter = launch(Dispatchers.IO) {
                for ((pct, stage) in statusChannel) {
                    runCatching { reportDesktopStatus(pct, stage) }
                }
            }
            var trackedJobId: String? = null
            var completedSuccessfully = false
            var earlyAudioDeferred: kotlinx.coroutines.Deferred<Boolean>? = null
            var earlyAudioSha: String? = null
            try {
                val existing = runCatching { autoMuteDb.dao().getStatus(episodeId) }.getOrNull()
                val resumeAnalyzing = !forceReprocess &&
                    existing?.badge == app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name &&
                    existing.progressPercent > 5
                var highestPct = if (resumeAnalyzing) existing!!.progressPercent.coerceIn(5, 98) else 5
                highestWrittenPct = highestPct
                val startStage = if (resumeAnalyzing && existing!!.stageLabel.isNotBlank()) {
                    existing.stageLabel
                } else {
                    "Starting analysis…"
                }
                onProgressUpdate?.invoke(title, startStage, highestPct)
                DownloadTracker.update(episodeId, highestPct.toLong(), 100L, startStage)
                if (!resumeAnalyzing) {
                    reportDesktopStatus(highestPct, startStage)
                }

                val jobReq = app.hushcast.automute.desktop.DesktopJobRequest(
                    episodeId = episodeId,
                    audioUrl = audioUrl,
                    title = title,
                    feedTranscriptMime = feedTranscriptMime,
                    feedTranscriptUrl = feedTranscriptUrl,
                    feedChaptersUrl = feedChaptersUrl,
                    feedChaptersType = feedChaptersType,
                    language = language,
                    force = forceReprocess,
                    reprocessStage = reprocessStage
                )
                val job = app.hushcast.automute.desktop.DesktopWorkerClient.submitJob(
                    baseUrl = workerUrl,
                    token = token,
                    request = jobReq
                )
                Log.i(
                    "HushCastDesktop",
                    "submitJob ep=$episodeId job=${job.jobId} state=${job.state} pct=${job.progressPercent} force=$forceReprocess"
                )
                trackedJobId = job.jobId
                app.hushcast.automute.desktop.DesktopWorkerClient.trackActiveJob(
                    episodeId = episodeId,
                    baseUrl = workerUrl,
                    token = token,
                    jobId = job.jobId
                )

                var lastPollNotifyMs = 0L
                var lastPollNotifyPct = -1
                var lastFriendlyStage = ""
                val ready = app.hushcast.automute.desktop.DesktopWorkerClient.pollJobUntilReady(
                    baseUrl = workerUrl,
                    token = token,
                    jobId = job.jobId,
                    resubmitRequest = jobReq,
                    onResubmitted = { newJobId -> trackedJobId = newJobId }
                ) { status ->
                    // V5: Start early Range audio pull as soon as fileSha256 is known
                    val shaEarly = status.fileSha256?.takeIf { it.isNotBlank() }
                    if (shaEarly != null && earlyAudioDeferred == null && reprocessStage == null) {
                        earlyAudioSha = shaEarly
                        earlyAudioDeferred = async(Dispatchers.IO) {
                            runCatching {
                                val alreadyOk = destFile.exists() && destFile.length() > 0L &&
                                    app.hushcast.automute.hash.StreamingSha256.hashFile(destFile) == shaEarly
                                if (!alreadyOk) {
                                    app.hushcast.automute.desktop.DesktopWorkerClient.downloadAudioAndVerifySha256(
                                        baseUrl = workerUrl,
                                        token = token,
                                        expectedSha256 = shaEarly,
                                        destFile = destFile
                                    )
                                }
                                true
                            }.getOrDefault(false)
                        }
                    }

                    val rawPct = (5 + ((status.progressPercent * 65) / 100)).coerceIn(5, 70)
                    val unifiedPct = maxOf(highestPct, rawPct)
                    highestPct = unifiedPct
                    val friendlyStage = friendlyWorkerStage(status.stageLabel.ifBlank { status.state }, status.stage)
                    val now = System.currentTimeMillis()
                    if ((unifiedPct != lastPollNotifyPct || friendlyStage != lastFriendlyStage) &&
                        (now - lastPollNotifyMs >= 500L || unifiedPct >= 70 || friendlyStage != lastFriendlyStage)
                    ) {
                        lastPollNotifyMs = now
                        lastPollNotifyPct = unifiedPct
                        lastFriendlyStage = friendlyStage
                        onProgressUpdate?.invoke(title, friendlyStage, unifiedPct)
                        DownloadTracker.update(episodeId, unifiedPct.toLong(), 100L, friendlyStage)
                        statusChannel.trySend(unifiedPct to friendlyStage)
                    }
                }
                val sha256 = ready.fileSha256 ?: return@coroutineScope false
                Log.i("HushCastDesktop", "Job ${trackedJobId ?: job.jobId} READY ep=$episodeId sha=${sha256.take(12)}")

                val earlyPulledOk = if (earlyAudioSha == sha256 && earlyAudioDeferred != null) {
                    runCatching { earlyAudioDeferred!!.await() }.getOrDefault(false)
                } else {
                    false
                }
                val localAlreadyMatches = earlyPulledOk || withContext(Dispatchers.IO) {
                    destFile.exists() && destFile.length() > 0L &&
                        runCatching { app.hushcast.automute.hash.StreamingSha256.hashFile(destFile) == sha256 }
                            .getOrDefault(false)
                }

                if (!localAlreadyMatches) {
                    highestPct = maxOf(highestPct, 72)
                    statusChannel.trySend(highestPct to "Saving to phone…")
                    onProgressUpdate?.invoke(title, "Saving to phone…", highestPct)
                    var lastStatusUpdateMs = 0L
                    var lastTransferNotifyMs = 0L
                    var lastTransferNotifyPct = highestPct
                    app.hushcast.automute.desktop.DesktopWorkerClient.downloadAudioAndVerifySha256(
                        baseUrl = workerUrl,
                        token = token,
                        expectedSha256 = sha256,
                        destFile = destFile
                    ) { received, total ->
                        if (total > 0L) {
                            val now = System.currentTimeMillis()
                            val rawPct = (72 + ((received * 26L) / total).toInt()).coerceIn(72, 98)
                            val unifiedPct = maxOf(highestPct, rawPct)
                            highestPct = unifiedPct
                            if (unifiedPct != lastTransferNotifyPct && (now - lastTransferNotifyMs >= 500L || unifiedPct >= 98)) {
                                lastTransferNotifyMs = now
                                lastTransferNotifyPct = unifiedPct
                                DownloadTracker.update(episodeId, unifiedPct.toLong(), 100L, "Saving to phone…")
                                onProgressUpdate?.invoke(title, "Saving to phone…", unifiedPct)
                            }
                            if (now - lastStatusUpdateMs >= 500L) {
                                lastStatusUpdateMs = now
                                statusChannel.trySend(
                                    unifiedPct to "Saving to phone (${(received * 100L / total).toInt()}%)"
                                )
                            }
                        }
                    }
                }

                statusChannel.close()
                statusWriter.join()
                reportDesktopStatus(98, "Finishing up…")
                val bundle = app.hushcast.automute.desktop.DesktopWorkerClient.fetchBundle(
                    baseUrl = workerUrl,
                    token = token,
                    sha256 = sha256
                )
                app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                val mutePrecision = app.hushcast.automute.engine.HushCastPrefs.getMuteTimingPrecision(context)
                app.hushcast.automute.desktop.DesktopWorkerClient.ingestBundleIntoDb(
                    db = autoMuteDb,
                    episodeId = episodeId,
                    bundle = bundle,
                    prePadMs = mutePrecision.prePadMs,
                    postPadMs = mutePrecision.postPadMs,
                    defaultActionMode = app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode
                )
                app.hushcast.automute.worker.EpisodeAnalysisWorker.analysisCompletedEvents.tryEmit(episodeId)

                db.podcastEpisodeDownloads().registerDownload(episodeId, destFile.absolutePath, destFile.length())
                runCatching {
                    app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.extractAndStoreId3FromFile(
                        context = context,
                        db = db,
                        episodeId = episodeId,
                        uri = android.net.Uri.fromFile(destFile)
                    )
                }
                app.podiumpodcasts.podium.background.PlaybackService.notifyEpisodeDownloaded(episodeId)
                completedSuccessfully = true
                true
            } catch (c: CancellationException) {
                statusChannel.close()
                statusWriter.cancel()
                earlyAudioDeferred?.cancel()
                // Do not cancel the remote job on WorkManager constraint stops so retries can re-attach
                throw c
            } catch (t: Throwable) {
                statusChannel.close()
                statusWriter.cancel()
                earlyAudioDeferred?.cancel()
                val errMsg = t.message ?: t.javaClass.simpleName
                val isPermanent = (t as? app.hushcast.automute.desktop.DesktopWorkerClient.DesktopWorkerJobException)?.retryable == false
                Log.w("HushCastDesktop", "Desktop worker offload failed for $episodeId (permanent=$isPermanent): $errMsg", t)
                if (resetStatusOnError || isPermanent) {
                    runCatching {
                        autoMuteDb.dao().upsertStatus(
                            app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                                episodeId = episodeId,
                                badge = app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name,
                                progressPercent = 0,
                                stageLabel = if (isPermanent) "Worker error (permanent)" else "Desktop/Cloud failed",
                                errorMessage = errMsg
                            )
                        )
                    }
                } else {
                    runCatching {
                        val current = autoMuteDb.dao().getStatus(episodeId)
                        autoMuteDb.dao().upsertStatus(
                            app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                                episodeId = episodeId,
                                fileSha256 = current?.fileSha256 ?: "",
                                badge = app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name,
                                progressPercent = current?.progressPercent ?: 0,
                                stageLabel = "Retrying worker…",
                                errorMessage = errMsg
                            )
                        )
                    }
                }
                false
            } finally {
                trackedJobId?.let {
                    if (!completedSuccessfully) {
                        app.hushcast.automute.desktop.DesktopWorkerClient.clearActiveJob(episodeId, it)
                    } else {
                        app.hushcast.automute.desktop.DesktopWorkerClient.clearActiveJob(episodeId, it)
                    }
                }
            }
        }
    }
}