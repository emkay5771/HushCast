package app.podiumpodcasts.podium.ui.route.content

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.SubscriptionsLayout
import app.podiumpodcasts.podium.SubscriptionsSort
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.model.SubscriptionCard
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.home.SubscriptionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubscriptionsRoute(
    onClickPodcast: (origin: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current
    val vm = viewModel { SubscriptionsViewModel(db) }

    val subscriptions = vm.subscriptions.collectAsLazyPagingItems()
    val layout by settingsRepository.appearance.subscriptionsLayout.collectAsState(SubscriptionsLayout.LARGE_GRID)
    val sort by settingsRepository.appearance.subscriptionsSort.collectAsState(SubscriptionsSort.RECENTLY_ADDED)
    val badgeMode by settingsRepository.appearance.subscriptionsBadgeMode.collectAsState(SubscriptionsBadgeMode.UNPLAYED_COUNT)

    var showOptionsSheet by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val sortedItems = remember(subscriptions.itemSnapshotList.items, sort) {
        val list = subscriptions.itemSnapshotList.items
        when(sort) {
            SubscriptionsSort.A_Z -> list.sortedBy { it.podcast.fetchTitle().lowercase() }
            SubscriptionsSort.RECENTLY_ADDED -> list
        }
    }

    if(showOptionsSheet) {
        ModalBottomSheet(onDismissRequest = { showOptionsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Subscriptions Options", style = Typography.titleLargeEmphasized)
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

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton { onBack() }
                },
                title = {
                    Text(stringResource(R.string.route_subscriptions))
                },
                actions = {
                    IconButton(onClick = { showOptionsSheet = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Subscriptions Options"
                        )
                    }
                }
            )
        }
    ) { inset ->
        if(layout == SubscriptionsLayout.LIST) {
            LazyColumn(
                modifier = Modifier
                    .padding(inset)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                itemsIndexed(sortedItems, key = { _, item -> item.podcast.origin }) { idx, item ->
                    SegmentedListItem(
                        modifier = if(sortedItems.size == 1) Modifier.clip(RoundedCornerShape(16.dp)) else Modifier,
                        onClick = { onClickPodcast(item.podcast.origin) },
                        shapes = ListItemDefaults.segmentedShapes(index = idx, count = sortedItems.size),
                        leadingContent = {
                            ShimmerAsyncImage(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                model = item.podcast.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop
                            )
                        },
                        supportingContent = {
                            Text(
                                text = item.podcast.author,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            if(item.subscription.newEpisodes > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                                when(badgeMode) {
                                    SubscriptionsBadgeMode.UNPLAYED_COUNT -> Badge {
                                        Text(item.subscription.newEpisodes.toString())
                                    }
                                    SubscriptionsBadgeMode.LATEST_ONLY -> Badge()
                                    SubscriptionsBadgeMode.OFF -> {}
                                }
                            }
                        }
                    ) {
                        Text(
                            text = item.podcast.fetchTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                item {
                    FloatingMediaPlayerSpacer()
                }
            }
        } else {
            val minCellSize = if(layout == SubscriptionsLayout.SMALL_GRID) 88.dp else 130.dp
            LazyVerticalGrid(
                state = when(sortedItems.size) {
                    0 -> LazyGridState()
                    else -> vm.lazyGridState
                },
                columns = GridCells.Adaptive(minCellSize),
                modifier = Modifier.padding(inset),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(sortedItems.size, key = { sortedItems[it].podcast.origin }) { idx ->
                    val item = sortedItems[idx]
                    SubscriptionCard(
                        bundle = item,
                        badgeMode = badgeMode,
                        onClick = { onClickPodcast(item.podcast.origin) }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    FloatingMediaPlayerSpacer()
                }
            }
        }
    }
}