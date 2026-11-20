package app.podiumpodcasts.podium

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AppThemeMode {
    AUTO,
    LIGHT,
    DARK
}

enum class AppDarkThemeStyle(val label: String) {
    DEFAULT("Default"),
    DIM("Dim"),
    AMOLED("AMOLED Black"),
    MIDNIGHT("Midnight"),
    WARM_DARK("Warm"),
    FOREST("Forest")
}

enum class AppColorPalette(val label: String, val seedColorHex: Long) {
    DEFAULT("Purple", 0xFF6750A4),
    INDIGO("Indigo", 0xFF3F51B5),
    OCEAN("Ocean", 0xFF0277BD),
    TEAL("Teal", 0xFF00796B),
    EMERALD("Emerald", 0xFF2E7D32),
    AMBER("Amber", 0xFFEF6C00),
    CORAL("Coral", 0xFFD84315),
    ROSE("Rose", 0xFFC2185B),
    /** Uses the seed color saved in [SettingsRepository.Appearance.customSeedColor]. */
    CUSTOM("Custom", 0xFF6750A4)
}

/** How MaterialKolor spreads the seed color across the scheme. */
enum class AppPaletteStyle(val label: String) {
    TONAL("Tonal"),
    VIBRANT("Vibrant"),
    EXPRESSIVE("Expressive"),
    NEUTRAL("Neutral")
}

/** Contrast level passed to MaterialKolor (0 = standard). */
enum class AppContrast(val label: String, val level: Double) {
    STANDARD("Standard", 0.0),
    MEDIUM("Medium", 0.5),
    HIGH("High", 1.0)
}

/** Built-in sounds played when an ad is skipped. [WHOOSH] is the default. */
enum class SkipSound(val label: String, val description: String) {
    WHOOSH("Whoosh", "A soft rising sweep"),
    TWO_NOTE("Soft two-note", "Two gentle rising notes")
}

enum class SubscriptionsLayout {
    LARGE_GRID,
    SMALL_GRID,
    LIST
}

enum class SubscriptionsSort {
    A_Z,
    RECENTLY_ADDED,
    CUSTOM
}

enum class SubscriptionsBadgeMode {
    UNPLAYED_COUNT,
    LATEST_ONLY,
    OFF
}

enum class AutoAddUpNextMode {
    OFF,
    PLAY_NEXT,
    PLAY_LAST
}

enum class EpisodeSwipeAction(val label: String) {
    PLAY_NEXT("Add to Up Next (Play next)"),
    PLAY_LAST("Add to Up Next (Play last)"),
    MARK_PLAYED("Mark played / unplayed"),
    DOWNLOAD("Download / delete download"),
    NONE("None")
}

enum class HeadsetNextAction(val label: String) {
    SKIP_FORWARD("Skip forward"),
    NEXT_EPISODE("Next episode in queue"),
    NEXT_CHAPTER("Next chapter (otherwise skip forward)")
}

enum class HeadsetPrevAction(val label: String) {
    SKIP_BACK("Skip back"),
    PREV_EPISODE("Previous episode / restart")
}

enum class RemainingTimeDisplayMode(val label: String) {
    BOTH("Both (e.g. -24:10 (-16:07))"),
    ADJUSTED("Adjusted only (speed & skips)"),
    RAW("Original time only")
}

enum class PlayerToolbarAction(val label: String) {
    HUSHCAST("HushCast"),
    SPEED("Speed"),
    SLEEP("Sleep timer"),
    TRANSCRIPT("Transcript"),
    FAVORITE("Favorite"),
    DOWNLOAD("Download"),
    GO_TO_PODCAST("Go to podcast"),
    EPISODE_DETAILS("Episode details & notes"),
    MARK_PLAYED("Mark played"),
    ANALYZE("Analyze with HushCast AI")
}

@kotlinx.serialization.Serializable
data class CustomSoundPreset(
    val id: String,
    val name: String,
    val uri: String
)

/**
 * Custom sounds saved before 1.0.26 live only in the single-URI keys
 * [SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI] and [SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI],
 * which also mean "the selected sound".  These pure helpers put them into the sound list, so the
 * selection keys can later be cleared without losing the sound.
 */
object LegacyCustomSounds {
    const val MUTE_NAME = "Custom Mute Sound"
    const val SKIP_NAME = "Custom Skip Chime"

    fun muteId(uri: String): String = "legacy_mute_${uri.hashCode()}"
    fun skipId(uri: String): String = "legacy_skip_${uri.hashCode()}"

    /** The saved sound list; empty when nothing is saved, null when it can't be read. */
    fun parse(rawJson: String?): List<CustomSoundPreset>? =
        if (rawJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<CustomSoundPreset>>(rawJson)
            }.getOrNull()
        }

    fun encode(sounds: List<CustomSoundPreset>): String =
        kotlinx.serialization.json.Json.encodeToString(sounds)

    /** [stored] plus an entry for each legacy URI that isn't in it yet (same ids as before 1.0.31). */
    fun withLegacy(
        stored: List<CustomSoundPreset>,
        legacyMuteUri: String?,
        legacySkipUri: String?
    ): List<CustomSoundPreset> {
        val result = stored.toMutableList()
        val mute = legacyMuteUri?.takeIf { it.isNotBlank() }
        if (mute != null && result.none { it.uri == mute }) {
            result += CustomSoundPreset(id = muteId(mute), name = MUTE_NAME, uri = mute)
        }
        val skip = legacySkipUri?.takeIf { it.isNotBlank() }
        if (skip != null && result.none { it.uri == skip }) {
            result += CustomSoundPreset(id = skipId(skip), name = SKIP_NAME, uri = skip)
        }
        return result
    }

    sealed interface Migration {
        /** Write [sounds] as the new list. */
        data class Write(val sounds: List<CustomSoundPreset>) : Migration

        /** Every legacy sound is already in the list. */
        data object UpToDate : Migration

        /** The saved list can't be read; leave it alone rather than overwrite it. */
        data object Unreadable : Migration
    }

    fun migrate(rawJson: String?, legacyMuteUri: String?, legacySkipUri: String?): Migration {
        val stored = parse(rawJson) ?: return Migration.Unreadable
        val merged = withLegacy(stored, legacyMuteUri, legacySkipUri)
        return if (merged.size == stored.size) Migration.UpToDate else Migration.Write(merged)
    }

    /**
     * Before 1.0.31, picking a built-in tone left the custom mute URI behind.  While the tone is
     * in use (saved mode BEEP) that URI is a leftover, not a selection; clearing it once keeps
     * "Sound" on the tone the user hears today.  The sound itself stays in the list.
     */
    fun isLeftoverMuteSelection(savedMuteActionMode: String?): Boolean =
        savedMuteActionMode == app.hushcast.automute.dsp.MuteActionMode.BEEP.name
}

