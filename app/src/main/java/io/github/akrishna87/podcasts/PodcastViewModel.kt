package io.github.akrishna87.podcasts

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import io.github.akrishna87.podcasts.data.Alert
import io.github.akrishna87.podcasts.data.AlertHit
import io.github.akrishna87.podcasts.data.Bookmark
import io.github.akrishna87.podcasts.data.Chapter
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.EpisodeFilter
import io.github.akrishna87.podcasts.data.EpisodeState
import io.github.akrishna87.podcasts.data.PodcastEntry
import io.github.akrishna87.podcasts.data.PodcastSettings
import io.github.akrishna87.podcasts.data.Stats
import io.github.akrishna87.podcasts.feed.CATEGORIES
import io.github.akrishna87.podcasts.feed.Category
import io.github.akrishna87.podcasts.feed.categoryNamed
import io.github.akrishna87.podcasts.feed.ChaptersJson
import io.github.akrishna87.podcasts.feed.Directory
import io.github.akrishna87.podcasts.feed.DirectoryEpisode
import io.github.akrishna87.podcasts.feed.DirectoryPodcast
import io.github.akrishna87.podcasts.feed.Http
import io.github.akrishna87.podcasts.feed.Opml
import io.github.akrishna87.podcasts.feed.ParsedFeed
import io.github.akrishna87.podcasts.feed.Transcript
import io.github.akrishna87.podcasts.feed.TranscriptParser
import io.github.akrishna87.podcasts.feed.formatClock
import io.github.akrishna87.podcasts.feed.indexAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Section(val label: String) { HOME("Home"), UP_NEXT("Up Next"), DISCOVER("Discover"), LIBRARY("Library") }

enum class LibraryTab(val label: String) {
    SHOWS("Shows"), FILTERS("Filters"), DOWNLOADS("Downloads"), STARRED("Starred"), BOOKMARKS("Bookmarks"), HISTORY("History")
}

enum class SearchMode(val label: String) { SHOWS("Shows"), EPISODES("Episodes"), MY_EPISODES("My shows") }

sealed interface Screen {
    /** A show: from your library by feed, or from the directory (whose feed may need looking up). */
    data class PodcastPage(val feedUrl: String?, val listing: DirectoryPodcast? = null) : Screen
    data class EpisodePage(val episodeId: String, val episode: Episode? = null) : Screen
    data class ShowSettings(val podcastId: String) : Screen
    data class CategoryPage(val category: Category) : Screen
    data class FilterPage(val filterId: String) : Screen
    data object Alerts : Screen
    data object SettingsPage : Screen
    data object StatsPage : Screen
}

/** Something loaded from the internet. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** A show in your library with what the Shows grid needs. */
data class ShowRow(val entry: PodcastEntry, val latestAt: Long, val unplayed: Int)

/** Everything the lists show, rebuilt off the main thread whenever the library changes. */
data class Snapshot(
    val shows: List<ShowRow> = emptyList(),
    val queue: List<Episode> = emptyList(),
    val newEpisodes: List<Episode> = emptyList(),
    /** The newest episodes you haven't heard from the shows you follow. */
    val latest: List<Episode> = emptyList(),
    val inProgress: List<Episode> = emptyList(),
    val downloads: List<Episode> = emptyList(),
    val starred: List<Episode> = emptyList(),
    val history: List<Episode> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val hits: List<AlertHit> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val filters: List<EpisodeFilter> = emptyList(),
    val loaded: Boolean = false,
)

val SPEEDS = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f, 2f, 2.25f, 2.5f, 2.75f, 3f)

/** A friendly message for a failed request. */
fun friendlyError(e: Throwable): String = when {
    e is java.net.UnknownHostException -> "No internet connection"
    e is java.net.SocketTimeoutException -> "The server is taking too long to answer"
    e is java.io.IOException && e.message?.startsWith("HTTP 404") == true -> "That address wasn't found"
    e is java.io.IOException && e.message?.startsWith("HTTP ") == true -> "The show's server said no (${e.message})"
    // Our own explanations ("This doesn't look like a podcast feed", "This show has no public feed" …).
    e is java.io.IOException && e.message?.contains("feed") == true -> e.message!!
    else -> "Couldn't load it. Check your connection and try again."
}

