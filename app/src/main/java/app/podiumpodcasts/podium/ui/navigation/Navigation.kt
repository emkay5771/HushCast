package app.podiumpodcasts.podium.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.DetailPaneKey
import app.podiumpodcasts.podium.ui.route.add.AddPodcastRoute
import app.podiumpodcasts.podium.ui.route.content.ContinuePlayingRoute
import app.podiumpodcasts.podium.ui.route.content.LocallyAvailableRoute
import app.podiumpodcasts.podium.ui.route.content.NewEpisodesRoute
import app.podiumpodcasts.podium.ui.route.content.SubscriptionsRoute
import app.podiumpodcasts.podium.ui.route.discover.DiscoverRoute
import app.podiumpodcasts.podium.ui.route.downloads.DownloadsRoute
import app.podiumpodcasts.podium.ui.route.history.HistoryRoute
import app.podiumpodcasts.podium.ui.route.home.HomeRoute
import app.podiumpodcasts.podium.ui.route.importing.OpmlImportingRoute
import app.podiumpodcasts.podium.ui.route.library.LibraryRoute
import app.podiumpodcasts.podium.ui.route.licenses.LicensesRoute
import app.podiumpodcasts.podium.ui.route.list.ListRoute
import app.podiumpodcasts.podium.ui.route.restore.RestoreRoute
import app.podiumpodcasts.podium.ui.route.settings.SettingsRoute
import kotlinx.serialization.Serializable

// Routes
@Serializable
open class PodiumNavKey(
    val showNavBar: Boolean = true,
    val showMediaPlayer: Boolean = true
) : NavKey

@Serializable
data object Home : PodiumNavKey()
@Serializable
data object Discover : PodiumNavKey()
@Serializable
data object Library : PodiumNavKey()

@Serializable
data object History : PodiumNavKey(showNavBar = false)
@Serializable
data object Downloads : PodiumNavKey(showNavBar = false)
@Serializable
data object Subscriptions : PodiumNavKey(showNavBar = false)
@Serializable
data object ContinuePlaying : PodiumNavKey(showNavBar = false)
@Serializable
data object NewEpisodes : PodiumNavKey(showNavBar = false)
@Serializable
data object LocallyAvailable : PodiumNavKey(showNavBar = false)

@Serializable
data class List(val listId: Int) : PodiumNavKey(showNavBar = false)

@Serializable
data object AddPodcast : PodiumNavKey(showNavBar = false, showMediaPlayer = false)
@Serializable
data object Settings : PodiumNavKey(showNavBar = false, showMediaPlayer = false)
@Serializable
data object Licenses : PodiumNavKey(showNavBar = false, showMediaPlayer = false)
@Serializable
data object Restore : PodiumNavKey(showNavBar = false, showMediaPlayer = false)

