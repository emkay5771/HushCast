package app.podiumpodcasts.podium.ui.vm.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

private data class NewEpisodesFilterState(
    val excludeListened: Boolean,
    val onlyInProgress: Boolean,
    val onlyDownloaded: Boolean
)

class NewEpisodesViewModel(
    val db: AppDatabase
) : ViewModel() {

    val lazyListState = LazyListState()

    val excludeListened = mutableStateOf(true)
    val onlyInProgress = mutableStateOf(false)
    val onlyDownloaded = mutableStateOf(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val newEpisodes = snapshotFlow {
        NewEpisodesFilterState(
            excludeListened = excludeListened.value,
            onlyInProgress = onlyInProgress.value,
            onlyDownloaded = onlyDownloaded.value
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            Pager(PagingConfig(pageSize = 15)) {
                val query = db.podcastEpisodes().buildNewEpisodesQuery(
                    excludePlayed = filter.excludeListened,
                    onlyInProgress = filter.onlyInProgress,
                    onlyDownloaded = filter.onlyDownloaded
                )
                db.podcastEpisodes().queryPaged(query)
            }.flow
        }
        .cachedIn(viewModelScope)

    fun markPlayed(item: PodcastEpisodeBundle, played: Boolean) {
        viewModelScope.launch {
            db.podcastEpisodePlayStates().upsertPlayed(item.episode.id, played)
            if (played) {
                db.podcastEpisodes().unnewAndUpdateNewEpisodesCount(item.episode.origin, item.episode.id)
            } else {
                db.podcastEpisodes().newAndUpdateNewEpisodesCount(item.episode.origin, item.episode.id)
            }
        }
    }

    fun markAllPlayed() {
        viewModelScope.launch {
            db.podcastEpisodePlayStates().markAllSubscribedPlayed()
        }
    }

    fun unnew(item: PodcastEpisodeBundle) {
        viewModelScope.launch {
            db.podcastEpisodes()
                .unnewAndUpdateNewEpisodesCount(item.episode.origin, item.episode.id)
        }
    }

    fun new(item: PodcastEpisodeBundle) {
        viewModelScope.launch {
            db.podcastEpisodes()
                .newAndUpdateNewEpisodesCount(item.episode.origin, item.episode.id)
        }
    }

}