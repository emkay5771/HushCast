package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.engine.Word
import app.hushcast.automute.storage.MuteSegmentEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException

private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

private fun normalizeForSearch(input: String): String {
    val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
    return DIACRITICS_REGEX.replace(nfd, "").lowercase()
}

/** A transcript word as it goes into the mute list: trimmed of punctuation, keeping ' and -. */
fun cleanMuteWord(raw: String): String =
    raw.trim().trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }

/** The ad block a transcript line falls in (midpoint inside ad or >=50% overlap), if any. */
internal fun adForLine(line: TranscriptLineUi, adWindows: List<AdWindow>): AdWindow? {
    if (adWindows.isEmpty() || line.endMs <= line.startMs) return null
    val mid = line.startMs + (line.endMs - line.startMs) / 2L
    adWindows.firstOrNull { ad -> mid >= ad.startMs && mid < ad.endMs }?.let { return it }
    val lineDur = (line.endMs - line.startMs).coerceAtLeast(1L)
    return adWindows.firstOrNull { ad ->
        val overlap = (minOf(line.endMs, ad.endMs) - maxOf(line.startMs, ad.startMs)).coerceAtLeast(0L)
        overlap * 2L >= lineDur
    }
}

/** Resolves the seek position in ms for a tapped word, falling back to the line start if unaligned. */
internal fun resolveWordSeekMs(word: Word?, lineStartMs: Long): Long {
    val wordMs = word?.let { (it.start * 1000.0).toLong() }
    return when {
        wordMs == null -> lineStartMs
        wordMs > 0L -> wordMs
        lineStartMs == 0L -> 0L
        else -> lineStartMs
    }
}

private fun wordAtPosition(
    offset: androidx.compose.ui.geometry.Offset,
    layout: androidx.compose.ui.text.TextLayoutResult?,
    wordRanges: List<Pair<IntRange, Word>>
): Word? {
    val charIdx = layout?.getOffsetForPosition(offset) ?: return null
    return wordRanges.firstOrNull { (range, _) -> charIdx in range }?.second
        ?: wordRanges.minByOrNull { (range, _) ->
            kotlin.math.min(
                kotlin.math.abs(charIdx - range.first),
                kotlin.math.abs(charIdx - range.last)
            )
        }?.second
}

/**
 * T2: once the transcript list can't scroll further, the leftover drag and fling stop here
 * instead of moving (and closing) the bottom sheet around it.
 */
