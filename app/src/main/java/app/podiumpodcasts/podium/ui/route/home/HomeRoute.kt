package app.podiumpodcasts.podium.ui.route.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.SubscriptionsLayout
import app.podiumpodcasts.podium.SubscriptionsSort
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerBreakpoint
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.model.SubscriptionCard
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeListItem
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.helper.PagerScaffold
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.home.ContinuePlayingViewModel
import app.podiumpodcasts.podium.ui.vm.home.SubscriptionsViewModel
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun HomeRoute(
    onSettings: () -> Unit,

    onClickSubscriptions: () -> Unit,
    onClickContinuePlaying: () -> Unit,
    onClickNewEpisodes: () -> Unit,
    onClickLocallyAvailable: () -> Unit,

    onClickAddPodcast: () -> Unit,

    onClickDiscover: () -> Unit,

    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit
) {
    val scope = rememberCoroutineScope()
    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current

    val subscriptionsVm = viewModel { SubscriptionsViewModel(db) }
    val continuePlayingVm = viewModel { ContinuePlayingViewModel(db) }

    val subscriptionsPager = subscriptionsVm.subscriptions.collectAsLazyPagingItems()
    val continuePlayingPager = continuePlayingVm.continuePlaying.collectAsLazyPagingItems()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.podcastSubscriptions().subscribeAllExistingPodcasts()
        }
    }

    val layout by settingsRepository.appearance.subscriptionsLayout.collectAsState(SubscriptionsLayout.LARGE_GRID)
    val sort by settingsRepository.appearance.subscriptionsSort.collectAsState(SubscriptionsSort.RECENTLY_ADDED)
    val badgeMode by settingsRepository.appearance.subscriptionsBadgeMode.collectAsState(SubscriptionsBadgeMode.UNPLAYED_COUNT)

    var showOptionsSheet by remember { mutableStateOf(false) }

    val itemCount = subscriptionsPager.itemCount
    val sortedSubscriptions = remember(itemCount, subscriptionsPager.itemSnapshotList.items, sort) {
        val list = subscriptionsPager.itemSnapshotList.items
        when(sort) {
            SubscriptionsSort.A_Z -> list.sortedBy { it.podcast.fetchTitle().lowercase() }
            SubscriptionsSort.RECENTLY_ADDED -> list
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val expandedFab by remember {
        derivedStateOf {
            if(layout == SubscriptionsLayout.LIST) {
                listState.firstVisibleItemIndex == 0
            } else {
                gridState.firstVisibleItemIndex == 0
            }
        }
    }

    if(showOptionsSheet) {
        ModalBottomSheet(onDismissRequest = { showOptionsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Podcasts Options", style = Typography.titleLargeEmphasized)
                Spacer(Modifier.height(16.dp))

                Text("Layout", style = Typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        SubscriptionsLayout.LARGE_GRID to "Large Grid",
                        SubscriptionsLayout.SMALL_GRID to "Small Grid",
                        SubscriptionsLayout.LIST to "List"
                    )
                    options.forEachIndexed { idx, (value, label) ->
                        SegmentedButton(
                            selected = layout == value,
                            onClick = { scope.launch { settingsRepository.appearance.setSubscriptionsLayout(value) } },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size)
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Sort by", style = Typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        SubscriptionsSort.RECENTLY_ADDED to "New Episodes",
                        SubscriptionsSort.A_Z to "A–Z"
                    )
                    options.forEachIndexed { idx, (value, label) ->
                        SegmentedButton(
                            selected = sort == value,
                            onClick = { scope.launch { settingsRepository.appearance.setSubscriptionsSort(value) } },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size)
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Badges", style = Typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        SubscriptionsBadgeMode.UNPLAYED_COUNT to "Count",
                        SubscriptionsBadgeMode.LATEST_ONLY to "Dot",
                        SubscriptionsBadgeMode.OFF to "Off"
                    )
                    options.forEachIndexed { idx, (value, label) ->
                        SegmentedButton(
                            selected = badgeMode == value,
                            onClick = { scope.launch { settingsRepository.appearance.setSubscriptionsBadgeMode(value) } },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size)
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    BoxWithConstraints {
        Scaffold(
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                CenterAlignedTopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(stringResource(R.string.app_name))
                    },
                    actions = {
                        IconButton(onClick = { showOptionsSheet = true }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Podcasts options")
                        }
                        FilledIconButton(
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            onClick = onSettings
                        ) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.route_settings)
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                MediumExtendedFloatingActionButton(
                    modifier = Modifier.padding(
                        bottom = if(maxWidth <= FloatingMediaPlayerBreakpoint)
                            LocalFloatingMediaPlayerHeight.current
                        else
                            0.dp
                    ),
                    expanded = expandedFab,
                    text = {
                        Text(stringResource(R.string.common_action_add))
                    },
                    icon = {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.common_action_add),
                            modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize),
                        )
                    },
                    onClick = onClickAddPodcast
                )
            }
        ) { inset ->
            Box(
                Modifier
                    .padding(inset)
                    .fillMaxSize()
            ) {
                PagerScaffold(
                    subscriptionsPager,
                    isEmpty = {
                        InfoLayout(
                            icon = Icons.Rounded.Podcasts,
                            title = {
                                stringResource(R.string.route_home_empty_title)
                            },
                            content = {
                                Text(
                                    text = stringResource(R.string.route_home_empty_text),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(32.dp))

                                Button(
                                    onClick = {
                                        onClickDiscover()
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.Explore,
                                        contentDescription = stringResource(R.string.route_discover),
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )

                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))

                                    Text(stringResource(R.string.route_discover))
                                }
                            }
                        )
                    }
                ) {
                    if(layout == SubscriptionsLayout.LIST) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                        ) {
                            if(continuePlayingPager.itemCount > 0) {
                                continuePlayingPager[0]?.let { item ->
                                    item(key = "CONTINUE_PLAYING", contentType = "continue") {
                                        Column(Modifier.padding(bottom = 8.dp)) {
                                            TextButton(onClick = onClickContinuePlaying) {
                                                Text(stringResource(R.string.route_continue_playing))
                                            }
                                            PodcastEpisodeListItem(
                                                bundle = item.toPodcastEpisodeBundle(),
                                                descriptionText = item.episode.podcastTitle,
                                                index = 0,
                                                count = 1,
                                                colors = ListItemDefaults.segmentedColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                                ),
                                                onClick = { onClickEpisode(item.episode) }
                                            )
                                        }
                                    }
                                }
                            }

                            itemsIndexed(
                                items = sortedSubscriptions,
                                key = { _, bundle -> bundle.podcast.origin },
                                contentType = { _, _ -> "podcast_row" }
                            ) { index, bundle ->
                                SegmentedListItem(
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = sortedSubscriptions.size
                                    ),
                                    leadingContent = {
                                        ShimmerAsyncImage(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                            model = bundle.podcast.imageUrl,
                                            contentDescription = bundle.podcast.fetchTitle(),
                                            contentScale = ContentScale.Crop
                                        )
                                    },
                                    content = {
                                        Text(
                                            text = bundle.podcast.fetchTitle(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = bundle.podcast.author,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    trailingContent = {
                                        if(bundle.subscription.newEpisodes > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            ) {
                                                if(badgeMode == SubscriptionsBadgeMode.UNPLAYED_COUNT) {
                                                    Text(bundle.subscription.newEpisodes.toString())
                                                }
                                            }
                                        }
                                    },
                                    onClick = { onClickPodcast(bundle.podcast.origin) }
                                )
                            }

                            item(key = "SPACER") {
                                FloatingMediaPlayerSpacer()
                            }
                        }
                    } else {
                        val minCellSize = if(layout == SubscriptionsLayout.SMALL_GRID) 84.dp else 110.dp
                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxSize(),
                            state = gridState,
                            columns = GridCells.Adaptive(minCellSize),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if(continuePlayingPager.itemCount > 0) {
                                continuePlayingPager[0]?.let { item ->
                                    item(
                                        key = "CONTINUE_PLAYING",
                                        span = { GridItemSpan(maxLineSpan) },
                                        contentType = "continue"
                                    ) {
                                        Column(Modifier.padding(bottom = 4.dp)) {
                                            TextButton(onClick = onClickContinuePlaying) {
                                                Text(stringResource(R.string.route_continue_playing))
                                            }
                                            PodcastEpisodeListItem(
                                                bundle = item.toPodcastEpisodeBundle(),
                                                descriptionText = item.episode.podcastTitle,
                                                index = 0,
                                                count = 1,
                                                colors = ListItemDefaults.segmentedColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                                ),
                                                onClick = { onClickEpisode(item.episode) }
                                            )
                                        }
                                    }
                                }
                            }

                            items(
                                count = sortedSubscriptions.size,
                                key = { sortedSubscriptions[it].podcast.origin },
                                contentType = { "podcast_card" }
                            ) { index ->
                                val bundle = sortedSubscriptions[index]
                                SubscriptionCard(
                                    bundle = bundle,
                                    badgeMode = badgeMode,
                                    onClick = { onClickPodcast(bundle.podcast.origin) }
                                )
                            }

                            item(
                                key = "SPACER",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                FloatingMediaPlayerSpacer()
                            }
                        }
                    }
                }
            }
        }
    }
}