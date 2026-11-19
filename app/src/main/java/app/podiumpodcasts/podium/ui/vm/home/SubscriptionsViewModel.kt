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

/** Home grid settings, held by the view model so they survive navigation (R9). */
data class HomeGridSettings(
    val layout: app.podiumpodcasts.podium.SubscriptionsLayout,
    val sort: app.podiumpodcasts.podium.SubscriptionsSort,
    val badgeMode: app.podiumpodcasts.podium.SubscriptionsBadgeMode,
    val showTitles: Boolean
)

class SubscriptionsViewModel(
    val db: AppDatabase,
    settingsRepository: app.podiumpodcasts.podium.SettingsRepository? = null
) : ViewModel() {

    /**
     * Started eagerly and kept while the user is elsewhere, so coming back to Home draws the grid
     * in the saved order straight away.  Re-reading the settings each time used the default sort
     * for the first frames, and every tile animated into place (R9).
     */
    val gridSettings: StateFlow<HomeGridSettings?> = settingsRepository?.let { repo ->
        kotlinx.coroutines.flow.combine(
            repo.appearance.subscriptionsLayout,
            repo.appearance.subscriptionsSort,
            repo.appearance.subscriptionsBadgeMode,
            repo.appearance.showGridTitles
        ) { layout, sort, badge, titles -> HomeGridSettings(layout, sort, badge, titles) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    } ?: kotlinx.coroutines.flow.MutableStateFlow<HomeGridSettings?>(null)

    val lazyGridState = LazyGridState()
    val lazyListState = LazyListState()

    val subscriptionsFlow = db.podcastSubscriptions().allByNewEpisodesFlow()

    val subscriptionsState: StateFlow<List<PodcastSubscriptionBundle>?> =
        subscriptionsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val unplayedCountsState: StateFlow<Map<String, Int>> =
        db.podcastSubscriptions().unplayedCountsFlow()
            .map { list -> list.associate { it.origin to it.count } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )

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