@kotlinx.serialization.Serializable
data class PlayerTabConfig(
    val id: String,
    val visible: Boolean = true
)

object SettingsKeys {
    val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    val APPEARANCE_DARK_THEME_STYLE = stringPreferencesKey("appearance_dark_theme_style")
    val APPEARANCE_COLOR_PALETTE = stringPreferencesKey("appearance_color_palette")
    val APPEARANCE_ENABLE_ARTWORK_COLORS = booleanPreferencesKey("appearance_enable_artwork_colors")
    val APPEARANCE_ENABLE_DYNAMIC_COLORS = booleanPreferencesKey("appearance_enable_dynamic_colors")
    val APPEARANCE_USE_ALTERNATIVE_BRANDING =
        booleanPreferencesKey("appearance_use_alternative_branding")
    val APPEARANCE_SUBSCRIPTIONS_LAYOUT = stringPreferencesKey("appearance_subscriptions_layout")
    val APPEARANCE_SUBSCRIPTIONS_SORT = stringPreferencesKey("appearance_subscriptions_sort")
    val APPEARANCE_SUBSCRIPTIONS_BADGE_MODE = stringPreferencesKey("appearance_subscriptions_badge_mode")
    val APPEARANCE_SHOW_GRID_TITLES = booleanPreferencesKey("appearance_show_grid_titles")
    val APPEARANCE_BOTTOM_BAR_ITEMS = stringPreferencesKey("appearance_bottom_bar_items")
    val APPEARANCE_STARTUP_DESTINATION = stringPreferencesKey("appearance_startup_destination")
    val APPEARANCE_PODCAST_GRID_STATE = stringPreferencesKey("appearance_podcast_grid_state")

    val BEHAVIOR_PLAYER_PLAYBACK_SPEED =
        floatPreferencesKey("behavior_player_playback_speed")
    val BEHAVIOR_TRIM_SILENCE =
        booleanPreferencesKey("behavior_trim_silence")
    val BEHAVIOR_VOLUME_BOOST =
        booleanPreferencesKey("behavior_volume_boost")
    val BEHAVIOR_REMAINING_TIME_DISPLAY =
        stringPreferencesKey("behavior_remaining_time_display")
    val BEHAVIOR_TAP_UP_NEXT_TO_PLAY =
        booleanPreferencesKey("behavior_tap_up_next_to_play")
    val BEHAVIOR_INTELLIGENT_PLAYBACK_RESUMPTION =
        booleanPreferencesKey("behavior_intelligent_playback_resumption")
    val BEHAVIOR_EPISODE_SWIPE_RIGHT_ACTION =
        stringPreferencesKey("behavior_episode_swipe_right_action")
    val BEHAVIOR_EPISODE_SWIPE_RIGHT_SECONDARY_ACTION =
        stringPreferencesKey("behavior_episode_swipe_right_secondary_action")
    val BEHAVIOR_EPISODE_SWIPE_LEFT_ACTION =
        stringPreferencesKey("behavior_episode_swipe_left_action")
    val BEHAVIOR_EPISODE_SWIPE_LEFT_SECONDARY_ACTION =
        stringPreferencesKey("behavior_episode_swipe_left_secondary_action")
    val BEHAVIOR_DISCOVER_COUNTRY_CODE =
        stringPreferencesKey("behavior_discover_country_code")
    val BEHAVIOR_PLAYER_SEEK_BACK_INCREMENT =
        longPreferencesKey("behavior_player_seek_back_increment")
    val BEHAVIOR_PLAYER_SEEK_FORWARD_INCREMENT =
        longPreferencesKey("behavior_player_seek_forward_increment")
    val BEHAVIOR_HEADSET_NEXT_ACTION =
        stringPreferencesKey("behavior_headset_next_action")
    val BEHAVIOR_HEADSET_PREV_ACTION =
        stringPreferencesKey("behavior_headset_prev_action")
    val BEHAVIOR_SPEED_PRESETS =
        stringPreferencesKey("behavior_speed_presets")
    val BEHAVIOR_PLAYER_TOOLBAR_ACTIONS =
        stringPreferencesKey("behavior_player_toolbar_actions")
    val BEHAVIOR_PLAYER_TABS_CONFIG =
        stringPreferencesKey("behavior_player_tabs_config")
    val BEHAVIOR_UPDATE_PODCASTS_IN_ROAMING =
        booleanPreferencesKey("behavior_update_podcasts_in_roaming")
    val BEHAVIOR_UPDATE_PODCASTS_INTERVAL_MINUTES =
        intPreferencesKey("behavior_update_podcasts_interval_minutes")
    val BEHAVIOR_ENABLE_NEW_EPISODE_NOTIFICATIONS =
        booleanPreferencesKey("behavior_enable_new_episode_notifications")
    val BEHAVIOR_DOWNLOAD_METERED = booleanPreferencesKey("behavior_download_metered")
    val BEHAVIOR_DOWNLOAD_IN_ROAMING = booleanPreferencesKey("behavior_download_in_roaming")
    val BEHAVIOR_AUTO_DOWNLOADS_APPLY_SETTINGS =
        booleanPreferencesKey("behavior_auto_downloads_apply_settings")
    val BEHAVIOR_AUTO_DOWNLOAD_QUEUE =
        booleanPreferencesKey("behavior_auto_download_queue")
    val BEHAVIOR_DELETE_PLAYED_DOWNLOADS = booleanPreferencesKey("behavior_delete_played_downloads")
    val BEHAVIOR_DELETE_DOWNLOADS_AFTER_SECONDS =
        intPreferencesKey("behavior_delete_downloads_after_seconds")

