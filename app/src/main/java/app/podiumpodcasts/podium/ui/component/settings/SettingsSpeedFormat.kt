package app.podiumpodcasts.podium.ui.component.settings

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A playback speed as the player shows it: rounded to 0.05, one decimal when that's exact,
 * otherwise two ("1.0x", "1.25x", "1.5x").
 *
 * The only implementation: the player's `formatPlaybackSpeed` calls this, so Settings and the
 * player always show the same text.
 */
fun formatSettingsPlaybackSpeed(speed: Float, locale: Locale = Locale.getDefault()): String {
    val rounded = (speed * 20f).roundToInt() / 20f
    return if (abs(rounded * 10f - (rounded * 10f).roundToInt()) < 0.01f) {
        String.format(locale, "%.1fx", rounded)
    } else {
        String.format(locale, "%.2fx", rounded)
    }
}
