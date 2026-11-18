package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.api.chapters.ResolvedChapter

@Composable
fun PlayerChaptersTab(
    chapters: List<ResolvedChapter>,
    skippedPublishedStarts: Set<Long>,
    currentPositionMs: Long,
    onToggleSkip: (publishedStartMs: Long, skip: Boolean) -> Unit,
    onSelectChapter: (chapter: ResolvedChapter, isCurrent: Boolean) -> Unit,
    contentBottomPadding: androidx.compose.ui.unit.Dp = 88.dp,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var editSkipsMode by remember { mutableStateOf(false) }

    val currentChapterIndex = remember(chapters, currentPositionMs) {
        chapters.indexOfFirst { currentPositionMs in it.startMs until it.endMs }
            .takeIf { it >= 0 }
            ?: chapters.indexOfLast { currentPositionMs >= it.startMs }
    }

    // C5: Open scrolled to the current chapter, accounting for the header row (+1)
    LaunchedEffect(chapters.size) {
        if (currentChapterIndex >= 0) {
            val targetIdx = (currentChapterIndex + 1 - 1).coerceAtLeast(0)
            listState.scrollToItem(targetIdx)
        }
    }

    val skippedCount = remember(chapters, skippedPublishedStarts) {
        chapters.count { it.publishedStartMs in skippedPublishedStarts }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "chapters_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val summaryText = if (skippedCount > 0) {
                    "${chapters.size} chapters • $skippedCount skipped"
                } else {
                    "${chapters.size} chapters"
                }
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = { editSkipsMode = !editSkipsMode }
                ) {
                    Icon(
                        imageVector = if (editSkipsMode) Icons.Rounded.Done else Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (editSkipsMode) "Done" else "Edit skips")
                }
            }
        }

        itemsIndexed(
            items = chapters,
            key = { _, ch -> "${ch.source}_${ch.publishedStartMs}_${ch.index}" }
        ) { idx, chapter ->
            val isCurrent = idx == currentChapterIndex
            val isPlayed = currentChapterIndex > idx
            val isSkipped = chapter.publishedStartMs in skippedPublishedStarts
            val chapterDurationSec = ((chapter.endMs - chapter.startMs) / 1000L).coerceAtLeast(0L)
            val progressFraction = if (isCurrent && chapter.endMs > chapter.startMs) {
                ((currentPositionMs - chapter.startMs).toFloat() / (chapter.endMs - chapter.startMs).toFloat())
                    .coerceIn(0f, 1f)
            } else 0f

            val rowAlpha = when {
                isSkipped && !editSkipsMode -> 0.50f
                isPlayed && !isCurrent -> 0.65f
                else -> 1f
            }

            val rowModifier = if (editSkipsMode) {
                val canToggleSkip = !isSkipped || (skippedCount < chapters.size - 1) || isSkipped
                val wouldSkipAll = !isSkipped && skippedCount >= chapters.size - 1
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                    .toggleable(
                        value = !isSkipped,
                        enabled = !wouldSkipAll,
                        role = Role.Checkbox,
                        onValueChange = { included ->
                            haptic.performHapticFeedback(
                                if (included) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                            )
                            onToggleSkip(chapter.publishedStartMs, !included)
                        }
                    )
            } else {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable {
                        onSelectChapter(chapter, isCurrent)
                    }
            }

            Box(
                modifier = rowModifier.alpha(rowAlpha)
            ) {
                if (isCurrent && progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f))
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editSkipsMode) {
                        val wouldSkipAll = !isSkipped && skippedCount >= chapters.size - 1
                        Checkbox(
                            checked = !isSkipped,
                            onCheckedChange = null,
                            enabled = !wouldSkipAll
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Text(
                            text = "${idx + 1}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(26.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSkipped) {
                            Text(
                                text = "Skipped",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (!chapter.linkUrl.isNullOrBlank() && !editSkipsMode) {
                        IconButton(
                            onClick = { runCatching { uriHandler.openUri(chapter.linkUrl) } },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open chapter link",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = DateUtils.formatElapsedTime(chapterDurationSec),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
