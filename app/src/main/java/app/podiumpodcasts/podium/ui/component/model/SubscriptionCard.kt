package app.podiumpodcasts.podium.ui.component.model

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubscriptionCard(
    modifier: Modifier = Modifier,
    bundle: PodcastSubscriptionBundle,
    badgeMode: SubscriptionsBadgeMode = SubscriptionsBadgeMode.UNPLAYED_COUNT,
    fillCell: Boolean = true,
    showTitles: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    PodcastCard(
        modifier = modifier,
        podcast = bundle.podcast,
        fillCell = fillCell,
        showTitles = showTitles,
        compact = compact,
        badge = {
            if(bundle.subscription.newEpisodes > 0) {
                when(badgeMode) {
                    SubscriptionsBadgeMode.UNPLAYED_COUNT -> Badge(
                        Modifier.offset((-4).dp, 4.dp)
                    ) {
                        Text(
                            text = bundle.subscription.newEpisodes.toString()
                        )
                    }
                    SubscriptionsBadgeMode.LATEST_ONLY -> Badge(
                        Modifier
                            .offset((-6).dp, 6.dp)
                            .size(10.dp)
                    )
                    SubscriptionsBadgeMode.OFF -> {}
                }
            }
        },
        onClick = onClick
    )
}