package app.podiumpodcasts.podium.ui.helper

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems

private enum class PagerScaffoldState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PagerScaffold(
    vararg pager: LazyPagingItems<*>,
    isLoading: @Composable (() -> Unit)? = null,
    onError: @Composable ((Throwable, () -> Unit) -> Unit)? = null,
    isEmpty: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val allEmpty = pager.all { it.itemCount == 0 }
    val anyLoading = pager.any { it.loadState.refresh is LoadState.Loading }
    val errorState = pager.firstNotNullOfOrNull { it.loadState.refresh as? LoadState.Error }

    val state = when {
        allEmpty && errorState != null -> PagerScaffoldState.ERROR
        allEmpty && anyLoading -> PagerScaffoldState.LOADING
        allEmpty && pager.all { it.loadState.isIdle } -> PagerScaffoldState.EMPTY
        else -> PagerScaffoldState.CONTENT
    }

    val motionScheme = MaterialTheme.motionScheme
    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = state,
        transitionSpec = {
            androidx.compose.animation.fadeIn(
                animationSpec = motionScheme.defaultEffectsSpec()
            ) togetherWith androidx.compose.animation.fadeOut(
                animationSpec = motionScheme.fastEffectsSpec()
            )
        }
    ) { target ->
        when (target) {
            PagerScaffoldState.LOADING -> {
                if (isLoading != null) {
                    isLoading()
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.LoadingIndicator()
                    }
                }
            }

            PagerScaffoldState.ERROR -> {
                val err = errorState!!.error
                val retryAll = { pager.forEach { it.retry() } }
                if (onError != null) {
                    onError(err, retryAll)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = err.localizedMessage ?: "Failed to load",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            modifier = Modifier.padding(top = 12.dp),
                            onClick = retryAll
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            PagerScaffoldState.EMPTY -> isEmpty()
            PagerScaffoldState.CONTENT -> content()
        }
    }
}