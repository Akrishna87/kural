package io.github.akrishna87.podcasts.feed

import io.github.akrishna87.podcasts.data.Chapter
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Person
import io.github.akrishna87.podcasts.data.Podcast
import io.github.akrishna87.podcasts.data.TranscriptRef
import org.w3c.dom.Element
import java.io.IOException

class ParsedFeed(val podcast: Podcast, val episodes: List<Episode>)

/**
 * Reads podcast feeds: RSS 2.0 with the iTunes tags, the Podcasting 2.0 namespace (chapters,
 * transcripts, people, funding) and Podlove Simple Chapters, plus Atom feeds.
 */
object FeedParser {
    /** Keep this many of a show's newest episodes. */
    const val MAX_EPISODES = 500
    /** Show notes longer than this are cut (some feeds paste whole transcripts in). */
    private const val MAX_NOTES = 12_000

    private val NS = mapOf(
        "itunes" to setOf("http://www.itunes.com/dtds/podcast-1.0.dtd", "http://www.itunes.com/DTDs/Podcast-1.0.dtd"),
        "podcast" to setOf(
            "https://podcastindex.org/namespace/1.0",
            "http://podcastindex.org/namespace/1.0",
            "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
        ),
        "psc" to setOf("http://podlove.org/simple-chapters", "http://podlove.org/simple-chapters/"),
        "content" to setOf("http://purl.org/rss/1.0/modules/content/"),
        "media" to setOf("http://search.yahoo.com/mrss/", "http://search.yahoo.com/mrss"),
        "googleplay" to setOf("http://www.google.com/schemas/play-podcasts/1.0"),
        "dc" to setOf("http://purl.org/dc/elements/1.1/"),
        "atom" to setOf("http://www.w3.org/2005/Atom"),
    )

