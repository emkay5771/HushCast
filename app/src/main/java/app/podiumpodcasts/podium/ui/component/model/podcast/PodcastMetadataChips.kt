package app.podiumpodcasts.podium.ui.component.model.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.api.rss.PodcastPerson
import app.podiumpodcasts.podium.api.rss.PodcastSoundbite
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import kotlin.math.roundToInt

@Composable
fun ExplicitBadge(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.semantics { contentDescription = "Explicit" },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = "E",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PodcastCategoriesRow(
    categories: List<String>,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        categories.forEach { category ->
            SuggestionChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = category,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PodcastPersonsRow(
    persons: List<PodcastPerson>,
    onClickUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (persons.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        persons.forEach { person ->
            val clickableModifier = if (person.href.isNotBlank()) {
                Modifier.clickable { onClickUrl(person.href) }
            } else {
                Modifier
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(clickableModifier),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (person.img.isNotBlank()) {
                        ShimmerAsyncImage(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape),
                            model = person.img,
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            targetSizePx = 64
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(28.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                modifier = Modifier.padding(5.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = person.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (person.role.isNotBlank()) {
                            Text(
                                text = person.role.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeSoundbitesRow(
    soundbites: List<PodcastSoundbite>,
    onPlaySoundbite: (PodcastSoundbite) -> Unit,
    modifier: Modifier = Modifier
) {
    if (soundbites.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        soundbites.forEach { soundbite ->
            val secs = soundbite.durationSec.roundToInt().coerceAtLeast(1)
            val labelText = if (soundbite.title.isNotBlank()) {
                "${soundbite.title} · ${secs}s"
            } else {
                "Preview clip · ${secs}s"
            }
            AssistChip(
                onClick = { onPlaySoundbite(soundbite) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = {
                    Text(
                        text = labelText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}
