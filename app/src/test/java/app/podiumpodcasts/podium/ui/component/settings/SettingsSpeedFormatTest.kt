package app.podiumpodcasts.podium.ui.component.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SettingsSpeedFormatTest {

    private fun format(speed: Float) = formatSettingsPlaybackSpeed(speed, Locale.US)

    @Test
    fun `quarter speeds keep two decimals`() {
        assertEquals("1.25x", format(1.25f))
        assertEquals("1.75x", format(1.75f))
    }

    @Test
    fun `tenth speeds keep one decimal`() {
        assertEquals("1.0x", format(1.0f))
        assertEquals("1.5x", format(1.5f))
        assertEquals("0.8x", format(0.8f))
        assertEquals("2.0x", format(2.0f))
    }

    @Test
    fun `speeds round to the nearest 0_05`() {
        assertEquals("1.25x", format(1.249f))
        assertEquals("1.3x", format(1.31f))
    }
}
