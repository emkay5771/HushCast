package app.hushcast.automute.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * WorkManager worker for on-device episode analysis.
 * Uses per-episode unique work + a process-wide Semaphore(1) so one episode's failure or
 * cancellation never aborts other queued episodes (E6).
 */
class EpisodeAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        val audioPath = inputData.getString(KEY_AUDIO_PATH) ?: return Result.failure()
        val episodeTitle = inputData.getString(KEY_EPISODE_TITLE).orEmpty()
        val knownSha256 = inputData.getString(KEY_KNOWN_SHA256)
        val quickMode = inputData.getBoolean(KEY_QUICK_MODE, false)
        val feedTranscriptRaw = readFeedTranscriptFromInput(inputData, deleteTempFile = false)
        val feedTranscriptMime = inputData.getString(KEY_FEED_TRANSCRIPT_MIME).orEmpty()
        val actionMode = runCatching {
            MuteActionMode.valueOf(inputData.getString(KEY_ACTION_MODE) ?: MuteActionMode.HARD_SILENCE.name)
        }.getOrDefault(MuteActionMode.HARD_SILENCE)

        val db = AutoMuteDatabase.getInstance(applicationContext)
        val audioFile = File(audioPath)
        val displayTitle = episodeTitle.ifBlank { "Episode analysis" }
        val notificationId = NOTIFICATION_ID_BASE + (episodeId.hashCode() and 0x0FFF)

        if (inputData.getBoolean(KEY_REALIGN_ONLY, false)) {
            if (!audioFile.exists()) return Result.success()
            return analysisSemaphore.withPermit { runRealign(db, episodeId, audioFile, actionMode) }
        }

        if (!audioFile.exists()) {
            db.dao().upsertStatus(
                EpisodeAnalysisStatusEntity(
                    episodeId = episodeId,
                    fileSha256 = knownSha256 ?: "",
                    badge = ReadinessBadge.NOT_PROCESSED.name,
                    progressPercent = 0,
                    stageLabel = "Audio not downloaded",
                    errorMessage = null
                )
            )
            deleteStagedTranscriptFile(inputData)
            return Result.failure()
        }

        return analysisSemaphore.withPermit {
            runCatching {
                setForeground(createForegroundInfo(notificationId, displayTitle, "Analyzing episode…", 0))
            }

            val analyzer = AutoMuteAnalyzer(applicationContext, db)
            android.util.Log.i("HushCastPerf", "Worker start: ep=$episodeId quick=$quickMode bytes=${audioFile.length()}")

            var lastNotifPct = 0
            try {
                analyzer.analyzeEpisode(
                    episodeId = episodeId,
                    audioFile = audioFile,
                    knownSha256 = knownSha256,
                    quickWordMuteOnly = quickMode,
                    feedTranscriptRaw = feedTranscriptRaw,
                    feedTranscriptMime = feedTranscriptMime,
                    defaultActionMode = actionMode,
                    onProgress = { p ->
                        val clampedPct = maxOf(lastNotifPct, p.percent.coerceIn(0, 100))
                        lastNotifPct = clampedPct
                        runCatching {
                            setForegroundAsync(createForegroundInfo(notificationId, displayTitle, p.stage, clampedPct))
                        }
                    }
                )
                android.util.Log.i("HushCastPerf", "Worker success: ep=$episodeId")
                analysisCompletedEvents.tryEmit(episodeId)
                deleteStagedTranscriptFile(inputData)
                Result.success()
            } catch (c: CancellationException) {
                val reason = if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_NOT_STOPPED
                val isPausedBySystem = reason == WorkInfo.STOP_REASON_CONSTRAINT_CHARGING ||
                    reason == WorkInfo.STOP_REASON_TIMEOUT ||
                    reason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
                    reason == WorkInfo.STOP_REASON_QUOTA
                android.util.Log.w("HushCastPerf", "Worker stopped: ep=$episodeId reason=$reason paused=$isPausedBySystem")
                withContext(NonCancellable) {
                    val existing = runCatching { db.dao().getStatus(episodeId) }.getOrNull()
                    // A stopped re-run of a Ready episode keeps its old, still-valid results.
                    if (existing?.badge == ReadinessBadge.READY.name) return@withContext
                    val preservedWordMute = existing?.badge == ReadinessBadge.WORD_MUTE_READY.name
                    val label = when {
                        isPausedBySystem && preservedWordMute -> "Paused (Word-Mute Ready)"
                        isPausedBySystem -> "Paused (waiting to resume)"
                        preservedWordMute -> "Cancelled (Word-Mute Ready)"
                        else -> "Cancelled"
                    }
                    db.dao().upsertStatus(
                        EpisodeAnalysisStatusEntity(
                            episodeId = episodeId,
                            fileSha256 = existing?.fileSha256?.ifBlank { knownSha256 ?: "" } ?: (knownSha256 ?: ""),
                            badge = if (preservedWordMute) ReadinessBadge.WORD_MUTE_READY.name else ReadinessBadge.NOT_PROCESSED.name,
                            progressPercent = if (preservedWordMute) 100 else (existing?.progressPercent ?: 0),
                            stageLabel = label,
                            peakRssMb = existing?.peakRssMb ?: 0,
                            errorMessage = null
                        )
                    )
                }
                throw c
            } catch (t: Throwable) {
                android.util.Log.e("HushCastPerf", "Worker failed: ep=$episodeId ${t.javaClass.simpleName}: ${t.message}", t)
                val existing = runCatching { db.dao().getStatus(episodeId) }.getOrNull()
                if (existing?.badge == ReadinessBadge.READY.name) {
                    // A failed re-run never downgrades a Ready episode; its old results stay in use.
                    deleteStagedTranscriptFile(inputData)
                    return@withPermit Result.failure()
                }
                val preservedWordMute = existing?.badge == ReadinessBadge.WORD_MUTE_READY.name
                db.dao().upsertStatus(
                    EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        fileSha256 = existing?.fileSha256?.ifBlank { knownSha256 ?: "" } ?: (knownSha256 ?: ""),
                        badge = if (preservedWordMute) ReadinessBadge.WORD_MUTE_READY.name else ReadinessBadge.NOT_PROCESSED.name,
                        progressPercent = if (preservedWordMute) 100 else 0,
                        stageLabel = if (preservedWordMute) "Ad detection failed (Word-Mute Ready)" else "Analysis failed",
                        peakRssMb = existing?.peakRssMb ?: 0,
                        errorMessage = t.message ?: t.javaClass.simpleName
                    )
                )
                deleteStagedTranscriptFile(inputData)
                Result.failure()
            }
        }
    }

    /** Re-aligns SRT/VTT cues that rules added since analysis match; never touches the badge. */
    private suspend fun runRealign(
        db: AutoMuteDatabase,
        episodeId: String,
        audioFile: File,
        actionMode: MuteActionMode
    ): Result = try {
        val n = AutoMuteAnalyzer(applicationContext, db).realignApproximateMatches(episodeId, audioFile, actionMode)
        android.util.Log.i("HushCastPerf", "Realign: ep=$episodeId cues=$n")
        Result.success()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        // The approximate (widened) windows stay in place; the next play or analysis tries again.
        android.util.Log.w("HushCastPerf", "Realign failed: ep=$episodeId ${t.javaClass.simpleName}: ${t.message}")
        Result.success()
    }

    private fun createForegroundInfo(
        notificationId: Int,
        episodeTitle: String,
        stage: String,
        progress: Int
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HushCast On-Device Analysis",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val clamped = progress.coerceIn(0, 100)
        val titleText = episodeTitle.ifBlank { "HushCast Audio Analysis" }
        val chipText = if (clamped > 0) "$clamped%" else "AI"

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chipText)
            .setProgress(100, clamped, clamped == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(clamped)
                    .setProgressIndeterminate(clamped == 0)
            )
        }

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= 35) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "hushcast_analysis"
        const val KEY_EPISODE_ID = "episode_id"
        const val KEY_AUDIO_PATH = "audio_path"
        const val KEY_EPISODE_TITLE = "episode_title"
        const val KEY_KNOWN_SHA256 = "known_sha256"
        const val KEY_QUICK_MODE = "quick_mode"
        const val KEY_ACTION_MODE = "action_mode"
        const val KEY_FEED_TRANSCRIPT_RAW = "feed_transcript_raw"
        const val KEY_FEED_TRANSCRIPT_PATH = "feed_transcript_path"
        const val KEY_FEED_TRANSCRIPT_MIME = "feed_transcript_mime"
        const val KEY_REALIGN_ONLY = "realign_only"

        /** Tag prefix carrying [requestSignature] on every analysis request (R2). */
        internal const val SIGNATURE_TAG_PREFIX = "sig:"
        internal const val CONSTRAINTS_NONE = "none"
        internal const val CONSTRAINTS_CHARGING = "charging"
        internal const val CONSTRAINTS_CHARGING_UNMETERED = "charging+unmetered"

        /** Checks and enqueues one request at a time, so two quick taps can't race. */
        private val enqueueExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "HushCast-Enqueue").apply { isDaemon = true }
        }

        /** What makes two analysis requests the same job (R2). */
        internal fun requestSignature(
            audioPath: String,
            quickMode: Boolean,
            actionMode: MuteActionMode,
            constraints: String
        ): String = "$SIGNATURE_TAG_PREFIX$audioPath|quick=$quickMode|mode=${actionMode.name}|$constraints"

        /**
         * R2: keep the pending (or running) request only when it is the same request; otherwise
         * replace it.  [force] always replaces.  With nothing pending, KEEP simply enqueues.
         */
        internal fun analyzeNowPolicy(
            existing: List<Pair<WorkInfo.State, Set<String>>>,
            signature: String,
            force: Boolean
        ): ExistingWorkPolicy {
            if (force) return ExistingWorkPolicy.REPLACE
            val unfinished = existing.filter { !it.first.isFinished }
            if (unfinished.isEmpty()) return ExistingWorkPolicy.KEEP
            return if (unfinished.all { signature in it.second }) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        }

        const val ANALYSIS_QUEUE_NAME = "hushcast_analysis_queue"
        private const val NOTIFICATION_ID_BASE = 41001
        private val analysisSemaphore = Semaphore(1)

        private fun uniqueWorkName(episodeId: String) = "hushcast_analysis_$episodeId"

        private fun stageTranscriptFile(context: Context, episodeId: String, raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
                val safeName = episodeId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
                val file = File(dir, "$safeName.txt")
                file.writeText(raw)
                file.absolutePath
            }.getOrNull()
        }

        internal fun deleteStagedTranscriptFile(inputData: androidx.work.Data) {
            val path = inputData.getString(KEY_FEED_TRANSCRIPT_PATH)?.takeIf { it.isNotBlank() } ?: return
            runCatching { File(path).delete() }
        }

        internal fun readFeedTranscriptFromInput(inputData: androidx.work.Data, deleteTempFile: Boolean = true): String? {
            val inlineRaw = inputData.getString(KEY_FEED_TRANSCRIPT_RAW)
            if (!inlineRaw.isNullOrBlank()) return inlineRaw
            val path = inputData.getString(KEY_FEED_TRANSCRIPT_PATH)?.takeIf { it.isNotBlank() } ?: return null
            val file = File(path)
            if (!file.exists()) return null
            return runCatching {
                file.readText()
            }.also {
                if (deleteTempFile) runCatching { file.delete() }
            }.getOrNull()
        }

        fun enqueueBackgroundCharging(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String? = null,
            quickMode: Boolean = false,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
            episodeTitle: String? = null,
            feedTranscriptRaw: String? = null,
            feedTranscriptMime: String = ""
        ) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .build()
            val transcriptPath = stageTranscriptFile(context, episodeId, feedTranscriptRaw)

            val request = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .addTag(ANALYSIS_QUEUE_NAME)
                .addTag("episode:$episodeId")
                .addTag(requestSignature(audioPath, quickMode, actionMode, CONSTRAINTS_CHARGING))
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_EPISODE_TITLE to episodeTitle,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to quickMode,
                        KEY_ACTION_MODE to actionMode.name,
                        KEY_FEED_TRANSCRIPT_PATH to transcriptPath,
                        KEY_FEED_TRANSCRIPT_MIME to feedTranscriptMime
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(episodeId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueAnalyzeNow(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String? = null,
            quickMode: Boolean = false,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
            episodeTitle: String? = null,
            feedTranscriptRaw: String? = null,
            feedTranscriptMime: String = "",
            force: Boolean = false
        ) {
            val chargingOnly = HushCastPrefs.isAnalyzeOnlyWhileCharging(context)
            val transcriptPath = stageTranscriptFile(context, episodeId, feedTranscriptRaw)
            val builder = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .addTag(ANALYSIS_QUEUE_NAME)
                .addTag("episode:$episodeId")
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_EPISODE_TITLE to episodeTitle,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to quickMode,
                        KEY_ACTION_MODE to actionMode.name,
                        KEY_FEED_TRANSCRIPT_PATH to transcriptPath,
                        KEY_FEED_TRANSCRIPT_MIME to feedTranscriptMime
                    )
                )
            if (chargingOnly) {
                builder.setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .build()
                )
            } else {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val signature = requestSignature(
                audioPath, quickMode, actionMode, if (chargingOnly) CONSTRAINTS_CHARGING else CONSTRAINTS_NONE
            )
            builder.addTag(signature)
            val request = builder.build()
            val wm = WorkManager.getInstance(context)
            val name = uniqueWorkName(episodeId)
            // R2: KEEP only an identical pending request (a second tap must not cancel a running
            // analysis); a different one, such as auto-download's charging-only job, is replaced.
            enqueueExecutor.execute {
                val existing = runCatching { wm.getWorkInfosForUniqueWork(name).get() }.getOrDefault(emptyList())
                val policy = analyzeNowPolicy(existing.map { it.state to it.tags }, signature, force)
                runCatching { wm.enqueueUniqueWork(name, policy, request).result.get() }
                    .onFailure { android.util.Log.w("HushCastPerf", "Enqueue failed: ep=$episodeId ${it.message}") }
            }
        }

        /**
         * Call when an episode starts playing (and after rules change while it plays).  If a rule
         * now matches SRT/VTT words whose times are only estimates, a short job runs Whisper
         * within +-2 s of those cues and fixes their times.  Does nothing otherwise; the check
         * runs off the calling thread.
         */
        fun enqueueRealignIfNeeded(
            context: Context,
            episodeId: String,
            audioPath: String,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
        ) {
            val app = context.applicationContext
            enqueueExecutor.execute {
                if (!File(audioPath).exists()) return@execute
                val needed = runCatching {
                    runBlocking { AutoMuteAnalyzer(app).needsRealign(episodeId, actionMode) }
                }.getOrDefault(false)
                if (!needed) return@execute
                val request = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                    .addTag(ANALYSIS_QUEUE_NAME)
                    .addTag(REALIGN_TAG)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(
                        workDataOf(
                            KEY_EPISODE_ID to episodeId,
                            KEY_AUDIO_PATH to audioPath,
                            KEY_ACTION_MODE to actionMode.name,
                            KEY_REALIGN_ONLY to true
                        )
                    )
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(
                    "hushcast_realign_$episodeId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
        }

        const val REALIGN_TAG = "hushcast_realign"

        fun cancelEpisode(context: Context, episodeId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(episodeId))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ANALYSIS_QUEUE_NAME)
        }

        fun isRerunEligible(
            isCharging: Boolean,
            isUnmeteredWifi: Boolean,
            isWorkerConfigured: Boolean
        ): Boolean = (isCharging && isUnmeteredWifi) || isWorkerConfigured

        fun findStaleEpisodesForRerun(
            statuses: List<EpisodeAnalysisStatusEntity>,
            currentPipelineVersion: Int = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
        ): List<EpisodeAnalysisStatusEntity> = statuses.filter {
            it.badge == ReadinessBadge.READY.name &&
                it.pipelineVersion < currentPipelineVersion &&
                it.fileSha256.isNotBlank()
        }

        fun enqueueBackgroundRerun(
            context: Context,
            episodeId: String,
            audioPath: String,
            knownSha256: String,
            actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
            episodeTitle: String? = null,
            feedTranscriptRaw: String? = null,
            feedTranscriptMime: String = ""
        ) {
            // On-device re-runs only ever happen while charging on unmetered Wi-Fi.  When a worker is
            // active, the caller re-processes through the worker instead of calling this.
            val constraintsBuilder = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
            val transcriptPath = stageTranscriptFile(context, episodeId, feedTranscriptRaw)
            val request = OneTimeWorkRequestBuilder<EpisodeAnalysisWorker>()
                .addTag(ANALYSIS_QUEUE_NAME)
                .addTag("episode:$episodeId")
                .addTag(requestSignature(audioPath, false, actionMode, CONSTRAINTS_CHARGING_UNMETERED))
                .setConstraints(constraintsBuilder.build())
                .setInputData(
                    workDataOf(
                        KEY_EPISODE_ID to episodeId,
                        KEY_AUDIO_PATH to audioPath,
                        KEY_EPISODE_TITLE to episodeTitle,
                        KEY_KNOWN_SHA256 to knownSha256,
                        KEY_QUICK_MODE to false,
                        KEY_ACTION_MODE to actionMode.name,
                        KEY_FEED_TRANSCRIPT_PATH to transcriptPath,
                        KEY_FEED_TRANSCRIPT_MIME to feedTranscriptMime
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(episodeId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        val analysisCompletedEvents = MutableSharedFlow<String>(
            extraBufferCapacity = 16
        )
    }
}
