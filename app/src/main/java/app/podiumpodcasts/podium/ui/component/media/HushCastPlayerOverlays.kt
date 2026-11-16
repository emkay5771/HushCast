package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
            Icons.AutoMirrored.Rounded.VolumeOff
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
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
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
 * - Searchable lines with timestamps (tap any line or word to jump)
 * - Long-press any word to mute/unmute it across the episode
 * - Collapses sponsor reads into "Sponsor Read — Tap to hear" cards with working Play
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SyncedTranscriptCard(
    segments: List<Segment>,
    words: List<Word>,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    currentPositionMs: Long,
    onSeekToMs: (Long) -> Unit,
    onToggleWordMute: (Word, Boolean) -> Unit,
    onPlayAdSegment: ((AdWindow, Long) -> Unit)? = null,
    onCopyReport: (() -> Unit)? = null,
    summary: String? = null,
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto> = emptyList(),
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

    var searchQuery by remember { mutableStateOf("") }
    var showInsights by remember { mutableStateOf(false) }
    val filteredSegments = remember(segments, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) segments
        else segments.filter { it.text.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!summary.isNullOrBlank() || chapters.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showInsights = !showInsights }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildString {
                                append("AI Summary")
                                if (chapters.isNotEmpty()) append(" • ${chapters.size} Chapters")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = if (showInsights) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (showInsights) {
                        if (!summary.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (chapters.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                chapters.forEach { ch ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSeekToMs(ch.startMs) }
                                            .padding(vertical = 4.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = formatTranscriptTimestamp(ch.startMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = ch.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
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
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search transcript…") },
                leadingIcon = {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        androidx.compose.material3.IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                }
            )

            onCopyReport?.let { copyReport ->
                androidx.compose.material3.FilledTonalIconButton(onClick = copyReport) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.ContentCopy,
                        contentDescription = "Copy transcript & ad report"
                    )
                }
            }
        }

        Text(
            text = if (searchQuery.isBlank()) {
                "Tap any line or word to jump • Long-press a word to mute/unmute"
            } else {
                "${filteredSegments.size} matching ${if (filteredSegments.size == 1) "line" else "lines"} • Tap to jump"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (filteredSegments.isEmpty()) {
            Text(
                text = "No lines matching \"$searchQuery\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            val displayRows = remember(filteredSegments, adWindows) {
                buildTranscriptDisplayRows(filteredSegments, adWindows)
            }
            val activeRowIndex = remember(displayRows, currentPositionMs) {
                displayRows.indexOfFirst { row ->
                    when (row) {
                        is TranscriptDisplayRow.SpeechLine -> {
                            val s = (row.segment.start * 1000.0).toLong()
                            val e = (row.segment.end * 1000.0).toLong()
                            currentPositionMs in s..e
                        }
                        is TranscriptDisplayRow.SponsorGroup -> {
                            currentPositionMs in row.startMs..row.endMs
                        }
                    }
                }
            }

            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var followLive by remember { mutableStateOf(true) }

            LaunchedEffect(listState.isScrollInProgress, activeRowIndex) {
                if (listState.isScrollInProgress && activeRowIndex >= 0) {
                    val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == activeRowIndex }
                    if (!visible) {
                        followLive = false
                    }
                }
            }

            LaunchedEffect(activeRowIndex, followLive, searchQuery) {
                if (followLive && searchQuery.isBlank() && activeRowIndex >= 0) {
                    listState.animateScrollToItem(activeRowIndex)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayRows) { _, row ->
                        when (row) {
                            is TranscriptDisplayRow.SponsorGroup -> {
                                var expanded by remember(row.startMs) { mutableStateOf(false) }
                                val durSec = ((row.endMs - row.startMs) / 1000L).coerceAtLeast(1L)
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
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${row.displayLabel} • ${formatTranscriptTimestamp(row.startMs)} (${durSec}s)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                                Text(
                                                    text = if (expanded) "Tap to collapse (${row.segments.size} lines)" else "Tap to expand transcript (${row.segments.size} lines)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    if (onPlayAdSegment != null) {
                                                        onPlayAdSegment(row.ad, row.startMs)
                                                    } else {
                                                        onSeekToMs(row.startMs)
                                                    }
                                                }
                                            ) {
                                                Text("Play", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        if (expanded) {
                                            Spacer(Modifier.height(8.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                row.segments.forEach { adSeg ->
                                                    val adSegMs = (adSeg.start * 1000.0).toLong()
                                                    Text(
                                                        text = "[${formatTranscriptTimestamp(adSegMs)}] ${adSeg.text.trim()}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier.clickable { onSeekToMs(adSegMs) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            is TranscriptDisplayRow.SpeechLine -> {
                                val seg = row.segment
                                val segStartMs = (seg.start * 1000.0).toLong()
                                val segEndMs = (seg.end * 1000.0).toLong()
                                val isActive = currentPositionMs in segStartMs..segEndMs
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
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = formatTranscriptTimestamp(segStartMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    seg.speaker?.let { spk ->
                                        val speakerColors = listOf(
                                            Color(0xFF1E88E5),
                                            Color(0xFF8E24AA),
                                            Color(0xFF00897B),
                                            Color(0xFFF4511E),
                                            Color(0xFF3949AB),
                                            Color(0xFF6D4C41)
                                        )
                                        val spkColor = speakerColors[spk.coerceAtLeast(0) % speakerColors.size]
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = spkColor.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = "Speaker ${spk + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = spkColor,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                if (segWords.isNotEmpty()) {
                                    FlowRow(
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
                                                modifier = Modifier.combinedClickable(
                                                    onClick = { onSeekToMs(ws) },
                                                    onLongClick = { onToggleWordMute(w, !isMuted) }
                                                )
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
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                            }
                        }
                    }
                }

                if (!followLive && searchQuery.isBlank() && activeRowIndex >= 0) {
                    FilledTonalButton(
                        onClick = {
                            followLive = true
                            scope.launch {
                                listState.animateScrollToItem(activeRowIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerticalAlignCenter,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Catch up to live", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private sealed interface TranscriptDisplayRow {
    data class SpeechLine(val segment: Segment) : TranscriptDisplayRow
    data class SponsorGroup(
        val ad: AdWindow,
        val segments: List<Segment>,
        val displayLabel: String,
        val startMs: Long,
        val endMs: Long
    ) : TranscriptDisplayRow
}

private val sponsorPatterns = listOf(
    Regex(
        """(?:support for (?:the|this) show comes from|sponsored by|brought to you by|thanks to|this episode is brought to you by)\s+([A-Z][A-Za-z0-9&'.-]+(?:\s+[A-Z][A-Za-z0-9&'.-]+){0,2})""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """(?:learn more at|visit|head to|go to|check out)\s+([A-Za-z0-9-]+)\.(?:com|io|co|org|net|ai)\b""",
        RegexOption.IGNORE_CASE
    )
)

private fun extractSponsorName(text: String): String? {
    for (pat in sponsorPatterns) {
        val m = pat.find(text) ?: continue
        val raw = m.groupValues.getOrNull(1)?.trim(' ', '.', ',', ':', ';', '!', '?', '"', '\'')
        if (!raw.isNullOrBlank() && raw.length in 2..28) {
            return if (raw.all { it.isLowerCase() }) {
                raw.replaceFirstChar { it.uppercase() }
            } else {
                raw
            }
        }
    }
    return null
}

private fun buildTranscriptDisplayRows(
    segments: List<Segment>,
    adWindows: List<AdWindow>
): List<TranscriptDisplayRow> {
    if (adWindows.isEmpty()) return segments.map { TranscriptDisplayRow.SpeechLine(it) }

    val rows = ArrayList<TranscriptDisplayRow>()
    var pendingAd: AdWindow? = null
    val pendingSegs = ArrayList<Segment>()

    fun flushPending() {
        val ad = pendingAd ?: return
        if (pendingSegs.isEmpty()) return
        val startMs = (pendingSegs.first().start * 1000.0).toLong()
        val endMs = (pendingSegs.last().end * 1000.0).toLong().coerceAtLeast(startMs + 1000L)
        val combined = pendingSegs.joinToString(" ") { it.text }
        val sponsor = extractSponsorName(combined)
        val label = when {
            sponsor != null && ad.isHighConfidence -> "Sponsor: $sponsor"
            sponsor != null -> "Suggested Ad: $sponsor"
            else -> ad.label
        }
        rows.add(
            TranscriptDisplayRow.SponsorGroup(
                ad = ad,
                segments = pendingSegs.toList(),
                displayLabel = label,
                startMs = startMs,
                endMs = endMs
            )
        )
        pendingAd = null
        pendingSegs.clear()
    }

    for (seg in segments) {
        val sMs = (seg.start * 1000.0).toLong()
        val eMs = (seg.end * 1000.0).toLong()
        val overlappingAd = adWindows.firstOrNull { ad -> sMs < ad.endMs && eMs > ad.startMs }
        if (overlappingAd != null) {
            val curAd = pendingAd
            val lastEndMs = pendingSegs.lastOrNull()?.let { (it.end * 1000.0).toLong() } ?: sMs
            if (curAd != null && (sMs - lastEndMs) <= 8000L && curAd.isHighConfidence == overlappingAd.isHighConfidence) {
                pendingSegs.add(seg)
            } else {
                flushPending()
                pendingAd = overlappingAd
                pendingSegs.add(seg)
            }
        } else {
            flushPending()
            rows.add(TranscriptDisplayRow.SpeechLine(seg))
        }
    }
    flushPending()
    return rows
}

private fun formatTranscriptTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
