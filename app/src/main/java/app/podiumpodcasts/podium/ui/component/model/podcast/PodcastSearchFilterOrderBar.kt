package app.podiumpodcasts.podium.ui.component.model.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesFilter
import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesOrder
import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesOrderBy
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.podcast.PodcastFilterBottomSheet
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.podcast.PodcastOrderBottomSheet
import kotlinx.coroutines.delay

class PodcastSearchFilterOrderBarState(
    isSerial: Boolean = false,
    defaults: app.podiumpodcasts.podium.EpisodeListDefaults = app.podiumpodcasts.podium.EpisodeListDefaults()
) {

    /**
     * The show is `itunes:type` "serial": read oldest first, grouped by season when it has
     * seasons (unless the user turned off serial defaults in Settings).  It can become true after
     * the page opened, when a refresh brings the metadata.
     */
    var isSerial by mutableStateOf(isSerial)
        private set

    private var defaults by mutableStateOf(defaults)

    val defaultOrder: PodcastEpisodesOrder
        get() = if (isSerial && defaults.serialDefaults) {
            PodcastEpisodesOrder.ASCENDING
        } else {
            defaults.order
        }

    val searchQuery = mutableStateOf("")

    val filter = mutableStateSetOf<PodcastEpisodesFilter>()
    val negativeFilter = mutableStateSetOf<PodcastEpisodesFilter>()

    val orderBy = mutableStateOf(PodcastEpisodesOrderBy.DATE)
    val order = mutableStateOf(defaultOrder)
    val groupBySeason = mutableStateOf(false)
    val seasonOrderOverride = mutableStateOf<PodcastEpisodesOrder?>(null)

    private var defaultGroupBySeason by mutableStateOf(false)
    private var groupBySeasonChanged = false
    private var orderChanged = false
    private var hasSeasons = false

    val effectiveSeasonOrder: PodcastEpisodesOrder
        get() = seasonOrderOverride.value ?: when (defaults.seasonOrder) {
            app.podiumpodcasts.podium.api.db.dao.SeasonOrder.DESCENDING -> PodcastEpisodesOrder.DESCENDING
            app.podiumpodcasts.podium.api.db.dao.SeasonOrder.ASCENDING -> PodcastEpisodesOrder.ASCENDING
            app.podiumpodcasts.podium.api.db.dao.SeasonOrder.MATCH_EPISODE -> order.value
        }

    fun setOrder(newOrder: PodcastEpisodesOrder) {
        orderChanged = true
        order.value = newOrder
    }

    fun toggleSeasonOrder() {
        val next = when (effectiveSeasonOrder) {
            PodcastEpisodesOrder.ASCENDING -> PodcastEpisodesOrder.DESCENDING
            PodcastEpisodesOrder.DESCENDING -> PodcastEpisodesOrder.ASCENDING
        }
        seasonOrderOverride.value = next
    }

    /** The "Group by season" chip.  After this, the automatic default leaves grouping alone. */
    fun setGroupBySeason(enabled: Boolean) {
        groupBySeasonChanged = true
        groupBySeason.value = enabled
    }

    /** Apply updated global defaults from Settings without overriding user choices in this session. */
    fun applyDefaults(newDefaults: app.podiumpodcasts.podium.EpisodeListDefaults) {
        val prevDefaultOrder = defaultOrder
        defaults = newDefaults
        if (!orderChanged && orderBy.value == PodcastEpisodesOrderBy.DATE && order.value == prevDefaultOrder) {
            order.value = defaultOrder
        }
        if (hasSeasons) {
            val shouldGroup = newDefaults.groupBySeason || (isSerial && newDefaults.serialDefaults)
            defaultGroupBySeason = shouldGroup
            if (!groupBySeasonChanged) groupBySeason.value = shouldGroup
        }
    }

    /** The show turned out to be serial: oldest first, unless the order was already changed or serial defaults are off. */
    fun applySerialDefault() {
        if (isSerial) return
        val prevDefaultOrder = defaultOrder
        val orderUnchanged = !orderChanged &&
            orderBy.value == PodcastEpisodesOrderBy.DATE &&
            order.value == prevDefaultOrder
        isSerial = true
        if (orderUnchanged && defaults.serialDefaults) {
            order.value = PodcastEpisodesOrder.ASCENDING
        }
    }

    /** A show with seasons (serial or global group-by-season default): group by season, unless the chip was already used. */
    fun applySeasonGroupingDefault() {
        hasSeasons = true
        val shouldGroup = defaults.groupBySeason || (isSerial && defaults.serialDefaults)
        defaultGroupBySeason = shouldGroup
        if (!groupBySeasonChanged) groupBySeason.value = shouldGroup
    }

    @Composable
    fun isDefault(): State<Boolean> {
        return remember {
            derivedStateOf {
                searchQuery.value.isEmpty()
                        && filter.isEmpty()
                        && negativeFilter.isEmpty()
                        && orderBy.value == PodcastEpisodesOrderBy.DATE
                        && order.value == defaultOrder
                        && groupBySeason.value == defaultGroupBySeason
                        && seasonOrderOverride.value == null
            }
        }
    }

}

@Composable
fun PodcastSearchFilterOrderBar(
    modifier: Modifier = Modifier,
    state: PodcastSearchFilterOrderBarState
) {
    val showFilterBottomSheet = remember { mutableStateOf(false) }
    val showOrderBottomSheet = remember { mutableStateOf(false) }

    val textValue = remember { mutableStateOf("") }
    LaunchedEffect(state.searchQuery.value) {
        textValue.value = state.searchQuery.value
    }

    LaunchedEffect(textValue.value) {
        delay(300)
        state.searchQuery.value = textValue.value
    }

    fun updateText() {
        state.searchQuery.value = textValue.value
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(CircleShape),

                leadingIcon = {
                    Icon(Icons.Rounded.Search, stringResource(R.string.common_search))
                },
                placeholder = {
                    Text(stringResource(R.string.common_search))
                },

                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),

                value = textValue.value,
                onValueChange = {
                    textValue.value = it
                },

                singleLine = true,

                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        updateText()
                    }
                )
            )

            Row {
                FilledIconToggleButton(
                    checked = state.filter.isNotEmpty() || state.negativeFilter.isNotEmpty(),
                    onCheckedChange = {
                        showFilterBottomSheet.value = true
                    }
                ) {
                    Icon(Icons.Rounded.FilterAlt, stringResource(R.string.common_filter))
                }

                FilledIconToggleButton(
                    checked = state.order.value != state.defaultOrder ||
                        state.orderBy.value != PodcastEpisodesOrderBy.DATE ||
                        state.seasonOrderOverride.value != null,
                    onCheckedChange = {
                        showOrderBottomSheet.value = true
                    }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.common_order_by))
                }
            }
        }
    }

    if(showFilterBottomSheet.value) PodcastFilterBottomSheet(
        state = state,
        onDismiss = {
            showFilterBottomSheet.value = false
        }
    )

    if(showOrderBottomSheet.value) PodcastOrderBottomSheet(
        state = state,
        onDismiss = {
            showOrderBottomSheet.value = false
        }
    )
}