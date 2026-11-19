package app.podiumpodcasts.podium.ui.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Turns the saved startup destination ("HOME", "DISCOVER", "LIST:12", …) into a nav key.
 * Anything unknown or broken falls back to [Home].
 */
fun parseStartupDestination(raw: String?): NavKey {
    val dest = raw?.trim().orEmpty()
    if (dest.startsWith("LIST:")) {
        val id = dest.removePrefix("LIST:").toIntOrNull()
        return if (id != null) List(id) else Home
    }
    return runCatching { NavBarItems.valueOf(dest).navKey }.getOrNull() ?: Home
}

/** What Back should do with the list-pane back stack. */
sealed interface BackAction {
    /** Drop the top entry. */
    data object Pop : BackAction

    /** Replace the whole stack with this root (another tab). */
    data class ReplaceRoot(val key: NavKey) : BackAction

    /** Nothing to do in the app: let the system handle it (leave the app). */
    data object None : BackAction
}

/**
 * B6: system Back.  Deeper screens pop; a root screen that isn't the startup destination goes to
 * the startup destination (not always Home); the startup destination itself leaves the app.
 */
fun resolveSystemBack(backStack: kotlin.collections.List<NavKey>, startKey: NavKey): BackAction {
    if (backStack.size > 1) return BackAction.Pop
    val current = backStack.lastOrNull() ?: return BackAction.None
    return if (current != startKey) BackAction.ReplaceRoot(startKey) else BackAction.None
}

/**
 * The back arrow in a screen's own top bar.  Same as [resolveSystemBack], except that on the
 * startup destination it still goes Home (an arrow should never close the app), unless that
 * screen already is Home.
 */
fun resolveUpButton(backStack: kotlin.collections.List<NavKey>, startKey: NavKey): BackAction {
    return when (val system = resolveSystemBack(backStack, startKey)) {
        BackAction.None -> {
            val current = backStack.lastOrNull()
            if (current != null && current != Home) BackAction.ReplaceRoot(Home) else BackAction.None
        }
        else -> system
    }
}

/**
 * M2: a switch between top-level destinations (a tab tap, or Back from a tab to the startup tab)
 * rather than a push or pop inside one tab.  The target is a root (nothing under it) and it isn't
 * the screen we're returning to by popping.
 */
fun isTopLevelSwitch(
    initialKey: Any,
    initialPreviousKeys: kotlin.collections.List<Any>,
    targetKey: Any,
    targetPreviousKeys: kotlin.collections.List<Any>
): Boolean {
    if (initialKey == targetKey) return false
    if (targetPreviousKeys.isNotEmpty()) return false
    return targetKey !in initialPreviousKeys
}
