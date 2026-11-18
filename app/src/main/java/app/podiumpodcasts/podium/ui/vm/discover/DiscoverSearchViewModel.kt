package app.podiumpodcasts.podium.ui.vm.discover

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podiumpodcasts.podium.api.apple.ApplePodcastClient
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.model.PodcastPreviewModel
import app.podiumpodcasts.podium.api.podcastindex.PodcastIndexClient
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.PodcastPreviewBottomSheetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

interface DiscoverSearchState {
    class Idle() : DiscoverSearchState
    class Loading() : DiscoverSearchState
    class Empty() : DiscoverSearchState
    data class Done(
        val result: List<PodcastPreviewModel>,
        val isSearching: Boolean = false
    ) : DiscoverSearchState

    data class Error(val error: String, val isNetworkError: Boolean = false) : DiscoverSearchState
}

class DiscoverSearchViewModel(
    val countryCode: String,
    val db: AppDatabase
) : ViewModel() {

    private val applePodcastClient = ApplePodcastClient()
    private val podcastIndexClient = PodcastIndexClient()

    val state = mutableStateOf<DiscoverSearchState>(DiscoverSearchState.Idle())
    val gridState = LazyGridState(0, 0)
    val podcastPreviewBottomSheetState = PodcastPreviewBottomSheetState()
    private var searchJob: Job? = null
    var lastQuery: String = ""
        private set

    fun search(query: String, disableAppleApi: Boolean = false) {
        lastQuery = query
        searchJob?.cancel()
        if(query.isBlank()) {
            state.value = DiscoverSearchState.Idle()
            return
        }

        searchJob = viewModelScope.launch {
            val prev = state.value
            state.value = if (prev is DiscoverSearchState.Done && prev.result.isNotEmpty()) {
                prev.copy(isSearching = true)
            } else {
                DiscoverSearchState.Loading()
            }
            state.value = try {
                val combined = kotlinx.coroutines.supervisorScope {
                    val piDeferred = async {
                        if(podcastIndexClient.isConfigured()) {
                            runCatching { podcastIndexClient.search(query) }.getOrDefault(emptyList())
                        } else emptyList()
                    }
                    val appleDeferred = if (disableAppleApi) {
                        null
                    } else {
                        async<List<PodcastPreviewModel>> {
                            applePodcastClient.search.search(
                                query = query,
                                countryCode = countryCode
                            )
                        }
                    }

                    (piDeferred.await() + (appleDeferred?.await() ?: emptyList()))
                        .distinctBy { it.fetchUrl.trimEnd('/') }
                }

                if(combined.isEmpty())
                    DiscoverSearchState.Empty()
                else
                    DiscoverSearchState.Done(combined, isSearching = false)
            } catch (e: CancellationException) {
                throw e
            } catch(e: Exception) {
                e.printStackTrace()
                val isNet = e is java.io.IOException || e.cause is java.io.IOException
                DiscoverSearchState.Error(
                    error = e.localizedMessage ?: "Search failed",
                    isNetworkError = isNet
                )
            }
        }
    }

}