    val HUSHCAST_WORD_MUTING_ENABLED = booleanPreferencesKey("hushcast_word_muting_enabled")
    val HUSHCAST_PREVIEW_MUTING = booleanPreferencesKey("hushcast_preview_muting")
    val HUSHCAST_MUTE_ACTION_MODE = stringPreferencesKey("hushcast_mute_action_mode")
    val HUSHCAST_MUTE_CUSTOM_SOUND_URI = stringPreferencesKey("hushcast_mute_custom_sound_uri")
    val HUSHCAST_SPONSOR_SKIP_SOUND_ENABLED = booleanPreferencesKey("hushcast_sponsor_skip_sound_enabled")
    val HUSHCAST_SPONSOR_SKIP_SOUND_URI = stringPreferencesKey("hushcast_sponsor_skip_sound_uri")
    val HUSHCAST_AD_POLICY_MODE = stringPreferencesKey("hushcast_ad_policy_mode")
    val HUSHCAST_AD_AUTO_SKIP_SCOPE = stringPreferencesKey("hushcast_ad_auto_skip_scope")
    val HUSHCAST_SETUP_COMPLETED = booleanPreferencesKey("hushcast_setup_completed")
    val HUSHCAST_SHIELD_HINT_SEEN = booleanPreferencesKey("hushcast_shield_hint_seen")
    val HUSHCAST_AUTO_DOWNLOAD_UP_NEXT = booleanPreferencesKey("hushcast_auto_download_up_next")
    val HUSHCAST_FAST_MODEL = booleanPreferencesKey("hushcast_fast_model")
    val HUSHCAST_FAST_ATTENTION = booleanPreferencesKey("hushcast_fast_attention")
    val HUSHCAST_ANALYZE_CHARGING_ONLY = booleanPreferencesKey("hushcast_analyze_charging_only")
    val HUSHCAST_CUSTOM_SOUNDS_JSON = stringPreferencesKey("hushcast_custom_sounds_json")
    /** Set once the pre-1.0.26 single-URI custom sounds have been written into the sound list. */
    val HUSHCAST_LEGACY_SOUNDS_MIGRATED = booleanPreferencesKey("hushcast_legacy_sounds_migrated_v1")
    val HUSHCAST_DEV_MODE = booleanPreferencesKey("hushcast_dev_mode")
    val HUSHCAST_BLEEP_STYLE = stringPreferencesKey("hushcast_bleep_style")
    val HUSHCAST_SKIP_SOUND = stringPreferencesKey("hushcast_skip_sound")
    val APPEARANCE_CUSTOM_SEED_COLOR = longPreferencesKey("appearance_custom_seed_color")
    val APPEARANCE_PALETTE_STYLE = stringPreferencesKey("appearance_palette_style")
    val APPEARANCE_CONTRAST = stringPreferencesKey("appearance_contrast")
    val APPEARANCE_PLAYER_BLUR_BACKGROUND = booleanPreferencesKey("appearance_player_blur_background")
    val APPEARANCE_PLAYER_BLUR_SHEETS = booleanPreferencesKey("appearance_player_blur_sheets")
    val BEHAVIOR_PLAYER_TOOLBAR_VISIBLE_COUNT = intPreferencesKey("behavior_player_toolbar_visible_count")

    val SYNC_ENABLE = booleanPreferencesKey("sync_enable")
    val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
    val SYNC_TYPE = stringPreferencesKey("sync_type")
    val SYNC_BASE_URL = stringPreferencesKey("sync_base_url")
    val SYNC_DEVICE_CAPTION = stringPreferencesKey("sync_device_caption")
    val SYNC_DEVICE_ID = stringPreferencesKey("sync_device_id")
    val SYNC_USERNAME = stringPreferencesKey("sync_username")
    val SYNC_PASSWORD = stringPreferencesKey("sync_password")
    val SYNC_AUTH = stringPreferencesKey("sync_auth")
    val SYNC_TIMESTAMP_SUBSCRIPTIONS = longPreferencesKey("sync_timestamp_subscriptions")
    val SYNC_TIMESTAMP_EPISODE_ACTIONS = longPreferencesKey("sync_timestamp_episode_actions")

    val PRIVACY_DISABLE_APPLE_PODCASTS_API =
        booleanPreferencesKey("privacy_disable_apple_podcasts_api")

    val DEBUG_ENABLE_UPDATE_NOTIFICATION = booleanPreferencesKey("debug_enable_update_notification")
    val DEBUG_ENABLE_NIGHTLY_NOTIFICATION =
        booleanPreferencesKey("debug_enable_nightly_notification")
}

class SettingsRepository(val context: Context) {

    val dataStore = context.dataStore

    val appearance = Appearance()
    val behavior = Behavior()
    val hushCast = HushCast()
    val sync = Sync()
    val privacy = Privacy()
    val debug = Debug()

