package app.podiumpodcasts.podium.background.car

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.db.model.PodcastModel

/** Media ids of the car's browse tree. */
object CarIds {
    const val ROOT = "root"
    const val TAB_UP_NEXT = "tab:up_next"
    const val TAB_NEW = "tab:new_episodes"
    const val TAB_PODCASTS = "tab:subscriptions"
    const val TAB_DOWNLOADS = "tab:downloads"

    /** Tabs of 1.0.47 and earlier. A car can still ask for them from a screen it kept. */
    const val LEGACY_CONTINUE = "tab:continue_playing"
    const val LEGACY_LOCAL = "tab:locally_available"

    const val PODCAST_PREFIX = "podcast:"
    const val EPISODE_PREFIX = "episode:"

    fun podcast(origin: String) = PODCAST_PREFIX + origin
    fun episode(id: String) = EPISODE_PREFIX + id
}

/** Custom session commands the car uses: row actions in lists and buttons on Now Playing. */
object CarActions {
    const val ADD_UP_NEXT = "app.hushcast.car.ADD_UP_NEXT"
    const val DOWNLOAD = "app.hushcast.car.DOWNLOAD"
    const val MARK_PLAYED = "app.hushcast.car.MARK_PLAYED"

    const val SKIP_AD = "app.hushcast.car.SKIP_AD"
    const val UNDO_SKIP = "app.hushcast.car.UNDO_SKIP"
    const val NEXT_CHAPTER = "app.hushcast.car.NEXT_CHAPTER"

    val ROW = listOf(ADD_UP_NEXT, DOWNLOAD, MARK_PLAYED)
    val NOW_PLAYING = listOf(SKIP_AD, UNDO_SKIP, NEXT_CHAPTER)
}

/** Extras keys Media3 has no constant for. */
object CarKeys {
    /** Browse action result: a short message the car shows. */
    const val ACTION_RESULT_MESSAGE = "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE"

    /** Browse action result: ask the car to reload the row (its actions changed). */
    const val ACTION_RESULT_REFRESH_ITEM = "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM"
}

/** What an episode row knows about itself, to pick its actions. */
data class CarRowState(
    val inUpNext: Boolean,
    val isCurrent: Boolean,
    val downloadState: Int?,
    val played: Boolean
)

object CarRowActions {
    /** Only the actions that would change something, in a fixed order. */
    fun forEpisode(s: CarRowState): List<String> = buildList {
        if (!s.inUpNext && !s.isCurrent) add(CarActions.ADD_UP_NEXT)
        if (s.downloadState != PodcastEpisodeDownloadState.DOWNLOADED.value &&
            s.downloadState != PodcastEpisodeDownloadState.DOWNLOADING.value
        ) add(CarActions.DOWNLOAD)
        if (!s.played) add(CarActions.MARK_PLAYED)
    }
}

/** Builds the browse tree's items. Everything here is cheap; the lists' data comes from [CarLibrary]. */
@UnstableApi
class CarMediaItems(private val context: Context) {

    val strings = CarStrings(
        episodeNumber = context.getString(R.string.car_episode_number),
        timeLeft = context.getString(R.string.car_time_left),
        played = context.getString(R.string.car_played),
        adUntil = context.getString(R.string.car_ad_until),
        adSkipped = context.getString(R.string.car_ad_skipped)
    )

    fun string(id: Int): String = context.getString(id)

    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** android.resource://<package>/drawable/<name>, which the car loads itself. */
    fun resourceUri(@DrawableRes id: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    fun root(): MediaItem = MediaItem.Builder()
        .setMediaId(CarIds.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsPlayable(false)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    /** The tabs, in order, within the host's [limit]: Downloads goes first, then New. */
    fun tabs(limit: Int = 4): List<MediaItem> = CarSections.tabsWithin(allTabs(), limit) { it.mediaId }

    private fun allTabs(): List<MediaItem> = listOf(
        tab(CarIds.TAB_UP_NEXT, R.string.route_up_next, R.drawable.ic_car_up_next, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        tab(CarIds.TAB_NEW, R.string.car_tab_new, R.drawable.ic_car_new, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        tab(CarIds.TAB_PODCASTS, R.string.car_tab_podcasts, R.drawable.ic_car_podcasts, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
        tab(CarIds.TAB_DOWNLOADS, R.string.car_tab_downloads, R.drawable.ic_car_downloads, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    )

    private fun tab(id: String, title: Int, @DrawableRes icon: Int, type: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(context.getString(title))
                .setArtworkUri(resourceUri(icon))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setExtras(
                    Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                    }
                )
                .build()
        )
        .build()

    /** A podcast tile; its episodes open as a list. */
    fun podcast(p: PodcastModel, groupTitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(CarIds.podcast(p.origin))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(p.fetchTitle())
                .setSubtitle(p.author.takeIf { it.isNotBlank() })
                .setArtist(p.author.takeIf { it.isNotBlank() })
                .apply { if (p.imageUrl.isNotBlank()) setArtworkUri(p.imageUrl.toUri()) }
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                .setExtras(
                    Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                        if (groupTitle != null) putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
                    }
                )
                .build()
        )
        .build()

    /**
     * An episode row. It has no URI: tapping it sends "episode:<id>", which the session resolves
     * (with the saved position) in onSetMediaItems.
     * @param podcastName set in lists that mix podcasts (the podcast's display name), so the
     *   subtitle leads with it; null inside a podcast.
     */
    fun episode(
        b: PodcastEpisodeBundle,
        podcastName: String?,
        actions: List<String>,
        groupTitle: String? = null
    ): MediaItem {
        val e = b.episode
        val positionSec = b.playState?.state ?: 0
        val played = b.playState?.played == true
        val completion = CarText.completion(played, positionSec, e.duration)

        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completion.status)
            completion.percentage?.let { putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, it) }
            if (e.explicit) putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT)
            putLong(
                MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS,
                when (b.download?.state) {
                    PodcastEpisodeDownloadState.DOWNLOADED.value -> MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED
                    PodcastEpisodeDownloadState.DOWNLOADING.value -> MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADING
                    else -> MediaConstants.EXTRAS_VALUE_STATUS_NOT_DOWNLOADED
                }
            )
            if (groupTitle != null) putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
        }

        return MediaItem.Builder()
            .setMediaId(CarIds.episode(e.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setDisplayTitle(e.title)
                    .setSubtitle(
                        CarText.episodeSubtitle(
                            strings,
                            podcastName = podcastName,
                            episodeNumber = e.episodeNumber,
                            durationSec = e.duration,
                            positionSec = positionSec,
                            played = played
                        )
                    )
                    .setArtist(podcastName ?: e.podcastTitle)
                    .apply { e.imageUrl?.takeIf { it.isNotBlank() }?.let { setArtworkUri(it.toUri()) } }
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setSupportedCommands(actions)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}
