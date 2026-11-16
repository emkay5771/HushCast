package app.podiumpodcasts.podium.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter.Companion.DefaultTransform
import coil3.compose.AsyncImagePainter.State

@Composable
fun ShimmerAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    transform: (State) -> State = DefaultTransform,
    onState: ((State) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DefaultFilterQuality,
    clipToBounds: Boolean = true,
    targetSizePx: Int = 0
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resolvedModel = androidx.compose.runtime.remember(model, targetSizePx) {
        if (targetSizePx > 0 && model is String) {
            coil3.request.ImageRequest.Builder(context)
                .data(model)
                .size(targetSizePx)
                .precision(coil3.size.Precision.INEXACT)
                .build()
        } else {
            model
        }
    }
    AsyncImage(
        model = resolvedModel,
        contentDescription = contentDescription,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface),
        transform = transform,
        onState = onState,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter,
        filterQuality = filterQuality,
        clipToBounds = clipToBounds
    ) /*{
        val state by painter.state.collectAsState()
        if (state is State.Success) {
            SubcomposeAsyncImageContent()
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    }*/
}