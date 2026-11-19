package app.podiumpodcasts.podium.ui.route.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.ui.component.common.CommonSearchInputField
import app.podiumpodcasts.podium.ui.component.layout.InfoLayout
import app.podiumpodcasts.podium.ui.component.model.PodcastCard
import app.podiumpodcasts.podium.ui.component.model.episode.PodcastEpisodeListItem
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.vm.library.LibrarySearchState
import app.podiumpodcasts.podium.ui.vm.library.LibrarySearchViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibrarySearch(
    scrollBehavior: SearchBarScrollBehavior,

    searchBarState: SearchBarState = rememberSearchBarState(),
    textFieldState: TextFieldState = rememberTextFieldState(),

    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,

    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val vm = viewModel { LibrarySearchViewModel(db) }

    fun dismiss(
        after: () -> Unit
    ) {
        scope.launch {
            searchBarState.animateToCollapsed()
            after()
        }
    }

    AppBarWithSearch(
        state = searchBarState,
        colors = SearchBarDefaults.appBarWithSearchColors(
            scrolledAppBarContainerColor = MaterialTheme.colorScheme.surface
        ),
        inputField = {
            CommonSearchInputField(
                enabled = searchBarState.currentValue == SearchBarValue.Expanded,
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = { vm.search(it) }
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior
    )

    ExpandedFullScreenContainedSearchBar(
        state = searchBarState,
        inputField = {
            CommonSearchInputField(
                enabled = searchBarState.currentValue == SearchBarValue.Expanded,
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = { vm.search(it) }
            )
        }
    ) {
        Box(
            Modifier.fillMaxSize()
        ) {
            SearchContent(
                vm = vm,
                onClickPodcast = {
                    dismiss {
                        onClickPodcast(it)
                    }
                },
                onClickEpisode = {
                    dismiss {
                        onClickEpisode(it)
                    }
                }
            )
        }
    }
}

/**
 * I14: the library search as its own screen, opened from Home's Search action.  It opens with
 * the search expanded; collapsing it (Back, or picking a result) leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibrarySearchRoute(
    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit,
    onBack: () -> Unit
) {
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val currentOnBack by androidx.compose.runtime.rememberUpdatedState(onBack)

    androidx.compose.runtime.LaunchedEffect(searchBarState) {
        // Leave once the search has been collapsed (it starts expanded).
        androidx.compose.runtime.snapshotFlow { searchBarState.currentValue }
            .collect { value ->
                if (value == SearchBarValue.Collapsed) currentOnBack()
            }
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            LibrarySearch(
                scrollBehavior = scrollBehavior,
                searchBarState = searchBarState,
                navigationIcon = {
                    app.podiumpodcasts.podium.ui.component.common.BackButton { onBack() }
                },
                onClickPodcast = onClickPodcast,
                onClickEpisode = onClickEpisode
            )
        }
    ) { inset ->
        Box(
            Modifier
                .padding(inset)
                .fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchContent(
    vm: LibrarySearchViewModel,
    onClickPodcast: (origin: String) -> Unit,
    onClickEpisode: (episode: PodcastEpisodeModel) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // S7: one lookup and one Set for the result list, not one per row.
    val playerVm = viewModel<app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel>()
    val queueEpisodeIds by androidx.compose.runtime.remember(playerVm) {
        androidx.compose.runtime.derivedStateOf { playerVm.queue.toHashSet() }
    }
    val desktopUrl = androidx.compose.runtime.remember(context) {
        // Transcript search needs the worker switched on, not just an address saved.
        if (app.hushcast.automute.engine.HushCastPrefs.isWorkerActive(context)) app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context) else ""
    }
    val desktopToken = androidx.compose.runtime.remember(context) {
        app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerToken(context)
    }
    var transcriptQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var transcriptBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var transcriptError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var transcriptHits by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<app.hushcast.automute.desktop.DesktopSearchHit>>(emptyList())
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        if (desktopUrl.isNotBlank()) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "Search transcripts",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = transcriptQuery,
                            onValueChange = { transcriptQuery = it },
                            label = { Text("Search spoken topics…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.Button(
                            enabled = !transcriptBusy && transcriptQuery.isNotBlank(),
                            onClick = {
                                transcriptBusy = true
                                transcriptError = null
                                scope.launch {
                                    try {
                                        val resp = app.hushcast.automute.desktop.DesktopWorkerClient.semanticSearch(
                                            baseUrl = desktopUrl,
                                            token = desktopToken,
                                            query = transcriptQuery,
                                            topK = 8
                                        )
                                        transcriptHits = resp.hits
                                        if (resp.hits.isEmpty()) {
                                            transcriptError = "No matching moments found."
                                        }
                                    } catch (t: Throwable) {
                                        transcriptError = t.message ?: t.javaClass.simpleName
                                    } finally {
                                        transcriptBusy = false
                                    }
                                }
                            }
                        ) {
                            Text(if (transcriptBusy) "…" else "Search")
                        }
                    }
                    transcriptError?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (transcriptHits.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        transcriptHits.take(5).forEach { hit ->
                            val totalSec = (hit.startMs / 1000L).coerceAtLeast(0L)
                            val m = totalSec / 60L
                            val s = totalSec % 60L
                            Text(
                                text = "${hit.episodeTitle.ifBlank { hit.episodeId }} • ${"%02d:%02d".format(m, s)} — ${hit.text}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = vm.state.value,
            modifier = Modifier.weight(1f)
        ) { state ->
        when(state) {
            is LibrarySearchState.Idle -> {
                InfoLayout(
                    icon = Icons.Rounded.Search,
                    title = { stringResource(R.string.route_library_search_title) }
                ) {
                    Text(
                        text = stringResource(R.string.route_library_search_text),
                        textAlign = TextAlign.Center
                    )
                }
            }

            is LibrarySearchState.Done -> {
                val podcastsPager = state.podcastsPager.collectAsLazyPagingItems()
                val episodesPager = state.episodesPager.collectAsLazyPagingItems()

                val isEmpty = (podcastsPager.itemCount == 0 && episodesPager.itemCount == 0)
                        && (podcastsPager.loadState.isIdle && episodesPager.loadState.isIdle)

                AnimatedContent(
                    targetState = isEmpty
                ) { isEmpty ->
                    when(isEmpty) {
                        true -> {
                            InfoLayout(
                                icon = Icons.Rounded.SearchOff,
                                title = { stringResource(R.string.route_library_search_empty_title) },
                            ) {
                                Text(
                                    text = stringResource(R.string.route_library_search_empty_text),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        else -> {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
                            ) {
                                if(podcastsPager.itemCount > 0) {
                                    item(
                                        key = "PODCASTS"
                                    ) {
                                        LazyRow(
                                            Modifier.animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),
                                            contentPadding = PaddingValues(
                                                start = 16.dp,
                                                end = 16.dp
                                            )
                                        ) {
                                            items(
                                                podcastsPager.itemCount,
                                                key = podcastsPager.itemKey { it.origin }
                                            ) {
                                                podcastsPager[it]?.let { podcast ->
                                                    PodcastCard(
                                                        podcast = podcast,
                                                        onClick = { onClickPodcast(podcast.origin) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }

                                items(
                                    count = episodesPager.itemCount,
                                    key = episodesPager.itemKey { it.episode.id }
                                ) { index ->
                                    episodesPager[index]?.let { bundle ->
                                        PodcastEpisodeListItem(
                                            modifier = Modifier
                                                .padding(start = 16.dp, end = 16.dp)
                                                .animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),

                                            bundle = bundle,

                                            index = index,
                                            count = episodesPager.itemCount,
                                            inQueue = bundle.episode.id in queueEpisodeIds,
                                            playerVm = playerVm,

                                            onClick = { onClickEpisode(bundle.episode) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}