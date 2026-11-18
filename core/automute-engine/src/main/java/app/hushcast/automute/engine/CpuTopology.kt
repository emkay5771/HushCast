package app.hushcast.automute.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Detects big/performance CPU cores via sysfs cpufreq so inference threads avoid slow
 * efficiency cores that cause contention and thermal throttling.
 */
object CpuTopology {
    private val cachedPerformanceCores: Int by lazy { detectPerformanceCores() }

    /**
     * S11: Dedicated analysis thread at nice priority 4 (below audio/UI, above background=10).
     */
    val analysisDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(4)
                r.run()
            }, "HushCast-Analysis")
        }.asCoroutineDispatcher()
    }

    fun performanceCoreCount(): Int = cachedPerformanceCores

    fun recommendedThreads(context: Context): Int {
        val base = performanceCoreCount()
        if (Build.VERSION.SDK_INT >= 30) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val headroom = runCatching { pm?.getThermalHeadroom(10) ?: 0f }.getOrDefault(0f)
            if (headroom >= 0.85f) return max(1, base / 2)
        }
        return if (isThermallyThrottled(context)) max(1, base / 2) else base
    }

    private fun detectPerformanceCores(): Int {
        val total = Runtime.getRuntime().availableProcessors()
        val freqs = ArrayList<Long>(total)
        for (i in 0 until total) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (f.canRead()) {
                runCatching { f.readText().trim().toLong() }.getOrNull()?.let { freqs.add(it) }
            }
        }
        if (freqs.isEmpty()) return max(1, min(4, total))
        val maxFreq = freqs.maxOrNull() ?: return max(1, min(4, total))
        val threshold = (maxFreq * 0.78).toLong()
        val fastCores = freqs.count { it >= threshold }
        return fastCores.coerceIn(2, min(4, total.coerceAtLeast(2)))
    }

    fun isThermallyThrottled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    fun thermalStatusLabel(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "Normal"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> "Normal"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE -> "Hot (throttling likely)"
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Overheating (throttled)"
            else -> "Normal"
        }
    }
}
