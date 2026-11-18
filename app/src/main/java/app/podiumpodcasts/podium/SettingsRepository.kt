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
    ROSE("Rose", 0xFFC2185B)
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
    PLAY_NEXT("Add to Up Next (Play Next)"),
    PLAY_LAST("Add to Up Next (Play Last)"),
    MARK_PLAYED("Mark Played / Unplayed"),
    DOWNLOAD("Download / Delete Download"),
    NONE("None")
}

enum class HeadsetNextAction(val label: String) {
    SKIP_FORWARD("Skip forward"),
    NEXT_EPISODE("Next episode in queue")
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
    FAVORITE("Favorite"),
    DOWNLOAD("Download"),
    GO_TO_PODCAST("Go to Podcast"),
    EPISODE_DETAILS("Episode Details & Notes"),
    MARK_PLAYED("Mark played"),
    ANALYZE("Analyze with HushCast AI")
}

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
                    ?: "HUSHCAST,SPEED,SLEEP,FAVORITE"
                raw.split(",").mapNotNull { token ->
                    runCatching { PlayerToolbarAction.valueOf(token.trim()) }.getOrNull()
                }.ifEmpty {
                    listOf(
                        PlayerToolbarAction.HUSHCAST,
                        PlayerToolbarAction.SPEED,
                        PlayerToolbarAction.SLEEP,
                        PlayerToolbarAction.FAVORITE
                    )
                }
            }

        suspend fun setPlayerToolbarActions(actions: List<PlayerToolbarAction>) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_PLAYER_TOOLBAR_ACTIONS] = actions.distinct().joinToString(",") { it.name }
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

        suspend fun setMuteCustomSoundUri(uri: String?) = dataStore.edit { preferences ->
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