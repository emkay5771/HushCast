package app.hushcast.automute.engine

import android.content.Context
import android.os.PowerManager
import kotlin.math.max

/**
 * S11: how many inference threads on-device analysis may use right now.  The analyzer asks again
 * before every Whisper chunk, DAI clip and embedding batch, so heat or playback that starts
 * mid-run takes effect at the next chunk instead of the next episode.
 */
object AnalysisThrottle {
    /**
     * True while the app is playing audio.  Set it from the player (e.g. PlaybackService's
     * `onIsPlayingChanged`); analysis then runs on half the threads so playback and the UI keep
     * their cores.
     */
    @Volatile
    @JvmStatic
    var playbackActive: Boolean = false

    enum class Heat { NORMAL, WARM, HOT }

    private const val HEAT_CACHE_MS = 2_000L

    @Volatile private var cachedHeat = Heat.NORMAL
    @Volatile private var cachedAtMs = 0L

    /** Threads for the next chunk: performance cores, halved when warm or playing, quartered when both. */
    fun threads(context: Context): Int =
        threadsFor(CpuTopology.performanceCoreCount(), heat(context), playbackActive)

    internal fun threadsFor(base: Int, heat: Heat, playing: Boolean): Int {
        var n = base.coerceAtLeast(1)
        when (heat) {
            Heat.NORMAL -> Unit
            Heat.WARM -> n /= 2
            Heat.HOT -> n = 1
        }
        if (playing) n /= 2
        return max(1, n)
    }

    /**
     * Thermal state, cached for 2 s ([PowerManager.getThermalHeadroom] returns NaN when asked
     * more than about once a second).
     */
    fun heat(context: Context): Heat {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cachedAtMs < HEAT_CACHE_MS && cachedAtMs != 0L) return cachedHeat
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = runCatching { pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val headroom = runCatching { pm?.getThermalHeadroom(10) ?: 0f }.getOrDefault(0f)
        val heat = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> Heat.HOT
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Heat.WARM
            !headroom.isNaN() && headroom >= 0.85f -> Heat.WARM
            else -> Heat.NORMAL
        }
        cachedHeat = heat
        cachedAtMs = now
        return heat
    }
}
