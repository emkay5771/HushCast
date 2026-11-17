package app.podiumpodcasts.podium.ui.vm

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.podiumpodcasts.podium.api.db.AppDatabase

class DownloadsViewModel(
    val db: AppDatabase
) : ViewModel() {

    val lazyListState = LazyListState()

    val totalSize = db.podcastEpisodeDownloads().totalSize()

    val downloads = Pager(
        PagingConfig(
            pageSize = 30
        )
    ) {
        db.podcastEpisodeDownloads()
            .all()
    }.flow.cachedIn(viewModelScope)

}