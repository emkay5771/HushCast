package app.podiumpodcasts.podium.api.chapters

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.transcript.OffsetMap
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel
import app.podiumpodcasts.podium.utils.rss.computeEpisodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object EpisodeChapterHelper {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeResolvedChapters(
        db: AppDatabase,
        autoMuteDb: AutoMuteDatabase,
        episodeId: String,
        durationMs: Long
    ): Flow<List<ResolvedChapter>> {
        val offsetMapFlow = autoMuteDb.dao().observeStatus(episodeId).flatMapLatest { status ->
            val sha = status?.fileSha256?.takeIf { it.isNotBlank() }
            if (sha != null) {
                autoMuteDb.dao().observeOffsetMap(sha)
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }
        return combine(
            db.episodeChapters().observeByEpisode(episodeId),
            offsetMapFlow
        ) { entities, offsetEntity ->
            val offsetMap = offsetEntity?.stretchesJson?.let { raw ->
                runCatching { json.decodeFromString<OffsetMap>(raw) }.getOrNull()
            }
            ChapterParser.resolveForPlayback(
                chapters = entities,
                offsetMap = offsetMap,
                durationMs = durationMs
            )
        }
    }

    /** Chapters fetched from a feed's chapters URL, ready to store. */
    class FetchedChapters(val source: String, val entities: List<EpisodeChapterModel>)

    /**
     * Fetches and parses a chapters URL without touching the database.  Null when the fetch
     * failed or gave no chapters.
     */
    suspend fun fetchFeedChapters(
        episodeId: String,
        chaptersUrl: String,
        chaptersType: String,
        durationMs: Long = 0L,
        client: OkHttpClient = httpClient
    ): FetchedChapters? {
        if (chaptersUrl.isBlank()) return null
        val raw = runCatching { fetchText(client, chaptersUrl) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return null

        val isXml = chaptersType.contains("xml", ignoreCase = true) ||
            chaptersUrl.endsWith(".xml", ignoreCase = true) ||
            raw.trimStart().startsWith("<")

        val parsed = runCatching {
            if (isXml) ChapterParser.parsePodloveSimpleChaptersXml(raw) else ChapterParser.parsePodcasting20Json(raw)
        }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return null
        val source = if (isXml) EpisodeChapterModel.SOURCE_PODLOVE else EpisodeChapterModel.SOURCE_JSON
        val entities = ChapterParser.toEntities(
            episodeId = episodeId,
            source = source,
            raw = parsed,
            durationMs = durationMs,
            forceFirstAtZero = true
        )
        return if (entities.isEmpty()) null else FetchedChapters(source, entities)
    }

    /** A GET that is cancelled with its coroutine (so a time budget really stops it). */
    private suspend fun fetchText(client: OkHttpClient, url: String): String? {
        val call = client.newCall(Request.Builder().url(url).build())
        val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
                }
            })
        }
        return withContext(Dispatchers.IO) {
            response.use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null || body.contentLength() > MAX_CHAPTERS_BYTES) {
                    null
                } else {
                    body.byteStream().use { input -> readCapped(input, MAX_CHAPTERS_BYTES) }?.decodeToString()
                }
            }
        }
    }

    private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            if (out.size().toLong() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    suspend fun fetchAndStoreFeedChapters(
        db: AppDatabase,
        episodeId: String,
        chaptersUrl: String,
        chaptersType: String,
        durationMs: Long = 0L
    ): List<EpisodeChapterModel> = withContext(Dispatchers.IO) {
        val fetched = fetchFeedChapters(episodeId, chaptersUrl, chaptersType, durationMs)
            ?: return@withContext emptyList()
        db.episodeChapters().upsertPublisherChapters(episodeId, fetched.source, fetched.entities)
        fetched.entities
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun extractId3ChaptersFromTracks(tracks: Tracks): List<PodcastChapter> {
        val chapters = ArrayList<PodcastChapter>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                collectChaptersFromMetadata(metadata, chapters)
            }
        }
        return chapters.sortedBy { it.startTimeMs }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun extractId3ChaptersFromMetadata(metadata: Metadata): List<PodcastChapter> {
        val chapters = ArrayList<PodcastChapter>()
        collectChaptersFromMetadata(metadata, chapters)
        return chapters.sortedBy { it.startTimeMs }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun collectChaptersFromMetadata(metadata: Metadata, out: MutableList<PodcastChapter>) {
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            if (entry is ChapterFrame) {
                var title = ""
                var url: String? = null
                for (subIdx in 0 until entry.subFrameCount) {
                    val sub = entry.getSubFrame(subIdx)
                    if (sub is TextInformationFrame && (sub.id == "TIT2" || sub.id == "TT2") && title.isBlank()) {
                        title = sub.values.firstOrNull().orEmpty().trim()
                    } else if (sub is UrlLinkFrame && url == null) {
                        url = sub.url.takeIf { it.isNotBlank() }
                    }
                }
                val startMs = entry.startTimeMs.toLong().coerceAtLeast(0L)
                val endMs = entry.endTimeMs.toLong().takeIf { it > startMs }
                out.add(
                    PodcastChapter(
                        startTimeMs = startMs,
                        title = title,
                        url = url,
                        endTimeMs = endMs
                    )
                )
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun extractAndStoreId3FromFile(
        context: Context,
        db: AppDatabase,
        episodeId: String,
        uri: Uri,
        durationMs: Long = 0L
    ): List<EpisodeChapterModel> = withContext(Dispatchers.IO) {
        val extracted = runCatching {
            val stream = if (uri.scheme == "file" && uri.path != null) {
                java.io.FileInputStream(uri.path!!)
            } else {
                context.contentResolver.openInputStream(uri)
            }
            stream?.use { input ->
                val header = ByteArray(Id3Decoder.ID3_HEADER_LENGTH)
                var headerRead = 0
                while (headerRead < header.size) {
                    val n = input.read(header, headerRead, header.size - headerRead)
                    if (n <= 0) break
                    headerRead += n
                }
                if (headerRead == Id3Decoder.ID3_HEADER_LENGTH &&
                    header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
                ) {
                    val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)
                    if (tagSize in 1..(16 * 1024 * 1024)) {
                        val total = Id3Decoder.ID3_HEADER_LENGTH + tagSize
                        val tagBytes = ByteArray(total)
                        System.arraycopy(header, 0, tagBytes, 0, Id3Decoder.ID3_HEADER_LENGTH)
                        var offset = Id3Decoder.ID3_HEADER_LENGTH
                        while (offset < total) {
                            val n = input.read(tagBytes, offset, total - offset)
                            if (n <= 0) break
                            offset += n
                        }
                        val metadata = Id3Decoder(Id3Decoder.NO_ARTWORK_PREDICATE).decode(tagBytes, offset)
                        val chapters = ArrayList<PodcastChapter>()
                        if (metadata != null) {
                            collectChaptersFromMetadata(metadata, chapters)
                        }
                        chapters.sortedBy { it.startTimeMs }
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())

        if (extracted.isNotEmpty()) {
            val entities = ChapterParser.toEntities(
                episodeId = episodeId,
                source = EpisodeChapterModel.SOURCE_ID3,
                raw = extracted,
                durationMs = durationMs,
                forceFirstAtZero = true
            )
            if (entities.isNotEmpty()) {
                db.episodeChapters().upsertPublisherChapters(episodeId, EpisodeChapterModel.SOURCE_ID3, entities)
                return@withContext entities
            }
        }
        db.episodeChapters().getByEpisodeSync(episodeId)
    }

    /** An episode whose chapters URL a refresh should fetch (see [ChapterFetchPolicy]). */
    data class ChapterFetchCandidate(
        val episodeId: String,
        val chaptersUrl: String,
        val chaptersType: String,
        val previousFailures: Int
    )

    /**
     * Stores a refreshed feed's transcript/chapter extras: only rows that changed, all in one
     * transaction.  Inline (Podlove) chapters are stored when their count differs from what's
     * stored.  Doesn't fetch anything: returns the chapter URLs worth fetching, for
     * [fetchCandidateChapters] to run after notifications and auto-downloads.
     */
    suspend fun applyFeedExtrasOnRefresh(
        db: AppDatabase,
        origin: String,
        items: List<com.prof18.rssparser.model.RssItem>,
        extras: app.podiumpodcasts.podium.api.rss.RssPodcastExtras,
        newEpisodeIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<ChapterFetchCandidate> = withContext(Dispatchers.IO) {
        val withExtras = items.mapNotNull { item ->
            extras.lookup(item)?.let { item.computeEpisodeId(origin) to it }
        }
        if (withExtras.isEmpty()) return@withContext emptyList()

        val stored = db.podcastEpisodes().getFeedExtrasStates(origin).associateBy { it.id }
        val chapterCounts = db.episodeChapters().chapterCountsForOrigin(origin).associateBy { it.episodeId }

        val extrasUpdates = ArrayList<Pair<String, app.podiumpodcasts.podium.api.rss.RssEpisodeExtras>>()
        val inlineWrites = ArrayList<Pair<String, List<EpisodeChapterModel>>>()
        val candidates = ArrayList<ChapterFetchCandidate>()
        val seen = HashSet<String>()

        for ((episodeId, extra) in withExtras) {
            if (!seen.add(episodeId)) continue
            val prev = stored[episodeId] ?: continue
            if (FeedExtrasDiff.needsUpdate(prev, extra)) extrasUpdates += episodeId to extra

            val counts = chapterCounts[episodeId]
            if (extra.inlineChapters.isNotEmpty()) {
                if ((counts?.feedCount ?: 0) != extra.inlineChapters.size) {
                    val entities = ChapterParser.toEntities(
                        episodeId = episodeId,
                        source = EpisodeChapterModel.SOURCE_PODLOVE,
                        raw = extra.inlineChapters,
                        durationMs = 0L,
                        forceFirstAtZero = true
                    )
                    if (entities.isNotEmpty()) inlineWrites += episodeId to entities
                }
            } else if (ChapterFetchPolicy.shouldFetch(
                    isNewEpisode = episodeId in newEpisodeIds,
                    storedUrl = prev.chaptersUrl,
                    feedUrl = extra.chaptersUrl,
                    failures = prev.chaptersFetchFailures,
                    retryAfterMs = prev.chaptersRetryAfter,
                    hasPublisherChapters = (counts?.publisherCount ?: 0) > 0,
                    nowMs = nowMs
                )
            ) {
                candidates += ChapterFetchCandidate(
                    episodeId = episodeId,
                    chaptersUrl = extra.chaptersUrl,
                    chaptersType = extra.chaptersType,
                    // A changed URL starts over (the update below clears the stored count).
                    previousFailures = if (prev.chaptersUrl == extra.chaptersUrl) prev.chaptersFetchFailures else 0
                )
            }
        }

        if (extrasUpdates.isNotEmpty() || inlineWrites.isNotEmpty()) {
            db.withTransaction {
                for ((episodeId, extra) in extrasUpdates) {
                    db.podcastEpisodes().updateFeedExtras(
                        id = episodeId,
                        transcriptUrl = extra.transcriptUrl,
                        transcriptType = extra.transcriptType,
                        transcriptAlternatesJson = extra.transcriptAlternatesJson,
                        chaptersUrl = extra.chaptersUrl,
                        chaptersType = extra.chaptersType
                    )
                }
                for ((episodeId, entities) in inlineWrites) {
                    db.episodeChapters().upsertPublisherChapters(episodeId, EpisodeChapterModel.SOURCE_PODLOVE, entities)
                }
            }
        }
        candidates
    }

    /**
     * Fetches the candidates' chapter URLs: [ChapterFetchPolicy.PARALLELISM] at a time, within
     * [budgetMs] overall.  Results and backoff are written in one transaction at the end.
     * Returns how many episodes got chapters.
     */
    suspend fun fetchCandidateChapters(
        db: AppDatabase,
        candidates: List<ChapterFetchCandidate>,
        budgetMs: Long = ChapterFetchPolicy.PERIODIC_BUDGET_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val unique = candidates.distinctBy { it.episodeId }
        if (unique.isEmpty()) return@withContext 0

        val results = java.util.concurrent.ConcurrentHashMap<String, Optional<FetchedChapters>>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(ChapterFetchPolicy.PARALLELISM)
        kotlinx.coroutines.withTimeoutOrNull(budgetMs) {
            kotlinx.coroutines.coroutineScope {
                for (candidate in unique) {
                    launch {
                        semaphore.withPermit {
                            val fetched = runCatching {
                                fetchFeedChapters(
                                    episodeId = candidate.episodeId,
                                    chaptersUrl = candidate.chaptersUrl,
                                    chaptersType = candidate.chaptersType,
                                    client = refreshHttpClient
                                )
                            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                                .getOrNull()
                            results[candidate.episodeId] = Optional(fetched)
                        }
                    }
                }
            }
        }

        var stored = 0
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching {
                db.withTransaction {
                    for (candidate in unique) {
                        val outcome = results[candidate.episodeId]
                        // Each write on its own: an episode deleted meanwhile mustn't undo the rest.
                        runCatching {
                            val fetched = outcome?.value
                            when {
                                fetched != null -> {
                                    db.episodeChapters().upsertPublisherChapters(
                                        candidate.episodeId, fetched.source, fetched.entities
                                    )
                                    db.podcastEpisodes().setChaptersFetchBackoff(candidate.episodeId, 0, 0L)
                                    stored++
                                }
                                outcome != null -> {
                                    val failures = candidate.previousFailures + 1
                                    db.podcastEpisodes().setChaptersFetchBackoff(
                                        candidate.episodeId,
                                        failures,
                                        ChapterFetchPolicy.nextRetryAt(failures, nowMs)
                                    )
                                }
                                candidate.previousFailures == 0 -> {
                                    // Out of time before this one started: try again next refresh.
                                    db.podcastEpisodes().setChaptersFetchBackoff(candidate.episodeId, 1, 0L)
                                }
                            }
                        }
                    }
                }
            }
        }
        stored
    }

    /** For callers that shouldn't wait (a refresh started from the UI). */
    fun launchCandidateChapterFetch(
        db: AppDatabase,
        candidates: List<ChapterFetchCandidate>,
        budgetMs: Long = ChapterFetchPolicy.MANUAL_BUDGET_MS
    ) {
        if (candidates.isEmpty()) return
        backgroundScope.launch {
            runCatching { fetchCandidateChapters(db, candidates, budgetMs) }
        }
    }

    private class Optional<T>(val value: T?)

    private const val MAX_CHAPTERS_BYTES = 2L * 1024L * 1024L

    private val backgroundScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** Shorter timeouts for refresh-time fetches, so one slow host can't eat the budget. */
    private val refreshHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/** Whether a stored episode's feed extras differ from what the feed now says. */
internal object FeedExtrasDiff {
    fun needsUpdate(
        prev: app.podiumpodcasts.podium.api.db.model.EpisodeFeedExtrasState,
        extra: app.podiumpodcasts.podium.api.rss.RssEpisodeExtras
    ): Boolean {
        val transcriptChanged = extra.transcriptUrl.isNotBlank() && (
            prev.transcriptUrl != extra.transcriptUrl ||
                prev.transcriptType != extra.transcriptType ||
                prev.transcriptAlternatesJson != extra.transcriptAlternatesJson
            )
        val chaptersChanged = extra.chaptersUrl.isNotBlank() && (
            prev.chaptersUrl != extra.chaptersUrl ||
                prev.chaptersType != extra.chaptersType
            )
        return transcriptChanged || chaptersChanged
    }
}
