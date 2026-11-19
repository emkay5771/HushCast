package app.podiumpodcasts.podium.ui.route.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeListItem
import app.podiumpodcasts.podium.ui.formatFileSize
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.PagerScaffold
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.DownloadsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsRoute(
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit,
    onBack: () -> Unit
) {
    val db = LocalDatabase.current
    val vm = viewModel { DownloadsViewModel(db) }

    val totalSize = vm.totalSize.collectAsState(0L)
    val pager = remember { vm.downloads }.collectAsLazyPagingItems()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showRemovePlayedConfirm by remember { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // S7: one lookup and one Set for the whole list, not one per row.
    val playerVm = androidx.lifecycle.viewmodel.compose.viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
    val queueEpisodeIds by remember(playerVm) {
        androidx.compose.runtime.derivedStateOf { playerVm.queue.toHashSet() }
    }

    val playedDownloads = pager.itemSnapshotList.items.mapNotNull { item ->
        val ep = item.episode ?: return@mapNotNull null
        if (item.download.state == PodcastEpisodeDownloadState.DOWNLOADED.value &&
            item.playState?.played == true
        ) ep else null
    }

    if (showRemovePlayedConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRemovePlayedConfirm = false },
            title = {
                Text(
                    if (playedDownloads.size == 1) "Remove 1 played download?"
                    else "Remove ${playedDownloads.size} played downloads?"
                )
            },
            text = { Text("The audio files of played episodes are deleted from this device. The episode that's playing is kept.") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    showRemovePlayedConfirm = false
                    // Item 2: never delete the file the player is using.
                    val toRemove = playedDownloads.filter { it.id != playerVm.metadataEpisodeId }
                    val removedCount = toRemove.size
                    scope.launch {
                        toRemove.forEach { ep ->
                            app.podiumpodcasts.podium.manager.DownloadManager.deleteEpisodeDownload(
                                context = context,
                                db = db,
                                episode = ep
                            )
                        }
                        snackbarHostState.showSnackbar(
                            if (removedCount == 1) "Removed 1 played download" else "Removed $removedCount played downloads"
                        )
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRemovePlayedConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost(snackbarHostState) },
        topBar = {
            // I14: M3 Expressive flexible top bar like Home's: centred title, count and size as
            // a subtitle, one primary action and the rest in an overflow menu.
            var showOverflowMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
            TopAppBar(
                scrollBehavior = scrollBehavior,
                titleHorizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                navigationIcon = {
                    BackButton { onBack() }
                },
                title = {
                    Text(
                        text = stringResource(R.string.route_downloads),
                        modifier = Modifier.semantics { heading() }
                    )
                },
                subtitle = {
                    val count = pager.itemCount
                    if (count > 0) {
                        val countText = "$count ${if (count == 1) "episode" else "episodes"}"
                        Text(
                            text = if (totalSize.value > 0L) "$countText · ${formatFileSize(totalSize.value)}" else countText,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    if(pager.itemCount > 0) {
                        app.podiumpodcasts.podium.ui.component.common.BubbleButton(
                            icon = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                            contentDescription = "Play all"
                        ) {
                            val episodes = pager.itemSnapshotList.items
                                .mapNotNull { item ->
                                    val ep = item.episode ?: return@mapNotNull null
                                    if (item.download.state != PodcastEpisodeDownloadState.DOWNLOADED.value) return@mapNotNull null
                                    PodcastEpisodeBundle(ep, item.playState, item.download)
                                }
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
                                        pager.itemSnapshotList.items
                                            .mapNotNull { item ->
                                                val ep = item.episode ?: return@mapNotNull null
                                                if (item.download.state != PodcastEpisodeDownloadState.DOWNLOADED.value) return@mapNotNull null
                                                PodcastEpisodeBundle(ep, item.playState, item.download)
                                            }
                                            .forEach {
                                                playerVm.enqueue(context, it)
                                            }
                                    }
                                )
                                if (playedDownloads.isNotEmpty()) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Remove played downloads") },
                                        leadingIcon = {
                                            androidx.compose.material3.Icon(
                                                androidx.compose.material.icons.Icons.Rounded.Delete,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            showRemovePlayedConfirm = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { inset ->
        PagerScaffold(
            pager,
            isEmpty = {
                InfoLayout(
                    modifier = Modifier.padding(inset),
                    icon = Icons.Rounded.Cloud,
                    title = { stringResource(R.string.route_downloads_empty_title) },
                ) {
                    Text(
                        text = stringResource(R.string.route_downloads_empty_text),
                        textAlign = TextAlign.Center
                    )
                }
            }
        ) {
            LazyColumn(
                Modifier
                    .padding(inset)
                    .fillMaxSize(),
                state = when(pager.itemCount) {
                    0 -> LazyListState()
                    else -> vm.lazyListState
                },
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                items(
                    count = pager.itemCount,
                    key = pager.itemKey { it.download.episodeId }
                ) {
                    val bundle = pager[it] ?: return@items
                    val episode = bundle.episode ?: return@items

                    PodcastEpisodeListItem(
                        modifier = Modifier.animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),
                        inQueue = episode.id in queueEpisodeIds,
                        playerVm = playerVm,

                        bundle = PodcastEpisodeBundle(
                            episode = episode,
                            playState = bundle.playState,
                            download = bundle.download
                        ),

                        descriptionText = episode.podcastTitle,

                        trailingContent = {
                            if(bundle.download.state != PodcastEpisodeDownloadState.NOT_DOWNLOADED.value) {
                                val liveProgress = remember(episode.id) {
                                    app.podiumpodcasts.podium.api.download.DownloadTracker.observe(episode.id)
                                }.collectAsState(initial = null).value
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ) {
                                    Text(
                                        modifier = Modifier.padding(8.dp),
                                        text = when(bundle.download.state) {
                                            PodcastEpisodeDownloadState.DOWNLOADING.value ->
                                                formatFileSize(liveProgress?.progressBytes ?: bundle.download.progress)
                                            PodcastEpisodeDownloadState.FAILED.value ->
                                                "Failed"
                                            else ->
                                                formatFileSize(bundle.download.size)
                                        },
                                        style = Typography.bodyMedium
                                    )
                                }
                            }
                        },

                        index = it,
                        count = pager.itemCount,

                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),

                        onClick = {
                            onClickEpisode(episode)
                        }
                    )
                }

                item {
                    FloatingMediaPlayerSpacer()
                }
            }
        }
    }
}