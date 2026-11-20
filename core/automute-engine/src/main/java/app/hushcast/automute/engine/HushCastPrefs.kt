package app.hushcast.automute.engine

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.hushcast.automute.matcher.BuiltInFilters
import app.hushcast.automute.matcher.FilterSettings
import app.hushcast.automute.matcher.ProfanityLevel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persisted preferences for HushCast on-device analysis & desktop/cloud worker pairing.
 * Desktop worker tokens are encrypted with an Android Keystore AES-256-GCM key (E18).
 */
object HushCastPrefs {
    private const val PREFS_NAME = "hushcast_engine_prefs"
    private const val KEY_FAST_MODEL = "fast_model_tiny_en"
    private const val KEY_FAST_ATTENTION = "fast_attention_mode"
    private const val KEY_CHARGING_ONLY = "analyze_only_while_charging"

    private const val KEY_DESKTOP_ENABLED = "desktop_worker_enabled"
    private const val KEY_DESKTOP_URL = "desktop_worker_url"
    private const val KEY_DESKTOP_TOKEN_ENC = "desktop_worker_token_enc"
    private const val KEY_DESKTOP_TOKEN_LEGACY = "desktop_worker_token"
    private const val KEY_DESKTOP_FINGERPRINT = "desktop_worker_cert_fp"
    private const val KEY_AUTO_DOWNLOAD_METHOD = "auto_download_analysis_method"
    private const val KEY_MUTE_TIMING_PRECISION = "mute_timing_precision"

    private const val KEY_FILTER_PROFANITY = "filter_profanity_level"
    private const val KEY_FILTER_EXPLICIT = "filter_explicit"
    private const val KEY_FILTER_RELIGIOUS = "filter_religious"
    private const val PREFIX_PODCAST_LEVEL = "filter_podcast_level::"
    private const val KEY_FILTERS_MIGRATED = "filters_migrated_1_0_45"

    private const val KEYSTORE_ALIAS = "hushcast_worker_token_aes"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"

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

