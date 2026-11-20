package app.podiumpodcasts.podium.ui.route.settings.pane

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hushcast.automute.matcher.BuiltInFilters
import app.hushcast.automute.matcher.FilterSettings
import app.hushcast.automute.matcher.ProfanityLevel
import app.hushcast.automute.matcher.Severity
import app.podiumpodcasts.podium.ui.component.common.SheetOverscroll

enum class FilterWordsSheetKind { PROFANITY, OTHER }

/** Labels for the built-in filters (Settings, the word sheets and the player's HushCast sheet). */
internal object FilterLabels {
    val levels = listOf(ProfanityLevel.LIGHT, ProfanityLevel.STANDARD, ProfanityLevel.STRICT)

    fun level(level: ProfanityLevel): String = when (level) {
        ProfanityLevel.OFF -> "Off"
        ProfanityLevel.LIGHT -> "Light"
        ProfanityLevel.STANDARD -> "Standard"
        ProfanityLevel.STRICT -> "Strict"
    }

    fun levelDescription(level: ProfanityLevel): String = when (level) {
        ProfanityLevel.OFF -> "Off. Words you add yourself are still muted."
        ProfanityLevel.LIGHT -> "The strongest words only"
        ProfanityLevel.STANDARD -> "Strong and moderate words. Mild ones play."
        ProfanityLevel.STRICT -> "Every listed word, mild ones too"
    }

    fun severity(severity: Severity): String = when (severity) {
        Severity.STRONG -> "Strong"
        Severity.MODERATE -> "Moderate"
        Severity.MILD -> "Mild"
    }

    fun mutedAt(severity: Severity): String = when (severity) {
        Severity.STRONG -> "Muted at Light, Standard and Strict"
        Severity.MODERATE -> "Muted at Standard and Strict"
        Severity.MILD -> "Muted at Strict"
    }
}

/**
 * First and last letter with stars between ("f**k"): a clean-listening app doesn't print the
 * words unasked, and keeping the last letter tells similar words apart (c**t, c**k, c**p).
 */
internal fun maskWord(word: String): String =
    if (word.length <= 2) word
    else word.mapIndexed { i, c -> if (i == 0 || i == word.lastIndex || !c.isLetter()) c else '*' }.joinToString("")

/** "oh my god" -> "oh my God"; words of more than a phrase stay as written. */
private fun displayPhrase(phrase: String): String = phrase.split(' ').joinToString(" ") { word ->
    when (word) {
        "god", "god's" -> word.replaceFirstChar { it.uppercase() }
        "jesus", "christ" -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }
}

/**
 * What the built-in filters mute, read-only.  Words are masked until "Show words"; variations
 * (plurals, compounds, masked spellings) aren't listed, only noted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterWordsSheet(
    kind: FilterWordsSheetKind,
    filters: FilterSettings,
    onDismiss: () -> Unit
) {
    var showWords by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SheetOverscroll {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (kind) {
                        FilterWordsSheetKind.PROFANITY ->
                            if (filters.profanity == ProfanityLevel.OFF) "Profanity" else "Muted at ${FilterLabels.level(filters.profanity)}"
                        FilterWordsSheetKind.OTHER -> "Other filters"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                TextButton(onClick = { showWords = !showWords }) {
                    Icon(
                        if (showWords) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (showWords) "Hide words" else "Show words")
                }
            }
            Text(
                text = "Variations are muted too: plurals, compounds, and spellings with asterisks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (kind) {
                FilterWordsSheetKind.PROFANITY -> Severity.entries.forEach { severity ->
                    val active = filters.profanity.mutes(severity)
                    FilterWordsSection(
                        title = FilterLabels.severity(severity) + when {
                            active -> " · muted"
                            filters.profanity == ProfanityLevel.OFF -> ""
                            else -> " · not muted at ${FilterLabels.level(filters.profanity)}"
                        },
                        subtitle = FilterLabels.mutedAt(severity),
                        words = BuiltInFilters.shownProfanity(severity).map { if (showWords) it else maskWord(it) },
                        active = active
                    )
                }
                FilterWordsSheetKind.OTHER -> {
                    FilterWordsSection(
                        title = "Explicit content · " + if (filters.explicit) "on" else "off",
                        subtitle = "Sexual terms",
                        words = BuiltInFilters.EXPLICIT.filter { it.shown }.map { if (showWords) it.pattern else maskWord(it.pattern) },
                        active = filters.explicit
                    )
                    FilterWordsSection(
                        title = "Religious exclamations · " + if (filters.religious) "on" else "off",
                        subtitle = "Phrases only, never “God” on its own",
                        words = BuiltInFilters.RELIGIOUS.filter { it.shown }.map { entry ->
                            val phrase = entry.pattern
                            if (' ' in phrase) displayPhrase(phrase) else if (showWords) phrase else maskWord(phrase)
                        },
                        active = filters.religious
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun FilterWordsSection(
    title: String,
    subtitle: String,
    words: List<String>,
    active: Boolean
) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = words.joinToString("  ·  "),
        style = MaterialTheme.typography.bodyLarge,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    )
}
