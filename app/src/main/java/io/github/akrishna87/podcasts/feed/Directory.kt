package io.github.akrishna87.podcasts.feed

import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Podcast
import io.github.akrishna87.podcasts.data.objects
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** A show found in the directory. Top charts don't include the feed, so it's looked up when opened. */
data class DirectoryPodcast(
    val appleId: Long,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val feedUrl: String?,
    val genre: String = "",
    val episodeCount: Int = 0,
)

/** An episode found by searching every podcast, with enough of its show to play and follow it. */
data class DirectoryEpisode(val episode: Episode, val podcast: Podcast, val appleId: Long)

/** Podcast categories in Apple's directory, with their genre ids and the subcategories under them. */
data class Category(val id: Int, val name: String, val subcategories: List<String> = emptyList())

val CATEGORIES = listOf(
    Category(1489, "News", listOf("Business News", "Daily News", "Entertainment News", "News Commentary", "Politics", "Sports News", "Tech News")),
    Category(1303, "Comedy", listOf("Comedy Interviews", "Improv", "Stand-Up")),
    Category(1488, "True Crime"),
    Category(1324, "Society & Culture", listOf("Documentary", "Personal Journals", "Philosophy", "Places & Travel", "Relationships")),
    Category(1321, "Business", listOf("Careers", "Entrepreneurship", "Investing", "Management", "Marketing", "Non-Profit")),
    Category(1318, "Technology"),
    Category(1533, "Science", listOf("Astronomy", "Chemistry", "Earth Sciences", "Life Sciences", "Mathematics", "Natural Sciences", "Nature", "Physics", "Social Sciences")),
    Category(1487, "History"),
    Category(1512, "Health & Fitness", listOf("Alternative Health", "Fitness", "Medicine", "Mental Health", "Nutrition", "Sexuality")),
    Category(1545, "Sports", listOf("Baseball", "Basketball", "Cricket", "Fantasy Sports", "Football", "Golf", "Hockey", "Rugby", "Running", "Soccer", "Swimming", "Tennis", "Volleyball", "Wilderness", "Wrestling")),
    Category(1309, "TV & Film", listOf("After Shows", "Film History", "Film Interviews", "Film Reviews", "TV Reviews")),
    Category(1304, "Education", listOf("Courses", "How To", "Language Learning", "Self-Improvement")),
    Category(1301, "Arts", listOf("Books", "Design", "Fashion & Beauty", "Food", "Performing Arts", "Visual Arts")),
    Category(1310, "Music", listOf("Music Commentary", "Music History", "Music Interviews")),
    Category(1305, "Kids & Family", listOf("Education for Kids", "Parenting", "Pets & Animals", "Stories for Kids")),
    Category(1483, "Fiction", listOf("Comedy Fiction", "Drama", "Science Fiction")),
    Category(1502, "Leisure", listOf("Animation & Manga", "Automotive", "Aviation", "Crafts", "Games", "Hobbies", "Home & Garden", "Video Games")),
    Category(1314, "Religion & Spirituality", listOf("Buddhism", "Christianity", "Hinduism", "Islam", "Judaism", "Religion", "Spirituality")),
    Category(1511, "Government"),
)

private fun normaliseCategory(s: String) = s.lowercase().replace("&amp;", "&").replace(" and ", " & ").replace(Regex("\\s+"), " ").trim()

/** The directory category a feed's category (or subcategory, like "Careers") belongs to. */
fun categoryNamed(name: String): Category? {
    val n = normaliseCategory(name)
    if (n.isEmpty()) return null
    return CATEGORIES.firstOrNull { normaliseCategory(it.name) == n }
        ?: CATEGORIES.firstOrNull { c -> c.subcategories.any { normaliseCategory(it) == n } }
}

/**
 * Apple's public podcast directory: the same search AntennaPod and most podcast apps use. No
 * account or key is needed, and only the words you search for are sent.
 */
object Directory {
    private const val BASE = "https://itunes.apple.com"

    fun country(): String = Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"

    fun searchPodcasts(term: String, limit: Int = 40): List<DirectoryPodcast> =
        parsePodcastSearch(Http.get("$BASE/search?media=podcast&entity=podcast&limit=$limit&country=${country()}&term=${urlEncode(term)}"))

    /** Episodes from any podcast that mention [term]: guests, topics, names. */
    fun searchEpisodes(term: String, limit: Int = 50): List<DirectoryEpisode> =
        parseEpisodeSearch(Http.get("$BASE/search?media=podcast&entity=podcastEpisode&limit=$limit&country=${country()}&term=${urlEncode(term)}"))

