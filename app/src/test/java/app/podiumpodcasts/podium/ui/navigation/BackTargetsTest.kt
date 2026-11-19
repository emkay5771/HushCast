package app.podiumpodcasts.podium.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackTargetsTest {

    @Test
    fun `parses the saved startup destination`() {
        assertEquals(Home, parseStartupDestination("HOME"))
        assertEquals(Discover, parseStartupDestination("DISCOVER"))
        assertEquals(Library, parseStartupDestination("LIBRARY"))
        assertEquals(NewEpisodes, parseStartupDestination("NEW_EPISODES"))
        assertEquals(List(12), parseStartupDestination("LIST:12"))
    }

    @Test
    fun `unknown or broken startup destinations fall back to Home`() {
        assertEquals(Home, parseStartupDestination(null))
        assertEquals(Home, parseStartupDestination(""))
        assertEquals(Home, parseStartupDestination("NOPE"))
        assertEquals(Home, parseStartupDestination("LIST:abc"))
    }

    @Test
    fun `system back pops deeper screens`() {
        assertEquals(BackAction.Pop, resolveSystemBack(listOf(Library, Downloads), startKey = Home))
    }

    @Test
    fun `system back on another root goes to the startup destination, not always Home`() {
        assertEquals(BackAction.ReplaceRoot(Library), resolveSystemBack(listOf(Discover), startKey = Library))
        assertEquals(BackAction.ReplaceRoot(Library), resolveSystemBack(listOf(Home), startKey = Library))
        assertEquals(BackAction.ReplaceRoot(Home), resolveSystemBack(listOf(Discover), startKey = Home))
        assertEquals(BackAction.ReplaceRoot(List(3)), resolveSystemBack(listOf(Discover), startKey = List(3)))
    }

    @Test
    fun `system back on the startup destination leaves the app`() {
        assertEquals(BackAction.None, resolveSystemBack(listOf(Home), startKey = Home))
        assertEquals(BackAction.None, resolveSystemBack(listOf(List(3)), startKey = List(3)))
        assertEquals(BackAction.None, resolveSystemBack(emptyList(), startKey = Home))
    }

    @Test
    fun `the up arrow never leaves the app`() {
        assertEquals(BackAction.Pop, resolveUpButton(listOf(Home, NewEpisodes), startKey = Home))
        assertEquals(BackAction.ReplaceRoot(Library), resolveUpButton(listOf(Downloads), startKey = Library))
        // On the startup destination itself the arrow goes Home…
        assertEquals(BackAction.ReplaceRoot(Home), resolveUpButton(listOf(List(3)), startKey = List(3)))
        // …and does nothing on Home.
        assertEquals(BackAction.None, resolveUpButton(listOf(Home), startKey = Home))
    }

    @Test
    fun `a tab tap is a top-level switch`() {
        assertTrue(isTopLevelSwitch("Home", emptyList(), "Discover", emptyList()))
        // From deep inside a tab to another tab's root.
        assertTrue(isTopLevelSwitch("Downloads", listOf("Library"), "Home", emptyList()))
    }

    @Test
    fun `pushes and pops inside a tab are not top-level switches`() {
        // Push.
        assertFalse(isTopLevelSwitch("Library", emptyList(), "Downloads", listOf("Library")))
        // Pop back to the tab's root.
        assertFalse(isTopLevelSwitch("Downloads", listOf("Library"), "Library", emptyList()))
        // Same screen.
        assertFalse(isTopLevelSwitch("Home", emptyList(), "Home", emptyList()))
    }
}
