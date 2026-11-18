package app.podiumpodcasts.podium.ui

import android.annotation.SuppressLint
import android.os.Parcelable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import app.podiumpodcasts.podium.ui.component.common.SwitchableDynamicMaterialExpressiveTheme
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayer
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerShown
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.MediaPlayerBottomSheet
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.MediaPlayerQueueBottomSheet
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.navigation.Home
import app.podiumpodcasts.podium.ui.navigation.NavBarScaffold
import app.podiumpodcasts.podium.ui.navigation.Navigation
import app.podiumpodcasts.podium.ui.navigation.PodiumNavKey
import app.podiumpodcasts.podium.ui.route.settings.SettingsPane
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import app.podiumpodcasts.podium.ui.view.model.PodcastDetailView
import app.podiumpodcasts.podium.ui.view.model.PodcastEpisodeDetailView
import app.podiumpodcasts.podium.ui.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
sealed class DetailPaneKey : Parcelable {
    @Serializable
    @Parcelize
    data class PodcastKey(
        val origin: String
    ) : DetailPaneKey()

    @Serializable
    @Parcelize
    data class PodcastEpisodeKey(
        val episodeOrigin: String,
        val episodeId: String
    ) : DetailPaneKey()

    @Serializable
    @Parcelize
    data class EpisodeKey(
        val episodeOrigin: String,
        val episodeId: String
    ) : DetailPaneKey()

