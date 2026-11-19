package app.podiumpodcasts.podium.player

import app.podiumpodcasts.podium.background.PlaybackSpeedResolver
import app.podiumpodcasts.podium.background.PlaybackSpeedResolver.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedResolverTest {

    @Test
    fun `podcast speed wins over the global speed`() {
        assertEquals(1.5f, PlaybackSpeedResolver.resolve(podcastSpeed = 1.5f, globalSpeed = 1.2f), 0.0001f)
    }

    @Test
    fun `no podcast speed falls back to the global speed`() {
        assertEquals(1.2f, PlaybackSpeedResolver.resolve(podcastSpeed = 0f, globalSpeed = 1.2f), 0.0001f)
        assertEquals(1.2f, PlaybackSpeedResolver.resolve(podcastSpeed = null, globalSpeed = 1.2f), 0.0001f)
        assertEquals(1.2f, PlaybackSpeedResolver.resolve(podcastSpeed = -1f, globalSpeed = 1.2f), 0.0001f)
        assertEquals(1.2f, PlaybackSpeedResolver.resolve(podcastSpeed = Float.NaN, globalSpeed = 1.2f), 0.0001f)
    }

    @Test
    fun `missing or invalid global speed falls back to normal speed`() {
        assertEquals(1f, PlaybackSpeedResolver.resolve(podcastSpeed = 0f, globalSpeed = null), 0.0001f)
        assertEquals(1f, PlaybackSpeedResolver.resolve(podcastSpeed = 0f, globalSpeed = 0f), 0.0001f)
        assertEquals(1f, PlaybackSpeedResolver.resolve(podcastSpeed = null, globalSpeed = Float.NaN), 0.0001f)
    }

    @Test
    fun `override detection treats zero as unset`() {
        assertTrue(PlaybackSpeedResolver.hasOverride(0.8f))
        assertFalse(PlaybackSpeedResolver.hasOverride(0f))
        assertFalse(PlaybackSpeedResolver.hasOverride(null))
    }

    @Test
    fun `speed changes are saved to the podcast only when it has its own speed`() {
        assertEquals(Scope.PODCAST, PlaybackSpeedResolver.scopeFor("https://feed", 1.3f))
        assertEquals(Scope.GLOBAL, PlaybackSpeedResolver.scopeFor("https://feed", 0f))
        assertEquals(Scope.GLOBAL, PlaybackSpeedResolver.scopeFor("https://feed", null))
        assertEquals(Scope.GLOBAL, PlaybackSpeedResolver.scopeFor(null, 1.3f))
        assertEquals(Scope.GLOBAL, PlaybackSpeedResolver.scopeFor("", 1.3f))
    }
}
