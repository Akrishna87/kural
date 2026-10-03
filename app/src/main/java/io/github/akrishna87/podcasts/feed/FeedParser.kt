package io.github.akrishna87.podcasts.feed

import io.github.akrishna87.podcasts.data.Chapter
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Person
import io.github.akrishna87.podcasts.data.Podcast
import io.github.akrishna87.podcasts.data.TranscriptRef
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

class ParsedFeed(val podcast: Podcast, val episodes: List<Episode>)

/**
 * Reads podcast feeds: RSS 2.0 with the iTunes tags, the Podcasting 2.0 namespace (chapters,
 * transcripts, people, funding) and Podlove Simple Chapters, plus Atom feeds.
 *
 * Feeds are read as they stream in, keeping only what's needed, so shows with thousands of
 * episodes and tens of megabytes of show notes load without running the phone out of memory.
 */
object FeedParser {
    /** Keep this many of a show's newest episodes. */
    const val MAX_EPISODES = 500
    /** Show notes longer than this are cut (some feeds paste whole transcripts in). */
    private const val MAX_NOTES = 12_000
    /** Text kept while reading one element; notes are cut to [MAX_NOTES] afterwards. */
    private const val MAX_TEXT = 40_000

    private val NS: Map<String, String> = buildMap {
        listOf("http://www.itunes.com/dtds/podcast-1.0.dtd", "http://www.itunes.com/DTDs/Podcast-1.0.dtd").forEach { put(it, "itunes") }
        listOf(
            "https://podcastindex.org/namespace/1.0",
            "http://podcastindex.org/namespace/1.0",
            "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
        ).forEach { put(it, "podcast") }
        listOf("http://podlove.org/simple-chapters", "http://podlove.org/simple-chapters/").forEach { put(it, "psc") }
        put("http://purl.org/rss/1.0/modules/content/", "content")
        listOf("http://search.yahoo.com/mrss/", "http://search.yahoo.com/mrss").forEach { put(it, "media") }
        put("http://www.google.com/schemas/play-podcasts/1.0", "googleplay")
        put("http://purl.org/dc/elements/1.1/", "dc")
        put("http://www.w3.org/2005/Atom", "atom")
        // RSS 1.0 (RDF) elements count as plain RSS.
        put("http://purl.org/rss/1.0/", "")
    }

    /** Elements whose content is HTML: nested tags are kept as text. */
    private val RICH = setOf("description", "content:encoded", "itunes:summary", "summary", "content", "subtitle")

    fun parse(xml: String, feedUrl: String): ParsedFeed = parse(StringReader(xml), feedUrl)