    @Serializable
    @Parcelize
    data class SettingsKey(
        val pane: SettingsPaneKey
    ) : DetailPaneKey()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun enterSpec(): androidx.compose.animation.EnterTransition {
    val spatial = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = androidx.compose.material3.MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return androidx.compose.animation.slideInHorizontally(animationSpec = spatial) { it / 12 } +
        fadeIn(animationSpec = effects)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun listEnterSpec(): androidx.compose.animation.EnterTransition {
    val spatial = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    return androidx.compose.animation.slideInHorizontally(animationSpec = spatial) { -it / 16 }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun exitSpec(): androidx.compose.animation.ExitTransition {
    val effects = androidx.compose.material3.MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return fadeOut(targetAlpha = 0.94f, animationSpec = effects)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun detailExitSpec(): androidx.compose.animation.ExitTransition {
    val spatial = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = androidx.compose.material3.MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return androidx.compose.animation.slideOutHorizontally(animationSpec = spatial) { it / 10 } +
        fadeOut(targetAlpha = 0.0f, animationSpec = effects)
}


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(
    ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun Main(
    deepLink: DeepLink?
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val vm = viewModel(key = "MAIN") {
        MainViewModel(
            db = db,
            defaultShowMediaPlayerBottomSheet = false
        )
    }
    val playerVm = viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
    LaunchedEffect(playerVm.openPlayerSheetNonce) {
        if (playerVm.openPlayerSheetNonce > 0L) {
            vm.showMediaPlayerBottomSheet.value = true
            playerVm.openPlayerSheetNonce = 0L
        }
    }

    val settingsRepository = LocalSettingsRepository.current
    val bottomBarRaw = settingsRepository.appearance.bottomBarItemsRaw
        .collectAsState("HOME,DISCOVER,LIBRARY")
    val enabledBottomBarNavKeys = remember(bottomBarRaw.value) {
        app.podiumpodcasts.podium.ui.navigation.NavBarItems.parseList(bottomBarRaw.value)
            .map { it.navKey }
            .toSet()
    }

    val initialRoute = remember(deepLink) {
        if (deepLink?.route != null) {
            deepLink.route
        } else {
            val dest = kotlinx.coroutines.runBlocking {
                settingsRepository.appearance.startupDestination.first()
            }
            when {
                dest.startsWith("LIST:") -> {
                    val id = dest.removePrefix("LIST:").toIntOrNull()
                    if (id != null) app.podiumpodcasts.podium.ui.navigation.List(id) else Home
                }
                else -> {
                    runCatching {
                        app.podiumpodcasts.podium.ui.navigation.NavBarItems.valueOf(dest).navKey
                    }.getOrNull() ?: Home
                }
            }
        }
    }

    val backStack = rememberNavBackStack(initialRoute)

    val listDetailNavigator = rememberListDetailPaneScaffoldNavigator<DetailPaneKey>()

    val navigationScaffoldState = rememberNavigationSuiteScaffoldState()
    val rawLayoutType =
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val navigationScaffoldLayoutType =
        if (rawLayoutType == NavigationSuiteType.NavigationBar) {
            NavigationSuiteType.ShortNavigationBarCompact
        } else {
            rawLayoutType
        }

    fun listDetailBack() {
        scope.launch {
            if(!listDetailNavigator.canNavigateBack(BackNavigationBehavior.PopUntilScaffoldValueChange)) return@launch
            listDetailNavigator.navigateBack(
                BackNavigationBehavior.PopUntilScaffoldValueChange
            )
        }
    }

    LaunchedEffect(Unit) {
        if(deepLink?.detailPaneKey != null)
            listDetailNavigator.navigateTo(ThreePaneScaffoldRole.Primary, deepLink.detailPaneKey)

        if(deepLink?.showMediaPlayerBottomSheet == true) {
            delay(500)
            vm.showMediaPlayerBottomSheet.value = true
        }
    }

    LaunchedEffect(backStack.last(), enabledBottomBarNavKeys, listDetailNavigator.scaffoldValue.primary) {
        if(navigationScaffoldLayoutType != NavigationSuiteType.ShortNavigationBarCompact &&
            navigationScaffoldLayoutType != NavigationSuiteType.NavigationBar) return@LaunchedEffect

        val entry = backStack.last()
        if(entry is PodiumNavKey) {
            vm.hideFloatingMediaPlayer.value = !entry.showMediaPlayer

            val showForCustomTab = entry in enabledBottomBarNavKeys ||
                (backStack.size == 1 && entry is app.podiumpodcasts.podium.ui.navigation.List)
            val shouldNavBeVisible =
                (entry.showNavBar || showForCustomTab) &&
                    listDetailNavigator.scaffoldValue.primary == PaneAdaptedValue.Hidden

            val isNavVisible =
                navigationScaffoldState.currentValue == NavigationSuiteScaffoldValue.Visible
            if(shouldNavBeVisible == isNavVisible) return@LaunchedEffect

            if(shouldNavBeVisible) {
                navigationScaffoldState.show()
            } else {
                navigationScaffoldState.hide()
            }
        }
    }

    val floatingMediaPlayerShown = remember { mutableStateOf(false) }
    val floatingMediaPlayerHeightAnimation = remember { Animatable(0f) }
    val showQueueBottomSheet = remember { mutableStateOf(false) }

    NavBarScaffold(
        layoutType = navigationScaffoldLayoutType,
        state = navigationScaffoldState,
        onClickItem = {
            if (backStack.lastOrNull() != it.navKey) {
                backStack.clear()
                backStack.add(it.navKey)
            }
            listDetailBack()
        },
        currentNavKey = { backStack.last() }
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
        ) {
            CompositionLocalProvider(
                LocalFloatingMediaPlayerHeight provides (FloatingMediaPlayerHeight + 8.dp)
                        * floatingMediaPlayerHeightAnimation.value,
                LocalFloatingMediaPlayerShown provides floatingMediaPlayerShown.value
            ) {
                androidx.activity.compose.BackHandler(
                    enabled = backStack.size == 1 &&
                        backStack.lastOrNull() != Home &&
                        !listDetailNavigator.canNavigateBack(BackNavigationBehavior.PopUntilScaffoldValueChange)
                ) {
                    backStack.clear()
                    backStack.add(Home)
                }
                NavigableListDetailPaneScaffold(
                    defaultBackBehavior = BackNavigationBehavior.PopLatest,
                    navigator = listDetailNavigator,
                    listPane = {
                        AnimatedPane(
                            enterTransition = listEnterSpec(),
                            exitTransition = exitSpec()
                        ) {
                            Navigation(
                                backStack = backStack,

                                onOpenPane = {
                                    scope.launch {
                                        listDetailNavigator.navigateTo(
                                            pane = ThreePaneScaffoldRole.Primary,
                                            contentKey = it
                                        )
                                    }
                                },
                                onClosePane = {
                                    if(listDetailNavigator.canNavigateBack(BackNavigationBehavior.PopUntilScaffoldValueChange)) {
                                        scope.launch {
                                            listDetailNavigator.navigateBack(
                                                BackNavigationBehavior.PopUntilScaffoldValueChange
                                            )
                                        }
                                    }
                                },

                                onBack = {
                                    if(listDetailNavigator.canNavigateBack(BackNavigationBehavior.PopUntilScaffoldValueChange)) {
                                        listDetailBack()
                                    } else if (backStack.size > 1) {
                                        backStack.removeLastOrNull()
                                    } else if (backStack.lastOrNull() != Home) {
                                        backStack.clear()
                                        backStack.add(Home)
                                    }
                                },

                                onClickPodcast = { origin ->
                                    scope.launch {
                                        listDetailNavigator.navigateTo(
                                            pane = ThreePaneScaffoldRole.Primary,
                                            contentKey = DetailPaneKey.PodcastKey(
                                                origin = origin
                                            )
                                        )
                                    }
                                },
                                onClickEpisode = { episode ->
                                    scope.launch {
                                        listDetailNavigator.navigateTo(
                                            pane = ThreePaneScaffoldRole.Primary,
                                            contentKey = DetailPaneKey.EpisodeKey(
                                                episodeOrigin = episode.origin,
                                                episodeId = episode.id
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    },
                    detailPane = {
                        DetailPane(
                            vm = vm,
                            scaffoldNavigator = listDetailNavigator,
                            backStack = backStack
                        )
                    },
                    paneExpansionState = rememberPaneExpansionState(
                        keyProvider = listDetailNavigator.scaffoldValue,
                        anchors = listOf(
                            PaneExpansionAnchor.Proportion(0f),
                            PaneExpansionAnchor.Proportion(0.3f),
                            PaneExpansionAnchor.Proportion(0.5f),
                            PaneExpansionAnchor.Proportion(0.7f),
                        )
                    ),
                    paneExpansionDragHandle = { state ->
                        val interactionSource = remember { MutableInteractionSource() }
                        VerticalDragHandle(
                            modifier = Modifier.paneExpansionDraggable(
                                state,
                                LocalMinimumInteractiveComponentSize.current,
                                interactionSource
                            ),
                            interactionSource = interactionSource
                        )
                    }
                )
            }

            val isNavHidden = navigationScaffoldState.currentValue == NavigationSuiteScaffoldValue.Hidden
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .then(if (isNavHidden) Modifier.navigationBarsPadding() else Modifier)
                    .padding(bottom = 8.dp, end = 16.dp)
            ) {
                FloatingMediaPlayer(
                    hide = vm.hideFloatingMediaPlayer.value,
                    showMediaPlayerBottomSheet = vm.showMediaPlayerBottomSheet.value,
                    onMediaPlayerShownChange = {
                        floatingMediaPlayerShown.value = it

                        scope.launch {
                            floatingMediaPlayerHeightAnimation.animateTo(
                                if (it) 1f else 0f
                            )
                        }
                    },
                    onOpenQueue = {
                        showQueueBottomSheet.value = true
                    },
                    onClick = {
                        vm.showMediaPlayerBottomSheet.value = true
                    }
                )
            }
        }

        if(vm.showMediaPlayerBottomSheet.value) MediaPlayerBottomSheet(
            onOpenEpisode = { origin, id ->
                vm.showMediaPlayerBottomSheet.value = false

                scope.launch {
                    listDetailNavigator.navigateTo(
                        pane = ThreePaneScaffoldRole.Primary,
                        contentKey = DetailPaneKey.EpisodeKey(
                            episodeOrigin = origin,
                            episodeId = id
                        )
                    )
                }
            },
            onOpenPodcast = { origin ->
                vm.showMediaPlayerBottomSheet.value = false

                scope.launch {
                    listDetailNavigator.navigateTo(
                        pane = ThreePaneScaffoldRole.Primary,
                        contentKey = DetailPaneKey.PodcastKey(
                            origin = origin
                        )
                    )
                }
            },
            onDismiss = { vm.showMediaPlayerBottomSheet.value = false }
        )

        if(showQueueBottomSheet.value) MediaPlayerQueueBottomSheet(
            onOpenEpisode = { origin, id ->
                showQueueBottomSheet.value = false

                scope.launch {
                    listDetailNavigator.navigateTo(
                        pane = ThreePaneScaffoldRole.Primary,
                        contentKey = DetailPaneKey.EpisodeKey(
                            episodeOrigin = origin,
                            episodeId = id
                        )
                    )
                }
            },
            onDismiss = { showQueueBottomSheet.value = false }
        )
    }
}

@OptIn(
    ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
private fun ThreePaneScaffoldPaneScope.DetailPane(
    vm: MainViewModel,
    scaffoldNavigator: ThreePaneScaffoldNavigator<DetailPaneKey>,
    backStack: NavBackStack<NavKey>
) {
    val settingsRepository = LocalSettingsRepository.current

    val enableArtworkColors = settingsRepository.appearance.enableArtworkColors
        .collectAsState(true)

    val scope = rememberCoroutineScope()

    scaffoldNavigator.currentDestination?.contentKey?.let { contentKey ->
        when(contentKey) {
            is DetailPaneKey.SettingsKey -> {
                AnimatedPane(
                    enterTransition = enterSpec(),
                    exitTransition = detailExitSpec()
                ) {
                    SettingsPane(
                        showBackButton = scaffoldNavigator.scaffoldValue.secondary == PaneAdaptedValue.Hidden,

                        contentKey = contentKey.pane,
                        backStack = backStack,
                        onClose = {
                            scope.launch {
                                scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilScaffoldValueChange)
                            }
                        }
                    )
                }
            }

            is DetailPaneKey.PodcastKey, is DetailPaneKey.PodcastEpisodeKey -> {
                val podcastOrigin = when (contentKey) {
                    is DetailPaneKey.PodcastKey -> contentKey.origin
                    is DetailPaneKey.PodcastEpisodeKey -> contentKey.episodeOrigin
                    else -> ""
                }
                val podcast = vm.fetchPodcast(podcastOrigin)
                    .collectAsState(null)

                AnimatedPane(
                    enterTransition = enterSpec(),
                    exitTransition = detailExitSpec()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        podcast.value?.let { podcast ->
                            SwitchableDynamicMaterialExpressiveTheme(
                                enable = enableArtworkColors.value,
                                seedColor = Color(podcast.imageSeedColor)
                            ) {
                                val displayBundle = remember { mutableStateOf(false) }
                                val episodeId = remember { mutableStateOf<String?>(null) }

                                val bundle = episodeId.value?.let {
                                    vm.fetchEpisode(it)
                                        .collectAsState(null)
                                }

                                LaunchedEffect(contentKey, bundle?.value) {
                                    if(contentKey is DetailPaneKey.PodcastEpisodeKey)
                                        episodeId.value = contentKey.episodeId

                                    displayBundle.value = bundle?.value != null
                                            && contentKey is DetailPaneKey.PodcastEpisodeKey
                                }

                                @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
                                val spatial = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
                                @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
                                val effects = androidx.compose.material3.MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                                AnimatedContent(
                                    targetState = displayBundle.value,
                                    transitionSpec = {
                                        if (targetState) {
                                            androidx.compose.animation.ContentTransform(
                                                targetContentEnter = androidx.compose.animation.slideInHorizontally(animationSpec = spatial) { it / 8 } + fadeIn(animationSpec = effects),
                                                initialContentExit = androidx.compose.animation.slideOutHorizontally(animationSpec = spatial) { -it / 12 } + fadeOut(animationSpec = effects)
                                            )
                                        } else {
                                            androidx.compose.animation.ContentTransform(
                                                targetContentEnter = androidx.compose.animation.slideInHorizontally(animationSpec = spatial) { -it / 12 } + fadeIn(animationSpec = effects),
                                                initialContentExit = androidx.compose.animation.slideOutHorizontally(animationSpec = spatial) { it / 8 } + fadeOut(animationSpec = effects)
                                            )
                                        }
                                    }
                                ) { isBundle ->
                                    when(isBundle) {
                                        false -> PodcastDetailView(
                                            podcast = podcast,
                                            onBack = {
                                                scope.launch {
                                                    scaffoldNavigator.navigateBack(
                                                        backNavigationBehavior = BackNavigationBehavior.PopLatest
                                                    )
                                                }
                                            },
                                            onClickEpisode = { episode ->
                                                scope.launch {
                                                    scaffoldNavigator.navigateTo(
                                                        pane = ThreePaneScaffoldRole.Primary,
                                                        contentKey = DetailPaneKey.PodcastEpisodeKey(
                                                            episodeOrigin = podcast.origin,
                                                            episodeId = episode.id
                                                        )
                                                    )
                                                }
                                            }
                                        )

                                        true -> PodcastEpisodeDetailView(
                                            bundle = bundle!!.value!!,
                                            parent = podcast,
                                            onBack = {
                                                scope.launch {
                                                    scaffoldNavigator.navigateBack(
                                                        backNavigationBehavior = BackNavigationBehavior.PopLatest
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is DetailPaneKey.EpisodeKey -> {
                val bundle = vm.fetchEpisode(contentKey.episodeId)
                    .collectAsState(null)

                val podcast = vm.fetchPodcast(contentKey.episodeOrigin)
                    .collectAsState(null)

                AnimatedPane(
                    enterTransition = enterSpec(),
                    exitTransition = detailExitSpec()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        bundle.value?.let { bundle ->
                            SwitchableDynamicMaterialExpressiveTheme(
                                enable = enableArtworkColors.value,
                                seedColor = Color(bundle.episode.imageSeedColor)
                            ) {
                                PodcastEpisodeDetailView(
                                    bundle = bundle,
                                    parent = podcast.value,
                                    showParentLink = true,
                                    onShowParent = {
                                        scope.launch {
                                            scaffoldNavigator.navigateTo(
                                                pane = ThreePaneScaffoldRole.Primary,
                                                contentKey = DetailPaneKey.PodcastKey(
                                                    origin = contentKey.episodeOrigin
                                                )
                                            )
                                        }
                                    },
                                    onBack = {
                                        scope.launch {
                                            scaffoldNavigator.navigateBack(
                                                backNavigationBehavior = BackNavigationBehavior.PopLatest
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}