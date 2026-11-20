package app.podiumpodcasts.podium.background.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.AdWindow

/**
 * Debug builds only (BuildConfig.DEV_TOOLS): adb hooks for testing the car screens without running
 * full processing. Examples:
 *
 *   adb shell am broadcast -a app.podiumpodcasts.podium.debug.INJECT_AD --el start_ms 60000 --el end_ms 120000 --es policy MARK_ONLY
 *   adb shell am broadcast -a app.podiumpodcasts.podium.debug.VOICE --es query "Serial"
 *
 * INJECT_AD without positions adds a one-minute ad starting 5 s from now. "confidence" below 0.8
 * (the default here) keeps it from being skipped automatically.
 */
class CarDebugReceiver(
    private val positionMs: () -> Long,
    private val injectAd: (AdWindow, AdPolicyMode?) -> Unit,
    private val voice: (query: String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INJECT_AD -> {
                val start = intent.getLongExtra("start_ms", -1L).takeIf { it >= 0 } ?: (positionMs() + 5_000L)
                val end = intent.getLongExtra("end_ms", -1L).takeIf { it > start } ?: (start + 60_000L)
                val confidence = intent.getFloatExtra("confidence", 0.5f)
                val adClass = intent.getStringExtra("class") ?: "sponsor_ad"
                val policy = intent.getStringExtra("policy")?.let { runCatching { AdPolicyMode.valueOf(it) }.getOrNull() }
                Log.i(TAG, "Inject ad $start..$end ($adClass, $confidence, policy=$policy)")
                injectAd(AdWindow(startMs = start, endMs = end, confidence = confidence, adClass = adClass), policy)
            }
            ACTION_VOICE -> voice(intent.getStringExtra("query") ?: "")
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_INJECT_AD)
            addAction(ACTION_VOICE)
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    companion object {
        private const val TAG = "HushCastCarDebug"
        const val ACTION_INJECT_AD = "app.podiumpodcasts.podium.debug.INJECT_AD"
        const val ACTION_VOICE = "app.podiumpodcasts.podium.debug.VOICE"
    }
}
