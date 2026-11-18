package app.podiumpodcasts.podium.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import app.podiumpodcasts.podium.R
import kotlinx.serialization.Serializable

@Serializable
enum class NavBarItems(
    val label: Int,
    val icon: ImageVector,
    val navKey: NavKey,
    val displayTitle: String
) {
    HOME(R.string.route_home, Icons.Rounded.Home, Home, "Podcasts"),
    DISCOVER(R.string.route_discover, Icons.Rounded.Explore, Discover, "Discover"),
    LIBRARY(R.string.route_library, Icons.Rounded.CollectionsBookmark, Library, "Library"),
    NEW_EPISODES(R.string.route_new_episodes, Icons.Rounded.NewReleases, NewEpisodes, "New Releases"),
    CONTINUE_PLAYING(R.string.route_continue_playing, Icons.Rounded.Pause, ContinuePlaying, "In Progress"),
    DOWNLOADS(R.string.route_downloads, Icons.Rounded.Download, Downloads, "Downloads"),
    HISTORY(R.string.route_history, Icons.Rounded.History, History, "History");

    companion object {
        val DEFAULT_ITEMS = listOf(HOME, DISCOVER, LIBRARY)

        fun parseList(raw: String): kotlin.collections.List<NavBarItems> {
            val parsed = raw.split(",")
                .mapNotNull { name -> runCatching { valueOf(name.trim()) }.getOrNull() }
                .distinct()
            return if (parsed.size >= 2) parsed.take(5) else DEFAULT_ITEMS
        }

        fun serializeList(items: kotlin.collections.List<NavBarItems>): String {
            return items.distinct().take(5).joinToString(",") { it.name }
        }
    }
}