package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.MuteRuleEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.WordIndexBlob
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.transcript.TranscriptParser
import app.hushcast.automute.worker.RuleReevaluationWorker
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper
import app.podiumpodcasts.podium.api.chapters.ResolvedChapter
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.ChapterSkipModel
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.transcriptCandidates
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import app.hushcast.automute.storage.loadResolvedMuteWindows

data class TranscriptLineUi(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val speaker: String?,
    val text: String,
    val words: List<Word>
)

/**
 * A value tagged with the episode it belongs to.  The episode page shares one
 * [PlayerScreenViewModel], so right after it re-binds, the flows still hold the previous episode's
 * data; screens compare [episodeId] and ignore anything that isn't theirs (item 2).
 */
data class EpisodeScoped<T>(val episodeId: String, val value: T) {
    fun valueFor(id: String): T? = if (episodeId == id) value else null
}

/**
 * Mute segments that cover [word] (in seconds) and are on.  "Unmute word" turns these off for
 * the episode; the word stays in the mute list (item 5).
 */
fun mutedSegmentIdsForWord(word: Word, segments: List<MuteSegmentEntity>): List<Long> {
    val wStartMs = (word.start * 1000.0).toLong()
    val wEndMs = (word.end * 1000.0).toLong()
    return segments.filter { m -> m.enabled && wEndMs >= m.startMs && wStartMs <= m.endMs }.map { it.id }
}

sealed interface TranscriptLoadState {
    data object Loading : TranscriptLoadState
    data class Ready(
        val lines: List<TranscriptLineUi>,
        val sourceLabel: String,
        val isWordSynced: Boolean
    ) : TranscriptLoadState
    data object Empty : TranscriptLoadState
    data class NotProcessed(val isDownloaded: Boolean) : TranscriptLoadState
    data class Transcribing(val progressPercent: Int) : TranscriptLoadState
    data object UnsupportedFormat : TranscriptLoadState
    data class Error(val message: String) : TranscriptLoadState
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val appDb = app.podiumpodcasts.podium.manager.DatabaseManager.build(application)
    private val autoMuteDb = AutoMuteDatabase.getInstance(application)
    val settingsRepository = SettingsRepository(application)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val _episodeId = MutableStateFlow("")
    private val _durationMs = MutableStateFlow(0L)
    private val _feedTranscriptRetry = MutableStateFlow(0)

    /**
     * Points every flow at [episodeId].  Cheap and idempotent, so screens call it during composition
     * (before their first frame).  A duration of 1 ms or less means "unknown".
     */
    fun bindEpisode(episodeId: String, durationMs: Long) {
        val dur = durationMs.takeIf { it > 1L } ?: 0L
        if (_episodeId.value != episodeId) {
            // Duration first, so chapters never resolve for the new episode with the old duration.
            _durationMs.value = dur
            _episodeId.value = episodeId
        } else if (dur > 0L && _durationMs.value != dur) {
            _durationMs.value = dur
        }
    }

