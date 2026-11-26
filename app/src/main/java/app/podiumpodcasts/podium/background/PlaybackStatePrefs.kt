package app.podiumpodcasts.podium.background

import android.content.Context

object PlaybackStatePrefs {
    private const val PREFS_NAME = "hushcast_playback_state"
    private const val KEY_LAST_PLAYING_EPISODE_ID = "last_playing_episode_id"

    fun saveLastPlayingEpisodeId(context: Context, episodeId: String?) {
        if (episodeId.isNullOrBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PLAYING_EPISODE_ID, episodeId)
            .apply()
    }

    fun loadLastPlayingEpisodeId(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PLAYING_EPISODE_ID, null)
    }
}
