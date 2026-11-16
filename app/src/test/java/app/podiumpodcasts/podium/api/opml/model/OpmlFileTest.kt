package app.podiumpodcasts.podium.api.opml.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OpmlFileTest {
    @Test
    fun `flattens nested Pocket Casts OPML outlines and deduplicates by xmlUrl`() {
        val body = OpmlBody(
            outlines = listOf(
                OpmlOutline(
                    text = "feeds",
                    outlines = listOf(
                        OpmlOutline(
                            type = "rss",
                            text = "99% Invisible",
                            xmlUrl = "https://feeds.simplecast.com/BqbsxVfO"
                        ),
                        OpmlOutline(
                            type = "rss",
                            text = "Decoder with Nilay Patel",
                            xmlUrl = "https://feeds.megaphone.fm/recodedecode"
                        ),
                        OpmlOutline(
                            type = "rss",
                            text = "Duplicate",
                            xmlUrl = "https://feeds.simplecast.com/BqbsxVfO"
                        )
                    )
                )
            )
        )

        val flattened = body.flattenedOutlines()
        assertEquals(2, flattened.size)
        assertEquals("99% Invisible", flattened[0].displayTitle)
        assertEquals("https://feeds.simplecast.com/BqbsxVfO", flattened[0].xmlUrl)
        assertEquals("Decoder with Nilay Patel", flattened[1].displayTitle)
    }
}