    /** Namespaced children, matched by namespace or, for feeds that get the namespace wrong, by prefix. */
    private fun Element.ns(prefix: String, local: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k !is Element) continue
            val name = k.localName ?: k.nodeName.substringAfter(':')
            if (name != local) continue
            val uri = k.namespaceURI
            if ((uri != null && uri in NS.getValue(prefix)) || k.prefix == prefix || k.nodeName == "$prefix:$local") out += k
        }
        return out
    }

    private fun Element.ns1(prefix: String, local: String): Element? = ns(prefix, local).firstOrNull()

    private fun Element.plain(local: String): Element? = plainChildren(local).firstOrNull()

    fun parse(xml: String, feedUrl: String): ParsedFeed {
        val doc = try {
            parseXml(xml)
        } catch (e: Exception) {
            throw IOException("This doesn't look like a podcast feed", e)
        }
        val root = doc.documentElement ?: throw IOException("The feed is empty")
        return when (root.localName ?: root.nodeName) {
            "rss" -> parseRss(root.plain("channel") ?: root.child("channel") ?: throw IOException("The feed has no channel"), feedUrl)
            "feed" -> parseAtom(root, feedUrl)
            "RDF" -> parseRss(root, feedUrl) // RSS 1.0: items sit next to the channel
            else -> throw IOException("This doesn't look like a podcast feed")
        }
    }

    private fun parseRss(channel: Element, feedUrl: String): ParsedFeed {
        val title = channel.plain("title")?.text ?: channel.ns1("itunes", "title")?.text ?: ""
        val artwork = channel.ns1("itunes", "image")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: channel.plain("image")?.plain("url")?.text?.takeIf { it.isNotBlank() }
            ?: channel.ns1("media", "thumbnail")?.attr("url")?.takeIf { it.isNotBlank() }
            ?: channel.ns1("googleplay", "image")?.attr("href")?.takeIf { it.isNotBlank() }
        val categories = channel.descendants("category")
            .filter { it.namespaceURI in NS.getValue("itunes") || it.prefix == "itunes" }
            .map { it.attr("text") }
            .filter { it.isNotBlank() }
            .distinct()
        val funding = channel.ns1("podcast", "funding")
        val podcast = Podcast(
            feedUrl = feedUrl,
            title = title.ifBlank { feedUrl },
            author = channel.ns1("itunes", "author")?.text?.takeIf { it.isNotBlank() }
                ?: channel.ns1("googleplay", "author")?.text?.takeIf { it.isNotBlank() }
                ?: channel.plain("managingEditor")?.text?.replace(Regex("^\\S+@\\S+\\s*\\((.*)\\)$"), "$1").orEmpty(),
            description = longest(
                channel.plain("description")?.rawText,
                channel.ns1("itunes", "summary")?.rawText,
                channel.ns1("content", "encoded")?.rawText,
            ),
            artworkUrl = artwork?.let { resolveUrl(feedUrl, it) },
            link = channel.plain("link")?.text?.takeIf { it.startsWith("http") },
            categories = categories,
            serial = channel.ns1("itunes", "type")?.text.equals("serial", ignoreCase = true),
            fundingUrl = funding?.attr("url")?.takeIf { it.startsWith("http") },
            fundingLabel = funding?.text?.takeIf { it.isNotBlank() },
        )
        val showPeople = channel.ns("podcast", "person").mapNotNull(::person)
        val items = channel.plainChildren("item").ifEmpty { channel.ownerDocument.documentElement.plainChildren("item") }
        val episodes = items.mapNotNull { parseItem(it, podcast, showPeople) }
        return ParsedFeed(podcast, newestFirst(episodes))
    }

    private fun parseItem(item: Element, podcast: Podcast, showPeople: List<Person>): Episode? {
        val feedUrl = podcast.feedUrl
        val enclosure = item.plain("enclosure")
        val media = item.ns("media", "content").firstOrNull { it.attr("type").startsWith("audio") || it.attr("medium") == "audio" }
            ?: item.ns("media", "group").flatMap { it.ns("media", "content") }.firstOrNull { it.attr("type").startsWith("audio") }
        val rawUrl = enclosure?.attr("url")?.takeIf { it.isNotBlank() } ?: media?.attr("url")?.takeIf { it.isNotBlank() } ?: return null
        val audioUrl = resolveUrl(feedUrl, rawUrl)
        val type = (enclosure?.attr("type") ?: media?.attr("type")).orEmpty().ifBlank { guessType(audioUrl) }
        if (!(type.startsWith("audio") || type.startsWith("video") || type.isBlank())) return null

        val title = item.plain("title")?.text?.takeIf { it.isNotBlank() } ?: item.ns1("itunes", "title")?.text.orEmpty()
        val guid = item.plain("guid")?.text?.takeIf { it.isNotBlank() } ?: audioUrl
        val notes = longest(
            item.ns1("content", "encoded")?.rawText,
            item.plain("description")?.rawText,
            item.ns1("itunes", "summary")?.rawText,
        )
        val published = parseDate(item.plain("pubDate")?.text ?: item.ns1("dc", "date")?.text)
        val itunesImage = item.ns1("itunes", "image")?.attr("href")?.takeIf { it.isNotBlank() }
        val chapterFile = item.ns("podcast", "chapters").firstOrNull()?.attr("url")?.takeIf { it.isNotBlank() }
        val inline = item.ns1("psc", "chapters")?.ns("psc", "chapter")?.mapNotNull { c ->
            val start = parseClock(c.attr("start"))
            if (start < 0) null
            else Chapter(
                start,
                decodeEntities(c.attr("title")).trim(),
                c.attr("href").takeIf { it.startsWith("http") },
                c.attr("image").takeIf { it.startsWith("http") },
            )
        }.orEmpty().sortedBy { it.startMs }
        val transcripts = item.ns("podcast", "transcript").mapNotNull { t ->
            val url = t.attr("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TranscriptRef(resolveUrl(feedUrl, url), t.attr("type").lowercase(), t.attr("language").takeIf { it.isNotBlank() })
        }
        val people = item.ns("podcast", "person").mapNotNull(::person).ifEmpty { showPeople }
        val explicit = item.ns1("itunes", "explicit")?.text?.lowercase()
        return Episode(
            id = Episode.idFor(feedUrl, guid),
            podcastId = feedUrl,
            guid = guid,
            title = title.ifBlank { "Untitled episode" },
            description = notes.take(MAX_NOTES),
            audioUrl = audioUrl,
            mimeType = type.ifBlank { "audio/mpeg" },
            sizeBytes = enclosure?.attr("length")?.trim()?.toLongOrNull()?.takeIf { it > 1_000 } ?: 0,
            durationSec = parseDurationSec(item.ns1("itunes", "duration")?.text).takeIf { it > 0 }
                ?: media?.attr("duration")?.toLongOrNull() ?: 0,
            publishedAt = published,
            link = item.plain("link")?.text?.takeIf { it.startsWith("http") },
            artworkUrl = itunesImage?.let { resolveUrl(feedUrl, it) },
            season = item.ns1("itunes", "season")?.text?.toIntOrNull(),
            number = item.ns1("itunes", "episode")?.text?.toIntOrNull(),
            type = item.ns1("itunes", "episodeType")?.text?.lowercase()?.takeIf { it in setOf("full", "trailer", "bonus") } ?: "full",
            explicit = explicit == "yes" || explicit == "true" || explicit == "explicit",
            chaptersUrl = chapterFile?.let { resolveUrl(feedUrl, it) },
            chapters = inline,
            transcripts = transcripts,
            persons = people,
        )
    }

    private fun person(e: Element): Person? {
        val name = e.text.takeIf { it.isNotBlank() } ?: return null
        return Person(
            name = name,
            role = e.attr("role").lowercase().ifBlank { "host" },
            imageUrl = e.attr("img").takeIf { it.startsWith("http") },
            link = e.attr("href").takeIf { it.startsWith("http") },
        )
    }

    private fun parseAtom(feed: Element, feedUrl: String): ParsedFeed {
        val podcast = Podcast(
            feedUrl = feedUrl,
            title = feed.child("title")?.text.orEmpty().ifBlank { feedUrl },
            author = feed.child("author")?.child("name")?.text.orEmpty(),
            description = feed.child("subtitle")?.rawText ?: feed.ns1("itunes", "summary")?.rawText.orEmpty(),
            artworkUrl = (feed.ns1("itunes", "image")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: feed.child("logo")?.text?.takeIf { it.isNotBlank() }
                ?: feed.child("icon")?.text?.takeIf { it.isNotBlank() })?.let { resolveUrl(feedUrl, it) },
            link = feed.children("link").firstOrNull { it.attr("rel").let { r -> r.isEmpty() || r == "alternate" } }?.attr("href"),
        )
        val episodes = feed.children("entry").mapNotNull { entry ->
            val enclosure = entry.children("link").firstOrNull { it.attr("rel") == "enclosure" } ?: return@mapNotNull null
            val url = resolveUrl(feedUrl, enclosure.attr("href"))
            val guid = entry.child("id")?.text?.takeIf { it.isNotBlank() } ?: url
            Episode(
                id = Episode.idFor(feedUrl, guid),
                podcastId = feedUrl,
                guid = guid,
                title = entry.child("title")?.text.orEmpty().ifBlank { "Untitled episode" },
                description = longest(entry.child("content")?.rawText, entry.child("summary")?.rawText).take(MAX_NOTES),
                audioUrl = url,
                mimeType = enclosure.attr("type").ifBlank { guessType(url) },
                sizeBytes = enclosure.attr("length").toLongOrNull() ?: 0,
                durationSec = parseDurationSec(entry.ns1("itunes", "duration")?.text),
                publishedAt = parseDate(entry.child("published")?.text ?: entry.child("updated")?.text),
                link = entry.children("link").firstOrNull { it.attr("rel").let { r -> r.isEmpty() || r == "alternate" } }?.attr("href"),
            )
        }
        return ParsedFeed(podcast, newestFirst(episodes))
    }

    private fun newestFirst(list: List<Episode>): List<Episode> =
        list.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(MAX_EPISODES)

    private fun longest(vararg options: String?): String = options.filterNotNull().maxByOrNull { it.length }.orEmpty()

    private fun guessType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m4a") || path.endsWith(".aac") -> "audio/mp4"
            path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".mov") -> "video/quicktime"
            else -> "audio/mpeg"
        }
    }
}
