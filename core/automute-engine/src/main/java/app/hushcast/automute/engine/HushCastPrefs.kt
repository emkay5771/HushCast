package app.hushcast.automute.engine

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
