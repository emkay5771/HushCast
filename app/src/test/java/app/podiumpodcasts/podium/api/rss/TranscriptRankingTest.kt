package app.podiumpodcasts.podium.api.rss

import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.api.db.model.transcriptCandidates
import app.podiumpodcasts.podium.api.download.FeedTranscriptFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRankingTest {

    @Test
    fun `order is JSON then VTT then SRT then HTML then unknown`() {
        val refs = listOf(
            TranscriptRef("https://x/t.html", "text/html"),
            TranscriptRef("https://x/t.txt", "text/plain"),
            TranscriptRef("https://x/t.srt", "application/x-subrip"),
            TranscriptRef("https://x/t.json", "application/json"),
            TranscriptRef("https://x/t.vtt", "text/vtt")
        )
        val ordered = TranscriptRanking.order(refs).map { it.url }
        assertEquals(
            listOf("https://x/t.json", "https://x/t.vtt", "https://x/t.srt", "https://x/t.html", "https://x/t.txt"),
            ordered
        )
    }

    @Test
    fun `html is chosen when it is the only transcript`() {
        val ordered = TranscriptRanking.order(listOf(TranscriptRef("https://x/t.html", "text/html")))
        assertEquals("https://x/t.html", ordered.single().url)
    }

    @Test
    fun `blank or generic type falls back to the extension`() {
        assertEquals(TranscriptRanking.RANK_VTT, TranscriptRanking.rank("", "https://x/a.vtt?sig=1"))
        assertEquals(TranscriptRanking.RANK_JSON, TranscriptRanking.rank("text/plain", "https://x/a.json"))
        assertEquals(TranscriptRanking.RANK_SRT, TranscriptRanking.rank("application/octet-stream", "https://x/a.SRT"))
        assertEquals(TranscriptRanking.RANK_SRT, TranscriptRanking.rank("application/srt", ""))
        assertEquals(TranscriptRanking.RANK_UNKNOWN, TranscriptRanking.rank("", "https://x/transcript"))
    }

    @Test
    fun `ties keep feed order and duplicates or blanks are dropped`() {
        val ordered = TranscriptRanking.order(
            listOf(
                TranscriptRef("https://x/a.vtt", "text/vtt"),
                TranscriptRef("", "application/json"),
                TranscriptRef("https://x/b.vtt", "text/vtt"),
                TranscriptRef("https://x/a.vtt", "text/vtt")
            )
        ).map { it.url }
        assertEquals(listOf("https://x/a.vtt", "https://x/b.vtt"), ordered)
    }

    @Test
    fun `alternates round trip as a JSON array of url and type`() {
        val alternates = listOf(TranscriptRef("https://x/t.vtt", "text/vtt"), TranscriptRef("https://x/t.srt", ""))
        val json = TranscriptRanking.encodeAlternates(alternates)
        assertTrue(json.startsWith("["))
        assertTrue(json.contains("\"url\":\"https://x/t.vtt\""))
        assertTrue(json.contains("\"type\":\"text/vtt\""))
        assertEquals(alternates, TranscriptRanking.decodeAlternates(json))
        assertEquals("", TranscriptRanking.encodeAlternates(emptyList()))
        assertEquals(emptyList<TranscriptRef>(), TranscriptRanking.decodeAlternates("not json"))
    }

    @Test
    fun `transcriptCandidates gives the primary first then the alternates`() {
        val episode = episode(
            transcriptUrl = "https://x/t.json",
            transcriptType = "application/json",
            alternates = TranscriptRanking.encodeAlternates(
                listOf(
                    TranscriptRef("https://x/t.vtt", "text/vtt"),
                    TranscriptRef("https://x/t.json", "application/json"),
                    TranscriptRef("https://x/t.html", "text/html")
                )
            )
        )
        assertEquals(
            listOf(
                "https://x/t.json" to "application/json",
                "https://x/t.vtt" to "text/vtt",
                "https://x/t.html" to "text/html"
            ),
            episode.transcriptCandidates()
        )
        assertEquals(emptyList<Pair<String, String>>(), episode(transcriptUrl = "", alternates = "").transcriptCandidates())
    }

    @Test
    fun `analysis candidates skip html and fill a missing type from the extension`() {
        val usable = FeedTranscriptFetcher.analysisCandidates(
            listOf(
                "https://x/t.html" to "text/html",
                "https://x/t.vtt" to "",
                "https://x/t.json" to "application/json"
            )
        )
        assertEquals(listOf("https://x/t.vtt" to "text/vtt", "https://x/t.json" to "application/json"), usable)
    }

    @Test
    fun `withFeedExtras stores the alternates of a new episode`() {
        val extra = RssEpisodeExtras(
            transcriptUrl = "https://x/t.json",
            transcriptType = "application/json",
            transcriptAlternates = listOf(TranscriptRef("https://x/t.vtt", "text/vtt"))
        )
        val filled = episode(transcriptUrl = "https://x/t.json", alternates = "").withFeedExtras(extra)
        assertEquals(extra.transcriptAlternatesJson, filled.transcriptAlternatesJson)
        // A different primary (stale model) is left alone.
        val other = episode(transcriptUrl = "https://x/other.json", alternates = "").withFeedExtras(extra)
        assertEquals("", other.transcriptAlternatesJson)
    }

    private fun episode(
        transcriptUrl: String,
        transcriptType: String = "",
        alternates: String
    ) = PodcastEpisodeModel(
        id = "o:1",
        guid = "1",
        origin = "o",
        link = "",
        title = "t",
        description = "",
        imageUrl = null,
        author = "",
        pubDate = 0L,
        duration = 0,
        audioUrl = "https://x/a.mp3",
        podcastTitle = "p",
        imageSeedColor = 0,
        transcriptUrl = transcriptUrl,
        transcriptType = transcriptType,
        transcriptAlternatesJson = alternates
    )
}