@OptIn(FlowPreview::class)
class PodcastViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    val lib = app.library()
    private val prefs = Settings.prefs(app)

    // ----- Navigation -----
    val screens = mutableStateListOf<Screen>()
    var section by mutableStateOf(Section.HOME); private set
    var libraryTab by mutableStateOf(LibraryTab.SHOWS)
    var showPlayer by mutableStateOf(false)
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun say(text: String) {
        messageChannel.trySend(text)
    }

    fun selectSection(s: Section) {
        showPlayer = false
        screens.clear()
        section = s
    }

    fun open(screen: Screen) {
        showPlayer = false
        if (screens.lastOrNull() != screen) screens += screen
    }

    fun back(): Boolean = when {
        showPlayer -> { showPlayer = false; true }
        screens.isNotEmpty() -> { screens.removeAt(screens.lastIndex); true }
        else -> false
    }

    fun openPodcast(feedUrl: String) = open(Screen.PodcastPage(lib.findByFeed(feedUrl)?.id ?: feedUrl))

    fun openListing(p: DirectoryPodcast) = open(Screen.PodcastPage(p.feedUrl?.let { lib.findByFeed(it)?.id ?: it }, p))

    fun openEpisode(e: Episode) = open(Screen.EpisodePage(e.id, e))

    // ----- The library -----

    /** Bumped when anything in the library changes; screens read it to redraw. */
    var tick by mutableLongStateOf(0L); private set
    var snap by mutableStateOf(Snapshot()); private set

    fun state(id: String): EpisodeState? {
        tick
        return lib.state(id)
    }

    fun entry(id: String): PodcastEntry? {
        tick
        return lib.entry(id)
    }

    fun episodesOf(podcastId: String): List<Episode> {
        tick
        return lib.episodes(podcastId)
    }

    fun episode(id: String): Episode? {
        tick
        return lib.episode(id)
    }

    fun bookmarksOf(episodeId: String): List<Bookmark> {
        tick
        return lib.bookmarks(episodeId)
    }

    /** A show we know of: followed or played, previewed, or found by an episode search or alert. */
    private fun anyPodcast(id: String) = lib.podcast(id) ?: previews[id]?.podcast
        ?: episodeResults.firstOrNull { it.podcast.id == id }?.podcast
        ?: snap.hits.firstOrNull { it.podcast.id == id }?.podcast

    fun podcastTitle(id: String): String {
        tick
        return anyPodcast(id)?.title.orEmpty()
    }

    fun artworkOf(e: Episode): String? = e.artworkUrl ?: anyPodcast(e.podcastId)?.artworkUrl

    private fun buildSnapshot(): Snapshot {
        val shows = lib.subscriptions().map { entry ->
            val eps = lib.episodes(entry.id)
            ShowRow(entry, eps.firstOrNull()?.publishedAt ?: 0, eps.count { lib.state(it.id)?.played != true && it.type != "trailer" })
        }.sortedByDescending { it.latestAt }
        val queue = lib.queueEpisodes()
        val newEpisodes = lib.newEpisodes().map { it.first }
        val skip = (queue + newEpisodes).mapTo(HashSet()) { it.id }
        val latest = shows.flatMap { row -> lib.episodes(row.entry.id).take(3) }
            .filter { it.id !in skip && it.type != "trailer" && lib.state(it.id)?.let { s -> s.played || s.inProgress } != true }
            .sortedByDescending { it.publishedAt }
            .take(12)
        return Snapshot(
            shows = shows,
            queue = queue,
            newEpisodes = newEpisodes,
            latest = latest,
            inProgress = lib.inProgress().map { it.first }.filter { it.id != lib.queue().firstOrNull() }.take(20),
            downloads = lib.downloads().map { it.first },
            starred = lib.starred().map { it.first },
            history = lib.history().map { it.first },
            bookmarks = lib.bookmarks(),
            hits = lib.hits(),
            alerts = lib.alerts(),
            filters = lib.filters(),
            loaded = true,
        )
    }

    // ----- Previews of shows you don't follow -----

    /** Feeds read for show pages, by feed address, kept while the app is open. */
    val previews = mutableStateMapOf<String, ParsedFeed>()
    val pageLoads = mutableStateMapOf<String, Load<String>>()

    /** Reads a show's feed for its page. Resolves directory listings to their feed first. Returns the feed address. */
    fun loadPage(page: Screen.PodcastPage) {
        val key = page.feedUrl ?: "apple:${page.listing?.appleId}"
        if (pageLoads[key] is Load.Loading || pageLoads[key] is Load.Ready) return
        val known = page.feedUrl?.let { lib.findByFeed(it) }
        if (known != null && lib.episodes(known.id).isNotEmpty()) {
            pageLoads[key] = Load.Ready(known.id)
            // Followed shows refresh quietly when opened, at most every 15 minutes.
            if (known.subscribed && System.currentTimeMillis() - known.lastRefreshAt > 15 * 60_000L) refresh(known.id, quiet = true)
            return
        }
        pageLoads[key] = Load.Loading
        viewModelScope.launch {
            pageLoads[key] = try {
                val url = page.feedUrl ?: withContext(Dispatchers.IO) { Directory.feedUrlFor(page.listing!!.appleId) }
                val existing = lib.findByFeed(url)
                if (existing != null && lib.episodes(existing.id).isNotEmpty()) {
                    Load.Ready(existing.id)
                } else {
                    val feed = Refresher.fetchFeed(url)
                    val withArt = if (feed.podcast.artworkUrl == null && page.listing?.artworkUrl != null) {
                        ParsedFeed(feed.podcast.copy(artworkUrl = page.listing.artworkUrl), feed.episodes)
                    } else feed
                    previews[url] = withArt
                    if (existing != null) lib.storeFeed(withArt) // a show you only played from: bring it up to date
                    Load.Ready(url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(friendlyError(e))
            }
        }
    }

    fun retryPage(page: Screen.PodcastPage) {
        pageLoads.remove(page.feedUrl ?: "apple:${page.listing?.appleId}")
        loadPage(page)
    }

    /** A show's episodes: from the library, or from the preview of its feed. */
    fun pageEpisodes(feedUrl: String): List<Episode> {
        tick
        val stored = lib.episodes(feedUrl)
        return stored.ifEmpty { previews[feedUrl]?.episodes.orEmpty() }
    }

    fun pagePodcast(feedUrl: String) = entry(feedUrl)?.podcast ?: previews[feedUrl]?.podcast

    /** Makes sure an episode (and its show) is in the library before it's played, queued or saved. */
    private fun keep(e: Episode) {
        if (lib.episode(e.id) != null) return
        val podcast = anyPodcast(e.podcastId) ?: return
        lib.remember(podcast, listOf(e))
    }

    // ----- Following -----

    val subscribing = mutableStateListOf<String>()

    fun subscribe(feedUrl: String) {
        if (feedUrl in subscribing) return
        val preview = previews[feedUrl]
        if (preview != null) {
            lib.storeFeed(preview, subscribe = true)
            say("Following “${preview.podcast.title}”")
            return
        }
        subscribing += feedUrl
        viewModelScope.launch {
            try {
                val entry = Refresher.subscribe(context, feedUrl)
                say("Following “${entry.podcast.title}”")
            } catch (e: Exception) {
                say(friendlyError(e))
            } finally {
                subscribing -= feedUrl
            }
        }
    }

    fun unsubscribe(id: String) {
        val title = lib.podcast(id)?.title.orEmpty()
        lib.setSubscribed(id, false)
        say("Stopped following “$title”")
    }

    var refreshing by mutableStateOf(false); private set

    fun refreshAll() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                val r = Refresher.refreshAll(context, notify = false)
                prefs.edit().putLong("last_refresh", System.currentTimeMillis()).apply()
                when {
                    lib.subscriptions().isEmpty() -> Unit
                    r.newEpisodes > 0 -> say(if (r.newEpisodes == 1) "1 new episode" else "${r.newEpisodes} new episodes")
                    r.failed > 0 && r.failed == lib.subscriptions().size -> say("Couldn't refresh. Check your connection.")
                    else -> say("You're up to date")
                }
            } finally {
                refreshing = false
            }
        }
    }

    fun refresh(id: String, quiet: Boolean = false) {
        viewModelScope.launch {
            val r = Refresher.refreshAll(context, only = listOf(id), notify = false)
            if (!quiet && r.failed > 0) say("Couldn't refresh this show")
        }
    }

    fun updateShowSettings(id: String, change: (PodcastSettings) -> PodcastSettings) = lib.updateSettings(id, change)

    // ----- Episodes -----

    fun play(e: Episode) {
        keep(e)
        lib.playNow(e)
        ensureController()
    }

    /** Plays an episode from [ms] (a bookmark, a timestamp in the notes, a chapter). */
    fun playAt(e: Episode, ms: Long) {
        if (currentId == e.id) {
            controller?.let {
                it.seekTo(ms)
                if (!it.isPlaying) resume(it)
            }
            return
        }
        keep(e)
        lib.updateState(e) { it.copy(positionMs = ms, played = false) }
        lib.playNow(e)
    }

    fun playNext(e: Episode) {
        keep(e)
        lib.playNext(e)
        say(if (lib.queue().size == 1) "Ready to play" else "Playing next")
    }

    fun playLast(e: Episode) {
        keep(e)
        lib.playLast(e)
        say("Added to Up Next")
    }

    fun inQueue(id: String): Boolean {
        tick
        return id in lib.queue()
    }

    fun removeFromQueue(id: String) = lib.removeFromQueue(id)

    fun moveUpNext(from: Int, to: Int) = lib.moveUpcoming(from, to)

    fun clearUpNext() {
        lib.clearUpcoming()
        say("Cleared Up Next")
    }

    fun markPlayed(e: Episode, played: Boolean) {
        keep(e)
        lib.markPlayed(e.id, played)
        if (played && Settings.deletePlayed(prefs) && lib.state(e.id)?.downloaded == true) Downloads.delete(context, lib, e.id)
    }

    fun markAllPlayed(podcastId: String) {
        lib.markAllPlayed(podcastId)
        say("Marked every episode as played")
    }

    fun toggleStar(e: Episode) {
        keep(e)
        val on = lib.state(e.id)?.starred != true
        lib.updateState(e) { it.copy(starred = on) }
        say(if (on) "Starred" else "Removed the star")
    }

    fun dismissNew(ids: Collection<String>) = lib.dismissNew(ids)

    fun download(e: Episode) {
        keep(e)
        try {
            Downloads.start(context, lib, e)
            say("Downloading “${e.title}”")
        } catch (ex: Exception) {
            say("Couldn't start the download")
        }
    }

    fun deleteDownload(id: String) {
        val wasCurrent = currentId == id
        Downloads.delete(context, lib, id)
        if (wasCurrent) say("Removed the download. It'll stream from now on.")
    }

    val downloadProgress = mutableStateMapOf<String, Float>()
    var bytesUsed by mutableLongStateOf(0L); private set

    /** Checks on downloads: the download manager's work happens off the main thread, the results land on it. */
    private suspend fun pollDownloads() {
        if (lib.states().none { it.downloadId != 0L } && downloadProgress.isEmpty()) return
        val (result, progress, used) = withContext(Dispatchers.IO) {
            val r = runCatching { Downloads.reconcile(context, lib) }.getOrNull()
            Triple(r, runCatching { Downloads.progress(context, lib) }.getOrDefault(emptyMap()), Downloads.bytesUsed(context))
        }
        if (result != null && result.second.isNotEmpty()) say("A download stopped. Check your connection and try again.")
        bytesUsed = used
        downloadProgress.keys.retainAll(progress.keys)
        downloadProgress.putAll(progress)
    }

    // ----- Chapters and transcripts -----

    val chapterLoads = mutableStateMapOf<String, Load<List<Chapter>>>()
    val transcripts = mutableStateMapOf<String, Load<Transcript>>()

    /** An episode's chapters: from its feed, its chapters file (fetched once), or the audio file. */
    fun chaptersOf(e: Episode): List<Chapter> {
        tick
        val stored = lib.episode(e.id) ?: e
        if (stored.chapters.isNotEmpty()) return stored.chapters
        (chapterLoads[e.id] as? Load.Ready)?.value?.let { if (it.isNotEmpty()) return it }
        return lib.state(e.id)?.fileChapters.orEmpty()
    }

    /** Fetches the episode's chapters file, if it has one we haven't read yet. */
    fun ensureChapters(e: Episode) {
        val stored = lib.episode(e.id) ?: e
        if (stored.chapters.isNotEmpty() || chapterLoads[e.id] != null) return
        val url = stored.chaptersUrl ?: return
        chapterLoads[e.id] = Load.Loading
        viewModelScope.launch {
            chapterLoads[e.id] = try {
                val list = withContext(Dispatchers.IO) { ChaptersJson.parse(Http.get(url), url) }
                if (list.isNotEmpty() && lib.episode(e.id) != null) lib.updateEpisode(stored.copy(chapters = list))
                Load.Ready(list)
            } catch (ex: Exception) {
                Load.Failed(friendlyError(ex))
            }
        }
    }

    fun loadTranscript(e: Episode) {
        val ref = e.bestTranscript ?: return
        if (transcripts[e.id] is Load.Ready || transcripts[e.id] is Load.Loading) return
        transcripts[e.id] = Load.Loading
        viewModelScope.launch {
            transcripts[e.id] = try {
                Load.Ready(withContext(Dispatchers.IO) { TranscriptParser.parse(Http.get(ref.url), ref.type) })
            } catch (ex: Exception) {
                Load.Failed(friendlyError(ex))
            }
        }
    }

    // ----- Bookmarks -----

    fun addBookmark(note: String = "") {
        val e = current ?: return
        lib.addBookmark(e, positionMs, note)
        say("Bookmarked at ${formatClock(positionMs)}")
    }

    fun editBookmark(id: String, note: String) = lib.updateBookmark(id, note)

    fun removeBookmark(id: String) = lib.removeBookmark(id)

    // ----- Sharing -----

    /** A share sheet for an episode, optionally from a moment in it. */
    fun shareIntent(e: Episode, atMs: Long? = null): Intent {
        val show = podcastTitle(e.podcastId)
        val link = e.link ?: lib.podcast(e.podcastId)?.link ?: e.audioUrl
        val text = buildString {
            append("“").append(e.title).append("”")
            if (show.isNotEmpty()) append(" — ").append(show)
            if (atMs != null && atMs > 0) append("\nListen from ").append(formatClock(atMs))
            append("\n").append(link)
            if (atMs == null || atMs <= 0) append("\n\nFeed: ").append(e.podcastId)
        }
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, e.title),
            "Share episode",
        )
    }

    // ----- Filters -----

    val filterResults = mutableStateMapOf<String, Load<List<Episode>>>()

    fun loadFilter(f: EpisodeFilter) {
        filterResults[f.id] = (filterResults[f.id] as? Load.Ready) ?: Load.Loading
        viewModelScope.launch {
            filterResults[f.id] = Load.Ready(withContext(Dispatchers.IO) { lib.filterEpisodes(f) })
        }
    }

    fun saveFilter(f: EpisodeFilter) {
        lib.saveFilter(f)
        loadFilter(f)
    }

    fun newFilter(): EpisodeFilter = EpisodeFilter(UUID.randomUUID().toString(), "")

    fun deleteFilter(id: String) {
        lib.deleteFilter(id)
        filterResults.remove(id)
        if ((screens.lastOrNull() as? Screen.FilterPage)?.filterId == id) back()
    }

    // ----- Alerts -----

    fun addAlert(term: String) {
        if (term.isBlank()) return
        lib.addAlert(term)
        say("You'll hear about new episodes that mention “${term.trim()}”")
        // Show what's out there already.
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Directory.searchEpisodes("\"${term.trim()}\"", limit = 25) }
                val needle = term.trim().lowercase()
                lib.addHits(
                    found.filter { it.episode.title.lowercase().contains(needle) || it.episode.description.lowercase().contains(needle) }
                        .map { AlertHit(term.trim(), it.episode, it.podcast, System.currentTimeMillis()) },
                )
            } catch (e: Exception) {
                // Shown on the next refresh instead.
            }
        }
    }

    fun removeAlert(term: String) = lib.removeAlert(term)

    fun clearHits() = lib.clearHits()

    // ----- Discover -----

    var topShows by mutableStateOf<Load<List<DirectoryPodcast>>>(Load.Loading); private set
    val categoryCharts = mutableStateMapOf<Int, Load<List<DirectoryPodcast>>>()
    var query by mutableStateOf("")
    var searchMode by mutableStateOf(SearchMode.SHOWS)
    var searching by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    var showResults by mutableStateOf<List<DirectoryPodcast>>(emptyList()); private set
    var episodeResults by mutableStateOf<List<DirectoryEpisode>>(emptyList()); private set
    var myResults by mutableStateOf<List<Episode>>(emptyList()); private set
    var searchedFor by mutableStateOf<String?>(null); private set
    private var searchJob: Job? = null

    fun loadDiscover() {
        if (topShows is Load.Ready) return
        topShows = Load.Loading
        viewModelScope.launch {
            topShows = try {
                Load.Ready(withContext(Dispatchers.IO) { Directory.top() })
            } catch (e: Exception) {
                Load.Failed(friendlyError(e))
            }
        }
    }

    fun retryDiscover() {
        topShows = Load.Failed("")
        loadDiscover()
    }

    fun loadCategory(c: Category, force: Boolean = false) {
        if (!force && categoryCharts[c.id] is Load.Ready) return
        categoryCharts[c.id] = Load.Loading
        viewModelScope.launch {
            categoryCharts[c.id] = try {
                Load.Ready(withContext(Dispatchers.IO) { Directory.top(c.id) })
            } catch (e: Exception) {
                Load.Failed(friendlyError(e))
            }
        }
    }

    val categories get() = CATEGORIES

    // ----- Suggestions -----

    /** A row of suggested shows, with why they're suggested. */
    data class Suggestion(val title: String, val shows: List<DirectoryPodcast>, val category: Category?)

    var suggestions by mutableStateOf<List<Suggestion>>(emptyList()); private set
    var suggestionsLoading by mutableStateOf(false); private set
    private var suggestionsFor: String? = null

    /**
     * Suggestions from the top charts of the categories you listen to most (subcategories like
     * "Careers" count for their category), leaving out shows you already follow. With nothing
     * followed yet, what's popular in your country.
     */
    fun loadSuggestions(force: Boolean = false) {
        val subs = lib.subscriptions()
        val key = subs.map { it.id }.sorted().joinToString("|")
        if (!force && key == suggestionsFor && (suggestions.isNotEmpty() || suggestionsLoading)) return
        suggestionsFor = key
        suggestionsLoading = true
        viewModelScope.launch {
            try {
                val followed = subs.mapTo(HashSet()) { it.podcast.title.lowercase().trim() }
                val ranked = subs.flatMap { e -> e.podcast.categories.mapNotNull(::categoryNamed).distinct() }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(3)
                val sections: List<Category?> = if (ranked.size >= 2) ranked else ranked + listOf(null)
                val charts = sections.map { c ->
                    async(Dispatchers.IO) { c to runCatching { Directory.top(c?.id) }.getOrDefault(emptyList()) }
                }.awaitAll()
                val seen = HashSet<Long>()
                suggestions = charts.mapNotNull { (c, shows) ->
                    val fresh = shows.filter { it.title.lowercase().trim() !in followed && seen.add(it.appleId) }.take(15)
                    if (fresh.isEmpty()) null
                    else Suggestion(if (c == null) (if (subs.isEmpty()) "Popular right now" else "Popular everywhere") else "Top in ${c.name}", fresh, c)
                }
            } finally {
                suggestionsLoading = false
            }
        }
    }

    /** Searches the directory (or a feed address you typed). */
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (q.startsWith("http://") || q.startsWith("https://") || q.startsWith("feed:") || q.startsWith("itpc:") || q.startsWith("pcast:")) {
            openLink(q)
            return
        }
        searchJob?.cancel()
        searchedFor = q
        searching = true
        searchError = null
        searchJob = viewModelScope.launch {
            try {
                when (searchMode) {
                    SearchMode.SHOWS -> showResults = withContext(Dispatchers.IO) { Directory.searchPodcasts(q) }
                    SearchMode.EPISODES -> episodeResults = withContext(Dispatchers.IO) { Directory.searchEpisodes(q) }
                    SearchMode.MY_EPISODES -> myResults = withContext(Dispatchers.IO) { lib.searchEpisodes(q) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchError = friendlyError(e)
            } finally {
                searching = false
            }
        }
    }

    fun setMode(m: SearchMode) {
        searchMode = m
        if (searchedFor != null) search()
    }

    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        searchedFor = null
        searching = false
        searchError = null
        showResults = emptyList()
        episodeResults = emptyList()
        myResults = emptyList()
    }

    /** Searches episodes of every podcast for a name (a guest or host you tapped). */
    fun searchPerson(name: String) {
        selectSection(Section.DISCOVER)
        query = name
        searchMode = SearchMode.EPISODES
        search()
    }

    /** Opens a feed address, Apple Podcasts link or itpc:// / pcast:// / feed:// link. */
    fun openLink(raw: String) {
        val link = raw.trim().substringBefore(' ').trim()
        val apple = Directory.appleIdIn(link)
        if (apple != null) {
            selectSection(Section.DISCOVER)
            open(Screen.PodcastPage(null, DirectoryPodcast(apple, "", "", null, null)))
            return
        }
        val url = when {
            link.startsWith("feed://") -> "https://" + link.removePrefix("feed://")
            link.startsWith("feed:") -> link.removePrefix("feed:")
            link.startsWith("itpc://") -> "https://" + link.removePrefix("itpc://")
            link.startsWith("pcast://") -> "https://" + link.removePrefix("pcast://")
            link.startsWith("podcast://") -> "https://" + link.removePrefix("podcast://")
            else -> link
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            say("That isn't a podcast link")
            return
        }
        selectSection(Section.DISCOVER)
        open(Screen.PodcastPage(lib.findByFeed(url)?.id ?: url))
    }

    /** Links and notifications that opened the app. */
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(Notifications.EXTRA_EPISODE)?.let { id ->
            intent.removeExtra(Notifications.EXTRA_EPISODE)
            val e = lib.episode(id) ?: lib.hits().firstOrNull { it.episode.id == id }?.episode
            if (e != null) open(Screen.EpisodePage(e.id, e))
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { openLink(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("(https?|feed|itpc|pcast|podcast)://\\S+").find(text)?.let { openLink(it.value) } ?: say("No podcast link found in what was shared")
            }
        }
        intent.action = null
    }

    // ----- OPML -----

    var importing by mutableStateOf<String?>(null); private set

    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                }
                val outlines = Opml.parse(text).filter { lib.findByFeed(it.feedUrl)?.subscribed != true }
                if (outlines.isEmpty()) {
                    say("No new shows in that file")
                    return@launch
                }
                var done = 0
                var failed = 0
                val gate = Semaphore(4)
                outlines.map { o ->
                    async {
                        gate.withPermit {
                            try {
                                Refresher.subscribe(context, o.feedUrl)
                            } catch (e: Exception) {
                                failed++
                            }
                            done++
                            importing = "Importing… $done of ${outlines.size}"
                        }
                    }
                }.awaitAll()
                say(if (failed == 0) "Imported ${outlines.size} shows" else "Imported ${outlines.size - failed} shows; $failed couldn't be reached")
            } catch (e: Exception) {
                say("That file couldn't be read as OPML")
            } finally {
                importing = null
            }
        }
    }

    fun exportOpml(uri: Uri) {
        viewModelScope.launch {
            try {
                val xml = Opml.write(lib.subscriptions().map { it.podcast })
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                }
                say("Exported ${lib.subscriptions().size} shows")
            } catch (e: Exception) {
                say("Couldn't save the file")
            }
        }
    }

    // ----- Settings -----

    var settingsTick by mutableIntStateOf(0); private set

    fun setting(key: String, value: Any) {
        val e = prefs.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
        settingsTick++
        if (key == Settings.REFRESH_HOURS) Refresher.schedule(context)
    }

    fun prefs(): android.content.SharedPreferences {
        settingsTick
        return prefs
    }

    fun stats(): Stats {
        tick
        return lib.stats()
    }

    fun resetStats() = lib.resetStats()

    // ----- Artwork colours -----

    val artColors = mutableStateMapOf<String, Color>()

    /** The artwork's most vivid colour, for backgrounds, once [loadArtColor] has found it. */
    fun artColor(url: String?): Color? = url?.let { artColors[it] }?.takeIf { it != Color.Unspecified }

    fun loadArtColor(url: String?) {
        if (url == null || artColors.containsKey(url)) return
        artColors[url] = Color.Unspecified
        viewModelScope.launch {
            try {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(url).allowHardware(false).size(96).build(),
                )
                val bitmap = result.drawable?.toBitmap() ?: return@launch
                val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
                val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: return@launch
                artColors[url] = Color(swatch.rgb)
            } catch (e: Exception) {
                // keep the default
            }
        }
    }

    // ----- Player -----

    var currentId by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    var sleepLeftMs by mutableLongStateOf(0L); private set
    var sleepEndOfEpisode by mutableStateOf(false); private set
    var sleepEndOfChapter by mutableStateOf(false); private set

    val current: Episode?
        get() {
            tick
            return currentId?.let { lib.episode(it) }
        }

    private var controller: MediaController? = null
    private var controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer()

        override fun onPlayerError(error: PlaybackException) {
            say(
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) "No connection. Download episodes to listen offline."
                else "Couldn't play this episode. Press play to try again.",
            )
        }
    }

    init {
        connect()
        viewModelScope.launch {
            lib.version.debounce(120).collect {
                tick = it
                snap = withContext(Dispatchers.IO) { buildSnapshot() }
            }
        }
        viewModelScope.launch {
            var n = 0
            while (true) {
                controller?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0)
                    durationMs = it.duration.let { d -> if (d == C.TIME_UNSET || d < 0) 0 else d }
                }
                val until = SleepTimer.until(context)
                sleepLeftMs = if (until > 0) (until - System.currentTimeMillis()).coerceAtLeast(0) else 0
                sleepEndOfEpisode = SleepTimer.endOfEpisode(context)
                sleepEndOfChapter = SleepTimer.endOfChapter(context)
                if (++n % 4 == 0) pollDownloads()
                delay(500)
            }
        }
        viewModelScope.launch(Dispatchers.IO) { bytesUsed = Downloads.bytesUsed(context) }
        Refresher.schedule(context)
        // Fresh episodes when you open the app, if the last look was a while ago.
        if (lib.subscriptions().isNotEmpty() && System.currentTimeMillis() - prefs.getLong("last_refresh", 0) > 30 * 60_000L) {
            viewModelScope.launch {
                Refresher.refreshAll(context, notify = false)
                prefs.edit().putLong("last_refresh", System.currentTimeMillis()).apply()
            }
        }
    }

    private fun connect() {
        viewModelScope.launch {
            val c = try {
                controllerFuture.await()
            } catch (e: Exception) {
                return@launch
            }
            controller = c
            c.addListener(playerListener)
            syncFromPlayer()
        }
    }

    /** Reconnects to the player if it was shut down (after the app sat idle). */
    private fun ensureController() {
        val c = controller
        if (c != null && !c.isConnected) {
            c.removeListener(playerListener)
            c.release()
            controller = null
            controllerFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            connect()
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        currentId = c.currentMediaItem?.mediaId
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady
        positionMs = c.currentPosition.coerceAtLeast(0)
        durationMs = c.duration.let { d -> if (d == C.TIME_UNSET || d < 0) 0 else d }
        speed = c.playbackParameters.speed
    }

    private fun resume(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        c.play()
    }

    fun togglePlay() {
        ensureController()
        val c = controller ?: return
        if (c.isPlaying) c.pause() else resume(c)
    }

    fun seekBack() = controller?.seekBack()
    fun seekForward() = controller?.seekForward()
    fun seekTo(ms: Long) = controller?.seekTo(ms)

    /** Skips to the next episode in Up Next, leaving this one unfinished. */
    fun nextEpisode() {
        val id = currentId ?: return
        val next = lib.queue().getOrNull(1)?.let { lib.episode(it) }
        if (next == null) {
            say("Nothing else in Up Next")
            return
        }
        lib.playNow(next, play = isPlaying)
        lib.removeFromQueue(id)
    }

    fun chapterIndex(chapters: List<Chapter>): Int = chapters.indexAt(positionMs + 250)

    fun nextChapter(chapters: List<Chapter>) {
        val i = chapterIndex(chapters)
        chapters.getOrNull(i + 1)?.let { seekTo(it.startMs) }
    }

    fun previousChapter(chapters: List<Chapter>) {
        val i = chapterIndex(chapters)
        if (i < 0) return
        val start = chapters[i].startMs
        // Like a CD player: back to the start of this chapter, or the one before if it just started.
        seekTo(if (positionMs - start > 3_000 || i == 0) start else chapters[i - 1].startMs)
    }

    /** Speed for the episode playing: saved to its show if the show has custom effects, else app-wide. */
    fun changeSpeed(value: Float) {
        val show = current?.podcastId?.let { lib.entry(it) }
        if (show?.settings?.customEffects == true) lib.updateSettings(show.id) { it.copy(speed = value) }
        else setting(Settings.SPEED, value)
        speed = value
    }

    fun effects(): Settings.Effects {
        settingsTick
        tick
        return Settings.effectsFor(prefs, current?.podcastId?.let { lib.entry(it)?.settings })
    }

    fun showHasCustomEffects(): Boolean = current?.podcastId?.let { entry(it)?.settings?.customEffects } == true

    fun setTrimSilence(on: Boolean) {
        val show = current?.podcastId?.let { lib.entry(it) }
        if (show?.settings?.customEffects == true) lib.updateSettings(show.id) { it.copy(trimSilence = on) } else setting(Settings.TRIM_SILENCE, on)
    }

    fun setBoost(level: Int) {
        val show = current?.podcastId?.let { lib.entry(it) }
        if (show?.settings?.customEffects == true) lib.updateSettings(show.id) { it.copy(boostLevel = level) } else setting(Settings.BOOST_LEVEL, level)
    }

    /** Turns this show's own effects on (starting from the current ones) or off. */
    fun setCustomEffects(on: Boolean) {
        val id = current?.podcastId ?: return
        val fx = effects()
        lib.updateSettings(id) {
            if (on) it.copy(customEffects = true, speed = fx.speed, trimSilence = fx.trimSilence, boostLevel = fx.boostLevel) else it.copy(customEffects = false)
        }
    }

    fun setSleep(minutes: Int) {
        SleepTimer.set(context, minutes)
        sleepEndOfEpisode = minutes == SleepTimer.END_OF_EPISODE
        sleepEndOfChapter = minutes == SleepTimer.END_OF_CHAPTER
        sleepLeftMs = if (minutes > 0) minutes * 60_000L else 0L
        say(
            when {
                minutes > 0 -> "Sleep timer: $minutes min"
                minutes == SleepTimer.END_OF_EPISODE -> "Pausing at the end of this episode"
                minutes == SleepTimer.END_OF_CHAPTER -> "Pausing at the end of this chapter"
                else -> "Sleep timer off"
            },
        )
    }

    fun extendSleep() {
        SleepTimer.extend(context, 5)
        say("Five more minutes")
    }
}
