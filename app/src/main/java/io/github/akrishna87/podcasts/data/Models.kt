package io.github.akrishna87.podcasts.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A show, as its feed describes it. Its feed address is its id. */
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val artworkUrl: String? = null,
    val link: String? = null,
    val categories: List<String> = emptyList(),
    /** itunes:type "serial": episodes are meant to be heard oldest first. */
    val serial: Boolean = false,
    /** podcast:funding: where to support the show. */
    val fundingUrl: String? = null,
    val fundingLabel: String? = null,
) {
    val id: String get() = feedUrl

    fun toJson(): JSONObject = JSONObject()
        .put("feedUrl", feedUrl)
        .put("title", title)
        .put("author", author)
        .put("description", description)
        .putOpt("artworkUrl", artworkUrl)
        .putOpt("link", link)
        .put("categories", JSONArray(categories))
        .put("serial", serial)
        .putOpt("fundingUrl", fundingUrl)
        .putOpt("fundingLabel", fundingLabel)

    companion object {
        fun fromJson(o: JSONObject) = Podcast(
            feedUrl = o.getString("feedUrl"),
            title = o.optString("title"),
            author = o.optString("author"),
            description = o.optString("description"),
            artworkUrl = o.optStringOrNull("artworkUrl"),
            link = o.optStringOrNull("link"),
            categories = o.optJSONArray("categories").strings(),
            serial = o.optBoolean("serial"),
            fundingUrl = o.optStringOrNull("fundingUrl"),
            fundingLabel = o.optStringOrNull("fundingLabel"),
        )
    }
}

/** A chapter mark: from the feed, a chapters file, or the audio file's own ID3 tags. */
data class Chapter(
    val startMs: Long,
    val title: String,
    val url: String? = null,
    val imageUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("start", startMs).put("title", title).putOpt("url", url).putOpt("img", imageUrl)

    companion object {
        fun fromJson(o: JSONObject) = Chapter(o.optLong("start"), o.optString("title"), o.optStringOrNull("url"), o.optStringOrNull("img"))

        fun listToJson(list: List<Chapter>) = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<Chapter> = if (a == null) emptyList() else List(a.length()) { fromJson(a.getJSONObject(it)) }
    }
}

/** podcast:transcript: a link to the episode's transcript in one of several formats. */
data class TranscriptRef(val url: String, val type: String, val language: String? = null) {
    /** How well we can show this format: timed formats first. */
    val rank: Int
        get() = when {
            type.contains("json") -> 0
            type.contains("vtt") -> 1
            type.contains("srt") || type.contains("subrip") -> 2
            type.contains("html") -> 3
            else -> 4
        }

    fun toJson(): JSONObject = JSONObject().put("url", url).put("type", type).putOpt("lang", language)

    companion object {
        fun fromJson(o: JSONObject) = TranscriptRef(o.getString("url"), o.optString("type"), o.optStringOrNull("lang"))
    }
}

/** podcast:person: a host or guest. */
data class Person(val name: String, val role: String = "host", val imageUrl: String? = null, val link: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("role", role).putOpt("img", imageUrl).putOpt("href", link)

    companion object {
        fun fromJson(o: JSONObject) = Person(o.getString("name"), o.optString("role", "host"), o.optStringOrNull("img"), o.optStringOrNull("href"))
    }
}