private object KeepOverscrollInside : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranscriptOverlay(
    state: TranscriptLoadState,
    episodeTitle: String,
    /** S4: the live position as state; only the parts that need it read it. */
    positionMsState: State<Long>,
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
    /** Sponsor blocks, marked in the transcript (T3). */
    adWindows: List<AdWindow> = emptyList(),
    /** Starts of the ad blocks that will be skipped automatically; these get "Play anyway". */
    autoSkipAdStarts: Set<Long> = emptySet(),
    /** "Play anyway" on a sponsor block: let it play this time, like Undo (T3).  Null hides it. */
    onPlayAnyway: ((AdWindow) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val context = LocalContext.current
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

    // Back closes search first, then the transcript.  Closing the transcript follows the
    // predictive-back gesture (T2): the panel shrinks with the swipe and closes on release.
    var backProgress by remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        searchInput = ""
        debouncedQuery = ""
    }
    PredictiveBackHandler(enabled = !searchOpen) { progress ->
        try {
            progress.collect { event -> backProgress = event.progress }
            onClose()
        } catch (e: CancellationException) {
            // Gesture cancelled: spring back.
        } finally {
            backProgress = 0f
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

    val posState = positionMsState
    val activeLineIndex by remember(lines) {
        derivedStateOf {
            val pos = posState.value
            if (lines.isEmpty()) -1
            else lines.indexOfFirst { pos in it.startMs..it.endMs }
                .takeIf { it >= 0 }
                ?: lines.indexOfLast { it.startMs > 0L && pos >= it.startMs }
        }
    }

    // Ad blocks the user chose to play this time; their "Play anyway" goes away.
    var playedAnywayStarts by remember { mutableStateOf(emptySet<Long>()) }
    var expandedAdStarts by remember { mutableStateOf(emptySet<Long>()) }

    LaunchedEffect(currentMatchLineIdx, lines, adWindows) {
        val matchLine = currentMatchLineIdx?.let { lines.getOrNull(it) } ?: return@LaunchedEffect
        val matchAd = adForLine(matchLine, adWindows)
        if (matchAd != null && matchAd.startMs !in expandedAdStarts) {
            expandedAdStarts = expandedAdStarts + matchAd.startMs
        }
    }

    val visibleEntries = remember(lines, adWindows, expandedAdStarts) {
        buildList(lines.size) {
            var prevAd: AdWindow? = null
            lines.forEachIndexed { idx, line ->
                val lineAd = adForLine(line, adWindows)
                val adStartsHere = lineAd != null && lineAd != prevAd
                prevAd = lineAd
                val isAdExpanded = lineAd == null || lineAd.startMs in expandedAdStarts
                if (isAdExpanded || adStartsHere) {
                    add(VisibleTranscriptEntry(idx, line, lineAd, adStartsHere))
                }
            }
        }
    }

    fun visibleIndexForOrig(origIdx: Int): Int {
        val exact = visibleEntries.indexOfFirst { it.origIdx == origIdx }
        if (exact >= 0) return exact
        return visibleEntries.indexOfLast { it.origIdx <= origIdx }.coerceAtLeast(0)
    }

    // T3: Keep active line ~30% from top when autoFollow is enabled and not searching
    LaunchedEffect(activeLineIndex, autoFollow, searchOpen, viewportHeightPx, visibleEntries) {
        if (autoFollow && !searchOpen && activeLineIndex >= 0 && visibleEntries.isNotEmpty() && !listState.isScrollInProgress) {
            val offset = if (viewportHeightPx > 0) -(viewportHeightPx * 0.30f).toInt() else 0
            listState.animateScrollToItem(visibleIndexForOrig(activeLineIndex), scrollOffset = offset)
        }
    }

    // Scroll current search match into view
    LaunchedEffect(currentMatchLineIdx, visibleEntries) {
        if (currentMatchLineIdx != null && currentMatchLineIdx >= 0 && visibleEntries.isNotEmpty()) {
            autoFollow = false
            val offset = if (viewportHeightPx > 0) -(viewportHeightPx * 0.25f).toInt() else 0
            listState.animateScrollToItem(visibleIndexForOrig(currentMatchLineIdx), scrollOffset = offset)
        }
    }
    // Row actions menu (long-press): which line, and the word pressed, if any (T3).
    var menuLineIdx by remember { mutableStateOf<Int?>(null) }
    var menuWord by remember { mutableStateOf<Word?>(null) }

    fun copyText(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    }

    fun isWordMuted(word: Word): Boolean {
        val wStartMs = (word.start * 1000.0).toLong()
        val wEndMs = (word.end * 1000.0).toLong()
        return muteWindows.any { m -> m.enabled && wEndMs >= m.startMs && wStartMs <= m.endMs }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // T2: predictive back shrinks the transcript towards the bottom as the user swipes.
                val p = backProgress
                val scale = 1f - 0.08f * p
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.25f * p
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
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

        AnimatedVisibility(
            visible = searchOpen,
            enter = app.podiumpodcasts.podium.ui.theme.AppMotion.expandVerticallyIn(),
            exit = app.podiumpodcasts.podium.ui.theme.AppMotion.shrinkVerticallyOut()
        ) {
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
                .nestedScroll(KeepOverscrollInside)
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
                    // T3: no SelectionContainer around clickable rows; copying is a row action instead.
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
                            items = visibleEntries,
                            key = { _, entry -> "${entry.origIdx}_${entry.line.startMs}" }
                        ) { _, entry ->
                            val idx = entry.origIdx
                            val line = entry.line
                            val lineAd = entry.lineAd
                            val adStartsHere = entry.adStartsHere

                            val isActive = idx == activeLineIndex
                            val isSearchMatch = idx in matchingLineIndices
                            val isCurrentSearchMatch = idx == currentMatchLineIdx

                            val prevSpeaker = lines.getOrNull(idx - 1)?.speaker
                            val showSpeaker = !line.speaker.isNullOrBlank() && line.speaker != prevSpeaker

                            val isAdExpanded = lineAd == null || lineAd.startMs in expandedAdStarts

                            val bgColor = when {
                                isCurrentSearchMatch -> MaterialTheme.colorScheme.tertiaryContainer
                                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                isSearchMatch -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                lineAd != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f)
                            }

                            val firstMutableWord = line.words.firstOrNull { it.text.trim().isNotEmpty() }
                            val firstWordMuted = firstMutableWord != null && isWordMuted(firstMutableWord)
                            val canPlayFromHere = line.startMs > 0L || idx == 0

                            fun openMenu(word: Word?) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuWord = word
                                menuLineIdx = idx
                            }

                            val rowSemantics = Modifier.semantics {
                                onClick(label = "Play from here") {
                                    if (canPlayFromHere) {
                                        onSeekToMs(line.startMs)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                customActions = buildList {
                                    add(
                                        CustomAccessibilityAction("Copy text") {
                                            copyText(line.text)
                                            true
                                        }
                                    )
                                    if (firstMutableWord != null) {
                                        add(
                                            CustomAccessibilityAction(
                                                if (firstWordMuted) "Unmute word" else "Mute word"
                                            ) {
                                                onMuteWord(firstMutableWord)
                                                true
                                            }
                                        )
                                    }
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

                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (adStartsHere && lineAd != null) {
                                    SponsorBlockHeader(
                                        ad = lineAd,
                                        isExpanded = isAdExpanded,
                                        onToggleExpand = {
                                            expandedAdStarts = if (isAdExpanded) {
                                                expandedAdStarts - lineAd.startMs
                                            } else {
                                                expandedAdStarts + lineAd.startMs
                                            }
                                        },
                                        showPlayAnyway = onPlayAnyway != null &&
                                            lineAd.startMs in autoSkipAdStarts &&
                                            lineAd.startMs !in playedAnywayStarts,
                                        onPlayAnyway = {
                                            playedAnywayStarts = playedAnywayStarts + lineAd.startMs
                                            onPlayAnyway?.invoke(lineAd)
                                        }
                                    )
                                }

                                if (isAdExpanded) {
                                Box {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(bgColor)
                                            .then(
                                                if (line.words.isEmpty()) {
                                                    Modifier.combinedClickable(
                                                        onClickLabel = "Play from here",
                                                        onLongClickLabel = "More actions",
                                                        onClick = {
                                                            if (canPlayFromHere) onSeekToMs(line.startMs)
                                                        },
                                                        onLongClick = { openMenu(null) }
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
                                                    // Only the active line reads the position (S4).
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
                                                            onTap = { offset ->
                                                                if (canPlayFromHere) {
                                                                    val hitWord = wordAtPosition(offset, textLayoutResult, wordRanges)
                                                                    onSeekToMs(resolveWordSeekMs(hitWord, line.startMs))
                                                                }
                                                            },
                                                            onLongPress = { offset ->
                                                                val hitWord = wordAtPosition(offset, textLayoutResult, wordRanges)
                                                                val target = (hitWord ?: firstMutableWord)
                                                                    ?.takeIf { it.text.trim().isNotEmpty() }
                                                                openMenu(target)
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

                                    // T3: long-press actions (copy replaces text selection).
                                    DropdownMenu(
                                        expanded = menuLineIdx == idx,
                                        onDismissRequest = {
                                            menuLineIdx = null
                                            menuWord = null
                                        }
                                    ) {
                                        if (canPlayFromHere) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                                text = { Text("Play from here") },
                                                onClick = {
                                                    val targetMs = resolveWordSeekMs(menuWord, line.startMs)
                                                    menuLineIdx = null
                                                    menuWord = null
                                                    onSeekToMs(targetMs)
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                            text = { Text("Copy text") },
                                            onClick = {
                                                menuLineIdx = null
                                                copyText(line.text)
                                            }
                                        )
                                        val word = menuWord
                                        if (word != null && cleanMuteWord(word.text).isNotEmpty()) {
                                            val muted = isWordMuted(word)
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeUp
                                                        else Icons.AutoMirrored.Rounded.VolumeOff,
                                                        contentDescription = null
                                                    )
                                                },
                                                text = {
                                                    Text(
                                                        if (muted) "Unmute \"${cleanMuteWord(word.text)}\""
                                                        else "Mute \"${cleanMuteWord(word.text)}\""
                                                    )
                                                },
                                                onClick = {
                                                    menuLineIdx = null
                                                    menuWord = null
                                                    onMuteWord(word)
                                                }
                                            )
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }

                    // Follow playback floating pill when user scrolled manually
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !autoFollow && !searchOpen && activeLineIndex >= 0,
                        enter = app.podiumpodcasts.podium.ui.theme.AppMotion.popIn(),
                        exit = app.podiumpodcasts.podium.ui.theme.AppMotion.popOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomControlsPadding + 8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                autoFollow = true
                                scope.launch {
                                    if (activeLineIndex >= 0 && visibleEntries.isNotEmpty()) {
                                        val offset = if (viewportHeightPx > 0) -(viewportHeightPx * 0.30f).toInt() else 0
                                        listState.animateScrollToItem(visibleIndexForOrig(activeLineIndex), scrollOffset = offset)
                                    }
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

/** The label above a sponsor block in the transcript, with an expander and "Play anyway" when skipped (T3). */
@Composable
private fun SponsorBlockHeader(
    ad: AdWindow,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    showPlayAnyway: Boolean,
    onPlayAnyway: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isExpanded) 0.28f else 0.42f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isExpanded) 4.dp else 0.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                onClickLabel = if (isExpanded) "Collapse sponsor transcript" else "Expand sponsor transcript",
                onClick = onToggleExpand
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            val seconds = ((ad.endMs - ad.startMs) / 1000L).coerceAtLeast(0L)
            Text(
                text = "${formatAdDisplayLabel(ad)} · ${seconds}s",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showPlayAnyway) {
                TextButton(
                    onClick = onPlayAnyway,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Play anyway")
                }
            }
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse sponsor transcript" else "Expand sponsor transcript",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * The "Mute word?" / "Unmute word?" confirmation shared by the player and the episode page (item 5).
 * Unmute turns off this word's mute segment(s) in the episode, as the player always did; the word
 * stays in the mute list.  Mute adds the word to the list.
 */
@Composable
fun WordMuteDialog(
    word: Word,
    muteSegments: List<MuteSegmentEntity>,
    onUnmuteSegments: (List<Long>) -> Unit,
    onAddMuteWord: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cleanWord = cleanMuteWord(word.text)
    val mutedIds = remember(word, muteSegments) { mutedSegmentIdsForWord(word, muteSegments) }
    if (cleanWord.isEmpty() && mutedIds.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    if (mutedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Unmute \"$cleanWord\" here?") },
            text = { Text("This spot plays with sound. \"$cleanWord\" stays on your mute list.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDismiss()
                        onUnmuteSegments(mutedIds)
                    }
                ) {
                    Text("Unmute")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Mute \"$cleanWord\"?") },
            text = { Text("Add \"$cleanWord\" to your mute list. It's muted in every episode.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDismiss()
                        onAddMuteWord(cleanWord)
                    }
                ) {
                    Text("Mute word")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

private data class VisibleTranscriptEntry(
    val origIdx: Int,
    val line: TranscriptLineUi,
    val lineAd: AdWindow?,
    val adStartsHere: Boolean
)