    /** The top shows in [country], overall or in one category. Falls back to the US chart. */
    fun top(genreId: Int? = null, limit: Int = 50): List<DirectoryPodcast> {
        val genre = if (genreId != null) "/genre=$genreId" else ""
        val c = country()
        return try {
            parseTopChart(Http.get("$BASE/$c/rss/toppodcasts/limit=$limit$genre/explicit=true/json"))
        } catch (e: IOException) {
            if (c == "us") throw e
            parseTopChart(Http.get("$BASE/us/rss/toppodcasts/limit=$limit$genre/explicit=true/json"))
        }.ifEmpty {
            if (c == "us") emptyList() else parseTopChart(Http.get("$BASE/us/rss/toppodcasts/limit=$limit$genre/explicit=true/json"))
        }
    }

    /** The feed address for a show in the directory. */
    fun feedUrlFor(appleId: Long): String {
        val r = JSONObject(Http.get("$BASE/lookup?id=$appleId&entity=podcast")).optJSONArray("results").objects()
        return r.firstNotNullOfOrNull { it.optString("feedUrl").takeIf { u -> u.startsWith("http") } }
            ?: throw IOException("This show has no public feed")
    }

    private val APPLE_ID = Regex("podcasts\\.apple\\.com/.*?/?id(\\d+)|itunes\\.apple\\.com/.*?/?id(\\d+)")

    /** The Apple id in a podcasts.apple.com link, if it is one. */
    fun appleIdIn(link: String): Long? = APPLE_ID.find(link)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toLongOrNull() }

    // ----- Parsing (separate so it can be tested without the network) -----

    fun parsePodcastSearch(json: String): List<DirectoryPodcast> =
        JSONObject(json).optJSONArray("results").objects().mapNotNull { o ->
            val feed = o.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            DirectoryPodcast(
                appleId = o.optLong("collectionId"),
                title = o.optString("collectionName").ifBlank { o.optString("trackName") },
                author = o.optString("artistName"),
                artworkUrl = bestArtwork(o),
                feedUrl = feed,
                genre = o.optString("primaryGenreName"),
                episodeCount = o.optInt("trackCount"),
            )
        }

    fun parseEpisodeSearch(json: String): List<DirectoryEpisode> =
        JSONObject(json).optJSONArray("results").objects().mapNotNull { o ->
            if (o.optString("wrapperType") != "podcastEpisode" && o.optString("kind") != "podcast-episode") return@mapNotNull null
            val feed = o.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val audio = o.optString("episodeUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val guid = o.optString("episodeGuid").ifBlank { audio }
            val art = bestArtwork(o)
            val podcast = Podcast(feedUrl = feed, title = o.optString("collectionName"), artworkUrl = art)
            val ext = o.optString("episodeFileExtension").lowercase()
            val episode = Episode(
                id = Episode.idFor(feed, guid),
                podcastId = feed,
                guid = guid,
                title = o.optString("trackName"),
                description = o.optString("description").ifBlank { o.optString("shortDescription") },
                audioUrl = audio,
                mimeType = when {
                    o.optString("episodeContentType") == "video" -> "video/mp4"
                    ext == "m4a" -> "audio/mp4"
                    else -> "audio/mpeg"
                },
                durationSec = o.optLong("trackTimeMillis") / 1000,
                publishedAt = parseDate(o.optString("releaseDate")),
                link = o.optString("trackViewUrl").takeIf { it.startsWith("http") },
                artworkUrl = art,
            )
            DirectoryEpisode(episode, podcast, o.optLong("collectionId"))
        }

    fun parseTopChart(json: String): List<DirectoryPodcast> {
        val entries = JSONObject(json).optJSONObject("feed")?.let { f ->
            // A chart with one entry comes back as an object rather than a list.
            f.optJSONArray("entry")?.objects() ?: f.optJSONObject("entry")?.let { listOf(it) }
        }.orEmpty()
        return entries.mapNotNull { e ->
            val id = e.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.toLongOrNull() ?: return@mapNotNull null
            val images = e.optJSONArray("im:image").objects()
            val art = images.maxByOrNull { it.optJSONObject("attributes")?.optString("height")?.toIntOrNull() ?: 0 }?.optString("label")
            DirectoryPodcast(
                appleId = id,
                title = e.optJSONObject("im:name")?.optString("label").orEmpty(),
                author = e.optJSONObject("im:artist")?.optString("label").orEmpty(),
                artworkUrl = art?.let(::biggerArtwork),
                feedUrl = null,
                genre = e.optJSONObject("category")?.optJSONObject("attributes")?.optString("label").orEmpty(),
            )
        }
    }

    private fun bestArtwork(o: JSONObject): String? =
        listOf("artworkUrl600", "artworkUrl160", "artworkUrl100", "artworkUrl60").firstNotNullOfOrNull { k ->
            o.optString(k).takeIf { it.startsWith("http") }
        }

    /** Chart artwork comes small (170 px); Apple serves any size from the same address. */
    private fun biggerArtwork(url: String): String = url.replace(Regex("/\\d+x\\d+(bb)?\\.(png|jpg|jpeg)$"), "/600x600bb.jpg")
}
