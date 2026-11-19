package app.podiumpodcasts.podium.ui.route.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.podiumpodcasts.podium.ui.component.model.episode.episodeRowShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.common.BubbleButton
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItem
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItemActionResult
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItemActions
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.model.list.ListItemListItem
import app.podiumpodcasts.podium.ui.dialog.DeleteConfirmationDialog
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.ListEditBottomSheet
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.PagerScaffold
import app.podiumpodcasts.podium.ui.vm.list.ListViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListRoute(
    listId: Int,

    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit,

    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val vm = viewModel(key = listId.toString()) { ListViewModel(db, listId) }

    val list = db.lists().get(listId).collectAsState(null)
    val items = vm.items.collectAsLazyPagingItems()

    val systemList = list.value?.systemList()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val reorderableLazyGridState = rememberReorderableLazyListState(
        vm.lazyListState
    ) { from, to ->
        vm.move(from.index - 1, to.index - 1)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // S7: one lookup and one Set for the whole list, not one per row.
    val rowPlayerVm = viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
    val queueEpisodeIds by remember(rowPlayerVm) {
        androidx.compose.runtime.derivedStateOf { rowPlayerVm.queue.toHashSet() }
    }

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            PodiumSnackbarHost(snackbarHostState)
        },
        topBar = {
            // I14: M3 Expressive flexible top bar like Home's: centred title, the count as a
            // subtitle, one primary action and the rest in an overflow menu.
            val settingsRepo = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
            val startupDest = settingsRepo.appearance.startupDestination.collectAsState("HOME")
            val isStartup = startupDest.value == "LIST:$listId"
            val context = androidx.compose.ui.platform.LocalContext.current
            val playerVm = androidx.lifecycle.viewmodel.compose.viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
            var showOverflowMenu by remember { mutableStateOf(false) }

            TopAppBar(
                scrollBehavior = scrollBehavior,
                titleHorizontalAlignment = Alignment.CenterHorizontally,
                navigationIcon = {
                    BackButton { onBack() }
                },
                title = {
                    Text(
                        text = systemList?.label?.let { stringResource(it) }
                            ?: list.value?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                subtitle = {
                    if (items.itemCount > 0) {
                        Text(
                            text = "${items.itemCount} ${if (items.itemCount == 1) "item" else "items"}",
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    if(items.itemCount > 0) {
                        BubbleButton(
                            icon = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                            contentDescription = "Play all"
                        ) {
                            val episodes = items.itemSnapshotList.items
                                .filter { it.episode != null }
                                .map { it.toPodcastEpisodeBundle() }
                            if(episodes.isNotEmpty()) {
                                playerVm.play(context, episodes.first())
                                episodes.drop(1).forEach { playerVm.enqueue(context, it) }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            if (items.itemCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Add all to Up Next") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        items.itemSnapshotList.items
                                            .filter { it.episode != null }
                                            .forEach {
                                                playerVm.enqueue(context, it.toPodcastEpisodeBundle())
                                            }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (isStartup) "Don't open at startup" else "Open at startup") },
                                leadingIcon = {
                                    Icon(
                                        if (isStartup) Icons.Rounded.Home else Icons.Outlined.Home,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        val next = if (isStartup) "HOME" else "LIST:$listId"
                                        settingsRepo.appearance.setStartupDestination(next)
                                        snackbarHostState.showSnackbar(
                                            if (isStartup) "The app now opens on Home" else "The app now opens on this list"
                                        )
                                    }
                                }
                            )
                            if(list.value?.isSystemList == false) {
                                DropdownMenuItem(
                                    text = { Text("Edit list") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        vm.listEditBottomSheetState.show(listId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete list") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        vm.showDeleteDialog.value = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { inset ->
        PagerScaffold(
            items,
            isEmpty = {
                InfoLayout(
                    modifier = Modifier.padding(inset),
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    title = { stringResource(R.string.route_list_empty_title) },
                ) {
                    Text(
                        text = stringResource(R.string.route_list_empty_text),
                        textAlign = TextAlign.Center
                    )
                }
            }
        ) {
            LazyColumn(
                state = when(items.itemCount) {
                    0 -> LazyListState()
                    else -> vm.lazyListState
                },
                modifier = Modifier
                    .padding(inset)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                item {
                    if(systemList == null) {
                        if((list.value?.description ?: "").isNotBlank()) Column {
                            Text(
                                text = list.value?.description ?: ""
                            )

                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }

                items(
                    count = items.itemCount,
                    key = items.itemKey { it.listItem.id }
                ) { index ->
                    val item = items[index] ?: return@items

                    ReorderableItem(
                        state = reorderableLazyGridState,
                        key = item.listItem.id
                    ) {
                        val elementDeleted = stringResource(R.string.snackbar_element_deleted)

                        SwipeableItem(
                            scope = scope,
                            snackbarHostState = snackbarHostState,

                            modifier = Modifier
                                .animateItem()
                                .longPressDraggableHandle(),
                            shape = episodeRowShape(index, items.itemCount),
                            endAction = SwipeableItemActions.DeleteAction(label = "Remove from list") {
                                vm.delete(item)

                                SwipeableItemActionResult(
                                    isDismissed = true,
                                    message = elementDeleted,
                                    onUndo = {
                                        vm.restore(item)
                                    }
                                )
                            },
                            content = {
                                ListItemListItem(
                                    listItem = item,

                                    index = index,
                                    count = items.itemCount,
                                    inQueue = item.episode?.id?.let { it in queueEpisodeIds },
                                    playerVm = rowPlayerVm,

                                    colors = ListItemDefaults.segmentedColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),

                                    onClickPodcast = onClickPodcast,
                                    onClickEpisode = onClickEpisode
                                )
                            },
                        )
                    }
                }

                item {
                    FloatingMediaPlayerSpacer()
                }
            }
        }
    }

    ListEditBottomSheet(
        state = vm.listEditBottomSheetState
    )

    if(vm.showDeleteDialog.value) DeleteConfirmationDialog(
        onDismiss = {
            vm.showDeleteDialog.value = false
        },
        title = "Delete list?",
        itemName = list.value?.name ?: "",
        additionalText = stringResource(R.string.dialog_delete_confirmation_all_data_will_be_lost),
        onConfirm = {
            vm.deleteList()
            onBack()
        }
    )
}