    fun isDesktopWorkerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESKTOP_ENABLED, false)

    fun setDesktopWorkerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESKTOP_ENABLED, enabled).apply()
    }

    /** A worker is used only when it's switched on and has an address. */
    fun isWorkerActive(context: Context): Boolean =
        isDesktopWorkerEnabled(context) && getDesktopWorkerUrl(context).isNotBlank()

    fun isRuleModeMigrated(context: Context): Boolean =
        prefs(context).getBoolean("rule_mode_default_migrated_v1", false)

    fun setRuleModeMigrated(context: Context) {
        prefs(context).edit().putBoolean("rule_mode_default_migrated_v1", true).apply()
    }

    /** The WordMatcher.MATCHER_VERSION that analyzed episodes were last matched with (0 = before 1.0.45). */
    fun getMatcherVersion(context: Context): Int =
        prefs(context).getInt("word_matcher_version", 0)

    fun setMatcherVersion(context: Context, version: Int) {
        prefs(context).edit().putInt("word_matcher_version", version).apply()
    }

    // ---- Built-in word filters (1.0.45).  All off on a new install. ----

    fun getFilterSettings(context: Context): FilterSettings {
        val p = prefs(context)
        return FilterSettings(
            profanity = p.getString(KEY_FILTER_PROFANITY, null)
                ?.let { runCatching { ProfanityLevel.valueOf(it) }.getOrNull() } ?: ProfanityLevel.OFF,
            explicit = p.getBoolean(KEY_FILTER_EXPLICIT, false),
            religious = p.getBoolean(KEY_FILTER_RELIGIOUS, false)
        )
    }

    fun setFilterSettings(context: Context, settings: FilterSettings) {
        prefs(context).edit()
            .putString(KEY_FILTER_PROFANITY, settings.profanity.name)
            .putBoolean(KEY_FILTER_EXPLICIT, settings.explicit)
            .putBoolean(KEY_FILTER_RELIGIOUS, settings.religious)
            .apply()
    }

    /** A podcast's own profanity level, or null to follow the global one. */
    fun getPodcastProfanityLevel(context: Context, origin: String): ProfanityLevel? =
        prefs(context).getString(PREFIX_PODCAST_LEVEL + origin, null)
            ?.let { runCatching { ProfanityLevel.valueOf(it) }.getOrNull() }

    fun setPodcastProfanityLevel(context: Context, origin: String, level: ProfanityLevel?) {
        prefs(context).edit().apply {
            if (level == null) remove(PREFIX_PODCAST_LEVEL + origin) else putString(PREFIX_PODCAST_LEVEL + origin, level.name)
        }.apply()
    }

    private fun podcastProfanityLevels(context: Context): Map<String, ProfanityLevel> =
        prefs(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX_PODCAST_LEVEL) || value !is String) return@mapNotNull null
            val level = runCatching { ProfanityLevel.valueOf(value) }.getOrNull() ?: return@mapNotNull null
            key.removePrefix(PREFIX_PODCAST_LEVEL) to level
        }.toMap()

    /** What the built-in filters mute for one episode: the global settings, with its podcast's own level. */
    fun effectiveFilterSettings(context: Context, episodeId: String): FilterSettings =
        BuiltInFilters.effective(getFilterSettings(context), podcastProfanityLevels(context), episodeId)

    /** The global filter settings, updated as they change. */
    fun filterSettingsFlow(context: Context): Flow<FilterSettings> = prefsFlow(context) { getFilterSettings(context) }

    fun podcastProfanityLevelFlow(context: Context, origin: String): Flow<ProfanityLevel?> =
        prefsFlow(context) { getPodcastProfanityLevel(context, origin) }

    private fun <T> prefsFlow(context: Context, read: () -> T): Flow<T> = callbackFlow {
        val p = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun isFilterMigrationDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTERS_MIGRATED, false)

    fun setFilterMigrationDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_FILTERS_MIGRATED, true).apply()
    }

    /** One-time removal of checkpoint rows leaked by 1.0.30 and earlier (see AutoMuteAnalyzer). */
    fun isCheckpointPruneDone(context: Context): Boolean =
        prefs(context).getBoolean("checkpoint_orphan_prune_v1", false)

    fun setCheckpointPruneDone(context: Context) {
        prefs(context).edit().putBoolean("checkpoint_orphan_prune_v1", true).apply()
    }

    /** Remote job id per episode, so a retried download re-attaches instead of starting over. */
    fun getWorkerJobId(context: Context, episodeId: String): String? =
        prefs(context).getString("worker_job_${episodeId.hashCode()}", null)?.takeIf { it.isNotBlank() }

    fun setWorkerJobId(context: Context, episodeId: String, jobId: String?) {
        prefs(context).edit().apply {
            if (jobId.isNullOrBlank()) remove("worker_job_${episodeId.hashCode()}")
            else putString("worker_job_${episodeId.hashCode()}", jobId)
        }.apply()
    }

    fun getDesktopWorkerUrl(context: Context): String =
        prefs(context).getString(KEY_DESKTOP_URL, "").orEmpty()

    fun setDesktopWorkerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_DESKTOP_URL, url.trim().trimEnd('/')).apply()
    }

    fun getDesktopWorkerFingerprint(context: Context): String =
        prefs(context).getString(KEY_DESKTOP_FINGERPRINT, "").orEmpty()

    fun setDesktopWorkerFingerprint(context: Context, fingerprint: String) {
        prefs(context).edit().putString(KEY_DESKTOP_FINGERPRINT, fingerprint.trim().lowercase()).apply()
    }

    fun getDesktopWorkerToken(context: Context): String {
        val p = prefs(context)
        val enc = p.getString(KEY_DESKTOP_TOKEN_ENC, null)
        if (!enc.isNullOrBlank()) {
            return decryptToken(enc).orEmpty()
        }
        // Migrate legacy plaintext token if present
        val legacy = p.getString(KEY_DESKTOP_TOKEN_LEGACY, "").orEmpty()
        if (legacy.isNotBlank()) {
            setDesktopWorkerToken(context, legacy)
            p.edit().remove(KEY_DESKTOP_TOKEN_LEGACY).apply()
        }
        return legacy
    }

    fun setDesktopWorkerToken(context: Context, token: String) {
        val trimmed = token.trim()
        val p = prefs(context)
        if (trimmed.isEmpty()) {
            p.edit().remove(KEY_DESKTOP_TOKEN_ENC).remove(KEY_DESKTOP_TOKEN_LEGACY).apply()
            return
        }
        val encrypted = encryptToken(trimmed)
        if (encrypted != null) {
            p.edit().putString(KEY_DESKTOP_TOKEN_ENC, encrypted).remove(KEY_DESKTOP_TOKEN_LEGACY).apply()
        }
    }

    fun getAutoDownloadMethod(context: Context): DownloadAnalysisMethod {
        val raw = prefs(context).getString(KEY_AUTO_DOWNLOAD_METHOD, null)
        return runCatching {
            if (raw != null) DownloadAnalysisMethod.valueOf(raw) else DownloadAnalysisMethod.AUTO
        }.getOrDefault(DownloadAnalysisMethod.AUTO)
    }

    fun setAutoDownloadMethod(context: Context, method: DownloadAnalysisMethod) {
        prefs(context).edit().putString(KEY_AUTO_DOWNLOAD_METHOD, method.name).apply()
    }

    fun getMuteTimingPrecision(context: Context): MuteTimingPrecision {
        val raw = prefs(context).getString(KEY_MUTE_TIMING_PRECISION, null)
        return MuteTimingPrecision.entries.firstOrNull { it.name == raw } ?: MuteTimingPrecision.BALANCED
    }

    fun setMuteTimingPrecision(context: Context, precision: MuteTimingPrecision) {
        prefs(context).edit().putString(KEY_MUTE_TIMING_PRECISION, precision.name).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encryptToken(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        "$ivB64:$ctB64"
    }.getOrNull()

    private fun decryptToken(encoded: String): String? = runCatching {
        val parts = encoded.split(':')
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}

enum class DownloadAnalysisMethod {
    AUTO,
    DESKTOP,
    ON_DEVICE,
    SKIP_ANALYSIS
}

enum class MuteTimingPrecision(
    val prePadMs: Long,
    val postPadMs: Long,
    val label: String
) {
    TIGHT(40L, 60L, "Tight (40/60 ms)"),
    BALANCED(80L, 100L, "Balanced (80/100 ms)"),
    WIDE(150L, 180L, "Wide (150/180 ms)")
}
