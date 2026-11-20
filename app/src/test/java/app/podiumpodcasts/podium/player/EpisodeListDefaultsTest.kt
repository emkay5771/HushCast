package app.podiumpodcasts.podium.player

import app.podiumpodcasts.podium.EpisodeListDefaults
import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesOrder
import app.podiumpodcasts.podium.api.db.dao.SeasonOrder
import app.podiumpodcasts.podium.ui.component.model.podcast.PodcastSearchFilterOrderBarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeListDefaultsTest {

    @Test
    fun `group by season with newest season first and oldest episode order`() {
        val defaults = EpisodeListDefaults(
            order = PodcastEpisodesOrder.ASCENDING,
            groupBySeason = true,
            seasonOrder = SeasonOrder.DESCENDING,
            serialDefaults = true
        )
        val state = PodcastSearchFilterOrderBarState(isSerial = false, defaults = defaults)
        state.applySeasonGroupingDefault()

        assertTrue(state.groupBySeason.value)
        assertEquals(PodcastEpisodesOrder.ASCENDING, state.order.value)
        assertEquals(PodcastEpisodesOrder.DESCENDING, state.effectiveSeasonOrder)
    }

    @Test
    fun `season order defaults to matching episode order and can be toggled`() {
        val state = PodcastSearchFilterOrderBarState(
            isSerial = false,
            defaults = EpisodeListDefaults(seasonOrder = SeasonOrder.MATCH_EPISODE)
        )
        assertEquals(PodcastEpisodesOrder.DESCENDING, state.effectiveSeasonOrder)
        state.setOrder(PodcastEpisodesOrder.ASCENDING)
        assertEquals(PodcastEpisodesOrder.ASCENDING, state.effectiveSeasonOrder)

        // Explicit toggle overrides MATCH_EPISODE
        state.toggleSeasonOrder()
        assertEquals(PodcastEpisodesOrder.DESCENDING, state.seasonOrderOverride.value)
        assertEquals(PodcastEpisodesOrder.DESCENDING, state.effectiveSeasonOrder)
    }

    @Test
    fun `serial defaults can be disabled so global defaults win`() {
        val defaults = EpisodeListDefaults(
            order = PodcastEpisodesOrder.DESCENDING,
            groupBySeason = false,
            seasonOrder = SeasonOrder.DESCENDING,
            serialDefaults = false
        )
        val state = PodcastSearchFilterOrderBarState(isSerial = false, defaults = defaults)
        state.applySerialDefault()
        state.applySeasonGroupingDefault()

        assertEquals(PodcastEpisodesOrder.DESCENDING, state.order.value)
        assertFalse(state.groupBySeason.value)
    }

    @Test
    fun `late arriving settings defaults do not override user changes`() {
        val state = PodcastSearchFilterOrderBarState(isSerial = false)
        state.setOrder(PodcastEpisodesOrder.ASCENDING)
        state.setGroupBySeason(false)

        state.applySeasonGroupingDefault()
        state.applyDefaults(
            EpisodeListDefaults(
                order = PodcastEpisodesOrder.DESCENDING,
                groupBySeason = true,
                seasonOrder = SeasonOrder.DESCENDING
            )
        )

        assertEquals(PodcastEpisodesOrder.ASCENDING, state.order.value)
        assertFalse(state.groupBySeason.value)
        assertNull(state.seasonOrderOverride.value)
        assertEquals(PodcastEpisodesOrder.DESCENDING, state.effectiveSeasonOrder)
    }
}