    fun parse(reader: Reader, feedUrl: String): ParsedFeed {
        val handler = Handler(feedUrl)
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            listOf(
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            ).forEach { runCatching { factory.setFeature(it, false) } }
            factory.newSAXParser().parse(InputSource(CleaningReader(reader)), handler)
        } catch (e: NotAFeed) {
            throw IOException("This doesn't look like a podcast feed")
        } catch (e: SAXException) {
            // A feed that breaks off part-way still gives us everything before the break.
            if (handler.title.isEmpty() || handler.episodes.isEmpty()) throw IOException("This doesn't look like a podcast feed", e)
        }
        if (handler.mode == Mode.UNKNOWN) throw IOException("This doesn't look like a podcast feed")
        return handler.result()
    }

    private class NotAFeed : SAXException("not a feed")

    private enum class Mode { UNKNOWN, RSS, ATOM }

    /** One episode as it's being read. */
    private class ItemBuilder {
        var title = ""
        var itunesTitle = ""
        var guid = ""
        val notes = ArrayList<String>()
        var enclosureUrl = ""
        var enclosureType = ""
        var enclosureLength = ""
        var mediaUrl = ""
        var mediaType = ""
        var mediaDuration = ""
        var pubDate = ""
        var duration = ""
        var image = ""
        var chaptersUrl = ""
        val chapters = ArrayList<Chapter>()
        val transcripts = ArrayList<TranscriptRef>()
        val persons = ArrayList<Person>()
        var link = ""
        var season = ""
        var number = ""
        var type = ""
        var explicit = ""
        var atomId = ""
    }

    private class Handler(val feedUrl: String) : DefaultHandler() {
        var mode = Mode.UNKNOWN
        private val stack = ArrayList<String>()
        private val text = StringBuilder()
        /** Depth of the rich (HTML) element being read, or -1. */
        private var richDepth = -1

        // The show.
        var title = ""
        private var itunesTitle = ""
        private var author = ""
        private var googleAuthor = ""
        private var editor = ""
        private val showNotes = ArrayList<String>()
        private var image = ""
        private var imageUrl = ""
        private var thumbnail = ""
        private var googleImage = ""
        private var link = ""
        private val categories = LinkedHashSet<String>()
        private var type = ""
        private var fundingUrl = ""
        private var fundingLabel = ""
        private val showPeople = ArrayList<Person>()
        private var personAttrs: Triple<String, String, String>? = null

        // Episodes.
        val episodes = ArrayList<Episode>()
        private var item: ItemBuilder? = null

        private fun nameOf(uri: String?, local: String?, qName: String): String {
            val localName = local?.takeIf { it.isNotEmpty() } ?: qName.substringAfter(':')
            val prefix = when {
                uri.isNullOrEmpty() -> if (':' in qName) qName.substringBefore(':') else ""
                mode == Mode.ATOM && uri == "http://www.w3.org/2005/Atom" -> ""
                else -> NS[uri] ?: if (':' in qName) qName.substringBefore(':') else "?"
            }
            return if (prefix.isEmpty()) localName else "$prefix:$localName"
        }

        private fun parent(): String = stack.getOrElse(stack.size - 2) { "" }

        private fun grandparent(): String = stack.getOrElse(stack.size - 3) { "" }

        private fun attr(a: Attributes, local: String): String {
            for (i in 0 until a.length) {
                val ln = a.getLocalName(i)?.takeIf { it.isNotEmpty() } ?: a.getQName(i)
                if (ln == local || ln.endsWith(":$local")) return a.getValue(i).orEmpty().trim()
            }
            return ""
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (mode == Mode.UNKNOWN) {
                val root = localName?.takeIf { it.isNotEmpty() } ?: qName.substringAfter(':')
                mode = when (root) {
                    "rss", "RDF" -> Mode.RSS
                    "feed" -> Mode.ATOM
                    else -> throw NotAFeed()
                }
            }
            val name = nameOf(uri, localName, qName)
            stack += name
            if (richDepth >= 0) {
                // HTML written straight into the feed: keep the tags as text.
                if (text.length < MAX_TEXT) {
                    text.append('<').append(localName ?: qName)
                    for (i in 0 until attributes.length) {
                        text.append(' ').append(attributes.getQName(i)).append("=\"").append(attributes.getValue(i).replace("\"", "&quot;")).append('"')
                    }
                    text.append('>')
                }
                return
            }
            text.setLength(0)
            if (name in RICH) richDepth = stack.size
            if (mode == Mode.ATOM) atomStart(name, attributes) else rssStart(name, attributes)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val room = MAX_TEXT - text.length
            if (room > 0) text.appendRange(ch, start, start + minOf(length, room))
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (richDepth >= 0 && stack.size > richDepth) {
                if (text.length < MAX_TEXT) text.append("</").append(localName ?: qName).append('>')
                stack.removeAt(stack.lastIndex)
                return
            }
            richDepth = -1
            val name = stack.last()
            val raw = text.toString().trim()
            text.setLength(0)
            if (mode == Mode.ATOM) atomEnd(name, raw) else rssEnd(name, raw)
            stack.removeAt(stack.lastIndex)
        }

        private fun tidy(s: String) = s.replace(Regex("\\s+"), " ").trim()

        // ----- RSS -----

        private fun rssStart(name: String, a: Attributes) {
            val it = item
            if (name == "item") {
                item = ItemBuilder()
                return
            }
            if (it != null) {
                when (name) {
                    "enclosure" -> if (it.enclosureUrl.isEmpty()) {
                        it.enclosureUrl = attr(a, "url")
                        it.enclosureType = attr(a, "type")
                        it.enclosureLength = attr(a, "length")
                    }
                    "media:content" -> {
                        val t = attr(a, "type")
                        if (it.mediaUrl.isEmpty() && (t.startsWith("audio") || attr(a, "medium") == "audio")) {
                            it.mediaUrl = attr(a, "url")
                            it.mediaType = t
                            it.mediaDuration = attr(a, "duration")
                        }
                    }
                    "itunes:image" -> if (it.image.isEmpty()) it.image = attr(a, "href")
                    "podcast:chapters" -> if (it.chaptersUrl.isEmpty()) it.chaptersUrl = attr(a, "url")
                    "psc:chapter" -> {
                        val start = parseClock(attr(a, "start"))
                        if (start >= 0) {
                            it.chapters += Chapter(
                                start,
                                decodeEntities(attr(a, "title")).trim(),
                                attr(a, "href").takeIf { u -> u.startsWith("http") },
                                attr(a, "image").takeIf { u -> u.startsWith("http") },
                            )
                        }
                    }
                    "podcast:transcript" -> attr(a, "url").takeIf { u -> u.isNotEmpty() }?.let { url ->
                        it.transcripts += TranscriptRef(resolveUrl(feedUrl, url), attr(a, "type").lowercase(), attr(a, "language").takeIf { l -> l.isNotEmpty() })
                    }
                    "podcast:person" -> personAttrs = Triple(attr(a, "role"), attr(a, "img"), attr(a, "href"))
                }
                return
            }
            when (name) {
                "itunes:image" -> if (image.isEmpty()) image = attr(a, "href")
                "media:thumbnail" -> if (thumbnail.isEmpty()) thumbnail = attr(a, "url")
                "googleplay:image" -> if (googleImage.isEmpty()) googleImage = attr(a, "href")
                "itunes:category" -> attr(a, "text").takeIf { c -> c.isNotEmpty() }?.let { categories += decodeEntities(it) }
                "podcast:funding" -> if (fundingUrl.isEmpty()) fundingUrl = attr(a, "url")
                "podcast:person" -> personAttrs = Triple(attr(a, "role"), attr(a, "img"), attr(a, "href"))
            }
        }

        private fun rssEnd(name: String, raw: String) {
            val it = item
            if (it != null) {
                if (name == "item") {
                    build(it)?.let(::add)
                    item = null
                    return
                }
                val inItem = parent() == "item"
                when {
                    name == "title" && inItem -> it.title = tidy(raw)
                    name == "itunes:title" -> it.itunesTitle = tidy(raw)
                    name == "guid" -> it.guid = tidy(raw)
                    name == "description" || name == "content:encoded" || name == "itunes:summary" -> if (inItem) it.notes += raw
                    name == "pubDate" || name == "dc:date" -> if (it.pubDate.isEmpty()) it.pubDate = tidy(raw)
                    name == "itunes:duration" -> it.duration = tidy(raw)
                    name == "link" && inItem -> it.link = tidy(raw)
                    name == "itunes:season" -> it.season = tidy(raw)
                    name == "itunes:episode" -> it.number = tidy(raw)
                    name == "itunes:episodeType" -> it.type = tidy(raw).lowercase()
                    name == "itunes:explicit" -> it.explicit = tidy(raw).lowercase()
                    name == "podcast:person" -> person(raw)?.let { p -> it.persons += p }
                }
                return
            }
            val inChannel = parent() == "channel"
            when {
                name == "title" && inChannel -> title = tidy(raw)
                name == "itunes:title" && inChannel -> itunesTitle = tidy(raw)
                name == "link" && inChannel -> link = tidy(raw)
                (name == "description" || name == "itunes:summary" || name == "content:encoded") && inChannel -> showNotes += raw
                name == "itunes:author" -> author = tidy(raw)
                name == "googleplay:author" -> googleAuthor = tidy(raw)
                name == "managingEditor" -> editor = tidy(raw)
                name == "url" && parent() == "image" && grandparent() == "channel" -> imageUrl = tidy(raw)
                name == "itunes:type" -> type = tidy(raw)
                name == "podcast:funding" -> if (fundingLabel.isEmpty()) fundingLabel = tidy(raw)
                name == "podcast:person" -> person(raw)?.let { showPeople += it }
            }
        }

        private fun person(raw: String): Person? {
            val (role, img, href) = personAttrs ?: Triple("", "", "")
            personAttrs = null
            val name = tidy(raw).takeIf { it.isNotEmpty() } ?: return null
            return Person(name, role.lowercase().ifBlank { "host" }, img.takeIf { it.startsWith("http") }, href.takeIf { it.startsWith("http") })
        }

        private fun build(it: ItemBuilder): Episode? {
            val rawUrl = it.enclosureUrl.ifEmpty { it.mediaUrl }.ifEmpty { return null }
            val audioUrl = resolveUrl(feedUrl, rawUrl)
            val declared = it.enclosureType.ifEmpty { it.mediaType }
            val type = when {
                declared.startsWith("audio") || declared.startsWith("video") -> declared
                // No type, or a vague one ("application/octet-stream"): go by the file's name.
                declared.isBlank() || MEDIA_FILE.containsMatchIn(audioUrl.substringBefore('?')) -> guessType(audioUrl)
                else -> return null // a PDF or picture, not an episode
            }
            val guid = it.guid.ifEmpty { audioUrl }
            return Episode(
                id = Episode.idFor(feedUrl, guid),
                podcastId = feedUrl,
                guid = guid,
                title = it.title.ifEmpty { it.itunesTitle }.ifBlank { "Untitled episode" },
                description = longest(it.notes).take(MAX_NOTES),
                audioUrl = audioUrl,
                mimeType = type,
                sizeBytes = it.enclosureLength.toLongOrNull()?.takeIf { s -> s > 1_000 } ?: 0,
                durationSec = parseDurationSec(it.duration).takeIf { d -> d > 0 } ?: it.mediaDuration.toLongOrNull() ?: 0,
                publishedAt = parseDate(it.pubDate),
                link = it.link.takeIf { l -> l.startsWith("http") },
                artworkUrl = it.image.takeIf { i -> i.isNotBlank() }?.let { i -> resolveUrl(feedUrl, i) },
                season = it.season.toIntOrNull(),
                number = it.number.toIntOrNull(),
                type = it.type.takeIf { t -> t in setOf("full", "trailer", "bonus") } ?: "full",
                explicit = it.explicit == "yes" || it.explicit == "true" || it.explicit == "explicit",
                chaptersUrl = it.chaptersUrl.takeIf { c -> c.isNotBlank() }?.let { c -> resolveUrl(feedUrl, c) },
                chapters = it.chapters.sortedBy { c -> c.startMs },
                transcripts = it.transcripts.toList(),
                persons = it.persons.toList(),
            )
        }

        /** Keeps the list bounded: for shows with thousands of episodes, only the newest are kept. */
        private fun add(e: Episode) {
            episodes += e
            if (episodes.size >= MAX_EPISODES * 2) {
                val keep = episodes.sortedByDescending { it.publishedAt }.take(MAX_EPISODES)
                episodes.clear()
                episodes += keep
            }
        }

        // ----- Atom -----

        private fun atomStart(name: String, a: Attributes) {
            if (name == "entry") {
                item = ItemBuilder()
                return
            }
            val it = item
            if (name == "link") {
                val rel = attr(a, "rel")
                if (it != null) {
                    when (rel) {
                        "enclosure" -> if (it.enclosureUrl.isEmpty()) {
                            it.enclosureUrl = attr(a, "href")
                            it.enclosureType = attr(a, "type")
                            it.enclosureLength = attr(a, "length")
                        }
                        "", "alternate" -> if (it.link.isEmpty()) it.link = attr(a, "href")
                    }
                } else if ((rel.isEmpty() || rel == "alternate") && link.isEmpty() && parent() == "feed") {
                    link = attr(a, "href")
                }
            }
            if (name == "itunes:image") {
                if (it != null) it.image = attr(a, "href") else if (image.isEmpty()) image = attr(a, "href")
            }
        }

        private fun atomEnd(name: String, raw: String) {
            val it = item
            if (it != null) {
                when (name) {
                    "entry" -> {
                        if (it.guid.isEmpty()) it.guid = it.atomId
                        build(it)?.let(::add)
                        item = null
                    }
                    "id" -> it.atomId = tidy(raw)
                    "title" -> if (parent() == "entry") it.title = tidy(raw)
                    "content", "summary" -> it.notes += raw
                    "published" -> it.pubDate = tidy(raw)
                    "updated" -> if (it.pubDate.isEmpty()) it.pubDate = tidy(raw)
                    "itunes:duration" -> it.duration = tidy(raw)
                }
                return
            }
            when {
                name == "title" && parent() == "feed" -> title = tidy(raw)
                name == "name" && parent() == "author" && grandparent() == "feed" -> author = tidy(raw)
                name == "subtitle" || name == "itunes:summary" -> showNotes += raw
                name == "logo" || name == "icon" -> if (imageUrl.isEmpty()) imageUrl = tidy(raw)
            }
        }

        fun result(): ParsedFeed {
            val art = listOf(image, imageUrl, thumbnail, googleImage).firstOrNull { it.isNotBlank() }
            val podcast = Podcast(
                feedUrl = feedUrl,
                title = title.ifEmpty { itunesTitle }.ifBlank { feedUrl },
                author = author.ifEmpty { googleAuthor }.ifEmpty { editor.replace(Regex("^\\S+@\\S+\\s*\\((.*)\\)$"), "$1") },
                description = longest(showNotes),
                artworkUrl = art?.let { resolveUrl(feedUrl, it) },
                link = link.takeIf { it.startsWith("http") },
                categories = categories.toList(),
                serial = type.equals("serial", ignoreCase = true),
                fundingUrl = fundingUrl.takeIf { it.startsWith("http") },
                fundingLabel = fundingLabel.takeIf { it.isNotBlank() },
            )
            val list = episodes.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(MAX_EPISODES)
                .map { e -> if (e.persons.isEmpty() && showPeople.isNotEmpty()) e.copy(persons = showPeople.toList()) else e }
            return ParsedFeed(podcast, list)
        }
    }

    private val MEDIA_FILE = Regex("\\.(mp3|m4a|aac|ogg|oga|opus|mp4|m4v|mov)$", RegexOption.IGNORE_CASE)

    private fun longest(options: List<String>): String = options.maxByOrNull { it.length }.orEmpty()

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
