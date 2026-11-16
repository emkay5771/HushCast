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

    private const val KEY_DESKTOP_ENABLED = "desktop_worker_enabled"
    private const val KEY_DESKTOP_URL = "desktop_worker_url"
    private const val KEY_DESKTOP_TOKEN = "desktop_worker_token"

    fun isDesktopWorkerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESKTOP_ENABLED, false)

    fun setDesktopWorkerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESKTOP_ENABLED, enabled).apply()
    }

    fun getDesktopWorkerUrl(context: Context): String =
        prefs(context).getString(KEY_DESKTOP_URL, "").orEmpty()

    fun setDesktopWorkerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_DESKTOP_URL, url.trim().trimEnd('/')).apply()
    }

    fun getDesktopWorkerToken(context: Context): String =
        prefs(context).getString(KEY_DESKTOP_TOKEN, "").orEmpty()

    fun setDesktopWorkerToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_DESKTOP_TOKEN, token.trim()).apply()
    }

    private const val KEY_AUTO_DOWNLOAD_METHOD = "auto_download_analysis_method"

    fun getAutoDownloadMethod(context: Context): DownloadAnalysisMethod {
        val raw = prefs(context).getString(KEY_AUTO_DOWNLOAD_METHOD, null)
        return runCatching {
            if (raw != null) DownloadAnalysisMethod.valueOf(raw) else DownloadAnalysisMethod.AUTO
        }.getOrDefault(DownloadAnalysisMethod.AUTO)
    }

    fun setAutoDownloadMethod(context: Context, method: DownloadAnalysisMethod) {
        prefs(context).edit().putString(KEY_AUTO_DOWNLOAD_METHOD, method.name).apply()
    }
}

enum class DownloadAnalysisMethod {
    AUTO,
    DESKTOP,
    ON_DEVICE,
    SKIP_ANALYSIS
}
