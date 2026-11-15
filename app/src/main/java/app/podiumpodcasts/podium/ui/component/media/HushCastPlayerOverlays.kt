package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdSkipEvent
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word

@Composable
fun EpisodeReadinessBadge(
    badge: ReadinessBadge,
    progressPercent: Int = 0,
    onAnalyzeNowClick: (() -> Unit)? = null
) {
    val (label, containerColor, contentColor, icon) = when (badge) {
        ReadinessBadge.READY -> Quad(
            "HushCast Ready",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.CheckCircle
        )
        ReadinessBadge.WORD_MUTE_READY -> Quad(
            "Word-Mute Ready",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.VolumeOff
        )
        ReadinessBadge.ANALYZING -> Quad(
            "Analyzing $progressPercent%",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Rounded.HourglassTop
        )
        ReadinessBadge.NOT_PROCESSED -> Quad(
            "Not Processed",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Rounded.Info
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (badge == ReadinessBadge.NOT_PROCESSED && onAnalyzeNowClick != null) {
            AssistChip(
                onClick = onAnalyzeNowClick,
                label = { Text("Analyze Now", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * Annotated scrubber bar rendered above/behind the player slider showing:
 * - Red/primary tick marks for muted words
 * - Shaded violet blocks for high-confidence sponsor ads
 * - Amber markers for borderline "Suggested Ad" spans
 */
@Composable
fun AnnotatedScrubberOverlay(
    durationMs: Long,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0L || (muteWindows.isEmpty() && adWindows.isEmpty())) return

    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val suggestedAdColor = Color(0xFFFFB300).copy(alpha = 0.65f)
    val muteTickColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val w = size.width
        val h = size.height
        val dur = durationMs.toFloat().coerceAtLeast(1f)

        for (ad in adWindows) {
            val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
            val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
            drawRect(
                color = if (ad.isHighConfidence) adColor else suggestedAdColor,
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(3f), h)
            )
        }

        for (mute in muteWindows) {
            if (!mute.enabled) continue
            val x = (mute.startMs / dur).coerceIn(0f, 1f) * w
            drawRect(
                color = muteTickColor,
                topLeft = Offset(x, 0f),
                size = Size(3f, h)
            )
        }
    }
}

@Composable
fun OneTapUndoAdSkipBanner(
    event: AdSkipEvent,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Skipped ${event.adWindow.label} (${((event.jumpedToMs - event.jumpedFromMs) / 1000L)}s)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onUndo) {
                Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@Composable
fun CastingBypassNoticeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Casting active: on-device word muting is bypassed on external receivers.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Synced Interactive Transcript View:
 * - Highlights active speech
 * - Flags muted words with strike-through pills (tap-to-mute/unmute)
 * - Collapses sponsor reads into "Sponsor Read — Tap to hear" cards
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncedTranscriptCard(
    segments: List<Segment>,
    words: List<Word>,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    currentPositionMs: Long,
    onSeekToMs: (Long) -> Unit,
    onToggleWordMute: (Word, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Subtitles, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "No transcript yet. Download & analyze this episode to enable interactive word muting and ad skipping.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(segments) { _, seg ->
            val segStartMs = (seg.start * 1000.0).toLong()
            val segEndMs = (seg.end * 1000.0).toLong()
            val isActive = currentPositionMs in segStartMs..segEndMs

            val overlappingAd = adWindows.firstOrNull { ad ->
                segStartMs < ad.endMs && segEndMs > ad.startMs
            }

            if (overlappingAd != null) {
                var expanded by remember(segStartMs) { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${overlappingAd.label} — Tap to hear",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Button(onClick = { onSeekToMs(segStartMs) }) {
                                Text("Play", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = seg.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            } else {
                val segWords = words.filter { w ->
                    val ws = (w.start * 1000.0).toLong()
                    ws in segStartMs..segEndMs
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekToMs(segStartMs) }
                ) {
                    if (segWords.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            segWords.forEach { w ->
                                val ws = (w.start * 1000.0).toLong()
                                val we = (w.end * 1000.0).toLong()
                                val matchedMute = muteWindows.firstOrNull { m ->
                                    ws < m.endMs && we > m.startMs
                                }
                                val isMuted = matchedMute?.enabled == true
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isMuted) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        Color.Transparent
                                    },
                                    modifier = Modifier.clickable {
                                        onToggleWordMute(w, !isMuted)
                                    }
                                ) {
                                    Text(
                                        text = w.text.trim(),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            textDecoration = if (isMuted) TextDecoration.LineThrough else TextDecoration.None
                                        ),
                                        color = if (isMuted) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = seg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}
