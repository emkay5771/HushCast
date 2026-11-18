package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.engine.Word
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer

private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

private fun normalizeForSearch(input: String): String {
    val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
    return DIACRITICS_REGEX.replace(nfd, "").lowercase()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranscriptOverlay(
    state: TranscriptLoadState,
    episodeTitle: String,
    currentPositionMs: Long,
    positionMsState: androidx.compose.runtime.State<Long>? = null,
    isPlaying: Boolean,
    muteWindows: List<MuteWindow>,
    onClose: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onMuteWord: (Word) -> Unit,
    onShareTxt: (List<TranscriptLineUi>) -> Unit,
    onRetry: () -> Unit,
    onProcessNow: (() -> Unit)? = null,
    onDownloadNow: (() -> Unit)? = null,
    bottomControlsPadding: Dp = 128.dp,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // T3: Keep screen awake while transcript is open and playing
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose {
            view.keepScreenOn = false
        }
    }

    var searchOpen by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var activeMatchCursor by remember { mutableIntStateOf(0) }

    LaunchedEffect(searchInput) {
        delay(300L)
        debouncedQuery = searchInput.trim()
        activeMatchCursor = 0
    }

    // Back closes search first, then the transcript (T2, T3)
    BackHandler(enabled = true) {
        if (searchOpen) {
            searchOpen = false
            searchInput = ""
            debouncedQuery = ""
        } else {
            onClose()
        }
    }

    val readyState = state as? TranscriptLoadState.Ready
    val lines = readyState?.lines.orEmpty()

    val normalizedQuery = remember(debouncedQuery) { normalizeForSearch(debouncedQuery) }
    val matchingLineIndices = remember(lines, normalizedQuery) {
        if (normalizedQuery.isBlank()) emptyList()
        else lines.mapIndexedNotNull { idx, line ->
            val inText = normalizeForSearch(line.text).contains(normalizedQuery)
            val inSpeaker = line.speaker?.let { normalizeForSearch(it).contains(normalizedQuery) } == true
            if (inText || inSpeaker) idx else null
        }
    }

    val currentMatchLineIdx = matchingLineIndices.getOrNull(activeMatchCursor)
    val listState = rememberLazyListState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var autoFollow by remember { mutableStateOf(true) }
    var lastTouchAtMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoFollow = false
            lastTouchAtMs = System.currentTimeMillis()
        }
    }

    // T3: Auto-resume follow 5s after last manual touch
    LaunchedEffect(autoFollow, lastTouchAtMs) {
        if (!autoFollow && lastTouchAtMs > 0L) {
            delay(5_000L)
            autoFollow = true
        }
    }

    val posState = positionMsState ?: androidx.compose.runtime.rememberUpdatedState(currentPositionMs)
    val activeLineIndex by remember(lines) {
        androidx.compose.runtime.derivedStateOf {
            val pos = posState.value
            if (lines.isEmpty()) -1
            else lines.indexOfFirst { pos in it.startMs..it.endMs }
                .takeIf { it >= 0 }
                ?: lines.indexOfLast { it.startMs > 0L && pos >= it.startMs }
        }
    }

    // T3: Keep active line ~30% from top when autoFollow is enabled and not searching
    LaunchedEffect(activeLineIndex, autoFollow, searchOpen, viewportHeightPx) {
        if (autoFollow && !searchOpen && activeLineIndex >= 0 && !listState.isScrollInProgress) {
            val offset = if (viewportHeightPx > 0) -(viewportHeightPx * 0.30f).toInt() else 0
            listState.animateScrollToItem(activeLineIndex, scrollOffset = offset)
        }
    }

    // Scroll current search match into view
    LaunchedEffect(currentMatchLineIdx) {
        if (currentMatchLineIdx != null && currentMatchLineIdx >= 0) {
            autoFollow = false
            val offset = if (viewportHeightPx > 0) -(viewportHeightPx * 0.25f).toInt() else 0
            listState.animateScrollToItem(currentMatchLineIdx, scrollOffset = offset)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Top bar: Close on left, Search & Share on right (T2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close transcript")
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Transcript",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (readyState != null) {
                    Text(
                        text = readyState.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            if (readyState != null) {
                IconButton(
                    onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) {
                            searchInput = ""
                            debouncedQuery = ""
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search transcript")
                }

                IconButton(
                    onClick = { onShareTxt(lines) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share transcript")
                }
            }
        }

        AnimatedVisibility(visible = searchOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    placeholder = { Text("Search transcript…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (debouncedQuery.isNotBlank()) {
                    val countText = if (matchingLineIndices.isEmpty()) "0 / 0"
                    else "${activeMatchCursor + 1} / ${matchingLineIndices.size}"
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        enabled = matchingLineIndices.isNotEmpty(),
                        onClick = {
                            if (matchingLineIndices.isNotEmpty()) {
                                activeMatchCursor = (activeMatchCursor - 1 + matchingLineIndices.size) % matchingLineIndices.size
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                    }
                    IconButton(
                        enabled = matchingLineIndices.isNotEmpty(),
                        onClick = {
                            if (matchingLineIndices.isNotEmpty()) {
                                activeMatchCursor = (activeMatchCursor + 1) % matchingLineIndices.size
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportHeightPx = it.height }
        ) {
            when (state) {
                TranscriptLoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }

                is TranscriptLoadState.Transcribing -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        LoadingIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Transcribing… ${state.progressPercent}%",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                is TranscriptLoadState.NotProcessed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (state.isDownloaded) "No transcript yet" else "Download to get a transcript",
                            style = MaterialTheme.typography.titleMedium
                        )
                        val action = if (state.isDownloaded) onProcessNow else onDownloadNow
                        if (action != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = action) {
                                Text(if (state.isDownloaded) "Process" else "Download")
                            }
                        }
                    }
                }

                TranscriptLoadState.Empty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Transcript is empty",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TranscriptLoadState.UnsupportedFormat -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Unsupported transcript format",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is TranscriptLoadState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry) {
                            Text("Try again")
                        }
                    }
                }

                is TranscriptLoadState.Ready -> {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            lastTouchAtMs = System.currentTimeMillis()
                                        }
                                    )
                                },
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = bottomControlsPadding
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                items = lines,
                                key = { idx, line -> "${idx}_${line.startMs}" }
                            ) { idx, line ->
                                val isActive = idx == activeLineIndex
                                val isSearchMatch = idx in matchingLineIndices
                                val isCurrentSearchMatch = idx == currentMatchLineIdx

                                val prevSpeaker = lines.getOrNull(idx - 1)?.speaker
                                val showSpeaker = !line.speaker.isNullOrBlank() && line.speaker != prevSpeaker

                                val bgColor = when {
                                    isCurrentSearchMatch -> MaterialTheme.colorScheme.tertiaryContainer
                                    isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    isSearchMatch -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    else -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f)
                                }

                                val firstMutableWord = line.words.firstOrNull { it.text.trim().isNotEmpty() }
                                val firstWordMuted = firstMutableWord != null && run {
                                    val wStartMs = (firstMutableWord.start * 1000.0).toLong()
                                    val wEndMs = (firstMutableWord.end * 1000.0).toLong()
                                    muteWindows.any { m -> m.enabled && wEndMs >= m.startMs && wStartMs <= m.endMs }
                                }
                                val rowSemantics = Modifier.semantics {
                                    onClick(label = "Play from here") {
                                        if (line.startMs > 0L || idx == 0) {
                                            onSeekToMs(line.startMs)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    if (firstMutableWord != null) {
                                        customActions = listOf(
                                            CustomAccessibilityAction(
                                                if (firstWordMuted) "Unmute word" else "Mute word"
                                            ) {
                                                onMuteWord(firstMutableWord)
                                                true
                                            }
                                        )
                                    }
                                }

                                var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                                val wordRanges = remember(line.words) {
                                    val ranges = ArrayList<Pair<IntRange, Word>>(line.words.size)
                                    var cursor = 0
                                    line.words.forEachIndexed { wIdx, w ->
                                        if (wIdx > 0 && !w.text.startsWith(" ")) {
                                            cursor += 1
                                        }
                                        val start = cursor
                                        cursor += w.text.length
                                        ranges.add((start until cursor) to w)
                                    }
                                    ranges
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(bgColor)
                                        .then(
                                            if (line.words.isEmpty()) {
                                                Modifier.combinedClickable(
                                                    onClickLabel = "Play from here",
                                                    onClick = {
                                                        if (line.startMs > 0L || idx == 0) {
                                                            onSeekToMs(line.startMs)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (firstMutableWord != null) {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            onMuteWord(firstMutableWord)
                                                        }
                                                    }
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .then(rowSemantics)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    if (showSpeaker) {
                                        Text(
                                            text = line.speaker!!,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }

                                    if (line.words.isNotEmpty()) {
                                        val annotated = buildAnnotatedString {
                                            line.words.forEachIndexed { wIdx, w ->
                                                if (wIdx > 0 && !w.text.startsWith(" ")) {
                                                    append(" ")
                                                }
                                                val wStartMs = (w.start * 1000.0).toLong()
                                                val wEndMs = (w.end * 1000.0).toLong()
                                                val isSpoken = isActive && posState.value in wStartMs..wEndMs
                                                val isMuted = muteWindows.any { m ->
                                                    m.enabled && wEndMs >= m.startMs && wStartMs <= m.endMs
                                                }
                                                val style = when {
                                                    isMuted -> SpanStyle(
                                                        color = MaterialTheme.colorScheme.error,
                                                        textDecoration = TextDecoration.LineThrough,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    isSpoken -> SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    else -> SpanStyle()
                                                }
                                                withStyle(style) {
                                                    append(w.text)
                                                }
                                            }
                                        }
                                        Text(
                                            text = annotated,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            onTextLayout = { textLayoutResult = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .pointerInput(wordRanges, line.startMs, idx) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            if (line.startMs > 0L || idx == 0) {
                                                                onSeekToMs(line.startMs)
                                                            }
                                                        },
                                                        onLongPress = { offset ->
                                                            val layout = textLayoutResult
                                                            val charIdx = layout?.getOffsetForPosition(offset)
                                                            val hitWord = if (charIdx != null) {
                                                                wordRanges.firstOrNull { (range, _) -> charIdx in range }?.second
                                                                    ?: wordRanges.minByOrNull { (range, _) ->
                                                                        kotlin.math.min(
                                                                            kotlin.math.abs(charIdx - range.first),
                                                                            kotlin.math.abs(charIdx - range.last)
                                                                        )
                                                                    }?.second
                                                            } else null
                                                            val target = (hitWord ?: firstMutableWord)
                                                                ?.takeIf { it.text.trim().isNotEmpty() }
                                                            if (target != null) {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                onMuteWord(target)
                                                            }
                                                        }
                                                    )
                                                }
                                        )
                                    } else {
                                        Text(
                                            text = line.text,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Follow playback floating pill when user scrolled manually
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !autoFollow && !searchOpen && activeLineIndex >= 0,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomControlsPadding + 8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                autoFollow = true
                                scope.launch {
                                    val offset = if (viewportHeightPx > 0) -(viewportHeightPx * 0.30f).toInt() else 0
                                    listState.animateScrollToItem(activeLineIndex, scrollOffset = offset)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VerticalAlignCenter,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Follow playback")
                        }
                    }
                }
            }
        }
    }
}
