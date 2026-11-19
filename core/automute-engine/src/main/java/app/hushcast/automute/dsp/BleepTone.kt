package app.hushcast.automute.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Built-in mute sounds for the BEEP mode. [SOFT] is the default; [CLASSIC] is the original
 * 1 kHz TV-style bleep. Levels are set against typical podcast speech (about -20 dBFS RMS).
 */
enum class BleepStyle(val label: String, val description: String) {
    SOFT("Soft tone", "Lower and quieter than the classic bleep"),
    WARM("Warm boop", "A rounder tone with soft harmonics"),
    CLASSIC("Classic bleep", "The original 1,000 Hz TV-style bleep");

    companion object {
        val DEFAULT = SOFT

        fun fromName(name: String?): BleepStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * One tone generator shared by playback ([AutoMuteAudioProcessor]) and the Settings "Test"
 * button, so the preview sounds exactly like a muted word.
 */
object BleepTone {

    /** Fade-in/out length for the tone, in milliseconds. */
    fun rampMs(style: BleepStyle): Double = when (style) {
        BleepStyle.SOFT -> 20.0
        BleepStyle.WARM -> 25.0
        BleepStyle.CLASSIC -> 8.0
    }

    /** The tone's sample, before the window envelope, [frame] frames after the window starts. */
    fun sample(style: BleepStyle, frame: Long, sampleRateHz: Int): Float {
        val t = frame.toDouble() / sampleRateHz.coerceAtLeast(1)
        return when (style) {
            BleepStyle.CLASSIC -> (sin(2.0 * PI * 1000.0 * t) * 0.34).toFloat()
            BleepStyle.SOFT -> (sin(2.0 * PI * 620.0 * t) * 0.11).toFloat()
            BleepStyle.WARM -> {
                val v = sin(2.0 * PI * 440.0 * t) +
                    0.28 * sin(2.0 * PI * 880.0 * t) +
                    0.10 * sin(2.0 * PI * 1320.0 * t)
                (v * WARM_SCALE).toFloat()
            }
        }
    }

    /** Peak of the three-partial sum is about 1.30, so this lands the peak near 0.12. */
    private const val WARM_SCALE = 0.12 / 1.30

    /** Raised-cosine fade used for the tone (0 at the edges, 1 in the middle). */
    fun envelope(framesFromStart: Int, framesToEnd: Int, rampFrames: Int): Float {
        val r = rampFrames.coerceAtLeast(1)
        val a = minOf(1.0, framesFromStart.toDouble() / r, framesToEnd.toDouble() / r).coerceAtLeast(0.0)
        return (0.5 - 0.5 * cos(PI * a)).toFloat()
    }

    /** Renders [durationMs] of the tone with its fades as 16-bit mono PCM, for previews. */
    fun renderPreview(style: BleepStyle, sampleRateHz: Int = 44100, durationMs: Int = 600): ShortArray {
        val n = (sampleRateHz.toLong() * durationMs / 1000L).toInt().coerceAtLeast(1)
        val ramp = ((rampMs(style) * sampleRateHz) / 1000.0).roundToInt()
        return ShortArray(n) { i ->
            val v = sample(style, i.toLong(), sampleRateHz) * envelope(i, n - 1 - i, ramp)
            (v * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
