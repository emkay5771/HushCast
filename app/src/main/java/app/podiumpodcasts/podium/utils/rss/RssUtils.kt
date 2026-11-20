package app.podiumpodcasts.podium.utils.rss

import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.api.rss.PodcastMetadataJson
import app.podiumpodcasts.podium.api.rss.RssPodcastExtras
import app.podiumpodcasts.podium.ui.parseItunesDuration
import app.podiumpodcasts.podium.ui.parsePubDate
import app.podiumpodcasts.podium.utils.sha256
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem

fun RssChannel.toPodcast(
    origin: String,
    fileSize: Long,
    oldPodcast: PodcastModel?,
    languageCode: String = "",
    extras: RssPodcastExtras = RssPodcastExtras()
): PodcastModel {
    val newFeed = itunesChannelData?.newsFeedUrl?.trim()
        ?.takeIf { (it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)) && it != origin }
    val explicitFlag = extras.explicit ||
        (itunesChannelData?.explicit?.trim()?.lowercase() in setOf("true", "yes", "explicit"))
    val categories = itunesChannelData?.categories
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        .orEmpty()

    return PodcastModel(
        origin = origin,
        link = link ?: "",
        title = title ?: "",
        description = description ?: "",
        author = itunesChannelData?.author ?: "",
        imageUrl = image?.url ?: "",
        imageSeedColor = oldPodcast?.imageSeedColor ?: 0,
        languageCode = languageCode.ifBlank { extras.language.ifBlank { oldPodcast?.languageCode ?: "" } },
        fileSize = fileSize,

        overrideTitle = oldPodcast?.overrideTitle ?: "",
        skipBeginning = oldPodcast?.skipBeginning ?: 0,
        skipEnding = oldPodcast?.skipEnding ?: 0,
        feedUrl = newFeed ?: oldPodcast?.feedUrl ?: "",
        itunesType = itunesChannelData?.type?.trim()?.lowercase().orEmpty(),
        categoriesJson = PodcastMetadataJson.encodeStrings(categories),
        explicit = explicitFlag,
        fundingJson = PodcastMetadataJson.encodeFunding(extras.funding),
        personsJson = PodcastMetadataJson.encodePersons(extras.persons),
        trailerUrl = extras.trailer?.url.orEmpty(),
        trailerTitle = extras.trailer?.title.orEmpty()
    )
}

fun RssItem.computeEpisodeId(origin: String): String {
    val stableKey = guid?.takeIf { it.isNotBlank() }
        ?: audio?.takeIf { it.isNotBlank() }
        ?: video?.takeIf { it.isNotBlank() }
        ?: link?.takeIf { it.isNotBlank() }
        ?: "${title.orEmpty()}|${pubDate.orEmpty()}".sha256()
    return "$origin:$stableKey"
}

fun RssItem.toPodcastEpisode(
    podcast: PodcastModel,
    new: Boolean = false,
    extras: RssPodcastExtras? = null
): PodcastEpisodeModel {
    val epExtras = extras?.lookup(this)
    val explicitFlag = (epExtras?.explicit == true) ||
        (itunesItemData?.explicit?.trim()?.lowercase() in setOf("true", "yes", "explicit"))
    return PodcastEpisodeModel(
        id = computeEpisodeId(podcast.origin),
        guid = guid ?: "",
        origin = podcast.origin,
        link = link ?: "",
        title = title ?: "",
        description = description ?: "",
        imageUrl = (itunesItemData?.image ?: image ?: "").ifBlank { podcast.imageUrl },
        author = author ?: "",
        pubDate = parsePubDate(pubDate ?: ""),
        duration = itunesItemData?.duration?.let {
            parseItunesDuration(it)
        } ?: -1,
        audioUrl = audio ?: video ?: "",
        podcastTitle = podcast.title,
        imageSeedColor = podcast.imageSeedColor,
        new = new,
        transcriptUrl = epExtras?.transcriptUrl ?: "",
        transcriptType = epExtras?.transcriptType ?: "",
        chaptersUrl = epExtras?.chaptersUrl ?: "",
        chaptersType = epExtras?.chaptersType ?: "",
        transcriptAlternatesJson = epExtras?.transcriptAlternatesJson ?: "",
        season = itunesItemData?.season?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: epExtras?.season,
        episodeNumber = itunesItemData?.episode?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: epExtras?.episode,
        episodeType = itunesItemData?.episodeType?.trim()?.lowercase().orEmpty(),
        explicit = explicitFlag,
        commentsUrl = commentsUrl?.trim().orEmpty().ifBlank { epExtras?.socialInteractUrl.orEmpty() },
        personsJson = PodcastMetadataJson.encodePersons(epExtras?.persons.orEmpty()),
        soundbitesJson = PodcastMetadataJson.encodeSoundbites(epExtras?.soundbites.orEmpty())
    )
}