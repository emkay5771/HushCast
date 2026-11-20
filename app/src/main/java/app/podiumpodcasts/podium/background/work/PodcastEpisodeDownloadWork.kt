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

        /** Health reads outlive nothing: a detached scope, so a slow worker never delays a result. */
        private val healthScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )
    }

    private var lastProgressUpdate = 0L

    suspend fun doWork(
        episodeId: String,
        downloadMethod: app.hushcast.automute.engine.DownloadAnalysisMethod = app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO,
        isAuto: Boolean = false,
        forceReprocess: Boolean = false,
        reprocessStage: String? = null,
        isFinalAttempt: Boolean = true,
        isRetry: Boolean = false,
        /** Runs once this work holds a download slot (the worker goes foreground here, S12). */
        onSlotAcquired: (suspend () -> Unit)? = null,
        onProgressUpdate: ((title: String, sourceLabel: String, percent: Int) -> Unit)? = null
    ): DownloadWorkOutcome {
        val bundle = db.podcastEpisodes().getSyncNullable(episodeId)
            ?: return DownloadWorkOutcome.PermanentFailure
        val episode = bundle.episode
        val podcastModel = runCatching { db.podcasts().getSync(episode.origin) }.getOrNull()
        val podcastLanguage = podcastModel?.languageCode.orEmpty()

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
                onSlotAcquired?.let { runCatching { it() } }
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

                // One meaning for the worker switch: switched on AND an address set.
                val disableAutoProcess = !forceReprocess && reprocessStage == null &&
                    app.podiumpodcasts.podium.SettingsRepository(context).hushCast
                        .isPodcastDisableAutoProcess(episode.origin)
                val effectiveDownloadMethod = if (disableAutoProcess) {
                    app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS
                } else {
                    downloadMethod
                }
                val workerActive = app.hushcast.automute.engine.HushCastPrefs.isWorkerActive(context)
                val workerUrl = app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context)
                val shouldTryDesktop = when (effectiveDownloadMethod) {
                    app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP,
                    app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO -> workerActive
                    app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE,
                    app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS -> false
                }
                // Set when the worker was asked for but is switched off: the phone does it instead.
                var workerOffFallback = false

                if (shouldTryDesktop) {
                    app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                    val offloaded = tryDownloadFromDesktopWorker(
                        episodeId = episodeId,
                        audioUrl = episode.audioUrl,
                        title = episode.title,
                        podcastTitle = podcastModel?.title,
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
                        resetStatusOnError = effectiveDownloadMethod != app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP || isFinalAttempt,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (offloaded) {
                        outcome = DownloadWorkOutcome.Success
                        stateHandled = true
                        return@withPermit outcome
                    }
                    if (effectiveDownloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP) {
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
                } else if (effectiveDownloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.DESKTOP) {
                    Log.w(
                        "HushCastDesktop",
                        "Worker requested for $episodeId but it's switched off or has no address; using this phone"
                    )
                    workerOffFallback = true
                }

                /** Writes a short note on the episode's status row, unless it's already Ready. */
                suspend fun noteStatus(note: String) {
                    runCatching {
                        val dao = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context).dao()
                        val current = dao.getStatus(episodeId)
                        if (current?.badge == app.hushcast.automute.dsp.ReadinessBadge.READY.name) return@runCatching
                        dao.upsertStatus(
                            (current ?: app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(episodeId = episodeId))
                                .copy(
                                    badge = if (current?.badge == app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name) {
                                        app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name
                                    } else {
                                        current?.badge ?: app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name
                                    },
                                    progressPercent = 0,
                                    stageLabel = note,
                                    updatedAt = System.currentTimeMillis()
                                )
                        )
                    }
                }

                /** The audio file is on the phone: register it, read its chapters, start analysis. */
                suspend fun finishLocalFile(): DownloadWorkOutcome {
                    val finalSize = withContext(Dispatchers.IO) { file.length() }
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
                    if (effectiveDownloadMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS || !canAnalyzeOnDevice) {
                        app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelEpisode(context, episodeId)
                        if (workerOffFallback) {
                            noteStatus("Worker is off. Turn it on in Settings to process this episode")
                        }
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
                            // The publisher's transcript (falling through to the alternates), so the
                            // analyzer can skip a full Whisper pass.
                            val feedTranscript = app.podiumpodcasts.podium.api.download.FeedTranscriptFetcher
                                .fetchBestForAnalysis(episode)
                            val chargingOnly = app.hushcast.automute.engine.HushCastPrefs.isAnalyzeOnlyWhileCharging(context)
                            if (isAuto && chargingOnly) {
                                if (workerOffFallback) {
                                    noteStatus("Worker is off. This phone will process it while charging")
                                }
                                app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueBackgroundCharging(
                                    context = context,
                                    episodeId = episodeId,
                                    audioPath = file.absolutePath,
                                    knownSha256 = sha256,
                                    episodeTitle = episode.title,
                                    feedTranscriptRaw = feedTranscript?.raw,
                                    feedTranscriptMime = feedTranscript?.mime ?: episode.transcriptType
                                )
                            } else {
                                if (workerOffFallback) {
                                    noteStatus("Worker is off. Processing on this phone")
                                }
                                app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueAnalyzeNow(
                                    context = context,
                                    episodeId = episodeId,
                                    audioPath = file.absolutePath,
                                    knownSha256 = sha256,
                                    episodeTitle = episode.title,
                                    feedTranscriptRaw = feedTranscript?.raw,
                                    feedTranscriptMime = feedTranscript?.mime ?: episode.transcriptType
                                )
                            }
                        }
                    }
                    return DownloadWorkOutcome.Success
                }

                if (workerOffFallback && wasAlreadyDownloaded && file.length() > 0L) {
                    // Already on the phone: no need to download the audio again.
                    outcome = finishLocalFile()
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
                        withContext(Dispatchers.IO) {
                            if (file.exists()) file.delete()
                            if (!tmpFile.renameTo(file)) {
                                tmpFile.copyTo(file, overwrite = true)
                                tmpFile.delete()
                            }
                        }
                        outcome = finishLocalFile()
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
        podcastTitle: String? = null,
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
            var queueTicker: kotlinx.coroutines.Job? = null
            // V15/V20: the worker's health (version, Modal or Mac, queue).  Read from a 10-minute
            // cache, started before the job is submitted; detached so a slow answer never holds up
            // this work's completion.
            val healthRef = java.util.concurrent.atomic.AtomicReference<app.hushcast.automute.desktop.DesktopHealthResponse?>(null)
            val healthDeferred = healthScope.async {
                app.hushcast.automute.desktop.DesktopWorkerClient.cachedHealth(workerUrl, token)
                    .also { healthRef.set(it) }
            }
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
                    reprocessStage = reprocessStage,
                    podcastTitle = podcastTitle
                )
                // A retry (constraint stop, process death) re-attaches to the job it started instead
                // of spawning a second, billed GPU job.
                val savedJobId = app.hushcast.automute.engine.HushCastPrefs.getWorkerJobId(context, episodeId)
                val reattached = if (savedJobId != null && reprocessStage == null) {
                    runCatching {
                        app.hushcast.automute.desktop.DesktopWorkerClient.getJob(workerUrl, token, savedJobId)
                    }.getOrNull()?.takeIf { it.state != "FAILED" && it.state != "CANCELLED" }
                } else {
                    null
                }
                val job = reattached ?: app.hushcast.automute.desktop.DesktopWorkerClient.submitJob(
                    baseUrl = workerUrl,
                    token = token,
                    request = jobReq
                )
                app.hushcast.automute.engine.HushCastPrefs.setWorkerJobId(context, episodeId, job.jobId)
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
                val publishLock = kotlinx.coroutines.sync.Mutex()
                suspend fun publishStage(pct: Int, stage: String) = publishLock.withLock {
                    val now = System.currentTimeMillis()
                    if ((pct != lastPollNotifyPct || stage != lastFriendlyStage) &&
                        (now - lastPollNotifyMs >= 500L || pct >= 70 || stage != lastFriendlyStage)
                    ) {
                        lastPollNotifyMs = now
                        lastPollNotifyPct = pct
                        lastFriendlyStage = stage
                        onProgressUpdate?.invoke(title, stage, pct)
                        DownloadTracker.update(episodeId, pct.toLong(), 100L, stage)
                        statusChannel.trySend(pct to stage)
                    }
                }

                fun knownHealth(): app.hushcast.automute.desktop.DesktopHealthResponse? = healthRef.get()

                var outdatedNoted = false
                suspend fun noteIfOutdated() {
                    val health = knownHealth() ?: return
                    if (outdatedNoted || !health.isOutdated()) return
                    outdatedNoted = true
                    Log.w(
                        "HushCastDesktop",
                        "Worker is out of date (pipeline=${health.pipelineVersion} api=${health.apiVersion}, " +
                            "phone wants pipeline=${app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION} " +
                            "api>=${app.hushcast.automute.desktop.DesktopWorkerClient.MIN_WORKER_API_VERSION}); processing anyway"
                    )
                    publishStage(highestPct, app.hushcast.automute.desktop.DesktopWorkerStatusText.OUTDATED_NOTE)
                }

                // V20: a job sitting in QUEUED (cold GPU start, busy Mac) gets an explanation.
                var lastJobState = job.state
                var lastJobQueuePosition = job.queuePosition
                var queuedSinceMs: Long? = if (job.state == "QUEUED") System.currentTimeMillis() else null
                fun queuedLabel(): String? {
                    val since = queuedSinceMs ?: return null
                    if (lastJobState != "QUEUED") return null
                    val health = knownHealth()
                    return app.hushcast.automute.desktop.DesktopWorkerStatusText.queuedLabel(
                        queuedForMs = System.currentTimeMillis() - since,
                        isCloud = health?.isCloud ?: workerUrl.contains("modal.run", ignoreCase = true),
                        jobQueuePosition = lastJobQueuePosition,
                        healthQueuePosition = health?.queuePosition
                    )
                }
                runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000L) { healthDeferred.await() } }
                noteIfOutdated()
                queueTicker = launch {
                    while (true) {
                        kotlinx.coroutines.delay(1_000L)
                        noteIfOutdated()
                        queuedLabel()?.let { label ->
                            if (label != lastFriendlyStage) publishStage(highestPct, label)
                        }
                    }
                }

                val ready = app.hushcast.automute.desktop.DesktopWorkerClient.pollJobUntilReady(
                    baseUrl = workerUrl,
                    token = token,
                    jobId = job.jobId,
                    resubmitRequest = jobReq,
                    onResubmitted = { newJobId ->
                        trackedJobId = newJobId
                        app.hushcast.automute.engine.HushCastPrefs.setWorkerJobId(context, episodeId, newJobId)
                    }
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
                    if (status.state == "QUEUED") {
                        if (queuedSinceMs == null || lastJobState != "QUEUED") queuedSinceMs = System.currentTimeMillis()
                    } else {
                        queuedSinceMs = null
                    }
                    lastJobState = status.state
                    lastJobQueuePosition = status.queuePosition
                    val friendlyStage = queuedLabel()
                        ?: friendlyWorkerStage(status.stageLabel.ifBlank { status.state }, status.stage)
                    publishStage(unifiedPct, friendlyStage)
                }
                queueTicker?.cancel()
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
                    context = context,
                    db = autoMuteDb,
                    episodeId = episodeId,
                    bundle = bundle,
                    prePadMs = mutePrecision.prePadMs,
                    postPadMs = mutePrecision.postPadMs,
                    defaultActionMode = app.podiumpodcasts.podium.background.HushCastEngineBridge.defaultActionMode
                )
                if (outdatedNoted || knownHealth()?.isOutdated() == true) {
                    // Keep the note on the finished row ("Ready (…). Worker is out of date…").
                    runCatching {
                        val dao = autoMuteDb.dao()
                        dao.getStatus(episodeId)?.let { st ->
                            val note = app.hushcast.automute.desktop.DesktopWorkerStatusText.OUTDATED_NOTE
                            dao.upsertStatus(st.copy(stageLabel = "${st.stageLabel}. $note", errorMessage = note))
                        }
                    }
                }
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
                app.hushcast.automute.engine.HushCastPrefs.setWorkerJobId(context, episodeId, null)
                // V17: the phone's copy matches the worker's SHA-256, so the worker can drop its
                // copy.  Best-effort; never fails the download.
                withContext(kotlinx.coroutines.NonCancellable) {
                    val deleted = runCatching {
                        app.hushcast.automute.desktop.DesktopWorkerClient.deleteWorkerAudio(workerUrl, token, sha256)
                    }.getOrDefault(false)
                    Log.i("HushCastDesktop", "Worker audio delete sha=${sha256.take(12)} ok=$deleted")
                }
                true
            } catch (c: CancellationException) {
                queueTicker?.cancel()
                statusChannel.close()
                statusWriter.cancel()
                earlyAudioDeferred?.cancel()
                // Do not cancel the remote job on WorkManager constraint stops so retries can re-attach
                throw c
            } catch (t: Throwable) {
                queueTicker?.cancel()
                statusChannel.close()
                statusWriter.cancel()
                earlyAudioDeferred?.cancel()
                val errMsg = t.message ?: t.javaClass.simpleName
                val isPermanent = (t as? app.hushcast.automute.desktop.DesktopWorkerClient.DesktopWorkerJobException)?.retryable == false
                val gaveUp = t is IllegalStateException && t.message.orEmpty().startsWith("Timed out")
                if (gaveUp) {
                    // The phone stopped waiting, so stop the worker too (it would keep billing GPU time).
                    trackedJobId?.let { jobId ->
                        withContext(kotlinx.coroutines.NonCancellable) {
                            runCatching { app.hushcast.automute.desktop.DesktopWorkerClient.cancelJob(workerUrl, token, jobId) }
                        }
                    }
                }
                if (isPermanent || gaveUp) {
                    app.hushcast.automute.engine.HushCastPrefs.setWorkerJobId(context, episodeId, null)
                }
                Log.w("HushCastDesktop", "Desktop worker offload failed for $episodeId (permanent=$isPermanent): $errMsg", t)
                if (resetStatusOnError || isPermanent) {
                    // V10: the worker's own reason on the row, shortened.
                    val jobFailure = t as? app.hushcast.automute.desktop.DesktopWorkerClient.DesktopWorkerJobException
                    val rowLabel = if (jobFailure != null) {
                        app.hushcast.automute.desktop.DesktopWorkerStatusText.shortReason(jobFailure.reason)
                            ?: "Worker failed"
                    } else {
                        "Desktop/Cloud failed"
                    }
                    runCatching {
                        autoMuteDb.dao().upsertStatus(
                            app.hushcast.automute.storage.EpisodeAnalysisStatusEntity(
                                episodeId = episodeId,
                                badge = app.hushcast.automute.dsp.ReadinessBadge.NOT_PROCESSED.name,
                                progressPercent = 0,
                                stageLabel = rowLabel,
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
                queueTicker?.cancel()
                trackedJobId?.let {
                    app.hushcast.automute.desktop.DesktopWorkerClient.clearActiveJob(episodeId, it)
                }
            }
        }
    }
}