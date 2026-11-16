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
    HEAR_LATER("Add / Remove Hear Later"),
    MARK_PLAYED("Mark Played / Unplayed"),
    DOWNLOAD("Download / Delete Download"),
    NONE("None")
}

object SettingsKeys {
    val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
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

    val BEHAVIOR_PLAYER_PLAYBACK_SPEED =
        floatPreferencesKey("behavior_player_playback_speed")
    val BEHAVIOR_TAP_UP_NEXT_TO_PLAY =
        booleanPreferencesKey("behavior_tap_up_next_to_play")
    val BEHAVIOR_EPISODE_SWIPE_RIGHT_ACTION =
        stringPreferencesKey("behavior_episode_swipe_right_action")
    val BEHAVIOR_EPISODE_SWIPE_LEFT_ACTION =
        stringPreferencesKey("behavior_episode_swipe_left_action")
    val BEHAVIOR_DISCOVER_COUNTRY_CODE =
        stringPreferencesKey("behavior_discover_country_code")
    val BEHAVIOR_PLAYER_SEEK_BACK_INCREMENT =
        longPreferencesKey("behavior_player_seek_back_increment")
    val BEHAVIOR_PLAYER_SEEK_FORWARD_INCREMENT =
        longPreferencesKey("behavior_player_seek_forward_increment")
    val BEHAVIOR_UPDATE_PODCASTS_IN_ROAMING =
        booleanPreferencesKey("behavior_update_podcasts_in_roaming")
    val BEHAVIOR_UPDATE_PODCASTS_INTERVAL_MINUTES =
        intPreferencesKey("behavior_update_podcasts_interval_minutes")
    val BEHAVIOR_DOWNLOAD_METERED = booleanPreferencesKey("behavior_download_metered")
    val BEHAVIOR_DOWNLOAD_IN_ROAMING = booleanPreferencesKey("behavior_download_in_roaming")
    val BEHAVIOR_AUTO_DOWNLOADS_APPLY_SETTINGS =
        booleanPreferencesKey("behavior_auto_downloads_apply_settings")
    val BEHAVIOR_AUTO_DOWNLOAD_QUEUE =
        booleanPreferencesKey("behavior_auto_download_queue")
    val BEHAVIOR_DELETE_PLAYED_DOWNLOADS = booleanPreferencesKey("behavior_delete_played_downloads")
    val BEHAVIOR_DELETE_DOWNLOADS_AFTER_SECONDS =
        intPreferencesKey("behavior_delete_downloads_after_seconds")

    val SYNC_ENABLE = booleanPreferencesKey("sync_enable")
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

        val episodeSwipeRightAction: Flow<EpisodeSwipeAction> = dataStore.data
            .map { preferences ->
                val raw = preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_RIGHT_ACTION]
                runCatching { raw?.let { EpisodeSwipeAction.valueOf(it) } }.getOrNull()
                    ?: EpisodeSwipeAction.HEAR_LATER
            }

        suspend fun setEpisodeSwipeRightAction(action: EpisodeSwipeAction) = dataStore.edit { preferences ->
            preferences[SettingsKeys.BEHAVIOR_EPISODE_SWIPE_RIGHT_ACTION] = action.name
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
    }

    inner class Sync {
        val enable: Flow<Boolean> = dataStore.data.map { preferences ->
            preferences[SettingsKeys.SYNC_ENABLE] ?: false
        }

        suspend fun setEnable(enable: Boolean) = dataStore.edit { preferences ->
            preferences[SettingsKeys.SYNC_ENABLE] = enable
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