data class Episode(
    val id: String,
    val podcastId: String,
    val guid: String,
    val title: String,
    /** The show notes, as HTML. */
    val description: String = "",
    val audioUrl: String,
    val mimeType: String = "audio/mpeg",
    val sizeBytes: Long = 0,
    val durationSec: Long = 0,
    /** ms since 1970; 0 if the feed doesn't say. */
    val publishedAt: Long = 0,
    val link: String? = null,
    val artworkUrl: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    /** "full", "trailer" or "bonus". */
    val type: String = "full",
    val explicit: Boolean = false,
    /** podcast:chapters: a JSON chapters file to fetch. */
    val chaptersUrl: String? = null,
    /** Chapters written into the feed itself (Podlove Simple Chapters). */
    val chapters: List<Chapter> = emptyList(),
    val transcripts: List<TranscriptRef> = emptyList(),
    val persons: List<Person> = emptyList(),
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** The transcript we can show best, if there is one. */
    val bestTranscript: TranscriptRef? get() = transcripts.minByOrNull { it.rank }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("podcastId", podcastId)
        .put("guid", guid)
        .put("title", title)
        .put("description", description)
        .put("audioUrl", audioUrl)
        .put("mimeType", mimeType)
        .put("size", sizeBytes)
        .put("duration", durationSec)
        .put("published", publishedAt)
        .putOpt("link", link)
        .putOpt("artworkUrl", artworkUrl)
        .putOpt("season", season)
        .putOpt("number", number)
        .put("type", type)
        .put("explicit", explicit)
        .putOpt("chaptersUrl", chaptersUrl)
        .apply { if (chapters.isNotEmpty()) put("chapters", Chapter.listToJson(chapters)) }
        .apply { if (transcripts.isNotEmpty()) put("transcripts", JSONArray().also { a -> transcripts.forEach { a.put(it.toJson()) } }) }
        .apply { if (persons.isNotEmpty()) put("persons", JSONArray().also { a -> persons.forEach { a.put(it.toJson()) } }) }

    companion object {
        fun fromJson(o: JSONObject) = Episode(
            id = o.getString("id"),
            podcastId = o.getString("podcastId"),
            guid = o.optString("guid"),
            title = o.optString("title"),
            description = o.optString("description"),
            audioUrl = o.getString("audioUrl"),
            mimeType = o.optString("mimeType", "audio/mpeg"),
            sizeBytes = o.optLong("size"),
            durationSec = o.optLong("duration"),
            publishedAt = o.optLong("published"),
            link = o.optStringOrNull("link"),
            artworkUrl = o.optStringOrNull("artworkUrl"),
            season = if (o.has("season")) o.optInt("season") else null,
            number = if (o.has("number")) o.optInt("number") else null,
            type = o.optString("type", "full"),
            explicit = o.optBoolean("explicit"),
            chaptersUrl = o.optStringOrNull("chaptersUrl"),
            chapters = Chapter.listFromJson(o.optJSONArray("chapters")),
            transcripts = o.optJSONArray("transcripts").objects().map(TranscriptRef::fromJson),
            persons = o.optJSONArray("persons").objects().map(Person::fromJson),
        )

        /** Stable ids: the same episode gets the same id whether found by search or in its feed. */
        fun idFor(feedUrl: String, guid: String): String = shortHash(normaliseFeedUrl(feedUrl)) + "-" + shortHash(guid)
    }
}

/** Feed addresses differ only in http/https or a trailing slash often enough to treat them as one. */
fun normaliseFeedUrl(url: String): String = url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').lowercase()

