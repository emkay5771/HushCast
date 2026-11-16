package app.hushcast.automute.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight persisted preferences for HushCast on-device analysis performance & scheduling.
 */
object HushCastPrefs {
    private const val PREFS_NAME = "hushcast_engine_prefs"
    private const val KEY_FAST_MODEL = "fast_model_tiny_en"
    private const val KEY_FAST_ATTENTION = "fast_attention_mode"
    private const val KEY_CHARGING_ONLY = "analyze_only_while_charging"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFastModelEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAST_MODEL, false)

    fun setFastModelEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAST_MODEL, enabled).apply()
    }

    fun isFastAttentionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAST_ATTENTION, false)

    fun setFastAttentionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAST_ATTENTION, enabled).apply()
    }

    fun isAnalyzeOnlyWhileCharging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHARGING_ONLY, false)

    fun setAnalyzeOnlyWhileCharging(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHARGING_ONLY, enabled).apply()
    }
}
