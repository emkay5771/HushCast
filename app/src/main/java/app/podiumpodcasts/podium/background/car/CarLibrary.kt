package app.podiumpodcasts.podium.background.car

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.api.db.model.PodcastModel

/**
 * The car's browse tree, search and voice lookups. Call from a background thread.
 *
 * Tabs: Up Next, New (Continue listening + New episodes), Podcasts (grid), Downloads.
 */
@UnstableApi
class CarLibrary(
    private val db: AppDatabase,
    private val items: CarMediaItems,
    /** The episode loaded in the player, if any. */
    private val currentEpisodeId: () -> String?
) {

    companion object {
        const val MAX_PODCASTS = 100
        const val MAX_PODCAST_EPISODES = 200
        const val MAX_DOWNLOADS = 100
        const val MAX_SEARCH_PODCASTS = 10
        const val MAX_SEARCH_EPISODES = 30
    }

    /** The host's tab limit, from its root request. */
    @Volatile
    var rootLimit: Int = 4

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        CarIds.ROOT -> items.tabs(rootLimit)
        CarIds.TAB_UP_NEXT -> upNext()
        CarIds.TAB_NEW -> newTab()
        CarIds.TAB_PODCASTS, CarIds.LEGACY_LOCAL -> podcasts()
        CarIds.TAB_DOWNLOADS -> downloads()
        CarIds.LEGACY_CONTINUE -> episodeRows(continuing(), mixed = true)
        else -> if (parentId.startsWith(CarIds.PODCAST_PREFIX)) {
            podcastEpisodes(parentId.removePrefix(CarIds.PODCAST_PREFIX))
        } else emptyList()
    }

    /** One item by id, for the car's "get item" requests. */
    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == CarIds.ROOT -> items.root()
        mediaId.startsWith("tab:") -> items.tabs().firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith(CarIds.PODCAST_PREFIX) ->
            db.podcasts().getSync(mediaId.removePrefix(CarIds.PODCAST_PREFIX))?.let { items.podcast(it) }
        mediaId.startsWith(CarIds.EPISODE_PREFIX) ->
            db.podcastEpisodes().getSyncNullable(mediaId.removePrefix(CarIds.EPISODE_PREFIX))
                ?.let { episodeRows(listOf(it), mixed = true).firstOrNull() }
        else -> null
    }

    private suspend fun upNext(): List<MediaItem> {
        val currentId = currentEpisodeId()
        val queuedIds = db.playbackQueue().getQueueSync().map { it.episodeId }
        val allIds = (listOfNotNull(currentId) + queuedIds).distinct()
        if (allIds.isEmpty()) return emptyList()
        val byId = db.podcastEpisodes().getByIds(allIds).associateBy { it.episode.id }
        val rows = CarSections.upNext(
            current = currentId?.let { byId[it] },
            queued = queuedIds.mapNotNull { byId[it] },
            id = { it.episode.id },
            nowPlayingTitle = items.string(R.string.car_section_now_playing),
            upNextTitle = items.string(R.string.route_up_next)
        )
        return episodeRows(rows, mixed = true)
    }

    private suspend fun continuing(): List<PodcastEpisodeBundle> =
        db.podcastEpisodePlayStates().getRecentlyPlayingSync(CarSections.MAX_CONTINUE)
            .map { it.toPodcastEpisodeBundle() }

    private suspend fun newTab(): List<MediaItem> {
        val rows = CarSections.newTab(
            continuing = continuing(),
            newEpisodes = db.podcastEpisodes().getNew(limit = CarSections.MAX_NEW + CarSections.MAX_CONTINUE, offset = 0),
            id = { it.episode.id },
            continueTitle = items.string(R.string.car_section_continue),
            newTitle = items.string(R.string.route_new_episodes)
        )
        return episodeRows(rows, mixed = true)
    }

    private suspend fun podcasts(): List<MediaItem> =
        db.podcastSubscriptions().getByNewEpisodes(limit = MAX_PODCASTS, offset = 0)
            .map { items.podcast(it.podcast) }

    private suspend fun downloads(): List<MediaItem> = episodeRows(
        db.podcastEpisodes().getDownloadedForCar(PodcastEpisodeDownloadState.DOWNLOADED.value, MAX_DOWNLOADS),
        mixed = true
    )

    private suspend fun podcastEpisodes(origin: String): List<MediaItem> {
        val podcast = db.podcasts().getSync(origin)
        return if (podcast != null && podcast.isSerial()) {
            val rows = CarSections.serial(
                episodes = db.podcastEpisodes().getForCarSerial(origin, MAX_PODCAST_EPISODES),
                season = { it.episode.season },
                seasonTitle = { items.string(R.string.car_section_season, it) },
                otherTitle = items.string(R.string.car_section_other)
            )
            episodeRows(rows, mixed = false)
        } else {
            episodeRows(db.podcastEpisodes().get(origin = origin, limit = MAX_PODCAST_EPISODES, offset = 0), mixed = false)
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val podcastsTitle = items.string(R.string.car_tab_podcasts)
        val podcasts = db.podcastSubscriptions().getByNewEpisodes(limit = 500, offset = 0)
            .map { it.podcast }
            .filter { it.fetchTitle().contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true) }
            .take(MAX_SEARCH_PODCASTS)
            .map { items.podcast(it, groupTitle = podcastsTitle) }
        val episodesTitle = items.string(R.string.car_section_episodes)
        val episodes = episodeRows(
            db.podcastEpisodes().searchForCar(q, MAX_SEARCH_EPISODES).map { CarSections.Row(it, episodesTitle) },
            mixed = true
        )
        return podcasts + episodes
    }

    /**
     * The episode a voice request means, or null when nothing matches.
     * A podcast's name wins over an episode title.
     */
    suspend fun resolveVoice(query: String): PodcastEpisodeBundle? {
        val subs = db.podcastSubscriptions().getByNewEpisodes(limit = 500, offset = 0).map { it.podcast }
        val origin = CarVoice.matchPodcast(query, subs.map { it.origin to it.fetchTitle() })
        if (origin != null) {
            val podcast = subs.first { it.origin == origin }
            val episodes = db.podcastEpisodes().get(origin = origin, limit = MAX_PODCAST_EPISODES, offset = 0)
            val serialOrder = if (podcast.isSerial()) {
                db.podcastEpisodes().getForCarSerial(origin, MAX_PODCAST_EPISODES).map { it.episode.id }
            } else emptyList()
            val id = CarVoice.pickEpisode(
                episodes.map {
                    CarVoice.Candidate(
                        id = it.episode.id,
                        pubDate = it.episode.pubDate,
                        positionSec = it.playState?.state ?: 0,
                        played = it.playState?.played == true,
                        lastUpdate = it.playState?.lastUpdate ?: 0L
                    )
                },
                serialOrder
            )
            if (id != null) return episodes.first { it.episode.id == id }
        }
        val q = query.trim()
        if (q.length < 2) return null
        return db.podcastEpisodes().searchForCar(q, 1).firstOrNull()
    }

    private suspend fun episodeRows(list: List<PodcastEpisodeBundle>, mixed: Boolean): List<MediaItem> =
        episodeRows(list.map { CarSections.Row(it, null) }, mixed)

    @JvmName("episodeRowsGrouped")
    private suspend fun episodeRows(rows: List<CarSections.Row<PodcastEpisodeBundle>>, mixed: Boolean): List<MediaItem> {
        if (rows.isEmpty()) return emptyList()
        val queued = db.playbackQueue().getQueueSync().mapTo(HashSet()) { it.episodeId }
        val current = currentEpisodeId()
        // The podcast's display name (renames, and no stale per-episode copies).
        val names: Map<String, String> = if (mixed) {
            rows.map { it.item.episode.origin }.distinct()
                .associateWith { origin -> db.podcasts().getSync(origin)?.fetchTitle() ?: "" }
        } else emptyMap()
        return rows.map { row ->
            val b = row.item
            val id = b.episode.id
            items.episode(
                b,
                podcastName = if (mixed) names[b.episode.origin]?.takeIf { it.isNotBlank() } ?: b.episode.podcastTitle else null,
                actions = CarRowActions.forEpisode(
                    CarRowState(
                        inUpNext = id in queued,
                        isCurrent = id == current,
                        downloadState = b.download?.state,
                        played = b.playState?.played == true
                    )
                ),
                groupTitle = row.group
            )
        }
    }
}

private fun PodcastModel.isSerial() = itunesType.equals("serial", ignoreCase = true)