fun shortHash(s: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

/** What to do with a show's new episodes. */
enum class AutoAdd(val label: String) { OFF("Don't add"), TOP("Play next"), BOTTOM("Play last") }

/** Per-show settings, like Pocket Casts' and Overcast's. Null effect values follow the app-wide setting. */
data class PodcastSettings(
    val newestFirst: Boolean = true,
    val autoAdd: AutoAdd = AutoAdd.OFF,
    val autoDownload: Boolean = false,
    val notify: Boolean = false,
    /** Skip this many seconds at the start of every episode (intros, pre-roll ads). */
    val skipIntroSec: Int = 0,
    /** Skip this many seconds at the end (outros, credits). */
    val skipOutroSec: Int = 0,
    /** Custom effects for this show: speed, trim silence and volume boost. */
    val customEffects: Boolean = false,
    val speed: Float = 1f,
    val trimSilence: Boolean = false,
    val boost: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("newestFirst", newestFirst)
        .put("autoAdd", autoAdd.name)
        .put("autoDownload", autoDownload)
        .put("notify", notify)
        .put("skipIntro", skipIntroSec)
        .put("skipOutro", skipOutroSec)
        .put("customEffects", customEffects)
        .put("speed", speed.toDouble())
        .put("trimSilence", trimSilence)
        .put("boost", boost)

    companion object {
        fun fromJson(o: JSONObject?): PodcastSettings {
            if (o == null) return PodcastSettings()
            return PodcastSettings(
                newestFirst = o.optBoolean("newestFirst", true),
                autoAdd = AutoAdd.entries.firstOrNull { it.name == o.optString("autoAdd") } ?: AutoAdd.OFF,
                autoDownload = o.optBoolean("autoDownload"),
                notify = o.optBoolean("notify"),
                skipIntroSec = o.optInt("skipIntro"),
                skipOutroSec = o.optInt("skipOutro"),
                customEffects = o.optBoolean("customEffects"),
                speed = o.optDouble("speed", 1.0).toFloat(),
                trimSilence = o.optBoolean("trimSilence"),
                boost = o.optBoolean("boost"),
            )
        }
    }
}

/** A show you follow (or one you've played from without following). */
data class PodcastEntry(
    val podcast: Podcast,
    val subscribed: Boolean,
    val subscribedAt: Long = 0,
    val settings: PodcastSettings = PodcastSettings(),
    val lastRefreshAt: Long = 0,
    /** HTTP validators so unchanged feeds aren't downloaded again. */
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val id: String get() = podcast.id

    fun toJson(): JSONObject = JSONObject()
        .put("podcast", podcast.toJson())
        .put("subscribed", subscribed)
        .put("subscribedAt", subscribedAt)
        .put("settings", settings.toJson())
        .put("lastRefreshAt", lastRefreshAt)
        .putOpt("etag", etag)
        .putOpt("lastModified", lastModified)

    companion object {
        fun fromJson(o: JSONObject) = PodcastEntry(
            podcast = Podcast.fromJson(o.getJSONObject("podcast")),
            subscribed = o.optBoolean("subscribed"),
            subscribedAt = o.optLong("subscribedAt"),
            settings = PodcastSettings.fromJson(o.optJSONObject("settings")),
            lastRefreshAt = o.optLong("lastRefreshAt"),
            etag = o.optStringOrNull("etag"),
            lastModified = o.optStringOrNull("lastModified"),
        )
    }
}

/** What you've done with an episode. Kept apart from the episode so feed refreshes don't touch it. */
data class EpisodeState(
    val episodeId: String,
    val podcastId: String,
    val positionMs: Long = 0,
    /** The real length, once the player knows it. */
    val durationMs: Long = 0,
    val played: Boolean = false,
    val lastPlayedAt: Long = 0,
    val starred: Boolean = false,
    /** In the "New episodes" inbox until played, queued or dismissed. */
    val isNew: Boolean = false,
    /** Android download id while downloading; 0 otherwise. */
    val downloadId: Long = 0,
    /** The audio is on the phone. */
    val downloaded: Boolean = false,
    /** Chapters read from the audio file's ID3 tags, for episodes whose feed has none. */
    val fileChapters: List<Chapter> = emptyList(),
) {
    val inProgress: Boolean get() = !played && positionMs > 0

    fun toJson(): JSONObject = JSONObject()
        .put("e", episodeId)
        .put("p", podcastId)
        .put("pos", positionMs)
        .put("dur", durationMs)
        .put("played", played)
        .put("at", lastPlayedAt)
        .put("star", starred)
        .put("new", isNew)
        .put("dl", downloadId)
        .put("done", downloaded)
        .apply { if (fileChapters.isNotEmpty()) put("ch", Chapter.listToJson(fileChapters)) }

    /** Nothing worth keeping: the default for any episode. */
    val isEmpty: Boolean
        get() = positionMs == 0L && !played && !starred && !isNew && downloadId == 0L && !downloaded && fileChapters.isEmpty()

    companion object {
        fun fromJson(o: JSONObject) = EpisodeState(
            episodeId = o.getString("e"),
            podcastId = o.getString("p"),
            positionMs = o.optLong("pos"),
            durationMs = o.optLong("dur"),
            played = o.optBoolean("played"),
            lastPlayedAt = o.optLong("at"),
            starred = o.optBoolean("star"),
            isNew = o.optBoolean("new"),
            downloadId = o.optLong("dl"),
            downloaded = o.optBoolean("done"),
            fileChapters = Chapter.listFromJson(o.optJSONArray("ch")),
        )
    }
}

data class Bookmark(val id: String, val episodeId: String, val podcastId: String, val positionMs: Long, val note: String, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("e", episodeId).put("p", podcastId).put("pos", positionMs).put("note", note).put("at", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            o.getString("id"), o.getString("e"), o.getString("p"), o.optLong("pos"), o.optString("note"), o.optLong("at"),
        )
    }
}

