package app.podiumpodcasts.podium.api.chapters

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.transcript.OffsetMap
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    fun observeResolvedChapters(
        db: AppDatabase,
        autoMuteDb: AutoMuteDatabase,
        episodeId: String,
        durationMs: Long
    ): Flow<List<ResolvedChapter>> {
        return combine(
            db.episodeChapters().observeByEpisode(episodeId),
            autoMuteDb.dao().observeOffsetMapByEpisode(episodeId)
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

    suspend fun extractAndStoreId3FromFile(
        context: Context,
        db: AppDatabase,
        episodeId: String,
        uri: Uri,
        durationMs: Long = 0L
    ): List<EpisodeChapterModel> = withContext(Dispatchers.IO) {
        // ID3 ChapterFrame extraction runs live via PlaybackService onTracksChanged/onMetadata
        db.episodeChapters().getByEpisodeSync(episodeId)
    }
}
