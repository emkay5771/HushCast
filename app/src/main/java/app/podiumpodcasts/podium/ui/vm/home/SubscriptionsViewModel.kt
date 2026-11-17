package app.podiumpodcasts.podium.ui.vm.home

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.podiumpodcasts.podium.api.db.AppDatabase

class SubscriptionsViewModel(
    val db: AppDatabase
) : ViewModel() {

    val lazyGridState = LazyGridState()

    val subscriptionsFlow = db.podcastSubscriptions().allByNewEpisodesFlow()

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