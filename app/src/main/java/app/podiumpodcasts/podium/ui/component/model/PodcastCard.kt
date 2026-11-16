package app.podiumpodcasts.podium.ui.component.model

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.theme.Typography

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastCard(
    modifier: Modifier = Modifier,
    podcast: PodcastModel,
    fillCell: Boolean = false,
    showTitles: Boolean = true,
    compact: Boolean = false,
    badge: @Composable () -> Unit = { },
    onClick: () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = if (fillCell) Modifier.fillMaxWidth() else Modifier
        ) {
            Column(
                modifier = Modifier
                    .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(112.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick() }
                    .padding(if (compact) 2.dp else 4.dp)
            ) {
                ShimmerAsyncImage(
                    modifier = if (fillCell) {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(if (compact) 8.dp else 10.dp))
                    } else {
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                    },
                    model = podcast.imageUrl,
                    contentDescription = podcast.fetchTitle(),
                    contentScale = ContentScale.Crop,
                    targetSizePx = if (compact) 192 else 256
                )

                if (showTitles && !compact) {
                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = podcast.fetchTitle(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = Typography.titleSmallEmphasized
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = podcast.author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = Typography.labelMedium
                    )
                }
            }

            Box(
                Modifier.align(Alignment.TopEnd)
            ) {
                badge()
            }
        }
    }
}