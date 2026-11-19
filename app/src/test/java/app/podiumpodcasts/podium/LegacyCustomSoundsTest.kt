package app.podiumpodcasts.podium

import app.podiumpodcasts.podium.LegacyCustomSounds.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCustomSoundsTest {

    private val muteUri = "content://media/mute.mp3"
    private val skipUri = "content://media/skip.mp3"
    private val saved = CustomSoundPreset(id = "a1", name = "Ding", uri = "content://media/ding.mp3")

    @Test
    fun `nothing to migrate when there are no legacy sounds`() {
        assertEquals(Migration.UpToDate, LegacyCustomSounds.migrate(null, null, null))
        assertEquals(Migration.UpToDate, LegacyCustomSounds.migrate(LegacyCustomSounds.encode(listOf(saved)), null, null))
    }

    @Test
    fun `blank legacy uris are ignored`() {
        assertEquals(Migration.UpToDate, LegacyCustomSounds.migrate(null, "", "   "))
    }

    @Test
    fun `legacy mute sound is appended after the saved sounds`() {
        val result = LegacyCustomSounds.migrate(LegacyCustomSounds.encode(listOf(saved)), muteUri, null)

        val sounds = (result as Migration.Write).sounds
        assertEquals(listOf(saved.id, LegacyCustomSounds.muteId(muteUri)), sounds.map { it.id })
        assertEquals(LegacyCustomSounds.MUTE_NAME, sounds[1].name)
        assertEquals(muteUri, sounds[1].uri)
    }

    @Test
    fun `both legacy sounds are migrated into an empty list`() {
        val sounds = (LegacyCustomSounds.migrate(null, muteUri, skipUri) as Migration.Write).sounds

        assertEquals(
            listOf(
                CustomSoundPreset(LegacyCustomSounds.muteId(muteUri), LegacyCustomSounds.MUTE_NAME, muteUri),
                CustomSoundPreset(LegacyCustomSounds.skipId(skipUri), LegacyCustomSounds.SKIP_NAME, skipUri)
            ),
            sounds
        )
    }

    @Test
    fun `one uri used for both mute and skip is added once`() {
        val sounds = (LegacyCustomSounds.migrate("", muteUri, muteUri) as Migration.Write).sounds

        assertEquals(1, sounds.size)
        assertEquals(LegacyCustomSounds.muteId(muteUri), sounds.single().id)
    }

    @Test
    fun `sounds already in the list are not duplicated and keep their name`() {
        val renamed = CustomSoundPreset(id = LegacyCustomSounds.muteId(muteUri), name = "My bleep", uri = muteUri)
        val raw = LegacyCustomSounds.encode(listOf(saved, renamed))

        assertEquals(Migration.UpToDate, LegacyCustomSounds.migrate(raw, muteUri, null))
        assertEquals(listOf(saved, renamed), LegacyCustomSounds.withLegacy(listOf(saved, renamed), muteUri, null))
    }

    @Test
    fun `migration is idempotent`() {
        val first = (LegacyCustomSounds.migrate(null, muteUri, skipUri) as Migration.Write).sounds
        val second = LegacyCustomSounds.migrate(LegacyCustomSounds.encode(first), muteUri, skipUri)

        assertEquals(Migration.UpToDate, second)
    }

    @Test
    fun `an unreadable list is never overwritten`() {
        assertEquals(Migration.Unreadable, LegacyCustomSounds.migrate("{not json", muteUri, skipUri))
        assertNull(LegacyCustomSounds.parse("{not json"))
    }

    @Test
    fun `encoded list round-trips`() {
        val sounds = (LegacyCustomSounds.migrate(LegacyCustomSounds.encode(listOf(saved)), muteUri, skipUri) as Migration.Write).sounds
        val parsed = LegacyCustomSounds.parse(LegacyCustomSounds.encode(sounds))

        assertEquals(sounds, parsed)
        assertTrue(parsed!!.first() == saved)
    }

    @Test
    fun `custom mute uri is a leftover only while a built-in tone is in use`() {
        assertTrue(LegacyCustomSounds.isLeftoverMuteSelection("BEEP"))
        assertEquals(false, LegacyCustomSounds.isLeftoverMuteSelection("CUSTOM_SOUND"))
        assertEquals(false, LegacyCustomSounds.isLeftoverMuteSelection("HARD_SILENCE"))
        assertEquals(false, LegacyCustomSounds.isLeftoverMuteSelection(null))
    }
}
