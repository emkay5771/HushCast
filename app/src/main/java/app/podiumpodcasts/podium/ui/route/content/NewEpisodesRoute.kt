package app.podiumpodcasts.podium.ui.route.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItem
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItemActionResult
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItemActions
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeListItem
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.PagerScaffold
import app.podiumpodcasts.podium.ui.vm.home.NewEpisodesViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewEpisodesRoute(
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val activity = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
    val vm = viewModel(
        viewModelStoreOwner = activity,
        key = "NEW_EPISODES"
    ) { NewEpisodesViewModel(db) }
    val playerVm = viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
    val settingsRepository = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val itemSwipeRightAction by settingsRepository.behavior.episodeSwipeRightAction.collectAsStateWithLifecycle(
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT
    )
    val secondaryRightType by settingsRepository.behavior.episodeSwipeRightSecondaryAction.collectAsStateWithLifecycle(
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST
    )
    val queueEpisodeIds by androidx.compose.runtime.remember(playerVm) {
        derivedStateOf { playerVm.queue.toHashSet() }
    }

    val newEpisodes = vm.newEpisodes.collectAsLazyPagingItems()
    val context = androidx.compose.ui.platform.LocalContext.current
    var isRefreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showOverflowMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showMarkAllConfirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val snackbarHostState = remember { SnackbarHostState() }

    if (showMarkAllConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMarkAllConfirm = false },
            title = { Text("Mark all as listened?") },
            text = { Text("This marks all unplayed episodes from your subscribed podcasts as listened.") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    showMarkAllConfirm = false
                    vm.markAllPlayed()
                    scope.launch {
                        snackbarHostState.showSnackbar("Marked all episodes as listened")
                    }
                }) { Text("Mark all") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showMarkAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            PodiumSnackbarHost(snackbarHostState)
        },
        topBar = {
            CenterAlignedTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton { onBack() }
                },
                title = {
                    Text(stringResource(R.string.route_new_episodes))
                },
                actions = {
                    if(newEpisodes.itemCount > 0) {
                        app.podiumpodcasts.podium.ui.component.common.BubbleButton(
                            icon = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                            contentDescription = "Play all"
                        ) {
                            val episodes = newEpisodes.itemSnapshotList.items
                            if(episodes.isNotEmpty()) {
                                playerVm.play(context, episodes.first())
                                episodes.drop(1).forEach { playerVm.enqueue(context, it) }
                            }
                        }
                        androidx.compose.foundation.layout.Box {
                            androidx.compose.material3.IconButton(onClick = { showOverflowMenu = true }) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                    contentDescription = "More actions"
                                )
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Add all to Up Next") },
                                    leadingIcon = {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.PlaylistAdd,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        newEpisodes.itemSnapshotList.items.forEach {
                                            playerVm.enqueue(context, it)
                                        }
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Mark all as listened") },
                                    leadingIcon = {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.Rounded.Done,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showMarkAllConfirm = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { inset ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = pullState,
            onRefresh = {
                if (!isRefreshing) {
                    isRefreshing = true
                    scope.launch {
                        runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                app.podiumpodcasts.podium.background.work.PodcastUpdateWork(context, db).doWork()
                            }
                        }
                        isRefreshing = false
                    }
                }
            },
            indicator = {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                )
            },
            modifier = Modifier
                .padding(inset)
                .fillMaxSize()
        ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize()
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.FilterChip(
                    selected = vm.excludeListened.value,
                    onClick = { vm.excludeListened.value = !vm.excludeListened.value },
                    label = { Text("Exclude listened") }
                )
                androidx.compose.material3.FilterChip(
                    selected = vm.onlyInProgress.value,
                    onClick = { vm.onlyInProgress.value = !vm.onlyInProgress.value },
                    label = { Text("In Progress") }
                )
                androidx.compose.material3.FilterChip(
                    selected = vm.onlyDownloaded.value,
                    onClick = { vm.onlyDownloaded.value = !vm.onlyDownloaded.value },
                    label = { Text("Downloaded") }
                )
            }

            PagerScaffold(
                newEpisodes,
                isEmpty = {
                    InfoLayout(
                        modifier = Modifier.fillMaxSize(),
                        icon = Icons.Rounded.Verified,
                        title = { stringResource(R.string.route_new_episodes_empty_title) },
                    ) {
                        Text(
                            text = stringResource(R.string.route_new_episodes_empty_text),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = vm.lazyListState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    items(
                        newEpisodes.itemCount,
                        key = newEpisodes.itemKey { it.episode.id },
                        contentType = { "episode" }
                    ) {
                        val item = newEpisodes[it] ?: return@items
                        val inQueue = item.episode.id in queueEpisodeIds

                        SwipeableItem(
                            scope = scope,
                            modifier = Modifier.animateItem(),
                            snackbarHostState = snackbarHostState,
                            startAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                                actionType = itemSwipeRightAction,
                                bundle = item,
                                playerVm = playerVm,
                                onMarkPlayed = { markPlayed ->
                                    vm.markPlayed(item, markPlayed)
                                    if (markPlayed) {
                                        playerVm.removeEpisodeFromPlaybackAndQueue(item.episode.id)
                                    }
                                }
                            ),
                            secondaryStartAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                                actionType = app.podiumpodcasts.podium.ui.component.common.swipeable.consolidateSecondarySwipeAction(
                                    primary = itemSwipeRightAction,
                                    secondary = secondaryRightType,
                                    inQueue = inQueue
                                ),
                                bundle = item,
                                playerVm = playerVm,
                                onMarkPlayed = { markPlayed ->
                                    vm.markPlayed(item, markPlayed)
                                    if (markPlayed) {
                                        playerVm.removeEpisodeFromPlaybackAndQueue(item.episode.id)
                                    }
                                }
                            ),
                            endAction = SwipeableItemActions.CheckAction(
                                onAction = {
                                    val markPlayed = !(item.playState?.played ?: false)
                                    vm.markPlayed(item, markPlayed)
                                    if (markPlayed) {
                                        playerVm.removeEpisodeFromPlaybackAndQueue(item.episode.id)
                                    }

                                    SwipeableItemActionResult(
                                        isDismissed = vm.excludeListened.value && markPlayed,
                                        message = if (markPlayed) "Marked as listened" else "Marked as unlistened",
                                        onUndo = {
                                            vm.markPlayed(item, !markPlayed)
                                        }
                                    )
                                }
                            ),
                        content = {
                            PodcastEpisodeListItem(
                                bundle = item,

                                descriptionText = item.episode.podcastTitle,

                                index = it,
                                count = newEpisodes.itemCount,

                                colors = ListItemDefaults.segmentedColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),

                                onClick = {
                                    onClickEpisode(item.episode)
                                }
                            )
                        },
                    )
                }

                item {
                    FloatingMediaPlayerSpacer()
                }
            }
        }
        }
    }
}
}