    inner class Appearance {
        val themeMode: Flow<AppThemeMode> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.APPEARANCE_THEME_MODE]
                runCatching { raw?.let { AppThemeMode.valueOf(it) } }.getOrNull() ?: AppThemeMode.AUTO
            }

        suspend fun setThemeMode(mode: AppThemeMode) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_THEME_MODE] = mode.name
        }

        val darkThemeStyle: Flow<AppDarkThemeStyle> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.APPEARANCE_DARK_THEME_STYLE]
                runCatching { raw?.let { AppDarkThemeStyle.valueOf(it) } }.getOrNull()
                    ?: AppDarkThemeStyle.DEFAULT
            }

        suspend fun setDarkThemeStyle(style: AppDarkThemeStyle) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_DARK_THEME_STYLE] = style.name
        }

        val colorPalette: Flow<AppColorPalette> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.APPEARANCE_COLOR_PALETTE]
                runCatching { raw?.let { AppColorPalette.valueOf(it) } }.getOrNull()
                    ?: AppColorPalette.DEFAULT
            }

        suspend fun setColorPalette(palette: AppColorPalette) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_COLOR_PALETTE] = palette.name
        }

        val enableArtworkColors: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_ENABLE_ARTWORK_COLORS] ?: true
            }

        suspend fun setEnableArtworkColors(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_ENABLE_ARTWORK_COLORS] = enable
        }

        val enableDynamicColors: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_ENABLE_DYNAMIC_COLORS] ?: true
            }

        suspend fun setEnableDynamicColors(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_ENABLE_DYNAMIC_COLORS] = enable
        }

        /** Seed for [AppColorPalette.CUSTOM], as ARGB. */
        val customSeedColor: Flow<Long> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_CUSTOM_SEED_COLOR] ?: AppColorPalette.CUSTOM.seedColorHex
            }

        suspend fun setCustomSeedColor(argb: Long) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_CUSTOM_SEED_COLOR] = argb or 0xFF000000L
        }

        val paletteStyle: Flow<AppPaletteStyle> = dataStore.data
            .map { preferences ->
                runCatching { preferences[SettingsKeys.APPEARANCE_PALETTE_STYLE]?.let { AppPaletteStyle.valueOf(it) } }
                    .getOrNull() ?: AppPaletteStyle.TONAL
            }

        suspend fun setPaletteStyle(style: AppPaletteStyle) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_PALETTE_STYLE] = style.name
        }

        val contrast: Flow<AppContrast> = dataStore.data
            .map { preferences ->
                runCatching { preferences[SettingsKeys.APPEARANCE_CONTRAST]?.let { AppContrast.valueOf(it) } }
                    .getOrNull() ?: AppContrast.STANDARD
            }

        suspend fun setContrast(contrast: AppContrast) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_CONTRAST] = contrast.name
        }

        /** Blurred artwork behind the full player. */
        val playerBlurBackground: Flow<Boolean> = dataStore.data
            .map { preferences -> preferences[SettingsKeys.APPEARANCE_PLAYER_BLUR_BACKGROUND] ?: false }

        suspend fun setPlayerBlurBackground(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_PLAYER_BLUR_BACKGROUND] = enable
        }

        /** Blur the player behind its sheets, where the device supports window blur. */
        val playerBlurSheets: Flow<Boolean> = dataStore.data
            .map { preferences -> preferences[SettingsKeys.APPEARANCE_PLAYER_BLUR_SHEETS] ?: true }

        suspend fun setPlayerBlurSheets(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_PLAYER_BLUR_SHEETS] = enable
        }

        val useAlternativeBranding: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_USE_ALTERNATIVE_BRANDING] ?: false
            }

        suspend fun setUseAlternativeBranding(context: Context, enable: Boolean): Preferences {
            val default = ComponentName(context.packageName, "app.podiumpodcasts.podium.Default")
            val alias = ComponentName(context.packageName, "app.podiumpodcasts.podium.Alias")

            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    if(enable) alias else default,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                context.packageManager.setComponentEnabledSetting(
                    if(enable) default else alias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            return dataStore.edit { preferences ->
                preferences[SettingsKeys.APPEARANCE_USE_ALTERNATIVE_BRANDING] = enable
            }
        }

        val subscriptionsLayout: Flow<SubscriptionsLayout> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.APPEARANCE_SUBSCRIPTIONS_LAYOUT]
                runCatching { raw?.let { SubscriptionsLayout.valueOf(it) } }.getOrNull()
                    ?: SubscriptionsLayout.LARGE_GRID
            }

        suspend fun setSubscriptionsLayout(layout: SubscriptionsLayout) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_SUBSCRIPTIONS_LAYOUT] = layout.name
        }

        val subscriptionsSort: Flow<SubscriptionsSort> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.APPEARANCE_SUBSCRIPTIONS_SORT]
                runCatching { raw?.let { SubscriptionsSort.valueOf(it) } }.getOrNull()
                    ?: SubscriptionsSort.RECENTLY_ADDED
            }

        suspend fun setSubscriptionsSort(sort: SubscriptionsSort) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_SUBSCRIPTIONS_SORT] = sort.name
        }

        val subscriptionsBadgeMode: Flow<SubscriptionsBadgeMode> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.APPEARANCE_SUBSCRIPTIONS_BADGE_MODE]
                runCatching { raw?.let { SubscriptionsBadgeMode.valueOf(it) } }.getOrNull()
                    ?: SubscriptionsBadgeMode.UNPLAYED_COUNT
            }

        suspend fun setSubscriptionsBadgeMode(mode: SubscriptionsBadgeMode) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_SUBSCRIPTIONS_BADGE_MODE] = mode.name
        }

        val showGridTitles: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_SHOW_GRID_TITLES] ?: true
            }

        suspend fun setShowGridTitles(show: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_SHOW_GRID_TITLES] = show
        }

        val bottomBarItemsRaw: Flow<String> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_BOTTOM_BAR_ITEMS] ?: "HOME,DISCOVER,LIBRARY"
            }

        suspend fun setBottomBarItemsRaw(raw: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_BOTTOM_BAR_ITEMS] = raw
        }

        val startupDestination: Flow<String> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_STARTUP_DESTINATION] ?: "HOME"
            }

        suspend fun setStartupDestination(destination: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_STARTUP_DESTINATION] = destination
        }

        val podcastGridStateRaw: Flow<String?> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.APPEARANCE_PODCAST_GRID_STATE]
            }

        suspend fun setPodcastGridStateRaw(raw: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.APPEARANCE_PODCAST_GRID_STATE] = raw
        }
    }

    inner class Behavior {
        val playerPlaybackSpeed: Flow<Float> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_PLAYER_PLAYBACK_SPEED] ?: 1f
            }

        suspend fun setPlayerPlaybackSpeed(speed: Float) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_PLAYER_PLAYBACK_SPEED] = speed
        }

        val tapUpNextToPlay: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_TAP_UP_NEXT_TO_PLAY] ?: true
            }

        suspend fun setTapUpNextToPlay(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_TAP_UP_NEXT_TO_PLAY] = enable
        }

        val intelligentPlaybackResumption: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_INTELLIGENT_PLAYBACK_RESUMPTION] ?: false
            }

        suspend fun setIntelligentPlaybackResumption(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_INTELLIGENT_PLAYBACK_RESUMPTION] = enable
        }

        val episodeSwipeRightAction: Flow<EpisodeSwipeAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_RIGHT_ACTION]
                runCatching { raw?.let { EpisodeSwipeAction.valueOf(it) } }.getOrNull()
                    ?: EpisodeSwipeAction.PLAY_NEXT
            }

        suspend fun setEpisodeSwipeRightAction(action: EpisodeSwipeAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_RIGHT_ACTION] = action.name
        }

        val episodeSwipeRightSecondaryAction: Flow<EpisodeSwipeAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_RIGHT_SECONDARY_ACTION]
                runCatching { raw?.let { EpisodeSwipeAction.valueOf(it) } }.getOrNull()
                    ?: EpisodeSwipeAction.PLAY_LAST
            }

        suspend fun setEpisodeSwipeRightSecondaryAction(action: EpisodeSwipeAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_RIGHT_SECONDARY_ACTION] = action.name
        }

        val episodeSwipeLeftAction: Flow<EpisodeSwipeAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_LEFT_ACTION]
                runCatching { raw?.let { EpisodeSwipeAction.valueOf(it) } }.getOrNull()
                    ?: EpisodeSwipeAction.MARK_PLAYED
            }

        suspend fun setEpisodeSwipeLeftAction(action: EpisodeSwipeAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_LEFT_ACTION] = action.name
        }

        val episodeSwipeLeftSecondaryAction: Flow<EpisodeSwipeAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_LEFT_SECONDARY_ACTION]
                runCatching { raw?.let { EpisodeSwipeAction.valueOf(it) } }.getOrNull()
                    ?: EpisodeSwipeAction.DOWNLOAD
            }

        suspend fun setEpisodeSwipeLeftSecondaryAction(action: EpisodeSwipeAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_LEFT_SECONDARY_ACTION] = action.name
        }

        fun podcastAutoAddToUpNext(origin: String): Flow<AutoAddUpNextMode> {
            val key = stringPreferencesKey("podcast_auto_upnext_${origin.hashCode()}")
            return dataStore.data.map { preferences ->
                val raw = preferences[key]
                runCatching { raw?.let { AutoAddUpNextMode.valueOf(it) } }.getOrNull()
                    ?: AutoAddUpNextMode.OFF
            }
        }

        suspend fun setPodcastAutoAddToUpNext(origin: String, mode: AutoAddUpNextMode) {
            val key = stringPreferencesKey("podcast_auto_upnext_${origin.hashCode()}")
            dataStore.edit { preferences ->
                preferences[key] = mode.name
            }
        }

        fun podcastPlaybackSpeed(origin: String): Flow<Float> {
            val key = floatPreferencesKey("podcast_speed_${origin.hashCode()}")
            return dataStore.data.map { preferences ->
                preferences[key] ?: 0f
            }
        }

        suspend fun setPodcastPlaybackSpeed(origin: String, speed: Float) {
            val key = floatPreferencesKey("podcast_speed_${origin.hashCode()}")
            dataStore.edit { preferences ->
                preferences[key] = speed
            }
        }

        fun getDiscoverCountryCode(default: String): Flow<String> {
            return dataStore.data.map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_DISCOVER_COUNTRY_CODE]
                    ?: default
            }
        }

        suspend fun setDiscoverCountryCode(cc: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_DISCOVER_COUNTRY_CODE] = cc
        }

        val playerSeekBackIncrement: Flow<Long> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_PLAYER_SEEK_BACK_INCREMENT] ?: 10000
            }

        suspend fun setPlayerSeekBackIncrement(increment: Long) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_PLAYER_SEEK_BACK_INCREMENT] = increment
        }

        val playerSeekForwardIncrement: Flow<Long> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_PLAYER_SEEK_FORWARD_INCREMENT] ?: 10000
            }

        suspend fun setPlayerSeekForwardIncrement(increment: Long) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_PLAYER_SEEK_FORWARD_INCREMENT] = increment
        }

        val updatePodcastsInRoaming: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_UPDATE_PODCASTS_IN_ROAMING] ?: false
            }

        suspend fun setUpdatePodcastsInRoaming(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_UPDATE_PODCASTS_IN_ROAMING] = enable
        }

        val updatePodcastsIntervalMinutes: Flow<Int> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_UPDATE_PODCASTS_INTERVAL_MINUTES] ?: 60
            }

        suspend fun setUpdatePodcastsIntervalMinutes(minutes: Int) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_UPDATE_PODCASTS_INTERVAL_MINUTES] = minutes
        }

        val downloadMetered: Flow<Boolean> = dataStore.data
            .map { preferences -> preferences[SettingsKeys.BEHAVIOR_DOWNLOAD_METERED] ?: false }

        suspend fun setDownloadMetered(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_DOWNLOAD_METERED] = enable
        }

        val downloadInRoaming: Flow<Boolean> = dataStore.data
            .map { preferences -> preferences[SettingsKeys.BEHAVIOR_DOWNLOAD_IN_ROAMING] ?: false }

        suspend fun setDownloadInRoaming(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_DOWNLOAD_IN_ROAMING] = enable
        }

        val applySettingsForAutoDownloads: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_AUTO_DOWNLOADS_APPLY_SETTINGS] ?: false
            }

        suspend fun setApplySettingsForAutoDownloads(enable: Boolean) =
            dataStore.edit { preferences ->
                preferences[SettingsKeys.BEHAVIOR_AUTO_DOWNLOADS_APPLY_SETTINGS] = enable
            }

        val autoDownloadQueue: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_AUTO_DOWNLOAD_QUEUE] ?: false
            }

        suspend fun setAutoDownloadQueue(enable: Boolean) =
            dataStore.edit { preferences ->
                preferences[SettingsKeys.BEHAVIOR_AUTO_DOWNLOAD_QUEUE] = enable
            }

        val deletePlayedDownloads: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_DELETE_PLAYED_DOWNLOADS] ?: false
            }

        suspend fun setDeletePlayedDownloads(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_DELETE_PLAYED_DOWNLOADS] = enabled
        }

        val deleteDownloadsAfterSeconds: Flow<Int> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_DELETE_DOWNLOADS_AFTER_SECONDS] ?: -1
            }

        suspend fun setDeleteDownloadsAfterSeconds(seconds: Int) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_DELETE_DOWNLOADS_AFTER_SECONDS] = seconds
        }

        val enableNewEpisodeNotifications: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_ENABLE_NEW_EPISODE_NOTIFICATIONS] ?: false
            }

        suspend fun setEnableNewEpisodeNotifications(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_ENABLE_NEW_EPISODE_NOTIFICATIONS] = enable
        }

        val headsetNextAction: Flow<HeadsetNextAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_HEADSET_NEXT_ACTION]
                runCatching { raw?.let { HeadsetNextAction.valueOf(it) } }.getOrNull()
                    ?: HeadsetNextAction.SKIP_FORWARD
            }

        suspend fun setHeadsetNextAction(action: HeadsetNextAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_HEADSET_NEXT_ACTION] = action.name
        }

        val headsetPrevAction: Flow<HeadsetPrevAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_HEADSET_PREV_ACTION]
                runCatching { raw?.let { HeadsetPrevAction.valueOf(it) } }.getOrNull()
                    ?: HeadsetPrevAction.SKIP_BACK
            }

        suspend fun setHeadsetPrevAction(action: HeadsetPrevAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_HEADSET_PREV_ACTION] = action.name
        }

        val speedPresets: Flow<List<Float>> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_SPEED_PRESETS]
                    ?: "0.8,1.0,1.2,1.5,1.8,2.0"
                raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.ifEmpty {
                    listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
                }
            }

        suspend fun setSpeedPresets(presets: List<Float>) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_SPEED_PRESETS] = presets.distinct().sorted().joinToString(",")
        }

        val playerToolbarActions: Flow<List<PlayerToolbarAction>> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_PLAYER_TOOLBAR_ACTIONS]
                    ?: "HUSHCAST,SPEED,SLEEP,TRANSCRIPT"
                raw.split(",").mapNotNull { token ->
                    runCatching { PlayerToolbarAction.valueOf(token.trim()) }.getOrNull()
                }.ifEmpty {
                    listOf(
                        PlayerToolbarAction.HUSHCAST,
                        PlayerToolbarAction.SPEED,
                        PlayerToolbarAction.SLEEP,
                        PlayerToolbarAction.TRANSCRIPT
                    )
                }
            }

        suspend fun setPlayerToolbarActions(actions: List<PlayerToolbarAction>) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_PLAYER_TOOLBAR_ACTIONS] = actions.distinct().joinToString(",") { it.name }
        }

        /** How many actions show in the player's toolbar before the ⋯ menu (3–5). */
        val playerToolbarVisibleCount: Flow<Int> = dataStore.data
            .map { preferences ->
                (preferences[SettingsKeys.BEHAVIOR_PLAYER_TOOLBAR_VISIBLE_COUNT] ?: 4).coerceIn(3, 5)
            }

        suspend fun setPlayerToolbarVisibleCount(count: Int) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_PLAYER_TOOLBAR_VISIBLE_COUNT] = count.coerceIn(3, 5)
        }

        val playerTabsConfig: Flow<List<PlayerTabConfig>> = dataStore.data
            .map { preferences ->
                val defaults = listOf(
                    PlayerTabConfig("PLAYER", visible = true),
                    PlayerTabConfig("NOTES", visible = true),
                    PlayerTabConfig("CHAPTERS", visible = true)
                )
                val raw = preferences[SettingsKeys.BEHAVIOR_PLAYER_TABS_CONFIG]
                val parsed = if (raw.isNullOrBlank()) {
                    defaults
                } else {
                    runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<List<PlayerTabConfig>>(raw)
                    }.getOrNull().orEmpty()
                }
                val validIds = setOf("PLAYER", "NOTES", "CHAPTERS")
                val deduped = parsed.filter { it.id in validIds }.distinctBy { it.id }.toMutableList()
                defaults.forEach { def ->
                    if (deduped.none { it.id == def.id }) {
                        deduped.add(def)
                    }
                }
                val playerIdx = deduped.indexOfFirst { it.id == "PLAYER" }
                if (playerIdx > 0) {
                    val playerCfg = deduped.removeAt(playerIdx)
                    deduped.add(0, playerCfg.copy(visible = true))
                } else if (playerIdx == 0) {
                    deduped[0] = deduped[0].copy(visible = true)
                }
                deduped
            }

        suspend fun setPlayerTabsConfig(tabs: List<PlayerTabConfig>) = dataStore.edit { preferences ->
            val sanitized = tabs.distinctBy { it.id }.toMutableList()
            if (sanitized.none { it.visible }) {
                val idx = sanitized.indexOfFirst { it.id == "PLAYER" }.takeIf { it >= 0 } ?: 0
                if (idx in sanitized.indices) {
                    sanitized[idx] = sanitized[idx].copy(visible = true)
                }
            }
            preferences[SettingsKeys.BEHAVIOR_PLAYER_TABS_CONFIG] =
                kotlinx.serialization.json.Json.encodeToString(sanitized)
        }

        val trimSilence: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_TRIM_SILENCE] ?: false
            }

        suspend fun setTrimSilence(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_TRIM_SILENCE] = enabled
        }

        val volumeBoost: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.BEHAVIOR_VOLUME_BOOST] ?: false
            }

        suspend fun setVolumeBoost(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_VOLUME_BOOST] = enabled
        }

        val remainingTimeDisplay: Flow<RemainingTimeDisplayMode> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_REMAINING_TIME_DISPLAY]
                runCatching { raw?.let { RemainingTimeDisplayMode.valueOf(it) } }.getOrNull()
                    ?: RemainingTimeDisplayMode.BOTH
            }

        suspend fun setRemainingTimeDisplay(mode: RemainingTimeDisplayMode) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_REMAINING_TIME_DISPLAY] = mode.name
        }
    }

    inner class HushCast {
        val wordMutingEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_WORD_MUTING_ENABLED] ?: true
        }

        suspend fun setWordMutingEnabled(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_WORD_MUTING_ENABLED] = enabled
        }

        /**
         * Apply word muting to soundbite previews of episodes HushCast has already processed.
         * Off by default: previews play as published, and never download or process anything.
         */
        val previewMuting: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_PREVIEW_MUTING] ?: false
        }

        suspend fun setPreviewMuting(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_PREVIEW_MUTING] = enabled
        }

        val muteActionMode: Flow<app.hushcast.automute.dsp.MuteActionMode> = dataStore.data.map { preferences ->
            val raw = preferences[SettingsKeys.HUSHCAST_MUTE_ACTION_MODE]
            runCatching { raw?.let { app.hushcast.automute.dsp.MuteActionMode.valueOf(it) } }.getOrNull()
                ?: app.hushcast.automute.dsp.MuteActionMode.HARD_SILENCE
        }

        suspend fun setMuteActionMode(mode: app.hushcast.automute.dsp.MuteActionMode) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_MUTE_ACTION_MODE] = mode.name
        }

        val muteCustomSoundUri: Flow<String?> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI]?.takeIf { it.isNotBlank() }
        }

        /** The selected custom mute sound; null when a built-in tone is chosen. */
        suspend fun setMuteCustomSoundUri(uri: String?) = dataStore.edit { preferences ->
            // A pre-1.0.26 sound lives only in this key: keep it in the list before replacing it.
            persistLegacySounds(preferences)
            if (uri.isNullOrBlank()) preferences.remove(SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI)
            else preferences[SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI] = uri
        }

        val sponsorSkipSoundEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_ENABLED] ?: false
        }

        suspend fun setSponsorSkipSoundEnabled(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_ENABLED] = enabled
        }

        val sponsorSkipSoundUri: Flow<String?> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI]?.takeIf { it.isNotBlank() }
        }

        suspend fun setSponsorSkipSoundUri(uri: String?) = dataStore.edit { preferences ->
            persistLegacySounds(preferences)
            if (uri.isNullOrBlank()) preferences.remove(SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI)
            else preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI] = uri
        }

        val adPolicyMode: Flow<app.hushcast.automute.dsp.AdPolicyMode> = dataStore.data.map { preferences ->
            val raw = preferences[SettingsKeys.HUSHCAST_AD_POLICY_MODE]
            runCatching { raw?.let { app.hushcast.automute.dsp.AdPolicyMode.valueOf(it) } }.getOrNull()
                ?: app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP
        }

        suspend fun setAdPolicyMode(mode: app.hushcast.automute.dsp.AdPolicyMode) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_AD_POLICY_MODE] = mode.name
        }

        val adAutoSkipScope: Flow<app.hushcast.automute.dsp.AdAutoSkipScope> = dataStore.data.map { preferences ->
            val raw = preferences[SettingsKeys.HUSHCAST_AD_AUTO_SKIP_SCOPE]
            runCatching { raw?.let { app.hushcast.automute.dsp.AdAutoSkipScope.valueOf(it) } }.getOrNull()
                ?: app.hushcast.automute.dsp.AdAutoSkipScope.HIGH_CONFIDENCE_ONLY
        }

        suspend fun setAdAutoSkipScope(scope: app.hushcast.automute.dsp.AdAutoSkipScope) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_AD_AUTO_SKIP_SCOPE] = scope.name
        }

        private val HUSHCAST_AUTO_SKIP_CLASSES_KEY = androidx.datastore.preferences.core.stringSetPreferencesKey("hushcast_auto_skip_classes")

        val adAutoSkipClasses: Flow<Set<String>> = dataStore.data.map { preferences ->
            preferences[HUSHCAST_AUTO_SKIP_CLASSES_KEY] ?: setOf("sponsor_ad")
        }

        suspend fun setAdAutoSkipClasses(classes: Set<String>) = dataStore.edit { preferences ->
            preferences[HUSHCAST_AUTO_SKIP_CLASSES_KEY] = classes
        }

        val setupCompleted: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_SETUP_COMPLETED] ?: false
        }

        suspend fun setSetupCompleted(completed: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_SETUP_COMPLETED] = completed
        }

        val shieldHintSeen: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_SHIELD_HINT_SEEN] ?: false
        }

        suspend fun setShieldHintSeen(seen: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_SHIELD_HINT_SEEN] = seen
        }

        val autoDownloadUpNext: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_AUTO_DOWNLOAD_UP_NEXT] ?: true
        }

        suspend fun setAutoDownloadUpNext(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_AUTO_DOWNLOAD_UP_NEXT] = enabled
        }

        fun podcastWordMuting(origin: String): Flow<Boolean?> {
            val key = booleanPreferencesKey("podcast_word_muting_${origin.hashCode()}")
            return dataStore.data.map { preferences ->
                preferences[key]
            }
        }

        suspend fun setPodcastWordMuting(origin: String, enabled: Boolean?) {
            val key = booleanPreferencesKey("podcast_word_muting_${origin.hashCode()}")
            dataStore.edit { preferences ->
                if (enabled == null) preferences.remove(key)
                else preferences[key] = enabled
            }
        }

        fun podcastDisableAutoProcess(origin: String): Flow<Boolean> {
            val key = booleanPreferencesKey("podcast_disable_auto_process_${origin.hashCode()}")
            return dataStore.data.map { preferences ->
                preferences[key] ?: false
            }
        }

        suspend fun isPodcastDisableAutoProcess(origin: String): Boolean {
            return podcastDisableAutoProcess(origin).first()
        }

        suspend fun setPodcastDisableAutoProcess(origin: String, disabled: Boolean) {
            val key = booleanPreferencesKey("podcast_disable_auto_process_${origin.hashCode()}")
            dataStore.edit { preferences ->
                if (!disabled) preferences.remove(key)
                else preferences[key] = true
            }
        }

        val devMode: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.HUSHCAST_DEV_MODE] ?: BuildConfig.DEV_TOOLS
        }

        suspend fun setDevMode(enabled: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_DEV_MODE] = enabled
        }

        val bleepStyle: Flow<app.hushcast.automute.dsp.BleepStyle> = dataStore.data.map { preferences ->
            app.hushcast.automute.dsp.BleepStyle.fromName(preferences[SettingsKeys.HUSHCAST_BLEEP_STYLE])
        }

        suspend fun setBleepStyle(style: app.hushcast.automute.dsp.BleepStyle) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_BLEEP_STYLE] = style.name
        }

        val skipSound: Flow<SkipSound> = dataStore.data.map { preferences ->
            runCatching { preferences[SettingsKeys.HUSHCAST_SKIP_SOUND]?.let { SkipSound.valueOf(it) } }
                .getOrNull() ?: SkipSound.WHOOSH
        }

        suspend fun setSkipSound(sound: SkipSound) = dataStore.edit { preferences ->
            preferences[SettingsKeys.HUSHCAST_SKIP_SOUND] = sound.name
        }

        /**
         * The saved sound list.  Sounds saved before 1.0.26 are moved into it once at startup
         * ([migrateLegacyCustomSoundsOnce]); until then they're added here, so they never vanish.
         */
        val customSounds: Flow<List<CustomSoundPreset>> = dataStore.data.map { preferences ->
            storedSoundsWithLegacy(preferences)
        }

        suspend fun addCustomSound(name: String, uri: String): CustomSoundPreset {
            val preset = CustomSoundPreset(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim().ifEmpty { "Custom Sound" },
                uri = uri
            )
            dataStore.edit { preferences ->
                val current = storedSoundsWithLegacy(preferences).toMutableList()
                current.add(preset)
                preferences[SettingsKeys.HUSHCAST_CUSTOM_SOUNDS_JSON] = LegacyCustomSounds.encode(current)
            }
            return preset
        }

        /**
         * The saved sound list plus the entries for sounds saved before 1.0.26 (one URI per slot)
         * that aren't in it yet.  Rename, delete and add write this list back, so those entries
         * are kept too.
         */
        private fun storedSoundsWithLegacy(preferences: Preferences): List<CustomSoundPreset> =
            LegacyCustomSounds.withLegacy(
                stored = LegacyCustomSounds.parse(preferences[SettingsKeys.HUSHCAST_CUSTOM_SOUNDS_JSON]).orEmpty(),
                legacyMuteUri = preferences[SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI],
                legacySkipUri = preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI]
            )

        /**
         * Writes legacy sounds that are only in the selection keys into the list.  Returns false
         * when the saved list can't be read (it's left untouched then).
         */
        private fun persistLegacySounds(preferences: androidx.datastore.preferences.core.MutablePreferences): Boolean =
            when (
                val migration = LegacyCustomSounds.migrate(
                    rawJson = preferences[SettingsKeys.HUSHCAST_CUSTOM_SOUNDS_JSON],
                    legacyMuteUri = preferences[SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI],
                    legacySkipUri = preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI]
                )
            ) {
                is LegacyCustomSounds.Migration.Write -> {
                    preferences[SettingsKeys.HUSHCAST_CUSTOM_SOUNDS_JSON] = LegacyCustomSounds.encode(migration.sounds)
                    true
                }
                LegacyCustomSounds.Migration.UpToDate -> true
                LegacyCustomSounds.Migration.Unreadable -> false
            }

        /**
         * One-time startup migration: custom sounds saved before 1.0.26 are written into the sound
         * list, so the mute/skip selection keys only mean "the selected sound" from then on.
         */
        suspend fun migrateLegacyCustomSoundsOnce() {
            dataStore.edit { preferences ->
                if (preferences[SettingsKeys.HUSHCAST_LEGACY_SOUNDS_MIGRATED] == true) return@edit
                // An unreadable list is left alone and retried next start; the read path still
                // shows the legacy sounds meanwhile.
                if (persistLegacySounds(preferences)) {
                    if (LegacyCustomSounds.isLeftoverMuteSelection(preferences[SettingsKeys.HUSHCAST_MUTE_ACTION_MODE])) {
                        preferences.remove(SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI)
                    }
                    preferences[SettingsKeys.HUSHCAST_LEGACY_SOUNDS_MIGRATED] = true
                }
            }
        }

        suspend fun renameCustomSound(id: String, newName: String) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return
            dataStore.edit { preferences ->
                val current = storedSoundsWithLegacy(preferences)
                val updated = current.map { if (it.id == id) it.copy(name = trimmed) else it }
                preferences[SettingsKeys.HUSHCAST_CUSTOM_SOUNDS_JSON] = LegacyCustomSounds.encode(updated)
            }
        }

        suspend fun removeCustomSound(id: String) {
            dataStore.edit { preferences ->
                val current = storedSoundsWithLegacy(preferences)
                val removed = current.firstOrNull { it.id == id }
                val updated = current.filterNot { it.id == id }
                preferences[SettingsKeys.HUSHCAST_CUSTOM_SOUNDS_JSON] = LegacyCustomSounds.encode(updated)
                if (removed != null) {
                    if (preferences[SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI] == removed.uri) {
                        preferences.remove(SettingsKeys.HUSHCAST_MUTE_CUSTOM_SOUND_URI)
                    }
                    if (preferences[SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI] == removed.uri) {
                        preferences.remove(SettingsKeys.HUSHCAST_SPONSOR_SKIP_SOUND_URI)
                    }
                }
            }
        }
    }

    inner class Sync {
        val enable: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_ENABLE] ?: false
        }

        suspend fun setEnable(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_ENABLE] = enable
        }

        val intervalMinutes: Flow<Int> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_INTERVAL_MINUTES] ?: 60
        }

        suspend fun setIntervalMinutes(minutes: Int) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_INTERVAL_MINUTES] = minutes
        }

        val type: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_TYPE] ?: "gpodder"
        }

        suspend fun setType(type: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_TYPE] = type
        }

        val baseUrl: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_BASE_URL] ?: "https://gpodder.net"
        }

        suspend fun setBaseUrl(baseUrl: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_BASE_URL] = baseUrl
        }

        val deviceId: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_DEVICE_ID] ?: ""
        }

        suspend fun setDeviceId(deviceId: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_DEVICE_ID] = deviceId
        }

        val deviceCaption: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_DEVICE_CAPTION] ?: ""
        }

        suspend fun setDeviceCaption(deviceCaption: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_DEVICE_CAPTION] = deviceCaption
        }

        val username: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_USERNAME] ?: ""
        }

        suspend fun setUsername(username: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_USERNAME] = username
        }

        val password: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_PASSWORD] ?: ""
        }

        suspend fun setPassword(password: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_PASSWORD] = password
        }

        val auth: Flow<String> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_AUTH] ?: ""
        }

        suspend fun setAuth(auth: String) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_AUTH] = auth
        }

        val timestampSubscriptions: Flow<Long> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_TIMESTAMP_SUBSCRIPTIONS] ?: 0L
        }

        suspend fun setTimestampSubscriptions(timestamp: Long) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_TIMESTAMP_SUBSCRIPTIONS] = timestamp
        }

        val timestampEpisodeActions: Flow<Long> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_TIMESTAMP_EPISODE_ACTIONS] ?: 0L
        }

        suspend fun setTimestampEpisodeActions(timestamp: Long) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_TIMESTAMP_EPISODE_ACTIONS] = timestamp
        }
    }

    inner class Privacy {
        val disableApplePodcastsApi: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.PRIVACY_DISABLE_APPLE_PODCASTS_API] ?: false
            }

        suspend fun setDisableApplePodcastsApi(disable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.PRIVACY_DISABLE_APPLE_PODCASTS_API] = disable
        }
    }

    inner class Debug {
        val enableUpdateNotification: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.DEBUG_ENABLE_UPDATE_NOTIFICATION] ?: false
            }

        suspend fun setEnableUpdateNotification(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.DEBUG_ENABLE_UPDATE_NOTIFICATION] = enable
        }

        val enableNightlyNotification: Flow<Boolean> = dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.DEBUG_ENABLE_NIGHTLY_NOTIFICATION] ?: false
            }

        suspend fun setEnableNightlyNotification(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.DEBUG_ENABLE_NIGHTLY_NOTIFICATION] = enable
        }
    }
}