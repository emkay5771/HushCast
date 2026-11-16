package app.podiumpodcasts.podium.ui.view.model

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
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
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Destinations(
    val index: Int,
    @StringRes val label: Int,
    val icon: ImageVector
) {
    EPISODES(0, R.string.common_episodes, Icons.AutoMirrored.Filled.QueueMusic),
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
    val playerVm = viewModel<MediaPlayerViewModel>()
    val context = androidx.compose.ui.platform.LocalContext.current

    val subscription = vm.subscription.collectAsState(null)
    val episodePager = vm.episodePager.collectAsLazyPagingItems()
    val selectedEpisodeIds = remember(podcast.origin) { androidx.compose.runtime.mutableStateListOf<String>() }

    val isSubscribed = subscription.value != null
    val enableNotifications = subscription.value?.enableNotifications == true
    val enableAutoDownload = subscription.value?.enableAutoDownload == true

    val BACKDROP_SIZE = 200.dp

    val isAtTop by remember {
        derivedStateOf { vm.lazyListState.firstVisibleItemIndex == 0 }
    }

    val showScrollUpButton by remember {
        derivedStateOf { vm.lazyListState.firstVisibleItemIndex > 7 && vm.selectedDestination == Destinations.EPISODES }
    }

    PullToRefreshBox(
        isRefreshing = vm.isRefreshing,
        onRefresh = { vm.updatePodcast(activity, podcast) }
    ) {
        BoxWithConstraints {
            Scaffold(
                snackbarHost = {
                    PodiumSnackbarHost(vm.snackbarHostState)
                },
                topBar = {
                    if(selectedEpisodeIds.isNotEmpty()) {
                        TopAppBar(
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
                                        val selectedBundles = (0 until episodePager.itemCount)
                                            .mapNotNull { episodePager.peek(it) }
                                            .filter { it.episode.id in selectedEpisodeIds }
                                        selectedBundles.asReversed().forEach { playerVm.playNext(context, it) }
                                        selectedEpisodeIds.clear()
                                    }
                                ) {
                                    Icon(Icons.Rounded.VerticalAlignTop, "Play Next")
                                }
                                IconButton(
                                    onClick = {
                                        val selectedBundles = (0 until episodePager.itemCount)
                                            .mapNotNull { episodePager.peek(it) }
                                            .filter { it.episode.id in selectedEpisodeIds }
                                        selectedBundles.forEach { playerVm.enqueue(context, it) }
                                        selectedEpisodeIds.clear()
                                    }
                                ) {
                                    Icon(Icons.Rounded.VerticalAlignBottom, "Play Last")
                                }
                                IconButton(
                                    onClick = {
                                        val ids = selectedEpisodeIds.toList()
                                        scope.launch {
                                            ids.forEach { id ->
                                                db.podcastEpisodePlayStates().savePlayed(id, true)
                                            }
                                        }
                                        selectedEpisodeIds.clear()
                                    }
                                ) {
                                    Icon(Icons.Rounded.Done, "Mark Played")
                                }
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
                                    Icon(Icons.Rounded.SelectAll, "Select All")
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
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent
                            ),
                            title = { },
                            actions = {
                                BubbleButton(
                                    icon = Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.route_podcast_settings),
                                    onClick = {
                                        vm.showSettingsBottomSheet.value = true
                                    }
                                )
                            }
                        )
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = showScrollUpButton,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
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
                        ShimmerAsyncImage(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(inset.calculateTopPadding() + BACKDROP_SIZE)
                                .hazeEffect(),

                            model = podcast.imageUrl,
                            contentDescription = null,

                            contentScale = ContentScale.Crop
                        )
                    }

                    LazyColumn(
                        state = if(episodePager.itemCount != 0)
                            vm.lazyListState
                        else
                            rememberLazyListState()
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
                                        .align(Alignment.Center)
                                        .size(128.dp)
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
                                    Spacer(Modifier.height(16.dp))

                                    SelectionContainer(
                                        Modifier
                                            .padding(start = 16.dp, end = 16.dp)
                                    ) {
                                        Column {
                                            ExpandableText(
                                                text = podcast.fetchTitle(),
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
                                                vm.unsubscribe()
                                            }
                                        },

                                        colors = ToggleButtonDefaults.toggleButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        AnimatedContent(isSubscribed) { isSubscribed ->
                                            ButtonLabelWithIconInset(
                                                icon = when(isSubscribed) {
                                                    true -> Icons.Rounded.Favorite
                                                    false -> Icons.Rounded.FavoriteBorder
                                                },
                                                label = when(isSubscribed) {
                                                    true -> stringResource(R.string.common_action_unsubscribe)
                                                    false -> stringResource(R.string.common_action_subscribe)
                                                }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    AnimatedVisibility(
                                        visible = isSubscribed,
                                        enter = fadeIn() + expandHorizontally(),
                                        exit = fadeOut() + shrinkHorizontally()
                                    ) {
                                        Row {
                                            FilledIconToggleButton(
                                                checked = enableNotifications,
                                                onCheckedChange = {
                                                    when(enableNotifications) {
                                                        true -> vm.disableNotifications()
                                                        false -> vm.enableNotifications()
                                                    }
                                                },

                                                colors = IconButtonDefaults.filledIconToggleButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surface,
                                                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                                                    checkedContentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                AnimatedContent(enableNotifications) { isEnabled ->
                                                    Icon(
                                                        imageVector = when(isEnabled) {
                                                            true -> Icons.Rounded.NotificationsActive
                                                            false -> Icons.Rounded.NotificationsNone
                                                        },
                                                        contentDescription = when(isEnabled) {
                                                            true -> stringResource(R.string.common_downloading)
                                                            false -> stringResource(R.string.common_action_download)
                                                        }
                                                    )
                                                }
                                            }

                                            FilledIconToggleButton(
                                                checked = enableAutoDownload,
                                                onCheckedChange = {
                                                    when(enableAutoDownload) {
                                                        true -> vm.disableAutoDownload()
                                                        false -> vm.enableAutoDownload()
                                                    }
                                                },

                                                colors = IconButtonDefaults.filledIconToggleButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surface,
                                                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                                                    checkedContentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                AnimatedContent(enableAutoDownload) { isEnabled ->
                                                    Icon(
                                                        imageVector = when(isEnabled) {
                                                            true -> Icons.Rounded.FileDownloadOff
                                                            false -> Icons.Rounded.FileDownload
                                                        },
                                                        contentDescription = when(isEnabled) {
                                                            true -> stringResource(R.string.common_action_disable_auto_download)
                                                            false -> stringResource(R.string.common_action_enable_auto_download)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    VerticalDivider(
                                        Modifier.height(24.dp)
                                    )

                                    Spacer(Modifier.width(16.dp))

                                    ContentSaveToListButton(
                                        contentId = podcast.origin,
                                        isPodcast = true
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    ContentFavoriteButton(
                                        contentId = podcast.origin,
                                        isPodcast = true
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    FilledIconButton(
                                        onClick = {
                                            vm.showDeleteDialog.value = true
                                        },
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.harmonizeWithPrimary(
                                                MaterialTheme.colorScheme.errorContainer
                                            ),
                                            contentColor = MaterialTheme.colorScheme.harmonizeWithPrimary(
                                                MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = stringResource(R.string.common_action_delete)
                                        )
                                    }

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
                                episodePager = episodePager,
                                selectedEpisodeIds = selectedEpisodeIds,
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
        itemName = podcast.fetchTitle(),
        additionalText = stringResource(R.string.dialog_delete_confirmation_podcast),
        onConfirm = {
            vm.deletePodcast()
            onBack()
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.podcastDetailViewEpisodesDestination(
    vm: PodcastDetailViewModel,
    scope: CoroutineScope,
    episodePager: LazyPagingItems<PodcastEpisodeBundle>,
    selectedEpisodeIds: MutableList<String>,
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
                .animateItem()
        ) {
            PodcastSearchFilterOrderBar(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                state = vm.searchFilterOrderBarState
            )

            Row(
                modifier = Modifier
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
                    label = { Text("Unplayed") }
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
                    label = { Text("In Progress") }
                )
            }
        }
    }

    item {
        val isSearchFilterOrderBarStateDefault by vm.searchFilterOrderBarState.isDefault()

        if(episodePager.itemCount == 0 && episodePager.loadState.isIdle) InfoLayout(
            modifier = Modifier
                .heightIn(min = 200.dp)
                .animateItem(),
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
        key = episodePager.itemKey { "EPISODE:${it.episode.id}" }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            episodePager[it]?.let { episodeBundle ->
                val isSelected = episodeBundle.episode.id in selectedEpisodeIds
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

                    val itemPlayerVm = viewModel<MediaPlayerViewModel>()
                    val itemSettings = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
                    val itemSwipeRightAction = itemSettings.behavior.episodeSwipeRightAction.collectAsState(
                        app.podiumpodcasts.podium.EpisodeSwipeAction.HEAR_LATER
                    ).value
                    val itemSwipeLeftAction = itemSettings.behavior.episodeSwipeLeftAction.collectAsState(
                        app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED
                    ).value

                    SwipeableItem(
                        snackbarHostState = vm.snackbarHostState,
                        scope = scope,

                        startAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                            actionType = itemSwipeRightAction,
                            bundle = episodeBundle,
                            playerVm = itemPlayerVm,
                            onMarkPlayed = { markPlayed ->
                                if (markPlayed) vm.markAsPlayed(episodeBundle)
                                else vm.markAsUnplayed(episodeBundle)
                            }
                        ),
                        endAction = app.podiumpodcasts.podium.ui.component.common.swipeable.buildEpisodeSwipeAction(
                            actionType = itemSwipeLeftAction,
                            bundle = episodeBundle,
                            playerVm = itemPlayerVm,
                            onMarkPlayed = { markPlayed ->
                                if (markPlayed) vm.markAsPlayed(episodeBundle)
                                else vm.markAsUnplayed(episodeBundle)
                            }
                        ),
                        content = {
                            PodcastEpisodeListItem(
                                bundle = episodeBundle,
                                index = it,
                                count = episodePager.itemCount,
                                isSelected = isSelected,
                                onLongClick = {
                                    if(isSelected) {
                                        selectedEpisodeIds.remove(episodeBundle.episode.id)
                                    } else {
                                        selectedEpisodeIds.add(episodeBundle.episode.id)
                                    }
                                },
                                onClick = {
                                    if(selectedEpisodeIds.isNotEmpty()) {
                                        if(isSelected) {
                                            selectedEpisodeIds.remove(episodeBundle.episode.id)
                                        } else {
                                            selectedEpisodeIds.add(episodeBundle.episode.id)
                                        }
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
                .animateItem(),
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
                    SelectionContainer {
                        Text(
                            modifier = Modifier
                                .padding(16.dp),
                            text = AnnotatedString.fromHtml(
                                htmlString = podcast.description
                                    .replace("\n", "<br>")
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                DetailsList(
                    items = listOf(
                        DetailsListItemModel(
                            icon = Icons.Rounded.Link,
                            label = R.string.common_source,
                            value = podcast.link,
                            onClick = {
                                uriHandler.openUri(podcast.link)
                            }
                        ),
                        DetailsListItemModel(
                            icon = Icons.Rounded.RssFeed,
                            label = R.string.common_rss_feed,
                            value = podcast.origin
                        ),
                        DetailsListItemModel(
                            icon = Icons.Rounded.Language,
                            label = R.string.common_language_code,
                            value = podcast.languageCode
                        ),
                        DetailsListItemModel(
                            icon = Icons.Rounded.DataUsage,
                            label = R.string.common_data_usage_per_update,
                            value = formatFileSize(podcast.fileSize)
                        )
                    )
                )
            }
        }
    }
}