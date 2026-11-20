package app.podiumpodcasts.podium.ui.view.model

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileDownloadOff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.podiumpodcasts.podium.AppActivity
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesFilter
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.ui.component.DetailsList
import app.podiumpodcasts.podium.ui.component.DetailsListItemModel
import app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.common.BubbleButton
import app.podiumpodcasts.podium.ui.component.common.ButtonLabelWithIconInset
import app.podiumpodcasts.podium.ui.component.common.ExpandableText
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItem
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItemActionResult
import app.podiumpodcasts.podium.ui.component.common.swipeable.SwipeableItemActions
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerBreakpoint
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.model.ContentFavoriteButton
import app.podiumpodcasts.podium.ui.component.model.ContentSaveToListButton
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeListItem
import app.podiumpodcasts.podium.ui.component.model.podcast.PodcastSearchFilterOrderBar
import app.podiumpodcasts.podium.ui.dialog.DeleteConfirmationDialog
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.PodcastSettingsBottomSheet
import app.podiumpodcasts.podium.ui.formatFileSize
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.PodcastDetailViewModel
import coil3.compose.AsyncImagePainter
import com.materialkolor.ktx.harmonizeWithPrimary
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.graphics.blur.BlurStop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Destinations(
    val index: Int,
    @StringRes val label: Int,
    val icon: ImageVector
) {
    EPISODES(0, R.string.common_episodes, Icons.AutoMirrored.Rounded.List),
    INFO(1, R.string.common_info, Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailView(
    podcast: PodcastModel,
    onBack: () -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val activity = LocalActivity.current as AppActivity

    val vm = viewModel(key = podcast.origin) {
        PodcastDetailViewModel(db, podcast)
    }
    val settingsRepositoryForVm = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val subscriptionsVm = viewModel<app.podiumpodcasts.podium.ui.vm.home.SubscriptionsViewModel>(
        viewModelStoreOwner = activity,
        key = "HOME_SUBSCRIPTIONS"
    ) {
        // Same arguments as Home: whichever screen creates this shared view model first, Home's
        // grid settings must be there (a deep link can open this page first).
        app.podiumpodcasts.podium.ui.vm.home.SubscriptionsViewModel(db, settingsRepositoryForVm)
    }
    val cachedSubscriptions by subscriptionsVm.subscriptionsState.collectAsState()
    val cachedSubModel = remember(cachedSubscriptions, podcast.origin) {
        cachedSubscriptions?.firstOrNull { it.podcast.origin == podcast.origin }?.subscription
    }
    val playerVm = viewModel<MediaPlayerViewModel>()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val itemSwipeRightAction by settingsRepository.behavior.episodeSwipeRightAction.collectAsStateWithLifecycle(
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT
    )
    val secondaryRightType by settingsRepository.behavior.episodeSwipeRightSecondaryAction.collectAsStateWithLifecycle(
        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST
    )
    val itemSwipeLeftAction by settingsRepository.behavior.episodeSwipeLeftAction.collectAsStateWithLifecycle(
        app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED
    )
    val secondaryLeftType by settingsRepository.behavior.episodeSwipeLeftSecondaryAction.collectAsStateWithLifecycle(
        app.podiumpodcasts.podium.EpisodeSwipeAction.DOWNLOAD
    )
    val queueEpisodeIds by remember(playerVm) {
        derivedStateOf { playerVm.queue.toHashSet() }
    }

    val subscription = vm.subscription.collectAsStateWithLifecycle(initialValue = cachedSubModel)
    val hasSeasons by vm.hasSeasons.collectAsStateWithLifecycle(initialValue = false)
    val episodePager = vm.episodePager.collectAsLazyPagingItems()
    val selectedEpisodeIds = remember(podcast.origin) { androidx.compose.runtime.mutableStateListOf<String>() }
    val inSelectionMode = selectedEpisodeIds.isNotEmpty()
    // B6: Back leaves multi-select before it leaves the page.
    androidx.activity.compose.BackHandler(enabled = inSelectionMode) {
        selectedEpisodeIds.clear()
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // M10: a haptic tick when a long-press starts multi-select.
    val toggleSelection: (String) -> Unit = { id ->
        if (id in selectedEpisodeIds) {
            selectedEpisodeIds.remove(id)
        } else {
            if (selectedEpisodeIds.isEmpty()) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            selectedEpisodeIds.add(id)
        }
    }

    val isSubscribed = subscription.value != null
    var showOverflowMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showMarkAllPlayedDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(vm.refreshError) {
        val err = vm.refreshError ?: return@LaunchedEffect
        vm.snackbarHostState.showSnackbar(err)
        vm.refreshError = null
    }

    if (showMarkAllPlayedDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMarkAllPlayedDialog = false },
            title = { Text("Mark all as played?") },
            text = { Text("All episodes of ${podcast.fetchTitle()} will be marked as played.") },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        showMarkAllPlayedDialog = false
                        scope.launch {
                            db.podcastEpisodePlayStates().markAllPlayedForOrigin(podcast.origin)
                            vm.snackbarHostState.showSnackbar("Marked all episodes as played")
                        }
                    }
                ) {
                    Text("Mark as played")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showMarkAllPlayedDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val BACKDROP_SIZE = 200.dp

    val isAtTop by remember {
        derivedStateOf { vm.lazyListState.firstVisibleItemIndex == 0 }
    }

    val isCollapsed by remember {
        derivedStateOf { vm.lazyListState.firstVisibleItemIndex > 0 }
    }

    val selectionFadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    // M7: the bar's colour animates as the header collapses, instead of snapping.
    val collapsedBarColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isCollapsed) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "podcastHeaderBar"
    )

    val showScrollUpButton by remember {
        derivedStateOf { vm.lazyListState.firstVisibleItemIndex > 7 && vm.selectedDestination == Destinations.EPISODES }
    }

    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = vm.isRefreshing,
        state = pullState,
        onRefresh = { vm.updatePodcast(activity, podcast) },
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = vm.isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    ) {
        BoxWithConstraints {
            Scaffold(
                snackbarHost = {
                    PodiumSnackbarHost(vm.snackbarHostState)
                },
                topBar = {
                    // M7: the selection bar cross-fades in and out instead of swapping instantly.
                    AnimatedContent(
                        targetState = inSelectionMode,
                        transitionSpec = {
                            fadeIn(selectionFadeSpec) togetherWith fadeOut(selectionFadeSpec)
                        },
                        label = "selectionTopBar"
                    ) { selecting ->
                    if(selecting) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            navigationIcon = {
                                IconButton(onClick = { selectedEpisodeIds.clear() }) {
                                    Icon(Icons.Rounded.Close, "Clear selection")
                                }
                            },
                            title = {
                                Text("${selectedEpisodeIds.size} selected")
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val allIds = (0 until episodePager.itemCount)
                                            .mapNotNull { episodePager.peek(it)?.episode?.id }
                                        if(selectedEpisodeIds.size == allIds.size) {
                                            selectedEpisodeIds.clear()
                                        } else {
                                            selectedEpisodeIds.clear()
                                            selectedEpisodeIds.addAll(allIds)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.SelectAll, "Select all")
                                }
                            }
                        )
                    } else {
                        TopAppBar(
                            navigationIcon = {
                                BackButton {
                                    onBack()
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = collapsedBarColor,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            title = {
                                // M7: the title fades in as the header scrolls away.
                                AnimatedVisibility(
                                    visible = isCollapsed,
                                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
                                ) {
                                    Text(
                                        text = podcast.fetchTitle(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            actions = {
                                BubbleButton(
                                    icon = Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.route_podcast_settings),
                                    onClick = {
                                        vm.showSettingsBottomSheet.value = true
                                    }
                                )
                                Box {
                                    BubbleButton(
                                        icon = androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                        contentDescription = "More options",
                                        onClick = { showOverflowMenu = true }
                                    )
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = showOverflowMenu,
                                        onDismissRequest = { showOverflowMenu = false }
                                    ) {
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text("Mark all as played") },
                                                leadingIcon = {
                                                    Icon(Icons.Rounded.Done, contentDescription = null)
                                                },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showMarkAllPlayedDialog = true
                                                }
                                            )
                                            if (isSubscribed) {
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text(stringResource(R.string.common_action_unsubscribe)) },
                                                leadingIcon = {
                                                    Icon(Icons.Rounded.FavoriteBorder, contentDescription = null)
                                                },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    vm.showUnsubscribeDialog.value = true
                                                }
                                            )
                                        }
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(stringResource(R.string.common_action_delete)) },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Delete, contentDescription = null)
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                vm.showDeleteDialog.value = true
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                    }
                },
                bottomBar = {
                    // M7: the multi-select action bar slides up instead of popping in.
                    AnimatedVisibility(
                        visible = inSelectionMode,
                        enter = slideInVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } +
                            fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                        exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } +
                            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = 8.dp,
                                        bottom = 8.dp + if (maxWidth <= FloatingMediaPlayerBreakpoint) {
                                            LocalFloatingMediaPlayerHeight.current
                                        } else {
                                            0.dp
                                        }
                                    ),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val selectedBundles = (0 until episodePager.itemCount)
                                    .mapNotNull { episodePager.peek(it) }
                                    .filter { it.episode.id in selectedEpisodeIds }

                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        selectedBundles.asReversed().forEach { playerVm.playNext(context, it) }
                                        selectedEpisodeIds.clear()
                                    }
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.QueuePlayNext, contentDescription = null)
                                        Text("Play next", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        selectedBundles.forEach { playerVm.enqueue(context, it) }
                                        selectedEpisodeIds.clear()
                                    }
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                                        Text("Play last", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                val actionScope = rememberCoroutineScope()
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        val toDownload = selectedBundles.toList()
                                        selectedEpisodeIds.clear()
                                        actionScope.launch {
                                            toDownload.forEach {
                                                app.podiumpodcasts.podium.manager.DownloadManager.downloadEpisode(context, db, it.episode.id)
                                            }
                                        }
                                    }
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.FileDownload, contentDescription = null)
                                        Text("Download", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        val ids = selectedEpisodeIds.toList()
                                        scope.launch {
                                            ids.forEach { id ->
                                                db.podcastEpisodePlayStates().savePlayed(id, true)
                                                playerVm.removeEpisodeFromPlaybackAndQueue(id)
                                            }
                                        }
                                        selectedEpisodeIds.clear()
                                    }
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.Done, contentDescription = null)
                                        Text("Mark as played", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = showScrollUpButton,
                        enter = app.podiumpodcasts.podium.ui.theme.AppMotion.popIn(),
                        exit = app.podiumpodcasts.podium.ui.theme.AppMotion.popOut()
                    ) {
                        FloatingActionButton(
                            modifier = Modifier.padding(
                                bottom = if(maxWidth <= FloatingMediaPlayerBreakpoint)
                                    LocalFloatingMediaPlayerHeight.current
                                else
                                    0.dp
                            ),
                            onClick = {
                                scope.launch {
                                    vm.lazyListState.animateScrollToItem(0)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                stringResource(R.string.common_action_scroll_to_top)
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) { inset ->
                Box {
                    if(isAtTop) {
                        val backdropHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                            (inset.calculateTopPadding() + BACKDROP_SIZE).toPx()
                        }
                        ShimmerAsyncImage(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(inset.calculateTopPadding() + BACKDROP_SIZE)
                                // M7: fades out with the scroll (read while drawing, so no
                                // recomposition), so it doesn't pop when the header leaves.
                                .graphicsLayer {
                                    val offset = if (vm.lazyListState.firstVisibleItemIndex == 0) {
                                        vm.lazyListState.firstVisibleItemScrollOffset.toFloat()
                                    } else backdropHeightPx
                                    alpha = (1f - offset / backdropHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                }
                                .blur(
                                    blurRadiusSpec = BlurRadiusSpec.verticalGradient(
                                        listOf(
                                            BlurStop(fraction = 0f, radius = 0.dp),
                                            BlurStop(fraction = 0.35f, radius = 4.dp),
                                            BlurStop(fraction = 1f, radius = 24.dp),
                                        )
                                    ),
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle
                                ),

                            model = podcast.imageUrl,
                            contentDescription = null,

                            contentScale = ContentScale.Crop
                        )
                    }

                    LazyColumn(
                        state = if(episodePager.itemCount != 0)
                            vm.lazyListState
                        else
                            rememberLazyListState(),
                        contentPadding = PaddingValues(
                            bottom = inset.calculateBottomPadding()
                        )
                    ) {
                        item(
                            key = "BACKDROP"
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(inset.calculateTopPadding() + BACKDROP_SIZE)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surfaceContainer
                                            )
                                        )
                                    )
                            ) {
                                ShimmerAsyncImage(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                        .size(192.dp)
                                        .shadow(
                                            elevation = 8.dp,
                                            shape = RoundedCornerShape(16.dp),
                                            clip = true
                                        ),

                                    model = podcast.imageUrl,
                                    contentDescription = null,

                                    onState = {
                                        // attempt to recalculate image seed color when = 0
                                        if(podcast.imageSeedColor != 0) return@ShimmerAsyncImage

                                        if(it is AsyncImagePainter.State.Success) {
                                            vm.updateImageSeedColor(activity, it.result.image)
                                        }
                                    },

                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        item(
                            key = "HEADING"
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column {
                                    Spacer(Modifier.height(8.dp))

                                    SelectionContainer(
                                        Modifier
                                            .padding(start = 16.dp, end = 16.dp)
                                    ) {
                                        Column {
                                            ExpandableText(
                                                text = podcast.fetchTitle(),
                                                modifier = Modifier.semantics { heading() },
                                                autoSize = TextAutoSize.StepBased(
                                                    minFontSize = 24.sp,
                                                    maxFontSize = 40.sp
                                                ),
                                                minAutoSize = 24.sp,
                                                maxLines = 3,
                                                style = Typography.displayMediumEmphasized.copy(
                                                    lineHeight = TextUnit.Unspecified
                                                )
                                            )

                                            Spacer(Modifier.height(4.dp))

                                            Text(
                                                text = podcast.author,
                                                style = Typography.labelLarge
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }

                        item(
                            key = "BUTTON_ROW"
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(Modifier.width(16.dp))

                                    ToggleButton(
                                        checked = isSubscribed,
                                        onCheckedChange = {
                                            if(!isSubscribed) {
                                                vm.subscribe()
                                            } else {
                                                vm.showUnsubscribeDialog.value = true
                                            }
                                        },

                                        colors = ToggleButtonDefaults.colors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        AnimatedContent(isSubscribed, transitionSpec = app.podiumpodcasts.podium.ui.theme.AppMotion.contentSwap()) { subscribed ->
                                            ButtonLabelWithIconInset(
                                                icon = when(subscribed) {
                                                    true -> Icons.Rounded.Done
                                                    false -> Icons.Rounded.FavoriteBorder
                                                },
                                                label = when(subscribed) {
                                                    true -> "Subscribed"
                                                    false -> stringResource(R.string.common_action_subscribe)
                                                }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    VerticalDivider(
                                        Modifier.height(24.dp)
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    ContentSaveToListButton(
                                        contentId = podcast.origin,
                                        isPodcast = true
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    ContentFavoriteButton(
                                        contentId = podcast.origin,
                                        isPodcast = true
                                    )

                                    Spacer(Modifier.width(16.dp))
                                }
                            }
                        }

                        item(
                            key = "SPACER_TAB_ROW"
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        item(
                            key = "TAB_ROW"
                        ) {
                            PrimaryTabRow(
                                selectedTabIndex = vm.selectedDestination.index,
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Destinations.entries.forEach {
                                    Tab(
                                        selected = vm.selectedDestination == it,
                                        onClick = { vm.selectedDestination = it },
                                        text = {
                                            Text(
                                                text = stringResource(it.label),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = it.icon,
                                                contentDescription = stringResource(it.label)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if(vm.selectedDestination == Destinations.INFO) {
                            podcastDetailViewInfoDestination(
                                podcast = podcast
                            )
                        } else if(vm.selectedDestination == Destinations.EPISODES) {
                            podcastDetailViewEpisodesDestination(
                                vm = vm,
                                scope = scope,
                                hasSeasons = hasSeasons,
                                episodePager = episodePager,
                                selectedEpisodeIds = selectedEpisodeIds,
                                playerVm = playerVm,
                                queueEpisodeIds = queueEpisodeIds,
                                itemSwipeRightAction = itemSwipeRightAction,
                                secondaryRightType = secondaryRightType,
                                itemSwipeLeftAction = itemSwipeLeftAction,
                                secondaryLeftType = secondaryLeftType,
                                onToggleSelection = toggleSelection,
                                onClickEpisode = onClickEpisode
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

    if(vm.showSettingsBottomSheet.value) PodcastSettingsBottomSheet(
        onDismiss = {
            vm.showSettingsBottomSheet.value = false
        },
        podcast = podcast
    )

    if(vm.showDeleteDialog.value) DeleteConfirmationDialog(
        onDismiss = {
            vm.showDeleteDialog.value = false
        },
        title = "Delete podcast?",
        itemName = podcast.fetchTitle(),
        additionalText = stringResource(R.string.dialog_delete_confirmation_podcast),
        onConfirm = {
            vm.deletePodcast()
            onBack()
        }
    )

    if(vm.showUnsubscribeDialog.value) androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            vm.showUnsubscribeDialog.value = false
        },
        title = {
            Text(stringResource(R.string.common_action_unsubscribe))
        },
        text = {
            Text("Unsubscribe from ${podcast.fetchTitle()}? Downloaded episodes and history will be kept.")
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    vm.showUnsubscribeDialog.value = false
                    vm.unsubscribe()
                }
            ) {
                Text(stringResource(R.string.common_action_unsubscribe))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    vm.showUnsubscribeDialog.value = false
                }
            ) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.podcastDetailViewEpisodesDestination(
    vm: PodcastDetailViewModel,
    scope: CoroutineScope,
    hasSeasons: Boolean,
    episodePager: LazyPagingItems<PodcastEpisodeBundle>,
    selectedEpisodeIds: MutableList<String>,
    playerVm: MediaPlayerViewModel,
    queueEpisodeIds: Set<String>,
    itemSwipeRightAction: app.podiumpodcasts.podium.EpisodeSwipeAction,
    secondaryRightType: app.podiumpodcasts.podium.EpisodeSwipeAction,
    itemSwipeLeftAction: app.podiumpodcasts.podium.EpisodeSwipeAction,
    secondaryLeftType: app.podiumpodcasts.podium.EpisodeSwipeAction,
    onToggleSelection: (episodeId: String) -> Unit = { id ->
        if (id in selectedEpisodeIds) selectedEpisodeIds.remove(id) else selectedEpisodeIds.add(id)
    },
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit
) {
    item(
        key = "SPACER_EPISODES:1"
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Spacer(Modifier.height(8.dp))
        }
    }

    item(
        key = "BAR"
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .fillMaxWidth()
                .animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
)
        ) {
            PodcastSearchFilterOrderBar(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                state = vm.searchFilterOrderBarState
            )

            Row(
                modifier = Modifier
                    .graphicsLayer(
                        compositingStrategy = CompositingStrategy.Offscreen
                    )
                    .drawWithContent {
                        drawContent()
                        val fadePx = 24.dp.toPx()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startX = size.width - fadePx,
                                endX = size.width
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val hidePlayed = PodcastEpisodesFilter.PLAYED in vm.searchFilterOrderBarState.negativeFilter
                FilterChip(
                    selected = hidePlayed,
                    onClick = {
                        if(hidePlayed) {
                            vm.searchFilterOrderBarState.negativeFilter.remove(PodcastEpisodesFilter.PLAYED)
                        } else {
                            vm.searchFilterOrderBarState.filter.remove(PodcastEpisodesFilter.PLAYED)
                            vm.searchFilterOrderBarState.negativeFilter.add(PodcastEpisodesFilter.PLAYED)
                        }
                    },
                    label = { Text("Exclude played") }
                )

                val onlyDownloaded = PodcastEpisodesFilter.DOWNLOADED in vm.searchFilterOrderBarState.filter
                FilterChip(
                    selected = onlyDownloaded,
                    onClick = {
                        if(onlyDownloaded) {
                            vm.searchFilterOrderBarState.filter.remove(PodcastEpisodesFilter.DOWNLOADED)
                        } else {
                            vm.searchFilterOrderBarState.negativeFilter.remove(PodcastEpisodesFilter.DOWNLOADED)
                            vm.searchFilterOrderBarState.filter.add(PodcastEpisodesFilter.DOWNLOADED)
                        }
                    },
                    label = { Text("Downloaded") }
                )

                val inProgress = PodcastEpisodesFilter.PAUSED in vm.searchFilterOrderBarState.filter
                FilterChip(
                    selected = inProgress,
                    onClick = {
                        if(inProgress) {
                            vm.searchFilterOrderBarState.filter.remove(PodcastEpisodesFilter.PAUSED)
                        } else {
                            vm.searchFilterOrderBarState.negativeFilter.remove(PodcastEpisodesFilter.PAUSED)
                            vm.searchFilterOrderBarState.filter.add(PodcastEpisodesFilter.PAUSED)
                        }
                    },
                    label = { Text("In progress") }
                )

                if (hasSeasons) {
                    val groupBySeason = vm.searchFilterOrderBarState.groupBySeason.value
                    FilterChip(
                        selected = groupBySeason,
                        onClick = {
                            vm.searchFilterOrderBarState.groupBySeason.value = !groupBySeason
                        },
                        label = { Text("Group by season") }
                    )
                }
            }
        }
    }

    item {
        val isSearchFilterOrderBarStateDefault by vm.searchFilterOrderBarState.isDefault()

        if(episodePager.itemCount == 0 && episodePager.loadState.isIdle) InfoLayout(
            modifier = Modifier
                .heightIn(min = 200.dp)
                .animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),
            icon = when(isSearchFilterOrderBarStateDefault) {
                true -> Icons.Rounded.Inbox
                false -> Icons.Rounded.SearchOff
            },
            title = {
                when(isSearchFilterOrderBarStateDefault) {
                    true -> stringResource(R.string.route_podcast_empty_title)
                    false -> stringResource(R.string.route_podcast_search_empty_title)
                }
            },
        ) {
            Text(
                text = when(isSearchFilterOrderBarStateDefault) {
                    true -> stringResource(R.string.route_podcast_empty_text)
                    false -> stringResource(R.string.route_podcast_search_empty_text)
                },
                textAlign = TextAlign.Center
            )
        }
    }

    items(
        count = episodePager.itemCount,
        key = episodePager.itemKey { "EPISODE:${it.episode.id}" },
        contentType = { "episode" }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            episodePager[it]?.let { episodeBundle ->
                val isSelected = episodeBundle.episode.id in selectedEpisodeIds
                val inQueue = episodeBundle.episode.id in queueEpisodeIds
                val groupBySeason = hasSeasons && vm.searchFilterOrderBarState.groupBySeason.value
                val currentSeason = episodeBundle.episode.season
                val prevLoaded = if (it > 0) episodePager.peek(it - 1) else null
                val nextLoaded = if (it < episodePager.itemCount - 1) episodePager.peek(it + 1) else null
                val isFirstInSeason = groupBySeason && (it == 0 || (prevLoaded != null && prevLoaded.episode.season != currentSeason))
                val isLastInSeason = groupBySeason && (it == episodePager.itemCount - 1 || (nextLoaded != null && nextLoaded.episode.season != currentSeason))
                val rowIndex = if (!groupBySeason) {
                    it
                } else when {
                    isFirstInSeason && isLastInSeason -> 0
                    isFirstInSeason -> 0
                    isLastInSeason -> 2
                    else -> 1
                }
                val rowCount = if (!groupBySeason) {
                    episodePager.itemCount
                } else if (isFirstInSeason && isLastInSeason) {
                    1
                } else {
                    3
                }

                Column {
                    if (isFirstInSeason) {
                        Text(
                            text = if (currentSeason != null) "Season $currentSeason" else "Other episodes",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(
                                    start = 20.dp,
                                    end = 20.dp,
                                    top = if (it == 0) 8.dp else 16.dp,
                                    bottom = 6.dp
                                )
                                .semantics { heading() }
                        )
                    }

                Box(
                    Modifier.padding(
                        top = 2.dp,

                        start = 16.dp,
                        end = 16.dp
                    )
                ) {
                    val markedAsPlayed = stringResource(R.string.snackbar_marked_as_played)
                    val markedAsUnplayed = stringResource(R.string.snackbar_marked_as_unplayed)
                    val isCurrentlyPlayed = episodeBundle.playState?.played == true

                    SwipeableItem(
                        snackbarHostState = vm.snackbarHostState,
                        scope = scope,
                        shape = app.podiumpodcasts.podium.ui.component.model.episode.episodeRowShape(rowIndex, rowCount),

                        startAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                            actionType = itemSwipeRightAction,
                            bundle = episodeBundle,
                            playerVm = playerVm,
                            inQueue = inQueue,
                            onMarkPlayed = { markPlayed ->
                                if (markPlayed) {
                                    vm.markAsPlayed(episodeBundle)
                                    playerVm.removeEpisodeFromPlaybackAndQueue(episodeBundle.episode.id)
                                } else vm.markAsUnplayed(episodeBundle)
                            }
                        ),
                        secondaryStartAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                            actionType = app.podiumpodcasts.podium.ui.component.common.swipeable.consolidateSecondarySwipeAction(
                                primary = itemSwipeRightAction,
                                secondary = secondaryRightType,
                                inQueue = inQueue
                            ),
                            bundle = episodeBundle,
                            playerVm = playerVm,
                            inQueue = inQueue,
                            onMarkPlayed = { markPlayed ->
                                if (markPlayed) {
                                    vm.markAsPlayed(episodeBundle)
                                    playerVm.removeEpisodeFromPlaybackAndQueue(episodeBundle.episode.id)
                                } else vm.markAsUnplayed(episodeBundle)
                            }
                        ),
                        endAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                            actionType = itemSwipeLeftAction,
                            bundle = episodeBundle,
                            playerVm = playerVm,
                            inQueue = inQueue,
                            onMarkPlayed = { markPlayed ->
                                if (markPlayed) {
                                    vm.markAsPlayed(episodeBundle)
                                    playerVm.removeEpisodeFromPlaybackAndQueue(episodeBundle.episode.id)
                                } else vm.markAsUnplayed(episodeBundle)
                            }
                        ),
                        secondaryEndAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                            actionType = app.podiumpodcasts.podium.ui.component.common.swipeable.consolidateSecondarySwipeAction(
                                primary = itemSwipeLeftAction,
                                secondary = secondaryLeftType,
                                inQueue = inQueue
                            ),
                            bundle = episodeBundle,
                            playerVm = playerVm,
                            inQueue = inQueue,
                            onMarkPlayed = { markPlayed ->
                                if (markPlayed) {
                                    vm.markAsPlayed(episodeBundle)
                                    playerVm.removeEpisodeFromPlaybackAndQueue(episodeBundle.episode.id)
                                } else vm.markAsUnplayed(episodeBundle)
                            }
                        ),
                        content = {
                            PodcastEpisodeListItem(
                                bundle = episodeBundle,
                                index = rowIndex,
                                count = rowCount,
                                isSelected = isSelected,
                                showArtwork = false,
                                inQueue = inQueue,
                                playerVm = playerVm,
                                onLongClick = {
                                    onToggleSelection(episodeBundle.episode.id)
                                },
                                onClick = {
                                    if(selectedEpisodeIds.isNotEmpty()) {
                                        onToggleSelection(episodeBundle.episode.id)
                                    } else {
                                        onClickEpisode(episodeBundle.episode)
                                    }
                                }
                            )
                        },
                    )
                }
                }
            }
        }
    }

    item {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.podcastDetailViewInfoDestination(
    podcast: PodcastModel
) {
    item(
        key = "INFO"
    ) {
        val uriHandler = LocalUriHandler.current

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                Modifier.padding(16.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    val parsedDesc = remember(podcast.origin, podcast.description) {
                        AnnotatedString.fromHtml(
                            htmlString = podcast.description.replace("\n", "<br>")
                        )
                    }
                    SelectionContainer {
                        Text(
                            modifier = Modifier
                                .padding(16.dp),
                            text = parsedDesc
                        )
                    }
                }

                val categories = remember(podcast.categoriesJson) {
                    app.podiumpodcasts.podium.api.rss.PodcastMetadataJson.decodeStrings(podcast.categoriesJson)
                }
                val persons = remember(podcast.personsJson) {
                    app.podiumpodcasts.podium.api.rss.PodcastMetadataJson.decodePersons(podcast.personsJson)
                }
                val funding = remember(podcast.fundingJson) {
                    app.podiumpodcasts.podium.api.rss.PodcastMetadataJson.decodeFunding(podcast.fundingJson)
                }

                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    app.podiumpodcasts.podium.ui.component.model.podcast.PodcastCategoriesRow(
                        categories = categories
                    )
                }

                if (persons.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    app.podiumpodcasts.podium.ui.component.model.podcast.PodcastPersonsRow(
                        persons = persons,
                        onClickUrl = { uriHandler.openUri(it) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                DetailsList(
                    items = buildList {
                        funding.forEach { item ->
                            add(
                                DetailsListItemModel(
                                    icon = Icons.Rounded.Favorite,
                                    label = R.string.common_support,
                                    value = item.label.ifBlank { item.url },
                                    onClick = {
                                        uriHandler.openUri(item.url)
                                    }
                                )
                            )
                        }
                        add(
                            DetailsListItemModel(
                                icon = Icons.Rounded.Link,
                                label = R.string.common_source,
                                value = podcast.link,
                                onClick = {
                                    uriHandler.openUri(podcast.link)
                                }
                            )
                        )
                        add(
                            DetailsListItemModel(
                                icon = Icons.Rounded.RssFeed,
                                label = R.string.common_rss_feed,
                                value = podcast.fetchFeedUrl()
                            )
                        )
                        add(
                            DetailsListItemModel(
                                icon = Icons.Rounded.Language,
                                label = R.string.common_language_code,
                                value = podcast.languageCode
                            )
                        )
                        add(
                            DetailsListItemModel(
                                icon = Icons.Rounded.DataUsage,
                                label = R.string.common_data_usage_per_update,
                                value = formatFileSize(podcast.fileSize)
                            )
                        )
                    }
                )
            }
        }
    }
}