@Serializable
data object OpmlImporting : PodiumNavKey(showNavBar = false, showMediaPlayer = false)
@Serializable
data object Unknown : PodiumNavKey()

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun Navigation(
    backStack: NavBackStack<NavKey>,

    onOpenPane: (key: DetailPaneKey) -> Unit,
    onClosePane: () -> Unit,

    onBack: () -> Unit = { },

    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit
) {
    val popOrHome: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        } else if (backStack.lastOrNull() != Home) {
            backStack.clear()
            backStack.add(Home)
        }
    }

    NavDisplay(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        backStack = backStack,
        onBack = onBack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator()
        ),
        transitionSpec = {
            (fadeIn(animationSpec = tween(180)) +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                .togetherWith(fadeOut(animationSpec = tween(150)))
        },
        popTransitionSpec = {
            (fadeIn(initialAlpha = 0.85f, animationSpec = tween(180)) +
                    slideInHorizontally(animationSpec = tween(180)) { -it / 12 })
                .togetherWith(
                    fadeOut(animationSpec = tween(180)) +
                            scaleOut(targetScale = 0.90f, animationSpec = tween(180))
                ).apply {
                    targetContentZIndex = -1f
                }
        },
        predictivePopTransitionSpec = {
            (fadeIn(initialAlpha = 0.85f, animationSpec = tween(180)) +
                    slideInHorizontally(animationSpec = tween(180)) { -it / 12 })
                .togetherWith(
                    fadeOut(animationSpec = tween(180)) +
                            scaleOut(targetScale = 0.90f, animationSpec = tween(180))
                ).apply {
                    targetContentZIndex = -1f
                }
        },
        entryProvider = { key ->
            when(key) {
                is Home -> NavEntry(key) {
                    HomeRoute(
                        onSettings = { backStack.add(Settings) },

                        onClickSubscriptions = { backStack.add(Subscriptions) },
                        onClickContinuePlaying = { backStack.add(ContinuePlaying) },
                        onClickNewEpisodes = { backStack.add(NewEpisodes) },
                        onClickLocallyAvailable = { backStack.add(LocallyAvailable) },

                        onClickAddPodcast = { backStack.add(AddPodcast) },

                        onClickDiscover = { backStack.add(Discover) },

                        onClickPodcast = onClickPodcast,
                        onClickEpisode = onClickEpisode
                    )
                }

                is Discover -> NavEntry(key) {
                    DiscoverRoute(
                        onClickPodcast = { podcast -> onClickPodcast(podcast) },
                        onClickAddPodcast = { backStack.add(AddPodcast) }
                    )
                }

                is Library -> NavEntry(key) {
                    LibraryRoute(
                        onClickDownloads = { backStack.add(Downloads) },
                        onClickHistory = { backStack.add(History) },
                        onClickContinuePlaying = { backStack.add(ContinuePlaying) },
                        onClickNewEpisodes = { backStack.add(NewEpisodes) },

                        onClickPodcast = onClickPodcast,
                        onClickEpisode = onClickEpisode,

                        onClickList = {
                            backStack.add(List(it))
                        }
                    )
                }

                is History -> NavEntry(key) {
                    HistoryRoute(
                        onClickEpisode = onClickEpisode
                    ) {
                        popOrHome()
                    }
                }

                is Downloads -> NavEntry(key) {
                    DownloadsRoute(
                        onClickEpisode = onClickEpisode
                    ) {
                        popOrHome()
                    }
                }

                is Subscriptions -> NavEntry(key) {
                    SubscriptionsRoute(
                        onClickPodcast = onClickPodcast
                    ) {
                        popOrHome()
                    }
                }

                is ContinuePlaying -> NavEntry(key) {
                    ContinuePlayingRoute(
                        onClickEpisode = onClickEpisode
                    ) {
                        popOrHome()
                    }
                }

                is NewEpisodes -> NavEntry(key) {
                    NewEpisodesRoute(
                        onClickEpisode = onClickEpisode
                    ) {
                        popOrHome()
                    }
                }

                is LocallyAvailable -> NavEntry(key) {
                    LocallyAvailableRoute(
                        onClickPodcast = onClickPodcast
                    ) {
                        popOrHome()
                    }
                }


                is List -> NavEntry(key) {
                    ListRoute(
                        listId = key.listId,
                        onClickPodcast = onClickPodcast,
                        onClickEpisode = onClickEpisode
                    ) {
                        popOrHome()
                    }
                }


                is AddPodcast -> NavEntry(key) {
                    AddPodcastRoute(
                        onOpenPodcast = onClickPodcast
                    ) {
                        backStack.removeLastOrNull()
                    }
                }

                is Settings -> NavEntry(key) {
                    SettingsRoute(
                        onLicenses = {
                            backStack.add(Licenses)
                        },
                        onPane = {
                            onOpenPane(
                                app.podiumpodcasts.podium.ui.DetailPaneKey.SettingsKey(it)
                            )
                        }
                    ) {
                        onClosePane()
                        backStack.removeLastOrNull()
                    }
                }

                is Licenses -> NavEntry(key) {
                    LicensesRoute {
                        backStack.removeLastOrNull()
                    }
                }

                is Restore -> NavEntry(key) {
                    RestoreRoute()
                }

                is OpmlImporting -> NavEntry(key) {
                    OpmlImportingRoute {
                        backStack.removeLastOrNull()
                    }
                }


                else -> NavEntry(Unknown) {
                    Text("Unknown route")
                }
            }
        }
    )
}