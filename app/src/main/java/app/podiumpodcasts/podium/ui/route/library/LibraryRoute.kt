package app.podiumpodcasts.podium.ui.route.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerShown
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.ListCreateBottomSheet
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.MediaPlayerQueueBottomSheet
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.vm.DownloadsViewModel
import app.podiumpodcasts.podium.ui.vm.HistoryViewModel
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.ui.vm.home.ContinuePlayingViewModel
import app.podiumpodcasts.podium.ui.vm.home.NewEpisodesViewModel
import app.podiumpodcasts.podium.ui.vm.library.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryRoute(
    onClickDownloads: () -> Unit,
    onClickHistory: () -> Unit,
    onClickContinuePlaying: () -> Unit,
    onClickNewEpisodes: () -> Unit,

    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit,
    onClickList: (listId: Int) -> Unit
) {
    val db = LocalDatabase.current
    val vm = viewModel { LibraryViewModel(db) }
    val playerVm = viewModel<MediaPlayerViewModel>()

    val systemLists = vm.systemLists.collectAsLazyPagingItems()
    val nonSystemLists = vm.nonSystemLists.collectAsLazyPagingItems()

    val downloadsVm = viewModel { DownloadsViewModel(db) }
    val historyVm = viewModel { HistoryViewModel(db) }
    val continuePlayingVm = viewModel { ContinuePlayingViewModel(db) }
    val newEpisodesVm = viewModel { NewEpisodesViewModel(db) }

    val downloadsPager = remember { downloadsVm.downloads }.collectAsLazyPagingItems()
    val historyPager = remember { historyVm.historyElements }.collectAsLazyPagingItems()
    val continuePlayingPager =
        remember { continuePlayingVm.continuePlaying }.collectAsLazyPagingItems()
    val newEpisodesPager = remember { newEpisodesVm.newEpisodes }.collectAsLazyPagingItems()

    var showUpNextSheet by remember { mutableStateOf(false) }

    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val listState = rememberLazyListState()
    val floatingMediaPlayerShown = LocalFloatingMediaPlayerShown.current

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LibrarySearch(
                scrollBehavior = scrollBehavior,
                onClickPodcast = onClickPodcast,
                onClickEpisode = onClickEpisode
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.padding(
                    bottom = if (floatingMediaPlayerShown) LocalFloatingMediaPlayerHeight.current else 0.dp
                ),
                onClick = { vm.showListCreateBottomSheet.value = true }
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.common_action_create)
                )
            }
        }
    ) { inset ->
        val smartFilters = listOf(
            Triple(
                stringResource(R.string.route_up_next),
                Icons.AutoMirrored.Rounded.PlaylistPlay,
                playerVm.queue.size to { showUpNextSheet = true }
            ),
            Triple(
                stringResource(R.string.route_new_episodes),
                Icons.Rounded.NewReleases,
                newEpisodesPager.itemCount to onClickNewEpisodes
            ),
            Triple(
                stringResource(R.string.route_continue_playing),
                Icons.Rounded.Pause,
                continuePlayingPager.itemCount to onClickContinuePlaying
            ),
            Triple(
                stringResource(R.string.route_downloads),
                Icons.Rounded.Download,
                downloadsPager.itemCount to onClickDownloads
            ),
            Triple(
                stringResource(R.string.route_history),
                Icons.Rounded.History,
                historyPager.itemCount to onClickHistory
            )
        )

        val allListsCount = systemLists.itemCount + nonSystemLists.itemCount

        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(inset)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
        ) {
            item {
                Text(
                    text = "Filters",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            items(smartFilters.size) { index ->
                val (title, icon, countAndClick) = smartFilters[index]
                val (count, onClick) = countAndClick
                PlaylistRow(
                    title = title,
                    icon = icon,
                    count = count,
                    index = index,
                    total = smartFilters.size,
                    onClick = onClick
                )
            }

            if (allListsCount > 0) {
                item {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)
                    )
                }

                items(systemLists.itemCount) { idx ->
                    systemLists[idx]?.let { list ->
                        PlaylistRow(
                            title = list.name,
                            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            count = null,
                            index = idx,
                            total = allListsCount,
                            onClick = { onClickList(list.id) }
                        )
                    }
                }

                items(nonSystemLists.itemCount) { idx ->
                    nonSystemLists[idx]?.let { list ->
                        PlaylistRow(
                            title = list.name,
                            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            count = null,
                            index = systemLists.itemCount + idx,
                            total = allListsCount,
                            onClick = { onClickList(list.id) }
                        )
                    }
                }
            }

            if (floatingMediaPlayerShown) {
                item {
                    FloatingMediaPlayerSpacer()
                }
            }
        }
    }

    if (vm.showListCreateBottomSheet.value) ListCreateBottomSheet {
        vm.showListCreateBottomSheet.value = false
    }

    if (showUpNextSheet) MediaPlayerQueueBottomSheet(
        onOpenEpisode = { _, _ -> showUpNextSheet = false },
        onDismiss = { showUpNextSheet = false }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistRow(
    title: String,
    icon: ImageVector,
    count: Int?,
    index: Int,
    total: Int,
    onClick: () -> Unit
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = total),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        },
        content = {
            Text(text = title)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (count != null && count > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(count.toString())
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick
    )
}