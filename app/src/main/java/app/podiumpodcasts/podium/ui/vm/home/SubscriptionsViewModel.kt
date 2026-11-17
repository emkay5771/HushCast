package app.podiumpodcasts.podium.ui.vm.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SubscriptionsViewModel(
    val db: AppDatabase
) : ViewModel() {

    val lazyGridState = LazyGridState()
    val lazyListState = LazyListState()

    val subscriptionsFlow = db.podcastSubscriptions().allByNewEpisodesFlow()

    val subscriptionsState: StateFlow<List<PodcastSubscriptionBundle>?> =
        subscriptionsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val unplayedCountsState: StateFlow<Map<String, Int>> =
        db.podcastSubscriptions().unplayedCountsFlow()
            .map { list -> list.associate { it.origin to it.count } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap()
            )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.podcastSubscriptions().recalculateAllNewEpisodesCounts() }
        }
    }

    val subscriptions = Pager(
        PagingConfig(
            pageSize = 200,
            initialLoadSize = 200,
            enablePlaceholders = false
        )
    ) {
        db.podcastSubscriptions().allByNewEpisodes()
    }.flow.cachedIn(viewModelScope)

}