package app.hushcast.automute

import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.WordIndexBlob
import org.junit.Assert.assertEquals
import org.junit.Test

class BlobCodecTest {

    @Test
    fun roundTripsCompressedWordIndexBlob() {
        val blob = WordIndexBlob(
            words = listOf(Word(0.5, 0.9, " hello"), Word(1.0, 1.4, " world")),
            segments = listOf(Segment(0.5, 1.4, "hello world"))
        )
        val compressed = BlobCodec.compress(blob)
        val restored = BlobCodec.decompress(compressed)
        assertEquals(blob, restored)
    }
}