    val episodeBundle: StateFlow<PodcastEpisodeBundle?> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf<PodcastEpisodeBundle?>(null)
            else appDb.podcastEpisodes().get(id).map { it as PodcastEpisodeBundle? }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val analysisStatus: StateFlow<EpisodeAnalysisStatusEntity?> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(null) else autoMuteDb.dao().observeStatus(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val muteSegmentsScoped: StateFlow<EpisodeScoped<List<MuteSegmentEntity>>> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(EpisodeScoped(id, emptyList()))
            else autoMuteDb.dao().observeMuteSegmentsByEpisode(id).map { EpisodeScoped(id, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeScoped("", emptyList()))

    val muteSegments: StateFlow<List<MuteSegmentEntity>> = muteSegmentsScoped
        .map { it.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val adSegmentsScoped: StateFlow<EpisodeScoped<List<AdSegmentEntity>>> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(EpisodeScoped(id, emptyList()))
            else autoMuteDb.dao().observeAdSegmentsByEpisode(id).map { EpisodeScoped(id, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeScoped("", emptyList()))

    val adSegments: StateFlow<List<AdSegmentEntity>> = adSegmentsScoped
        .map { it.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isFavorite: StateFlow<Boolean> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(false) else appDb.listItems().isFavorite(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val skippedChapterStarts: StateFlow<Set<Long>> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf<Set<Long>>(emptySet())
            else appDb.chapterSkips().observe(id).map { model -> model?.skippedStartsSet() ?: emptySet() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val resolvedChaptersScoped: StateFlow<EpisodeScoped<List<ResolvedChapter>>> = combine(_episodeId, _durationMs) { id, dur ->
        id to dur
    }.flatMapLatest { (id, dur) ->
        if (id.isBlank()) flowOf(EpisodeScoped(id, emptyList()))
        else EpisodeChapterHelper.observeResolvedChapters(appDb, autoMuteDb, id, dur).map { EpisodeScoped(id, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeScoped("", emptyList()))

    val resolvedChapters: StateFlow<List<ResolvedChapter>> = resolvedChaptersScoped
        .map { it.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val episodeSummary: StateFlow<String?> = _episodeId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(null) else autoMuteDb.dao().observeInsights(id).map { it?.summary }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val wordIndexBlobScoped: StateFlow<EpisodeScoped<WordIndexBlob?>> = combine(_episodeId, analysisStatus) { id, status ->
        id to status?.fileSha256
    }.flatMapLatest { (id, _) ->
        if (id.isBlank()) flowOf(EpisodeScoped<WordIndexBlob?>(id, null))
        else flowOf(id).map { epId ->
            withContext(Dispatchers.Default) {
                val idx = autoMuteDb.dao().getWordIndexByEpisode(epId)
                EpisodeScoped<WordIndexBlob?>(epId, idx?.let { runCatching { BlobCodec.decompress(it.compressedBlob) }.getOrNull() })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeScoped("", null))

    val wordIndexBlob: StateFlow<WordIndexBlob?> = wordIndexBlobScoped
        .map { it.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _publisherTranscript = MutableStateFlow<Pair<String, TranscriptLoadState>?>(null)

    val transcriptStateScoped: StateFlow<EpisodeScoped<TranscriptLoadState>> = combine(
        _episodeId,
        episodeBundle,
        analysisStatus,
        wordIndexBlobScoped,
        _publisherTranscript,
        _feedTranscriptRetry
    ) { arr ->
        val id = arr[0] as String
        val bundleRaw = arr[1] as PodcastEpisodeBundle?
        val statusRaw = arr[2] as EpisodeAnalysisStatusEntity?
        @Suppress("UNCHECKED_CAST")
        val blobScoped = arr[3] as EpisodeScoped<WordIndexBlob?>
        @Suppress("UNCHECKED_CAST")
        val pubPair = arr[4] as Pair<String, TranscriptLoadState>?

        if (id.isBlank()) return@combine EpisodeScoped<TranscriptLoadState>(id, TranscriptLoadState.Empty)
        // Right after a re-bind the inner flows can still hold the previous episode's data (item 2).
        if (blobScoped.episodeId != id || (bundleRaw != null && bundleRaw.episode.id != id)) {
            return@combine EpisodeScoped<TranscriptLoadState>(id, TranscriptLoadState.Loading)
        }
        val bundle = bundleRaw
        val status = statusRaw?.takeIf { it.episodeId == id }
        val blob = blobScoped.value
        val state: TranscriptLoadState = if (blob != null && (blob.segments.isNotEmpty() || blob.words.isNotEmpty())) {
            val lines = buildLinesFromWordIndex(blob)
            if (lines.isEmpty()) TranscriptLoadState.Empty
            else TranscriptLoadState.Ready(
                lines = lines,
                sourceLabel = "Generated by HushCast",
                isWordSynced = blob.words.isNotEmpty()
            )
        } else if (pubPair != null && pubPair.first == id) {
            pubPair.second
        } else {
            val badge = runCatching { status?.badge?.let { ReadinessBadge.valueOf(it) } }.getOrNull()
                ?: ReadinessBadge.NOT_PROCESSED
            if (badge == ReadinessBadge.ANALYZING) {
                TranscriptLoadState.Transcribing(status?.progressPercent ?: 0)
            } else if (!bundle?.episode?.transcriptUrl.isNullOrBlank()) {
                TranscriptLoadState.Loading
            } else {
                val downloaded = bundle?.episode?.craftDownloadFile(getApplication())?.exists() == true
                TranscriptLoadState.NotProcessed(downloaded)
            }
        }
        EpisodeScoped<TranscriptLoadState>(id, state)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeScoped("", TranscriptLoadState.Loading))

    val transcriptState: StateFlow<TranscriptLoadState> = transcriptStateScoped
        .map { it.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptLoadState.Loading)

    init {
        viewModelScope.launch {
            combine(_episodeId, episodeBundle, wordIndexBlob, _feedTranscriptRetry) { id, bundle, blob, retry ->
                TranscriptFetchKey(
                    id = id,
                    // Feed order ranked JSON > VTT > SRT > HTML; alternates are tried if the best one fails (T5).
                    candidates = bundle?.episode?.transcriptCandidates().orEmpty(),
                    hasBlob = blob != null,
                    retry = retry
                )
            }
                .distinctUntilChanged()
                // collectLatest: a new episode (or retry) cancels the fetch still running for the old one.
                .collectLatest { key ->
                    if (key.id.isNotBlank() && !key.hasBlob && key.candidates.isNotEmpty()) {
                        _publisherTranscript.value = key.id to TranscriptLoadState.Loading
                        var loaded: TranscriptLoadState = TranscriptLoadState.Empty
                        for ((url, type) in key.candidates) {
                            val attempt = fetchAndParsePublisherTranscript(url, type)
                            // Keep the first error to show if nothing works; an empty file is not an error.
                            if (attempt is TranscriptLoadState.Ready) { loaded = attempt; break }
                            if (attempt is TranscriptLoadState.Error && loaded !is TranscriptLoadState.Error) loaded = attempt
                        }
                        _publisherTranscript.value = key.id to loaded
                    }
                }
        }
    }

    private data class TranscriptFetchKey(
        val id: String,
        val candidates: List<Pair<String, String>>,
        val hasBlob: Boolean,
        val retry: Int
    )

    fun retryPublisherTranscript() {
        _feedTranscriptRetry.value += 1
    }

    private suspend fun fetchAndParsePublisherTranscript(url: String, mimeType: String): TranscriptLoadState =
        withContext(Dispatchers.IO) {
            val raw = runCatching {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            }.getOrElse { err ->
                return@withContext TranscriptLoadState.Error(err.message ?: "Failed to load transcript")
            }

            if (raw.isBlank()) return@withContext TranscriptLoadState.Empty

            val lowerType = mimeType.lowercase()
            val lowerUrl = url.lowercase()
            val isHtml = lowerType.contains("html") || lowerUrl.endsWith(".html") || lowerUrl.endsWith(".htm") ||
                raw.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) || raw.trimStart().startsWith("<html", ignoreCase = true)

            if (isHtml) {
                val plain = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (plain.isEmpty()) return@withContext TranscriptLoadState.Empty
                val lines = plain.mapIndexed { idx, line ->
                    TranscriptLineUi(
                        index = idx,
                        startMs = 0L,
                        endMs = 0L,
                        speaker = null,
                        text = line,
                        words = emptyList()
                    )
                }
                return@withContext TranscriptLoadState.Ready(
                    lines = lines,
                    sourceLabel = "From the publisher",
                    isWordSynced = false
                )
            }

            val isKnownFormat = lowerType.contains("json") || lowerType.contains("vtt") || lowerType.contains("srt") ||
                lowerUrl.endsWith(".json") || lowerUrl.endsWith(".vtt") || lowerUrl.endsWith(".srt") ||
                raw.trimStart().startsWith("{") || raw.trimStart().startsWith("WEBVTT") ||
                Regex("""\d{2}:\d{2}[.,]\d{3}\s*-->""").containsMatchIn(raw)

            if (!isKnownFormat) {
                return@withContext TranscriptLoadState.UnsupportedFormat
            }

            val parsed = runCatching { TranscriptParser.parse(raw, mimeType.ifBlank { url }) }.getOrNull()
                ?: return@withContext TranscriptLoadState.UnsupportedFormat

            if (parsed.segments.isEmpty()) return@withContext TranscriptLoadState.Empty

            val merged = mergeCueFragmentsIntoSentences(parsed.segments)
            val lines = merged.mapIndexed { idx, seg ->
                val segWords = parsed.words.filter { w ->
                    w.start >= seg.start - 0.05 && w.end <= seg.end + 0.05
                }
                TranscriptLineUi(
                    index = idx,
                    startMs = (seg.start * 1000.0).toLong().coerceAtLeast(0L),
                    endMs = (seg.end * 1000.0).toLong().coerceAtLeast(0L),
                    speaker = seg.speaker?.let { "Speaker $it" },
                    text = seg.text,
                    words = segWords
                )
            }
            TranscriptLoadState.Ready(
                lines = lines,
                sourceLabel = "From the publisher",
                isWordSynced = parsed.hasWordLevelTimings
            )
        }

    private fun buildLinesFromWordIndex(blob: WordIndexBlob): List<TranscriptLineUi> {
        val segs = blob.segments
        if (segs.isEmpty()) {
            return listOf(
                TranscriptLineUi(
                    index = 0,
                    startMs = (blob.words.firstOrNull()?.start?.times(1000.0))?.toLong() ?: 0L,
                    endMs = (blob.words.lastOrNull()?.end?.times(1000.0))?.toLong() ?: 0L,
                    speaker = null,
                    text = blob.words.joinToString("") { it.text }.trim(),
                    words = blob.words
                )
            )
        }
        // R3: group sentences into paragraphs and keep the worker's speaker IDs, as the 1.0.27
        // transcript card did.  A paragraph ends when the speaker changes, after a long pause, or
        // at a sentence end once it has grown long enough.
        val paragraphs = groupIntoParagraphs(segs)
        return paragraphs.mapIndexed { idx, group ->
            val start = group.first().start
            val end = group.last().end
            val paraWords = blob.words.filter { w -> w.start >= start - 0.05 && w.end <= end + 0.05 }
            TranscriptLineUi(
                index = idx,
                startMs = (start * 1000.0).toLong().coerceAtLeast(0L),
                endMs = (end * 1000.0).toLong().coerceAtLeast(0L),
                speaker = group.first().speaker?.let { "Speaker ${it + 1}" },
                text = group.joinToString(" ") { it.text.trim() }.trim(),
                words = paraWords
            )
        }
    }

    private fun mergeCueFragmentsIntoSentences(segments: List<Segment>): List<Segment> {
        if (segments.size <= 1) return segments
        val out = ArrayList<Segment>(segments.size)
        var accStart = segments[0].start
        var accEnd = segments[0].end
        val buf = StringBuilder(segments[0].text.trim())

        fun flush() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) {
                out.add(Segment(accStart, accEnd.coerceAtLeast(accStart + 0.01), t))
            }
            buf.clear()
        }

        for (i in 1 until segments.size) {
            val cur = segments[i]
            val curText = cur.text.trim()
            if (curText.isEmpty()) continue
            val lastChar = buf.lastOrNull()
            val endsSentence = lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == '…'
            val longEnough = buf.length > 220

            if (endsSentence || longEnough) {
                flush()
                accStart = cur.start
                accEnd = cur.end
                buf.append(curText)
            } else {
                accEnd = cur.end
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(curText)
            }
        }
        flush()
        return out
    }

    fun toggleSkippedChapter(publishedStartMs: Long, skip: Boolean) {
        val epId = _episodeId.value
        if (epId.isBlank()) return
        val current = skippedChapterStarts.value
        val next = if (skip) current + publishedStartMs else current - publishedStartMs
        viewModelScope.launch(Dispatchers.IO) {
            if (next.isEmpty()) {
                appDb.chapterSkips().delete(epId)
            } else {
                appDb.chapterSkips().upsert(
                    ChapterSkipModel(
                        episodeId = epId,
                        skippedStartsCsv = next.sorted().joinToString(",")
                    )
                )
            }
        }
    }

    fun toggleFavorite(currentlyFavorite: Boolean) {
        val epId = _episodeId.value
        if (epId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            appDb.lists().createFavorites()
            if (currentlyFavorite) {
                val item: app.podiumpodcasts.podium.api.db.model.ListItemModel? =
                    runCatching { appDb.listItems().get(-2, epId) }.getOrNull()
                if (item != null) {
                    appDb.listItems().deleteAndReindex(-2, item.id, item.position)
                }
            } else {
                val pos = appDb.listItems().getNextPosition(-2) ?: 0
                appDb.listItems().addListItemAndRefreshItemCount(
                    listId = -2,
                    contentId = epId,
                    isPodcast = false,
                    position = pos
                )
            }
        }
    }

    fun addMuteWordRule(wordRaw: String, actionMode: MuteActionMode) {
        val cleaned = wordRaw.trim().trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
        if (cleaned.isBlank()) return
        val epId = _episodeId.value
        viewModelScope.launch(Dispatchers.IO) {
            autoMuteDb.dao().upsertRuleIfNew(
                MuteRuleEntity(
                    pattern = cleaned,
                    isRegex = false,
                    matchPlurals = true,
                    // Follow the global mute mode, so changing it later applies to this word too.
                    actionMode = app.hushcast.automute.storage.DEFAULT_RULE_MODE,
                    enabled = true,
                    category = "Custom"
                )
            )
            if (HushCastEngineBridge.currentEpisodeId == epId) {
                // Read the status fresh: this view model is shared, so analysisStatus may be stale.
                val sha = autoMuteDb.dao().getStatus(epId)?.fileSha256
                if (!sha.isNullOrBlank()) {
                    val precision = app.hushcast.automute.engine.HushCastPrefs.getMuteTimingPrecision(getApplication())
                    val rules = autoMuteDb.dao().getEnabledRules().map { it.toDomain() }
                    val compiled = app.hushcast.automute.matcher.WordMatcher.compileRules(rules, actionMode)
                    app.hushcast.automute.pipeline.AutoMuteAnalyzer(getApplication(), autoMuteDb)
                        .reevaluateMuteRulesWithCompiled(
                            fileSha256 = sha,
                            episodeId = epId,
                            prePadMs = precision.prePadMs,
                            postPadMs = precision.postPadMs,
                            compiled = compiled
                        )
                    // The new word may fall in SRT/VTT cues whose word times are only estimates.
                    episodeBundle.value?.episode?.takeIf { it.id == epId }
                        ?.craftDownloadFile(getApplication())?.takeIf { it.exists() }
                        ?.let { file ->
                            app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueRealignIfNeeded(
                                getApplication(), epId, file.absolutePath, actionMode
                            )
                        }
                    // Same resolver as PlaybackService, so rules that follow the global mode do here too.
                    val updated = autoMuteDb.dao().loadResolvedMuteWindows(sha, HushCastEngineBridge.defaultActionMode)
                    HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                }
            }
            RuleReevaluationWorker.enqueue(getApplication(), defaultActionMode = actionMode)
        }
    }

    fun toggleMuteSegment(segmentId: Long, enabled: Boolean) = setMuteSegmentsEnabled(listOf(segmentId), enabled)

    /** Turns mute segments on or off for this episode only (the word stays in the mute list). */
    fun setMuteSegmentsEnabled(segmentIds: List<Long>, enabled: Boolean) {
        if (segmentIds.isEmpty()) return
        val epId = _episodeId.value
        viewModelScope.launch(Dispatchers.IO) {
            segmentIds.forEach { autoMuteDb.dao().setMuteSegmentEnabled(it, enabled) }
            if (HushCastEngineBridge.currentEpisodeId == epId) {
                val sha = autoMuteDb.dao().getStatus(epId)?.fileSha256
                if (!sha.isNullOrBlank()) {
                    // Same resolver as PlaybackService, so rules that follow the global mode do here too.
                    val updated = autoMuteDb.dao().loadResolvedMuteWindows(sha, HushCastEngineBridge.defaultActionMode)
                    HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                }
            }
        }
    }

    fun shareTranscriptTxt(context: Context, episodeTitle: String, lines: List<TranscriptLineUi>) {
        if (lines.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
            val safeTitle = episodeTitle
                .ifBlank { "transcript" }
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .take(48)
            val outFile = File(exportDir, "${safeTitle}.txt")
            val content = buildString {
                appendLine(episodeTitle)
                appendLine()
                var lastSpeaker: String? = null
                for (line in lines) {
                    if (!line.speaker.isNullOrBlank() && line.speaker != lastSpeaker) {
                        appendLine("${line.speaker}:")
                        lastSpeaker = line.speaker
                    }
                    appendLine(line.text)
                    appendLine()
                }
            }
            outFile.writeText(content)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "$episodeTitle — Transcript")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share transcript"))
            }
        }
    }
}

/** Sentence segments grouped into transcript paragraphs (R3); mirrors the 1.0.27 card's rules. */
internal fun groupIntoParagraphs(segments: List<Segment>): List<List<Segment>> {
    val out = ArrayList<List<Segment>>()
    val cur = ArrayList<Segment>()
    var chars = 0
    for (seg in segments) {
        if (cur.isNotEmpty()) {
            val prev = cur.last()
            val speakerChanged = seg.speaker != prev.speaker
            val longPause = seg.start - prev.end >= 1.5
            val durationSec = prev.end - cur.first().start
            val sentenceEnd = prev.text.trimEnd().let { t -> t.endsWith(".") || t.endsWith("?") || t.endsWith("!") }
            val longEnough = chars >= 260 || cur.size >= 3 || durationSec >= 12.0
            if (speakerChanged || longPause || (sentenceEnd && longEnough) || chars >= 500) {
                out.add(cur.toList())
                cur.clear()
                chars = 0
            }
        }
        cur.add(seg)
        chars += seg.text.trim().length + 1
    }
    if (cur.isNotEmpty()) out.add(cur.toList())
    return out
}
