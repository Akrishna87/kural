package io.github.akrishna87.podcasts.data

import io.github.akrishna87.podcasts.feed.ParsedFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Everything you've followed, played, queued and saved, kept in memory and written to small JSON
 * files in [dir] shortly after each change. Shared by the screens, the player service and the
 * background refresh, so every method is synchronized.
 *
 * Up Next is the queue: its first episode is the one in the player (playing or paused), and the
 * player service keeps its playlist in step with it.
 */
class Library(private val dir: File, private val writer: ScheduledExecutorService? = defaultWriter()) {

    companion object {
        @Volatile
        private var instance: Library? = null

        /** The app's library, in [filesDir]/library. */
        fun get(filesDir: File): Library = instance ?: synchronized(this) {
            instance ?: Library(File(filesDir, "library")).also { instance = it }
        }

        private fun defaultWriter(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "library-writer").apply { isDaemon = true }
        }

        /** Episodes kept per show beyond the feed's own, so played or saved ones don't vanish. */
        private const val MAX_HISTORY = 200
        private const val MAX_HITS = 100
    }

    /** Bumped on every change the screens might show. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /** Bumped when Up Next changes, which the player follows. */
    private val _queueVersion = MutableStateFlow(0L)
    val queueVersion: StateFlow<Long> = _queueVersion

    private val podcasts = LinkedHashMap<String, PodcastEntry>()
    private val episodeLists = HashMap<String, List<Episode>>()
    private val episodeIndex = HashMap<String, Episode>()
    private val states = HashMap<String, EpisodeState>()
    private val queue = ArrayList<String>()
    private val bookmarks = ArrayList<Bookmark>()
    private val filters = ArrayList<EpisodeFilter>()
    private val alerts = ArrayList<Alert>()
    private val hits = ArrayList<AlertHit>()
    private var stats = Stats(since = System.currentTimeMillis())
    private var playRequested = false

    private val pending = HashSet<String>()

    init {
        dir.mkdirs()
        File(dir, "episodes").mkdirs()
        readArray("podcasts.json")?.objects()?.forEach { o ->
            runCatching { PodcastEntry.fromJson(o) }.getOrNull()?.let { podcasts[it.id] = it }
        }
        readArray("states.json")?.objects()?.forEach { o ->
            runCatching { EpisodeState.fromJson(o) }.getOrNull()?.let { states[it.episodeId] = it }
        }
        readArray("queue.json")?.let { a -> queue += a.strings().filter { it in states } }
        readArray("bookmarks.json")?.objects()?.forEach { o -> runCatching { Bookmark.fromJson(o) }.getOrNull()?.let { bookmarks += it } }
        readArray("filters.json")?.objects()?.forEach { o -> runCatching { EpisodeFilter.fromJson(o) }.getOrNull()?.let { filters += it } }
        readObject("alerts.json")?.let { o ->
            o.optJSONArray("alerts").objects().forEach { a -> runCatching { Alert.fromJson(a) }.getOrNull()?.let { alerts += it } }
            o.optJSONArray("hits").objects().forEach { h -> runCatching { AlertHit.fromJson(h) }.getOrNull()?.let { hits += it } }
        }
        readObject("stats.json")?.let { stats = Stats.fromJson(it) }
        if (filters.isEmpty() && !File(dir, "filters.json").exists()) {
            filters += EpisodeFilter("new-this-week", "New this week", maxAgeDays = 7)
            filters += EpisodeFilter("short", "Under 30 minutes", maxMinutes = 30)
            filters += EpisodeFilter("in-progress", "In progress", unplayedOnly = true, inProgressOnly = true)
        }
    }

    // ----- Saving -----

    private fun readText(name: String): String? = try {
        File(dir, name).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }

    private fun readArray(name: String): JSONArray? = readText(name)?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun readObject(name: String): JSONObject? = readText(name)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun episodesFile(podcastId: String) = "episodes/${shortHash(podcastId)}.json"

    /** Writes [name] soon; several changes in a row are saved once. */
    private fun dirty(name: String) {
        val w = writer
        if (w == null) {
            write(name)
            return
        }
        if (pending.add(name)) w.schedule({ write(name) }, 400, TimeUnit.MILLISECONDS)
    }

    private fun write(name: String) {
        val text = synchronized(this) {
            pending.remove(name)
            snapshot(name)
        } ?: return
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    private fun snapshot(name: String): String? = when {
        name == "podcasts.json" -> JSONArray().also { a -> podcasts.values.forEach { a.put(it.toJson()) } }.toString()
        name == "states.json" -> JSONArray().also { a -> states.values.forEach { if (!it.isEmpty) a.put(it.toJson()) } }.toString()
        name == "queue.json" -> JSONArray(queue).toString()
        name == "bookmarks.json" -> JSONArray().also { a -> bookmarks.forEach { a.put(it.toJson()) } }.toString()
        name == "filters.json" -> JSONArray().also { a -> filters.forEach { a.put(it.toJson()) } }.toString()
        name == "alerts.json" -> JSONObject()
            .put("alerts", JSONArray().also { a -> alerts.forEach { a.put(it.toJson()) } })
            .put("hits", JSONArray().also { a -> hits.forEach { a.put(it.toJson()) } })
            .toString()
        name == "stats.json" -> stats.toJson().toString()
        name.startsWith("episodes/") -> {
            val id = podcasts.keys.firstOrNull { episodesFile(it) == name }
            val list = id?.let { episodeLists[it] }
            if (list == null) null else JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()
        }
        else -> null
    }

    /** Writes everything waiting to be saved now (when the player service stops). */
    fun flush() {
        val names = synchronized(this) { pending.toList() }
        names.forEach { write(it) }
    }

    private fun changed(queueToo: Boolean = false) {
        _version.value = _version.value + 1
        if (queueToo) _queueVersion.value = _queueVersion.value + 1
    }

    // ----- Shows -----

    @Synchronized
    fun entries(): List<PodcastEntry> = podcasts.values.toList()

    @Synchronized
    fun subscriptions(): List<PodcastEntry> = podcasts.values.filter { it.subscribed }

    @Synchronized
    fun entry(id: String): PodcastEntry? = podcasts[id]

    @Synchronized
    fun podcast(id: String): Podcast? = podcasts[id]?.podcast

    @Synchronized
    fun isSubscribed(id: String): Boolean = podcasts[id]?.subscribed == true

    /** A show you follow with the same feed apart from http/https or a trailing slash. */
    @Synchronized
    fun findByFeed(url: String): PodcastEntry? {
        val n = normaliseFeedUrl(url)
        return podcasts[url] ?: podcasts.values.firstOrNull { normaliseFeedUrl(it.id) == n }
    }

    /**
     * Stores a freshly read feed. Returns the episodes that weren't there before (empty for a show
     * you're only now following, so a new subscription doesn't flood the inbox).
     */
    @Synchronized
    fun storeFeed(feed: ParsedFeed, subscribe: Boolean? = null, etag: String? = null, lastModified: String? = null): List<Episode> {
        val id = feed.podcast.id
        val old = podcasts[id]
        val now = System.currentTimeMillis()
        val subscribed = subscribe ?: (old?.subscribed ?: false)
        podcasts[id] = (old ?: PodcastEntry(feed.podcast, subscribed)).copy(
            podcast = feed.podcast.copy(
                // Keep artwork we had if the feed lost its own.
                artworkUrl = feed.podcast.artworkUrl ?: old?.podcast?.artworkUrl,
            ),
            subscribed = subscribed,
            subscribedAt = if (subscribed && old?.subscribed != true) now else old?.subscribedAt ?: 0,
            lastRefreshAt = now,
            etag = etag ?: old?.etag,
            lastModified = lastModified ?: old?.lastModified,
        )
        val hadList = File(dir, episodesFile(id)).isFile || episodeLists.containsKey(id)
        val before = loadEpisodes(id)
        val known = before.mapTo(HashSet()) { it.id }
        val fresh = feed.episodes
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        // Keep older episodes the feed dropped if you've done something with them.
        val kept = before.filter { it.id !in freshIds && (states[it.id]?.isEmpty == false || bookmarks.any { b -> b.episodeId == it.id }) }
            .take(MAX_HISTORY)
        // Keep chapters found earlier if the feed's copy has none.
        val merged = fresh.map { e ->
            val prev = before.firstOrNull { it.id == e.id }
            if (prev != null && e.chapters.isEmpty() && prev.chapters.isNotEmpty()) e.copy(chapters = prev.chapters) else e
        }
        setEpisodes(id, merged + kept)
        dirty("podcasts.json")
        changed()
        // Only shows you already followed have "new" episodes; a fresh subscription starts quiet.
        return if (old?.subscribed == true && hadList && before.isNotEmpty()) merged.filter { it.id !in known } else emptyList()
    }

    @Synchronized
    fun markRefreshed(id: String) {
        podcasts[id]?.let { podcasts[id] = it.copy(lastRefreshAt = System.currentTimeMillis()) }
        dirty("podcasts.json")
    }

    @Synchronized
    fun setSubscribed(id: String, subscribed: Boolean) {
        val e = podcasts[id] ?: return
        podcasts[id] = e.copy(subscribed = subscribed, subscribedAt = if (subscribed) System.currentTimeMillis() else e.subscribedAt)
        if (!subscribed) {
            // Clear the inbox of this show's episodes.
            states.values.filter { it.podcastId == id && it.isNew }.forEach { states[it.episodeId] = it.copy(isNew = false) }
            dirty("states.json")
        }
        dirty("podcasts.json")
        changed()
    }

    @Synchronized
    fun updateSettings(id: String, change: (PodcastSettings) -> PodcastSettings) {
        val e = podcasts[id] ?: return
        podcasts[id] = e.copy(settings = change(e.settings))
        dirty("podcasts.json")
        changed()
    }

    /** Keeps a show and some of its episodes you found by search, without following it. */
    @Synchronized
    fun remember(podcast: Podcast, episodes: List<Episode>) {
        val old = podcasts[podcast.id]
        if (old == null) {
            podcasts[podcast.id] = PodcastEntry(podcast, subscribed = false)
        } else if (old.podcast.artworkUrl == null && podcast.artworkUrl != null) {
            podcasts[podcast.id] = old.copy(podcast = old.podcast.copy(artworkUrl = podcast.artworkUrl))
        }
        val list = loadEpisodes(podcast.id)
        val known = list.mapTo(HashSet()) { it.id }
        val added = episodes.filter { it.id !in known }
        if (added.isNotEmpty()) setEpisodes(podcast.id, (list + added).sortedByDescending { it.publishedAt })
        dirty("podcasts.json")
        changed()
    }

    // ----- Episodes -----

    private fun loadEpisodes(podcastId: String): List<Episode> {
        episodeLists[podcastId]?.let { return it }
        val list = readArray(episodesFile(podcastId))?.objects()?.mapNotNull { runCatching { Episode.fromJson(it) }.getOrNull() }.orEmpty()
        episodeLists[podcastId] = list
        list.forEach { episodeIndex[it.id] = it }
        return list
    }

    private fun setEpisodes(podcastId: String, list: List<Episode>) {
        episodeLists[podcastId]?.forEach { episodeIndex.remove(it.id) }
        episodeLists[podcastId] = list
        list.forEach { episodeIndex[it.id] = it }
        dirty(episodesFile(podcastId))
    }

    /** A show's episodes, newest first, read from the phone the first time. */
    @Synchronized
    fun episodes(podcastId: String): List<Episode> = loadEpisodes(podcastId)

    @Synchronized
    fun episode(id: String): Episode? {
        episodeIndex[id]?.let { return it }
        val podcastId = states[id]?.podcastId ?: bookmarks.firstOrNull { it.episodeId == id }?.podcastId
        if (podcastId != null) return loadEpisodes(podcastId).firstOrNull { it.id == id }
        // Not seen yet: look through every stored show.
        for (p in podcasts.keys) loadEpisodes(p).firstOrNull { it.id == id }?.let { return it }
        return null
    }

    /** Replaces one episode (to keep chapters fetched later, for example). */
    @Synchronized
    fun updateEpisode(e: Episode) {
        val list = loadEpisodes(e.podcastId)
        if (list.none { it.id == e.id }) return
        setEpisodes(e.podcastId, list.map { if (it.id == e.id) e else it })
        changed()
    }

    /** Episodes of every followed show whose title or notes mention [term]. */
    @Synchronized
    fun searchEpisodes(term: String, limit: Int = 100): List<Episode> {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return podcasts.values.filter { it.subscribed }.flatMap { loadEpisodes(it.id) }
            .filter { it.title.lowercase().contains(t) || it.description.lowercase().contains(t) || it.persons.any { p -> p.name.lowercase().contains(t) } }
            .sortedByDescending { it.publishedAt }
            .take(limit)
    }

    // ----- Episode state -----

    @Synchronized
    fun state(id: String): EpisodeState? = states[id]

    @Synchronized
    fun states(): List<EpisodeState> = states.values.toList()

    private fun edit(e: Episode, change: (EpisodeState) -> EpisodeState) = edit(e.id, e.podcastId, change)

    private fun edit(id: String, podcastId: String, change: (EpisodeState) -> EpisodeState) {
        val s = change(states[id] ?: EpisodeState(id, podcastId))
        states[id] = s
        dirty("states.json")
    }

    @Synchronized
    fun updateState(e: Episode, change: (EpisodeState) -> EpisodeState) {
        edit(e, change)
        changed()
    }

    @Synchronized
    fun updateState(id: String, change: (EpisodeState) -> EpisodeState) {
        val s = states[id] ?: episode(id)?.let { EpisodeState(it.id, it.podcastId) } ?: return
        states[id] = change(s)
        dirty("states.json")
        changed()
    }

    /** The player's place, saved every few seconds; [notify] only when the screens should redraw. */
    @Synchronized
    fun savePosition(id: String, positionMs: Long, durationMs: Long, notify: Boolean = false) {
        val s = states[id] ?: return
        states[id] = s.copy(
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = if (durationMs > 0) durationMs else s.durationMs,
            lastPlayedAt = System.currentTimeMillis(),
            isNew = false,
        )
        dirty("states.json")
        if (notify) changed()
    }

    @Synchronized
    fun markPlayed(id: String, played: Boolean) {
        val s = states[id] ?: episode(id)?.let { EpisodeState(it.id, it.podcastId) } ?: return
        states[id] = s.copy(played = played, positionMs = 0, isNew = false)
        dirty("states.json")
        var queueChanged = false
        if (played && queue.remove(id)) {
            dirty("queue.json")
            queueChanged = true
        }
        changed(queueChanged)
    }

    /** Marks every episode of a show played (or the ones older than [before]). */
    @Synchronized
    fun markAllPlayed(podcastId: String, before: Long = Long.MAX_VALUE) {
        val current = queue.firstOrNull()
        loadEpisodes(podcastId).filter { it.publishedAt < before && it.id != current }.forEach { e ->
            edit(e) { it.copy(played = true, positionMs = 0, isNew = false) }
        }
        val removed = queue.drop(1).filter { id -> states[id]?.played == true }
        if (removed.isNotEmpty()) {
            queue.removeAll(removed.toSet())
            dirty("queue.json")
        }
        changed(removed.isNotEmpty())
    }

    @Synchronized
    fun newEpisodes(): List<Pair<Episode, EpisodeState>> =
        states.values.filter { it.isNew && podcasts[it.podcastId]?.subscribed == true }
            .mapNotNull { s -> episode(s.episodeId)?.let { it to s } }
            .sortedByDescending { it.first.publishedAt }

    @Synchronized
    fun dismissNew(ids: Collection<String>) {
        ids.forEach { id -> states[id]?.let { states[id] = it.copy(isNew = false) } }
        dirty("states.json")
        changed()
    }

    /** Marks episodes as new (from a refresh). */
    @Synchronized
    fun markNew(episodes: List<Episode>) {
        episodes.forEach { e -> edit(e) { if (it.played) it else it.copy(isNew = true) } }
        changed()
    }

    @Synchronized
    fun inProgress(): List<Pair<Episode, EpisodeState>> =
        states.values.filter { it.inProgress }.sortedByDescending { it.lastPlayedAt }
            .mapNotNull { s -> episode(s.episodeId)?.let { it to s } }

    @Synchronized
    fun history(limit: Int = 100): List<Pair<Episode, EpisodeState>> =
        states.values.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }.take(limit)
            .mapNotNull { s -> episode(s.episodeId)?.let { it to s } }

    @Synchronized
    fun starred(): List<Pair<Episode, EpisodeState>> =
        states.values.filter { it.starred }.mapNotNull { s -> episode(s.episodeId)?.let { it to s } }.sortedByDescending { it.first.publishedAt }

    @Synchronized
    fun downloads(): List<Pair<Episode, EpisodeState>> =
        states.values.filter { it.downloaded || it.downloadId != 0L }
            .mapNotNull { s -> episode(s.episodeId)?.let { it to s } }
            .sortedByDescending { it.first.publishedAt }

    // ----- Up Next -----

    @Synchronized
    fun queue(): List<String> = queue.toList()

    @Synchronized
    fun queueEpisodes(): List<Episode> = queue.mapNotNull { episode(it) }

    @Synchronized
    fun current(): Episode? = queue.firstOrNull()?.let { episode(it) }

    private fun ensureState(e: Episode) {
        if (states[e.id] == null) edit(e) { it }
    }

    /** Puts [e] in the player and, if [play], starts it. Whatever was playing moves to Up Next's top. */
    @Synchronized
    fun playNow(e: Episode, play: Boolean = true) {
        ensureState(e)
        edit(e) { it.copy(isNew = false, played = if (it.played) false else it.played, positionMs = if (it.played) 0 else it.positionMs) }
        queue.remove(e.id)
        queue.add(0, e.id)
        playRequested = play
        dirty("queue.json")
        changed(queueToo = true)
    }

    /** Adds [e] right after the episode playing now (or makes it the current one if nothing is). */
    @Synchronized
    fun playNext(e: Episode) {
        ensureState(e)
        edit(e) { it.copy(isNew = false) }
        queue.remove(e.id)
        queue.add(if (queue.isEmpty()) 0 else 1, e.id)
        dirty("queue.json")
        changed(queueToo = true)
    }

    @Synchronized
    fun playLast(e: Episode) {
        ensureState(e)
        edit(e) { it.copy(isNew = false) }
        if (e.id !in queue) queue.add(e.id)
        dirty("queue.json")
        changed(queueToo = true)
    }

    @Synchronized
    fun removeFromQueue(id: String) {
        if (queue.remove(id)) {
            dirty("queue.json")
            changed(queueToo = true)
        }
    }

    /** Moves an upcoming episode; positions count Up Next after the one playing now. */
    @Synchronized
    fun moveUpcoming(from: Int, to: Int) {
        val f = from + 1
        val t = to + 1
        if (f !in 1 until queue.size || t !in 1 until queue.size || f == t) return
        val id = queue.removeAt(f)
        queue.add(t, id)
        dirty("queue.json")
        changed(queueToo = true)
    }

    @Synchronized
    fun clearUpcoming() {
        if (queue.size <= 1) return
        val first = queue.first()
        queue.clear()
        queue += first
        dirty("queue.json")
        changed(queueToo = true)
    }

    /**
     * The episode at the front finished ([completed]) or was skipped: it leaves Up Next and the
     * next one moves up. Returns false if [id] wasn't at the front (already handled).
     */
    @Synchronized
    fun finishFront(id: String, completed: Boolean): Boolean {
        if (queue.firstOrNull() != id) return false
        queue.removeAt(0)
        if (completed) {
            states[id]?.let { states[id] = it.copy(played = true, positionMs = 0, isNew = false) }
            stats = stats.copy(finished = stats.finished + 1)
            dirty("stats.json")
            dirty("states.json")
        }
        dirty("queue.json")
        changed(queueToo = true)
        return true
    }

    /** Clears the player: nothing at the front of Up Next. */
    @Synchronized
    fun stopCurrent() {
        if (queue.isEmpty()) return
        queue.removeAt(0)
        dirty("queue.json")
        changed(queueToo = true)
    }

    /** True once after [playNow] asked for playback, so the player starts it. */
    @Synchronized
    fun consumePlayRequest(): Boolean = playRequested.also { playRequested = false }

    /** For autoplay: the show's next episode you haven't heard, in its listening order. */
    @Synchronized
    fun nextUnplayed(after: Episode): Episode? {
        val entry = podcasts[after.podcastId] ?: return null
        val list = loadEpisodes(after.podcastId).filter { it.type != "trailer" }
        val oldestFirst = !entry.settings.newestFirst || entry.podcast.serial
        val candidates = list.filter { it.id != after.id && states[it.id]?.played != true && it.id !in queue }
        return if (oldestFirst) candidates.filter { it.publishedAt >= after.publishedAt }.minByOrNull { it.publishedAt }
        else candidates.maxByOrNull { it.publishedAt }
    }

    // ----- Bookmarks -----

    @Synchronized
    fun bookmarks(episodeId: String? = null): List<Bookmark> =
        bookmarks.filter { episodeId == null || it.episodeId == episodeId }.sortedWith(
            if (episodeId == null) compareByDescending { it.createdAt } else compareBy { it.positionMs },
        )

    @Synchronized
    fun addBookmark(e: Episode, positionMs: Long, note: String): Bookmark {
        ensureState(e)
        val b = Bookmark(UUID.randomUUID().toString(), e.id, e.podcastId, positionMs, note.trim(), System.currentTimeMillis())
        bookmarks += b
        dirty("bookmarks.json")
        changed()
        return b
    }

    @Synchronized
    fun updateBookmark(id: String, note: String) {
        val i = bookmarks.indexOfFirst { it.id == id }
        if (i < 0) return
        bookmarks[i] = bookmarks[i].copy(note = note.trim())
        dirty("bookmarks.json")
        changed()
    }

    @Synchronized
    fun removeBookmark(id: String) {
        if (bookmarks.removeAll { it.id == id }) {
            dirty("bookmarks.json")
            changed()
        }
    }

    // ----- Filters (smart playlists) -----

    @Synchronized
    fun filters(): List<EpisodeFilter> = filters.toList()

    @Synchronized
    fun saveFilter(f: EpisodeFilter) {
        val i = filters.indexOfFirst { it.id == f.id }
        if (i >= 0) filters[i] = f else filters += f
        dirty("filters.json")
        changed()
    }

    @Synchronized
    fun deleteFilter(id: String) {
        filters.removeAll { it.id == id }
        dirty("filters.json")
        changed()
    }

    /** Episodes matching a filter, newest first. Reads every followed show, so call it off the main thread. */
    @Synchronized
    fun filterEpisodes(f: EpisodeFilter, now: Long = System.currentTimeMillis(), limit: Int = 300): List<Episode> {
        val shows = podcasts.values.filter { it.subscribed && (f.podcastIds.isEmpty() || it.id in f.podcastIds) }
        return shows.flatMap { loadEpisodes(it.id) }
            .filter { f.matches(it, states[it.id], now) }
            .sortedByDescending { it.publishedAt }
            .take(limit)
    }

    // ----- Alerts -----

    @Synchronized
    fun alerts(): List<Alert> = alerts.toList()

    @Synchronized
    fun addAlert(term: String) {
        val t = term.trim()
        if (t.isEmpty() || alerts.any { it.term.equals(t, ignoreCase = true) }) return
        alerts += Alert(t, System.currentTimeMillis(), lastCheckedAt = System.currentTimeMillis())
        dirty("alerts.json")
        changed()
    }

    @Synchronized
    fun removeAlert(term: String) {
        alerts.removeAll { it.term == term }
        hits.removeAll { it.term == term }
        dirty("alerts.json")
        changed()
    }

    @Synchronized
    fun alertChecked(term: String, at: Long) {
        val i = alerts.indexOfFirst { it.term == term }
        if (i >= 0) alerts[i] = alerts[i].copy(lastCheckedAt = at)
        dirty("alerts.json")
    }

    @Synchronized
    fun hits(): List<AlertHit> = hits.sortedByDescending { it.episode.publishedAt }

    /** Records episodes found for an alert; returns the ones not seen before. */
    @Synchronized
    fun addHits(newHits: List<AlertHit>): List<AlertHit> {
        val known = hits.mapTo(HashSet()) { it.episode.id }
        val added = newHits.filter { known.add(it.episode.id) }
        if (added.isEmpty()) return added
        hits += added
        hits.sortByDescending { it.episode.publishedAt }
        while (hits.size > MAX_HITS) hits.removeAt(hits.lastIndex)
        dirty("alerts.json")
        changed()
        return added
    }

    @Synchronized
    fun clearHits() {
        hits.clear()
        dirty("alerts.json")
        changed()
    }

    // ----- Stats -----

    @Synchronized
    fun stats(): Stats = stats

    /** [wallMs] of listening that moved through [audioMs] of the episode. */
    @Synchronized
    fun addListening(podcastId: String, wallMs: Long, audioMs: Long) {
        if (wallMs <= 0) return
        val by = HashMap(stats.byPodcast)
        by[podcastId] = (by[podcastId] ?: 0L) + wallMs
        stats = stats.copy(
            listenedMs = stats.listenedMs + wallMs,
            savedMs = stats.savedMs + (audioMs - wallMs).coerceAtLeast(0),
            byPodcast = by,
        )
        dirty("stats.json")
    }

    @Synchronized
    fun addSkipped(ms: Long) {
        if (ms <= 0) return
        stats = stats.copy(skippedMs = stats.skippedMs + ms)
        dirty("stats.json")
    }

    @Synchronized
    fun resetStats() {
        stats = Stats(since = System.currentTimeMillis())
        dirty("stats.json")
        changed()
    }

    // ----- Tidying -----

    /** Forgets a show you stopped following, unless something of it is still queued, saved or downloaded. */
    @Synchronized
    fun forgetIfUnused(id: String): Boolean {
        val e = podcasts[id] ?: return false
        if (e.subscribed) return false
        val used = states.values.any { it.podcastId == id && (it.downloaded || it.downloadId != 0L || it.starred || it.episodeId in queue) } ||
            bookmarks.any { it.podcastId == id }
        if (used) return false
        podcasts.remove(id)
        episodeLists.remove(id)?.forEach { episodeIndex.remove(it.id) }
        states.values.filter { it.podcastId == id }.forEach { states.remove(it.episodeId) }
        File(dir, episodesFile(id)).delete()
        dirty("podcasts.json")
        dirty("states.json")
        changed()
        return true
    }
}
