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
import kotlinx.coroutines.withContext
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

    suspend fun fetchAndStoreFeedChapters(
        db: AppDatabase,
        episodeId: String,
        chaptersUrl: String,
        chaptersType: String,
        durationMs: Long = 0L
    ): List<EpisodeChapterModel> = withContext(Dispatchers.IO) {
        if (chaptersUrl.isBlank()) return@withContext emptyList()
        val raw = runCatching {
            val req = Request.Builder().url(chaptersUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext emptyList()

        val isXml = chaptersType.contains("xml", ignoreCase = true) ||
            chaptersUrl.endsWith(".xml", ignoreCase = true) ||
            raw.trimStart().startsWith("<")

        val parsed = if (isXml) {
            ChapterParser.parsePodloveSimpleChaptersXml(raw)
        } else {
            ChapterParser.parsePodcasting20Json(raw)
        }

        if (parsed.isEmpty()) return@withContext emptyList()
        val source = if (isXml) EpisodeChapterModel.SOURCE_PODLOVE else EpisodeChapterModel.SOURCE_JSON
        val entities = ChapterParser.toEntities(
            episodeId = episodeId,
            source = source,
            raw = parsed,
            durationMs = durationMs,
            forceFirstAtZero = true
        )
        if (entities.isNotEmpty()) {
            db.episodeChapters().upsertPublisherChapters(episodeId, source, entities)
        }
        entities
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

    suspend fun applyFeedExtrasOnRefresh(
        db: AppDatabase,
        origin: String,
        items: List<com.prof18.rssparser.model.RssItem>,
        extras: app.podiumpodcasts.podium.api.rss.RssPodcastExtras
    ) = withContext(Dispatchers.IO) {
        for (item in items) {
            val extra = extras.lookup(item) ?: continue
            val episodeId = item.computeEpisodeId(origin)
            if (extra.transcriptUrl.isNotBlank() || extra.chaptersUrl.isNotBlank()) {
                db.podcastEpisodes().updateFeedExtras(
                    id = episodeId,
                    transcriptUrl = extra.transcriptUrl,
                    transcriptType = extra.transcriptType,
                    chaptersUrl = extra.chaptersUrl,
                    chaptersType = extra.chaptersType
                )
            }
            if (extra.inlineChapters.isNotEmpty()) {
                val entities = ChapterParser.toEntities(
                    episodeId = episodeId,
                    source = EpisodeChapterModel.SOURCE_PODLOVE,
                    raw = extra.inlineChapters,
                    durationMs = 0L,
                    forceFirstAtZero = true
                )
                if (entities.isNotEmpty()) {
                    db.episodeChapters().upsertPublisherChapters(episodeId, EpisodeChapterModel.SOURCE_PODLOVE, entities)
                }
            } else if (extra.chaptersUrl.isNotBlank()) {
                val existing = db.episodeChapters().getByEpisodeSync(episodeId)
                if (existing.none { it.isPublisher }) {
                    fetchAndStoreFeedChapters(
                        db = db,
                        episodeId = episodeId,
                        chaptersUrl = extra.chaptersUrl,
                        chaptersType = extra.chaptersType
                    )
                }
            }
        }
    }
}
