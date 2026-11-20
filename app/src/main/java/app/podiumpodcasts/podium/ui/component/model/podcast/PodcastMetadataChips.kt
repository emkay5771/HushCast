package app.podiumpodcasts.podium.ui.component.model.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.api.rss.PodcastPerson
import app.podiumpodcasts.podium.api.rss.PodcastSoundbite
import app.podiumpodcasts.podium.background.PreviewPlayer
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import kotlin.math.ceil

@Composable
fun ExplicitBadge(
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.content_explicit)
    Surface(
        modifier = modifier.semantics { contentDescription = description },
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

/**
 * The show's categories as one quiet line ("Technology · Society & Culture · Documentary").
 * They aren't actions, so they aren't chips: disabled chips looked broken and read as
 * "disabled" to TalkBack.
 */
@Composable
fun PodcastCategoriesText(
    categories: List<String>,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return
    Text(
        text = categories.joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Hosts and guests on one line that scrolls sideways, so many people never take more room. */
@Composable
fun PodcastPersonsRow(
    persons: List<PodcastPerson>,
    onClickUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (persons.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            contentDescription = null,
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

/** "1:05" from milliseconds, rounding up so a countdown never shows 0:00 while still playing. */
internal fun formatPreviewClock(ms: Long): String {
    val totalSeconds = ceil(ms.coerceAtLeast(0L) / 1000.0).toLong()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Play / stop for a preview (M3E toggle button: its shape morphs while checked).  While it
 * plays, the label counts down and the icon becomes Stop, or the loading indicator while the
 * audio buffers.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreviewToggleButton(
    checked: Boolean,
    buffering: Boolean,
    label: String,
    time: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: ToggleButtonSize = FilledTonalToggleButtonDefaults.size
) {
    val playingDescription = stringResource(R.string.preview_playing)
    val iconSize = ButtonDefaults.iconSizeFor(buttonSize.height)
    FilledTonalToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = modifier
            .widthIn(max = 320.dp)
            .semantics { if (checked) stateDescription = playingDescription },
        buttonSize = buttonSize,
        icon = {
            when {
                checked && buffering -> LoadingIndicator(Modifier.size(iconSize))
                checked -> Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(iconSize))
                else -> Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(iconSize))
            }
        }
    ) {
        Text(
            text = if (time != null) "$label · $time" else label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * An episode's soundbites as preview buttons on one line.  A preview plays on its own player
 * (see [PreviewPlayer]): it never becomes Now Playing, joins Up Next, saves progress or starts
 * a download.  Leaving the episode stops its preview.
 */
@Composable
fun EpisodeSoundbitesRow(
    episode: PodcastEpisodeModel,
    soundbites: List<PodcastSoundbite>,
    modifier: Modifier = Modifier
) {
    if (soundbites.isEmpty()) return
    val context = LocalContext.current
    val preview by PreviewPlayer.state.collectAsStateWithLifecycle()
    // The downloaded file when there is one, otherwise the stream (the same choice as playback).
    val uri = remember(episode.id) {
        episode.createMediaItem(context).localConfiguration?.uri?.toString()
    }
    val idPrefix = "soundbite:${episode.id}:"
    DisposableEffect(episode.id) {
        onDispose { PreviewPlayer.stopIf { it.startsWith(idPrefix) } }
    }
    val untitled = stringResource(R.string.preview_clip)

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        soundbites.forEachIndexed { index, soundbite ->
            val id = PreviewPlayer.soundbiteId(episode.id, index)
            val state = preview?.takeIf { it.id == id }
            val startMs = (soundbite.startTimeSec * 1000.0).toLong().coerceAtLeast(0L)
            val lengthMs = (soundbite.durationSec * 1000.0).toLong().coerceAtLeast(1000L)
            PreviewToggleButton(
                checked = state != null,
                buffering = state?.buffering == true,
                label = soundbite.title.ifBlank { untitled },
                time = formatPreviewClock(state?.remainingMs ?: lengthMs),
                buttonSize = ToggleButtonSize.ExtraSmall,
                onToggle = {
                    if (uri == null) return@PreviewToggleButton
                    PreviewPlayer.toggle(
                        context,
                        PreviewPlayer.Request(
                            id = id,
                            uri = uri,
                            startMs = startMs,
                            endMs = startMs + lengthMs,
                            episodeId = episode.id,
                            podcastOrigin = episode.origin
                        )
                    )
                }
            )
        }
    }
}

/**
 * The show's trailer (`podcast:trailer`) as a preview, at the end of the podcast header's author
 * line (extra small, like the soundbites).  Nothing when the feed has no trailer.
 */
@Composable
fun PodcastTrailerButton(
    podcast: PodcastModel,
    modifier: Modifier = Modifier
) {
    val url = podcast.trailerUrl.trim()
    if (url.isBlank()) return
    val context = LocalContext.current
    val preview by PreviewPlayer.state.collectAsStateWithLifecycle()
    val id = PreviewPlayer.trailerId(podcast.origin)
    val state = preview?.takeIf { it.id == id }
    DisposableEffect(id) {
        onDispose { PreviewPlayer.stopIf { it == id } }
    }
    PreviewToggleButton(
        checked = state != null,
        buffering = state?.buffering == true,
        label = stringResource(R.string.action_trailer),
        time = state?.remainingMs?.let(::formatPreviewClock),
        modifier = modifier,
        buttonSize = ToggleButtonSize.ExtraSmall,
        onToggle = {
            PreviewPlayer.toggle(context, PreviewPlayer.Request(id = id, uri = url))
        }
    )
}
