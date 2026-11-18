package app.podiumpodcasts.podium.ui.route.home

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.SubscriptionsLayout
import app.podiumpodcasts.podium.SubscriptionsSort
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.PodcastGridOptionsSheet
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.model.SubscriptionCard
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
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

    val activity = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
    val subscriptionsVm = viewModel(
        viewModelStoreOwner = activity,
        key = "HOME_SUBSCRIPTIONS"
    ) { SubscriptionsViewModel(db) }
    val playerVm = viewModel<MediaPlayerViewModel>(viewModelStoreOwner = activity)

    val rawSubscriptions by subscriptionsVm.subscriptionsState.collectAsStateWithLifecycle()
    val allSubscriptions = rawSubscriptions ?: emptyList()
    val unplayedCounts by subscriptionsVm.unplayedCountsState.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.podiumpodcasts.podium.PodcastGridOrganization.init(context)
        }
    }

    val layout by settingsRepository.appearance.subscriptionsLayout.collectAsStateWithLifecycle(SubscriptionsLayout.LARGE_GRID)
    val sort by settingsRepository.appearance.subscriptionsSort.collectAsStateWithLifecycle(SubscriptionsSort.RECENTLY_ADDED)
    val badgeMode by settingsRepository.appearance.subscriptionsBadgeMode.collectAsStateWithLifecycle(SubscriptionsBadgeMode.UNPLAYED_COUNT)
    val showGridTitles by settingsRepository.appearance.showGridTitles.collectAsStateWithLifecycle(true)
    val gridOrgState by app.podiumpodcasts.podium.PodcastGridOrganization.state.collectAsStateWithLifecycle()

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showOrganizeFoldersDialog by remember { mutableStateOf(false) }
    var reorderMode by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = reorderMode) {
        reorderMode = false
    }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var moveToFolderTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var contextMenuTarget by remember { mutableStateOf<app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle?>(null) }
    var expandedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    val sortedSubscriptions = remember(allSubscriptions, sort) {
        when (sort) {
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
    val gridState = subscriptionsVm.lazyGridState
    val listState = subscriptionsVm.lazyListState

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

    contextMenuTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { contextMenuTarget = null },
            title = {
                Text(
                    text = target.podcast.fetchTitle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            contextMenuTarget = null
                            onClickPodcast(target.podcast.origin)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open podcast", modifier = Modifier.fillMaxWidth())
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            contextMenuTarget = null
                            scope.launch {
                                db.podcastEpisodePlayStates().markAllPlayedForOrigin(target.podcast.origin)
                                snackbarHostState.showSnackbar("Marked all episodes as listened")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mark all as listened", modifier = Modifier.fillMaxWidth())
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            contextMenuTarget = null
                            moveToFolderTarget = target.podcast.origin to target.podcast.fetchTitle()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Move to folder", modifier = Modifier.fillMaxWidth())
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            contextMenuTarget = null
                            scope.launch {
                                app.podiumpodcasts.podium.manager.SubscriptionManager(db)
                                    .unsubscribe(target.podcast.origin)
                                snackbarHostState.showSnackbar("Unsubscribed from ${target.podcast.fetchTitle()}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.common_action_unsubscribe),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { contextMenuTarget = null }) {
                    Text("Close")
                }
            }
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

    if (showOptionsSheet) {
        PodcastGridOptionsSheet(
            layout = layout,
            sort = sort,
            badgeMode = badgeMode,
            showGridTitles = showGridTitles,
            hasFolders = gridOrgState.folders.isNotEmpty(),
            reorderMode = reorderMode,
            onLayoutChange = { value ->
                scope.launch { settingsRepository.appearance.setSubscriptionsLayout(value) }
            },
            onSortChange = { value ->
                scope.launch { settingsRepository.appearance.setSubscriptionsSort(value) }
            },
            onBadgeModeChange = { value ->
                scope.launch { settingsRepository.appearance.setSubscriptionsBadgeMode(value) }
            },
            onShowGridTitlesChange = { enabled ->
                scope.launch { settingsRepository.appearance.setShowGridTitles(enabled) }
            },
            onToggleReorderMode = { enabled ->
                reorderMode = enabled
                if (enabled && sort != SubscriptionsSort.CUSTOM) {
                    scope.launch { settingsRepository.appearance.setSubscriptionsSort(SubscriptionsSort.CUSTOM) }
                }
            },
            onNewFolder = { showCreateFolderDialog = true },
            onOrganizeFolders = { showOrganizeFoldersDialog = true },
            onDismiss = { showOptionsSheet = false }
        )
    }

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost(snackbarHostState) },
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
        }
    ) { inset ->
        Column(
            Modifier
                .padding(inset)
                .fillMaxSize()
        ) {
            if (reorderMode) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Drag & drop to reorder podcasts",
                            style = Typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(onClick = { reorderMode = false }) {
                            Text("Done")
                        }
                    }
                }
            }

            if (rawSubscriptions != null && allSubscriptions.isEmpty()) {
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

                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = onClickDiscover,
                            modifier = Modifier.fillMaxWidth(0.75f)
                        ) {
                            Icon(
                                Icons.Rounded.Explore,
                                contentDescription = stringResource(R.string.route_discover),
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.route_discover))
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onClickAddPodcast,
                            modifier = Modifier.fillMaxWidth(0.75f)
                        ) {
                            Icon(
                                Icons.Rounded.RssFeed,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Add by RSS URL or OPML")
                        }
                    }
                )
            } else if (allSubscriptions.isNotEmpty()) {
                val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val refreshWorkInfos by androidx.work.WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(
                        app.podiumpodcasts.podium.background.worker.PeriodicPodcastUpdateWorker.INSTANT_UNIQUE_NAME
                    )
                    .collectAsState(initial = emptyList())
                val isWorkRefreshing = refreshWorkInfos.any {
                    it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        it.state == androidx.work.WorkInfo.State.RUNNING
                }
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = isWorkRefreshing || isRefreshing,
                    state = pullState,
                    onRefresh = {
                        if (!isWorkRefreshing && !isRefreshing) {
                            app.podiumpodcasts.podium.background.worker.PeriodicPodcastUpdateWorker.enqueueNow(context)
                        }
                    },
                    indicator = {
                        androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                            state = pullState,
                            isRefreshing = isWorkRefreshing || isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if(layout == SubscriptionsLayout.LIST) {
                        val byOrigin = remember(allSubscriptions) { allSubscriptions.associateBy { it.podcast.origin } }
                        val inFolders = remember(gridOrgState) { gridOrgState.folders.flatMap { it.origins }.toSet() }
                        val rootListPodcasts = remember(sortedSubscriptions, inFolders) {
                            sortedSubscriptions.filterNot { it.podcast.origin in inFolders }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                        ) {
                            gridOrgState.folders.forEach { folder ->
                                val folderSubs = folder.origins.mapNotNull { byOrigin[it] }
                                val isExpanded = folder.id in expandedFolderIds
                                val totalUnread = folderSubs.sumOf { unplayedCounts[it.podcast.origin] ?: it.subscription.newEpisodes }
                                item(key = "list_folder:${folder.id}", contentType = "folder_row") {
                                    Surface(
                                        onClick = {
                                            expandedFolderIds = if (isExpanded) expandedFolderIds - folder.id else expandedFolderIds + folder.id
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Rounded.CreateNewFolder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = folder.name,
                                                    style = Typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${folderSubs.size} ${if (folderSubs.size == 1) "podcast" else "podcasts"}",
                                                    style = Typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (totalUnread > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    if (badgeMode == SubscriptionsBadgeMode.UNPLAYED_COUNT) {
                                                        Text(if (totalUnread > 99) "99+" else totalUnread.toString())
                                                    }
                                                }
                                            }
                                            Text(
                                                text = if (isExpanded) "▾" else "▸",
                                                style = Typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                if (isExpanded) {
                                    itemsIndexed(
                                        items = folderSubs,
                                        key = { _, b -> "folder:${folder.id}:${b.podcast.origin}" },
                                        contentType = { _, _ -> "podcast_row" }
                                    ) { index, bundle ->
                                        SegmentedListItem(
                                            modifier = Modifier.padding(start = 16.dp),
                                            shapes = ListItemDefaults.segmentedShapes(
                                                index = index,
                                                count = folderSubs.size
                                            ),
                                            leadingContent = {
                                                ShimmerAsyncImage(
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
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
                                                val badgeCount = unplayedCounts[bundle.podcast.origin] ?: bundle.subscription.newEpisodes
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    if (badgeCount > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                                                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                            if (badgeMode == SubscriptionsBadgeMode.UNPLAYED_COUNT) {
                                                                Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                                                            }
                                                        }
                                                    }
                                                    IconButton(onClick = { contextMenuTarget = bundle }) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.MoreVert,
                                                            contentDescription = "Podcast options",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = { onClickPodcast(bundle.podcast.origin) }
                                        )
                                    }
                                }
                            }
                            itemsIndexed(
                                items = rootListPodcasts,
                                key = { _, bundle -> bundle.podcast.origin },
                                contentType = { _, _ -> "podcast_row" }
                            ) { index, bundle ->
                                SegmentedListItem(
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = rootListPodcasts.size
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
                                        val badgeCount = unplayedCounts[bundle.podcast.origin] ?: bundle.subscription.newEpisodes
                                        androidx.compose.foundation.layout.Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if(badgeCount > 0 && badgeMode != SubscriptionsBadgeMode.OFF) {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primary
                                                ) {
                                                    if(badgeMode == SubscriptionsBadgeMode.UNPLAYED_COUNT) {
                                                        Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                                                    }
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    contextMenuTarget = bundle
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                                    contentDescription = "Podcast options",
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
                                .pointerInput(reorderMode) {
                                    if (!reorderMode) return@pointerInput
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
                                            unplayedCounts = unplayedCounts,
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
                                            unplayedCountOverride = unplayedCounts[entryValue.podcast.origin],
                                            fillCell = true,
                                            showTitles = showGridTitles,
                                            compact = isCompact,
                                            onLongClick = if (!reorderMode) {
                                                { contextMenuTarget = entryValue }
                                            } else null,
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
    }