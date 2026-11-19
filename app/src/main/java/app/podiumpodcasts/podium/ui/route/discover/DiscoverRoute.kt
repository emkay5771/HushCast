package app.podiumpodcasts.podium.ui.route.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.common.PoweredByApplePodcastsBadge
import app.podiumpodcasts.podium.ui.component.layout.ErrorLayout
import app.podiumpodcasts.podium.ui.component.layout.LoadingLayout
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerSpacer
import app.podiumpodcasts.podium.ui.component.media.LocalFloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.model.PodcastPreviewCard
import app.podiumpodcasts.podium.ui.dialog.CountryCodeSelectorDialog
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.PodcastPreviewBottomSheet
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.vm.discover.DiscoverViewModel
import app.podiumpodcasts.podium.ui.vm.discover.State
import app.podiumpodcasts.podium.ui.vm.discover.Topics
import app.podiumpodcasts.podium.utils.getCountryCode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DiscoverRoute(
    onClickPodcast: (origin: String) -> Unit,
    onClickAddPodcast: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    val settingsRepository = LocalSettingsRepository.current
    val disableApplePodcastsApi = settingsRepository.privacy.disableApplePodcastsApi
        .collectAsState(false)

    val defaultCountryCode = remember { getCountryCode(context) }
    val discoverCountryCode = settingsRepository.behavior.getDiscoverCountryCode(defaultCountryCode)
        .collectAsState(defaultCountryCode)

    val textFieldState = rememberTextFieldState()
    val searchBarState = rememberSearchBarState()

    if(disableApplePodcastsApi.value) {
        Scaffold(
            topBar = {
                DiscoverSearch(
                    countryCode = discoverCountryCode.value,
                    textFieldState = textFieldState,
                    searchBarState = searchBarState,
                    actions = {
                        IconButton(onClick = onClickAddPodcast) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.common_action_add)
                            )
                        }
                    },
                    onClickPodcast = onClickPodcast
                )
            }
        ) { inset ->
            Box(
                modifier = Modifier
                    .padding(inset)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                app.podiumpodcasts.podium.ui.component.layout.InfoLayout(
                    icon = Icons.Rounded.Language,
                    title = { "Apple Podcasts charts disabled" }
                ) {
                    Text(
                        text = "Search above using Podcast Index, or add a podcast directly by RSS URL.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    androidx.compose.material3.Button(
                        onClick = onClickAddPodcast,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Add by RSS URL")
                    }
                }
            }
        }
        return
    }

    val vm = viewModel<DiscoverViewModel>()
    val pagerState = rememberPagerState { Topics.entries.size }

    val showCountryCodeSelector = remember { mutableStateOf(false) }

    LaunchedEffect(discoverCountryCode.value) {
        vm.updateCountryCode(
            countryCode = discoverCountryCode.value,
            currentPage = pagerState.currentPage
        )
    }

    Scaffold(
        topBar = {
            DiscoverSearch(
                countryCode = discoverCountryCode.value,

                textFieldState = textFieldState,
                searchBarState = searchBarState,
                actions = {
                    IconButton(
                        onClick = onClickAddPodcast
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.common_action_add)
                        )
                    }
                    IconButton(
                        onClick = {
                            showCountryCodeSelector.value = true
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Language,
                            contentDescription = stringResource(R.string.common_action_select_country_code)
                        )
                    }
                },

                onClickPodcast = onClickPodcast
            )
        }
    ) { inset ->
        Box(
            Modifier
                .padding(inset)
                .fillMaxSize()
        ) {
            Column {
                val selectedTopic = Topics.entries[pagerState.currentPage]

                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTopic.ordinal,
                    edgePadding = 16.dp,
                    minTabWidth = 60.dp,
                    indicator = {
                        if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                            androidx.compose.material3.TabRowDefaults.PrimaryIndicator(
                                Modifier.tabIndicatorOffset(selectedTopic.ordinal, matchContentSize = true),
                                width = androidx.compose.ui.unit.Dp.Unspecified
                            )
                        } else {
                            // M7: the indicator follows the pager while it's dragged, instead of
                            // jumping when the swipe settles.  Read during layout, so dragging
                            // doesn't recompose the tab row.
                            androidx.compose.material3.TabRowDefaults.PrimaryIndicator(
                                Modifier.tabIndicatorLayout { measurable, constraints, tabPositions ->
                                    if (tabPositions.isEmpty()) return@tabIndicatorLayout layout(0, 0) { }
                                    val (left, width) = pagerIndicatorSpan(
                                        tabPositions = tabPositions,
                                        currentPage = pagerState.currentPage,
                                        offsetFraction = pagerState.currentPageOffsetFraction
                                    )
                                    val widthPx = width.roundToPx().coerceAtLeast(0)
                                    val placeable = measurable.measure(
                                        constraints.copy(minWidth = widthPx, maxWidth = widthPx)
                                    )
                                    layout(placeable.width, placeable.height) {
                                        placeable.place(left.roundToPx(), 0)
                                    }
                                },
                                width = androidx.compose.ui.unit.Dp.Unspecified
                            )
                        }
                    }
                ) {
                    Topics.entries.forEachIndexed { index, item ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = {
                                Text(
                                    text = stringResource(item.label),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    // Standard stretch at the first/last topic, without the second bounce.
                    flingBehavior = app.podiumpodcasts.podium.ui.component.common
                        .rememberSingleBouncePagerFlingBehavior(pagerState)
                ) { index ->
                    Box(
                        Modifier.fillMaxSize()
                    ) {
                        LaunchedEffect(index) {
                            vm.updatePage(
                                countryCode = discoverCountryCode.value,
                                index = index
                            )
                        }

                        AnimatedContent(
                            transitionSpec = app.podiumpodcasts.podium.ui.theme.AppMotion.contentSwap(),
                            targetState = vm.states[index]
                        ) { state ->
                            when(state) {
                                is State.Loading -> {
                                    LoadingLayout()
                                }

                                is State.Done -> {
                                    LazyVerticalGrid(
                                        modifier = Modifier.fillMaxSize(),
                                        columns = GridCells.Adaptive(100.dp),
                                        contentPadding = PaddingValues(16.dp)
                                    ) {
                                        items(
                                            count = state.result.size,
                                            key = { state.result[it].fetchUrl }
                                        ) {
                                            val item = state.result[it]

                                            PodcastPreviewCard(
                                                podcast = item,
                                                onClick = {
                                                    vm.clickPodcastPreview(item)
                                                }
                                            )
                                        }

                                        item(
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            FloatingMediaPlayerSpacer(64.dp)
                                        }
                                    }
                                }

                                is State.Error -> {
                                    ErrorLayout {
                                        Text(state.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    PodcastPreviewBottomSheet(
        state = vm.previewBottomSheetState,
        onOpenPodcast = {
            onClickPodcast(it.origin)
        }
    )

    if(showCountryCodeSelector.value) CountryCodeSelectorDialog(
        value = discoverCountryCode.value,
        onValueChange = {
            scope.launch {
                settingsRepository.behavior.setDiscoverCountryCode(it)
            }
        },
        onDismiss = {
            showCountryCodeSelector.value = false
        }
    )
}
/**
 * M7: where the tab indicator sits while the pager is between pages: the content span of the
 * current tab, moved toward the next tab by the drag fraction.
 */
private fun pagerIndicatorSpan(
    tabPositions: List<androidx.compose.material3.TabPosition>,
    currentPage: Int,
    offsetFraction: Float
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val page = currentPage.coerceIn(0, tabPositions.lastIndex)
    val target = (if (offsetFraction > 0f) page + 1 else page - 1).coerceIn(0, tabPositions.lastIndex)
    val fraction = kotlin.math.abs(offsetFraction).coerceIn(0f, 1f)
    val cur = tabPositions[page]
    val next = tabPositions[target]
    fun androidx.compose.material3.TabPosition.contentLeft() = left + (width - contentWidth) / 2
    val left = androidx.compose.ui.unit.lerp(cur.contentLeft(), next.contentLeft(), fraction)
    val width = androidx.compose.ui.unit.lerp(cur.contentWidth, next.contentWidth, fraction)
    return left to width
}