/** A smart playlist: episodes from chosen shows that match some rules, always up to date. */
data class EpisodeFilter(
    val id: String,
    val name: String,
    /** Empty means every show you follow. */
    val podcastIds: List<String> = emptyList(),
    val unplayedOnly: Boolean = true,
    val downloadedOnly: Boolean = false,
    val inProgressOnly: Boolean = false,
    val starredOnly: Boolean = false,
    /** Released in the last N days; 0 for any time. */
    val maxAgeDays: Int = 0,
    /** At most N minutes long; 0 for any length. */
    val maxMinutes: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("podcasts", JSONArray(podcastIds))
        .put("unplayed", unplayedOnly).put("downloaded", downloadedOnly).put("inProgress", inProgressOnly)
        .put("starred", starredOnly).put("maxAge", maxAgeDays).put("maxMinutes", maxMinutes)

    fun matches(e: Episode, s: EpisodeState?, now: Long): Boolean {
        if (podcastIds.isNotEmpty() && e.podcastId !in podcastIds) return false
        if (unplayedOnly && s?.played == true) return false
        if (downloadedOnly && s?.downloaded != true) return false
        if (inProgressOnly && s?.inProgress != true) return false
        if (starredOnly && s?.starred != true) return false
        if (maxAgeDays > 0 && e.publishedAt < now - maxAgeDays * 86_400_000L) return false
        if (maxMinutes > 0) {
            val sec = if (e.durationSec > 0) e.durationSec else (s?.durationMs ?: 0) / 1000
            if (sec <= 0 || sec > maxMinutes * 60L) return false
        }
        return true
    }

    companion object {
        fun fromJson(o: JSONObject) = EpisodeFilter(
            id = o.getString("id"),
            name = o.optString("name"),
            podcastIds = o.optJSONArray("podcasts").strings(),
            unplayedOnly = o.optBoolean("unplayed", true),
            downloadedOnly = o.optBoolean("downloaded"),
            inProgressOnly = o.optBoolean("inProgress"),
            starredOnly = o.optBoolean("starred"),
            maxAgeDays = o.optInt("maxAge"),
            maxMinutes = o.optInt("maxMinutes"),
        )
    }
}

/** A name or topic to watch for across every podcast, e.g. a favourite guest. */
data class Alert(val term: String, val createdAt: Long, val lastCheckedAt: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("term", term).put("at", createdAt).put("checked", lastCheckedAt)

    companion object {
        fun fromJson(o: JSONObject) = Alert(o.getString("term"), o.optLong("at"), o.optLong("checked"))
    }
}

/** An episode found for an alert, with enough about its show to play it. */
data class AlertHit(val term: String, val episode: Episode, val podcast: Podcast, val foundAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("term", term).put("episode", episode.toJson()).put("podcast", podcast.toJson()).put("at", foundAt)

    companion object {
        fun fromJson(o: JSONObject) = AlertHit(
            o.getString("term"), Episode.fromJson(o.getJSONObject("episode")), Podcast.fromJson(o.getJSONObject("podcast")), o.optLong("at"),
        )
    }
}

/** Listening totals, like Pocket Casts' stats. */
data class Stats(
    val since: Long,
    /** Wall-clock time spent listening. */
    val listenedMs: Long = 0,
    /** Time saved by speed and trimmed silences: audio heard minus time spent. */
    val savedMs: Long = 0,
    val skippedMs: Long = 0,
    val finished: Int = 0,
    /** Listening time per show. */
    val byPodcast: Map<String, Long> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("since", since).put("listened", listenedMs).put("saved", savedMs).put("skipped", skippedMs).put("finished", finished)
        .put("byPodcast", JSONObject().also { o -> byPodcast.forEach { (k, v) -> o.put(k, v) } })

    companion object {
        fun fromJson(o: JSONObject): Stats {
            val by = o.optJSONObject("byPodcast")
            val map = HashMap<String, Long>()
            by?.keys()?.forEach { k -> map[k] = by.optLong(k) }
            return Stats(o.optLong("since"), o.optLong("listened"), o.optLong("saved"), o.optLong("skipped"), o.optInt("finished"), map)
        }
    }
}

// ----- org.json helpers -----

fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
