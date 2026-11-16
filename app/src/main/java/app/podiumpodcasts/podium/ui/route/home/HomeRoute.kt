package app.podiumpodcasts.podium.ui.route.home

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import app.podiumpodcasts.podium.ui.vm.home.SubscriptionsViewModel
import kotlinx.coroutines.launch

private var homeStartupSyncDone = false

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

    val subscriptionsPager = subscriptionsVm.subscriptions.collectAsLazyPagingItems()

    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.podiumpodcasts.podium.PodcastGridOrganization.init(context)
        }
        if (!homeStartupSyncDone) {
            homeStartupSyncDone = true
            kotlinx.coroutines.delay(450)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.podcastSubscriptions().subscribeAllExistingPodcasts()
                db.podcastSubscriptions().recalculateAllNewEpisodesCounts()
            }
        }
    }

    val layout by settingsRepository.appearance.subscriptionsLayout.collectAsState(SubscriptionsLayout.LARGE_GRID)
    val sort by settingsRepository.appearance.subscriptionsSort.collectAsState(SubscriptionsSort.RECENTLY_ADDED)
    val badgeMode by settingsRepository.appearance.subscriptionsBadgeMode.collectAsState(SubscriptionsBadgeMode.UNPLAYED_COUNT)
    val showGridTitles by settingsRepository.appearance.showGridTitles.collectAsState(true)
    val gridOrgState by app.podiumpodcasts.podium.PodcastGridOrganization.state.collectAsState()

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showOrganizeFoldersDialog by remember { mutableStateOf(false) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var moveToFolderTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val itemCount = subscriptionsPager.itemCount
    val allSubscriptions = remember(itemCount) {
        subscriptionsPager.itemSnapshotList.items
    }
    val sortedSubscriptions = remember(allSubscriptions, sort) {
        when(sort) {
            SubscriptionsSort.A_Z -> allSubscriptions.sortedBy { it.podcast.fetchTitle().lowercase() }
            SubscriptionsSort.RECENTLY_ADDED,
            SubscriptionsSort.CUSTOM -> allSubscriptions
        }
    }

    // Build grid entries (folders + root podcasts) respecting sort & customOrder
    val gridEntries = remember(allSubscriptions, gridOrgState, sort) {
        val byOrigin = allSubscriptions.associateBy { it.podcast.origin }
        val inFolders = gridOrgState.folders.flatMap { it.origins }.toSet()
        val folderMap = gridOrgState.folders.associate { f ->
            app.podiumpodcasts.podium.PodcastGridOrganization.folderKey(f.id) to
                Pair(f, f.origins.mapNotNull { byOrigin[it] })
        }
        val rootPodcasts = sortedSubscriptions.filterNot { it.podcast.origin in inFolders }

        if (sort == SubscriptionsSort.CUSTOM && gridOrgState.customOrder.isNotEmpty()) {
            val allKeys = LinkedHashMap<String, Any>()
            folderMap.forEach { (k, v) -> allKeys[k] = v }
            rootPodcasts.forEach { b ->
                allKeys[app.podiumpodcasts.podium.PodcastGridOrganization.podcastKey(b.podcast.origin)] = b
            }
            val ordered = ArrayList<Pair<String, Any>>(allKeys.size)
            for (k in gridOrgState.customOrder) {
                allKeys.remove(k)?.let { ordered.add(k to it) }
            }
            allKeys.forEach { (k, v) -> ordered.add(k to v) }
            ordered
        } else {
            val folderItems = folderMap.entries.map { it.key to (it.value as Any) }
            val podItems = rootPodcasts.map {
                app.podiumpodcasts.podium.PodcastGridOrganization.podcastKey(it.podcast.origin) to (it as Any)
            }
            if (sort == SubscriptionsSort.A_Z) {
                (folderItems + podItems).sortedBy { (_, v) ->
                    when (v) {
                        is Pair<*, *> -> (v.first as app.podiumpodcasts.podium.PodcastFolder).name.lowercase()
                        else -> (v as app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle).podcast.fetchTitle().lowercase()
                    }
                }
            } else {
                folderItems + podItems
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            allSubscriptions = allSubscriptions,
            onDismiss = { showCreateFolderDialog = false },
            onCreated = { showCreateFolderDialog = false }
        )
    }

    moveToFolderTarget?.let { (origin, title) ->
        MoveToFolderDialog(
            podcastOrigin = origin,
            podcastTitle = title,
            folders = gridOrgState.folders,
            onDismiss = { moveToFolderTarget = null }
        )
    }

    openFolderId?.let { fId ->
        val folder = gridOrgState.folders.find { it.id == fId }
        if (folder != null) {
            val byOrigin = remember(allSubscriptions) { allSubscriptions.associateBy { it.podcast.origin } }
            FolderBottomSheet(
                folder = folder,
                folderSubscriptions = folder.origins.mapNotNull { byOrigin[it] },
                allSubscriptions = allSubscriptions,
                badgeMode = badgeMode,
                onDismiss = { openFolderId = null },
                onClickPodcast = onClickPodcast
            )
        } else {
            openFolderId = null
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
                        SubscriptionsLayout.SMALL_GRID to "Compact Grid",
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
                        SubscriptionsSort.A_Z to "A–Z",
                        SubscriptionsSort.CUSTOM to "Drag & Drop"
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

                if (layout == SubscriptionsLayout.LARGE_GRID) {
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("Show titles in grid", style = Typography.bodyLarge)
                        androidx.compose.material3.Switch(
                            checked = showGridTitles,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepository.appearance.setShowGridTitles(enabled) }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        showOptionsSheet = false
                        showCreateFolderDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("New Folder")
                }
                if (gridOrgState.folders.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            showOptionsSheet = false
                            showOrganizeFoldersDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Organize Folders")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            CenterAlignedTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(stringResource(R.string.app_name))
                },
                actions = {
                    IconButton(onClick = { showCreateFolderDialog = true }) {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.CreateNewFolder,
                            contentDescription = "New folder"
                        )
                    }
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
                                            contentScale = ContentScale.Crop,
                                            targetSizePx = 128
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
                                        androidx.compose.foundation.layout.Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if(bundle.subscription.newEpisodes > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primary
                                                ) {
                                                    if(badgeMode == SubscriptionsBadgeMode.UNPLAYED_COUNT) {
                                                        Text(bundle.subscription.newEpisodes.toString())
                                                    }
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    moveToFolderTarget = bundle.podcast.origin to bundle.podcast.fetchTitle()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                                    contentDescription = "Move to folder",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
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
                        val isCompact = layout == SubscriptionsLayout.SMALL_GRID
                        val columns = if (isCompact) GridCells.Fixed(4) else GridCells.Fixed(3)
                        val gridSpacing = if (isCompact) 8.dp else 12.dp
                        var draggedKey by remember { mutableStateOf<String?>(null) }
                        var dragOffsetX by remember { mutableStateOf(0f) }
                        var dragOffsetY by remember { mutableStateOf(0f) }
                        val currentEntries by androidx.compose.runtime.rememberUpdatedState(gridEntries)

                        LazyVerticalGrid(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                                val k = item.key as? String ?: return@firstOrNull false
                                                (k.startsWith("folder:") || k.startsWith("podcast:")) &&
                                                    offset.x >= item.offset.x && offset.x <= item.offset.x + item.size.width &&
                                                    offset.y >= item.offset.y && offset.y <= item.offset.y + item.size.height
                                            }
                                            if (hit != null) {
                                                draggedKey = hit.key as String
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                                if (sort != SubscriptionsSort.CUSTOM) {
                                                    scope.launch {
                                                        settingsRepository.appearance.setSubscriptionsSort(SubscriptionsSort.CUSTOM)
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedKey = null
                                            dragOffsetX = 0f
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggedKey = null
                                            dragOffsetX = 0f
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            val activeKey = draggedKey ?: return@detectDragGesturesAfterLongPress
                                            change.consume()
                                            dragOffsetX += dragAmount.x
                                            dragOffsetY += dragAmount.y

                                            val visible = gridState.layoutInfo.visibleItemsInfo
                                            val currentItem = visible.firstOrNull { it.key == activeKey } ?: return@detectDragGesturesAfterLongPress
                                            val centerX = currentItem.offset.x + currentItem.size.width / 2f + dragOffsetX
                                            val centerY = currentItem.offset.y + currentItem.size.height / 2f + dragOffsetY
                                            val target = visible.firstOrNull { item ->
                                                val k = item.key as? String ?: return@firstOrNull false
                                                k != activeKey &&
                                                    (k.startsWith("folder:") || k.startsWith("podcast:")) &&
                                                    centerX >= item.offset.x && centerX <= item.offset.x + item.size.width &&
                                                    centerY >= item.offset.y && centerY <= item.offset.y + item.size.height
                                            }
                                            if (target != null) {
                                                val keys = currentEntries.map { it.first }
                                                val fromIdx = keys.indexOf(activeKey)
                                                val toIdx = keys.indexOf(target.key as String)
                                                if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                                                    app.podiumpodcasts.podium.PodcastGridOrganization.reorderVisibleKeys(
                                                        context, keys, fromIdx, toIdx
                                                    )
                                                    dragOffsetX -= (target.offset.x - currentItem.offset.x)
                                                    dragOffsetY -= (target.offset.y - currentItem.offset.y)
                                                }
                                            }
                                        }
                                    )
                                },
                            state = gridState,
                            columns = columns,
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                            verticalArrangement = Arrangement.spacedBy(gridSpacing)
                        ) {
                            items(
                                count = gridEntries.size,
                                key = { gridEntries[it].first },
                                contentType = {
                                    if (gridEntries[it].second is Pair<*, *>) "folder_card" else "podcast_card"
                                }
                            ) { index ->
                                val (entryKey, entryValue) = gridEntries[index]
                                val isDragging = draggedKey == entryKey
                                val itemMod = if (isDragging) {
                                    Modifier.graphicsLayer {
                                        scaleX = 1.06f
                                        scaleY = 1.06f
                                        translationX = dragOffsetX
                                        translationY = dragOffsetY
                                        shadowElevation = 12f
                                    }
                                } else {
                                    Modifier.animateItem()
                                }

                                when (entryValue) {
                                    is Pair<*, *> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        val folderPair = entryValue as Pair<app.podiumpodcasts.podium.PodcastFolder, List<app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle>>
                                        app.podiumpodcasts.podium.ui.component.model.FolderCard(
                                            modifier = itemMod,
                                            folder = folderPair.first,
                                            items = folderPair.second,
                                            badgeMode = badgeMode,
                                            showTitles = showGridTitles,
                                            compact = isCompact,
                                            onClick = { openFolderId = folderPair.first.id }
                                        )
                                    }
                                    is app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle -> {
                                        SubscriptionCard(
                                            modifier = itemMod,
                                            bundle = entryValue,
                                            badgeMode = badgeMode,
                                            fillCell = true,
                                            showTitles = showGridTitles,
                                            compact = isCompact,
                                            onClick = { onClickPodcast(entryValue.podcast.origin) }
                                        )
                                    }
                                }
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