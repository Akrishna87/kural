package io.github.akrishna87.podcasts

import io.github.akrishna87.podcasts.data.AutoAdd
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.EpisodeFilter
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.data.Podcast
import io.github.akrishna87.podcasts.feed.Directory
import io.github.akrishna87.podcasts.feed.ParsedFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val feedUrl = "https://example.com/feed"
    private val podcast = Podcast(feedUrl, "Show")

    private fun ep(n: Int, day: Int = n) = Episode(
        id = Episode.idFor(feedUrl, "g$n"),
        podcastId = feedUrl,
        guid = "g$n",
        title = "Episode $n",
        audioUrl = "https://example.com/$n.mp3",
        publishedAt = day * 86_400_000L,
        durationSec = n * 600L,
    )

    private fun library() = Library(tmp.root, writer = null)

    @Test
    fun subscribingDoesNotFloodTheInbox() {
        val lib = library()
        assertTrue(lib.storeFeed(ParsedFeed(podcast, listOf(ep(2), ep(1))), subscribe = true).isEmpty())
        val fresh = lib.storeFeed(ParsedFeed(podcast, listOf(ep(3), ep(2), ep(1))))
        assertEquals(listOf("Episode 3"), fresh.map { it.title })
        lib.markNew(fresh)
        assertEquals(listOf("Episode 3"), lib.newEpisodes().map { it.first.title })
    }

    @Test
    fun upNextPlaysInOrderAndForgetsFinishedEpisodes() {
        val lib = library()
        lib.storeFeed(ParsedFeed(podcast, listOf(ep(3), ep(2), ep(1))), subscribe = true)
        lib.playNow(ep(1))
        assertTrue(lib.consumePlayRequest())
        assertFalse(lib.consumePlayRequest())
        lib.playLast(ep(2))
        lib.playNext(ep(3))
        assertEquals(listOf(ep(1).id, ep(3).id, ep(2).id), lib.queue())
        lib.moveUpcoming(0, 1)
        assertEquals(listOf(ep(1).id, ep(2).id, ep(3).id), lib.queue())
        assertTrue(lib.finishFront(ep(1).id, completed = true))
        assertFalse(lib.finishFront(ep(1).id, completed = true))
        assertTrue(lib.state(ep(1).id)!!.played)
        assertEquals(1, lib.stats().finished)
        assertEquals(ep(2).id, lib.current()?.id)
        // Playing something else puts it in front; the one before stays next.
        lib.playNow(ep(3))
        assertEquals(listOf(ep(3).id, ep(2).id), lib.queue())
    }

    @Test
    fun everythingIsSavedAndReadBack() {
        val lib = library()
        lib.storeFeed(ParsedFeed(podcast, listOf(ep(2), ep(1))), subscribe = true)
        lib.updateSettings(feedUrl) { it.copy(skipIntroSec = 30, autoAdd = AutoAdd.TOP, customEffects = true, speed = 1.5f) }
        lib.playNow(ep(1))
        lib.savePosition(ep(1).id, 61_000, 600_000)
        lib.addBookmark(ep(1), 30_000, " Great quote ")
        lib.addAlert("Sam Guest")
        lib.addListening(feedUrl, wallMs = 60_000, audioMs = 90_000)
        lib.flush()

        val again = library()
        assertTrue(again.isSubscribed(feedUrl))
        assertEquals(30, again.entry(feedUrl)!!.settings.skipIntroSec)
        assertEquals(AutoAdd.TOP, again.entry(feedUrl)!!.settings.autoAdd)
        assertEquals(1.5f, again.entry(feedUrl)!!.settings.speed)
        assertEquals(listOf(ep(1).id), again.queue())
        assertEquals(61_000L, again.state(ep(1).id)!!.positionMs)
        assertEquals("Episode 1", again.episode(ep(1).id)?.title)
        assertEquals("Great quote", again.bookmarks(ep(1).id).single().note)
        assertEquals(listOf("Sam Guest"), again.alerts().map { it.term })
        assertEquals(30_000L, again.stats().savedMs)
        assertEquals(listOf(ep(1).id), again.inProgress().map { it.first.id })
    }

    @Test
    fun filtersPickMatchingEpisodes() {
        val lib = library()
        lib.storeFeed(ParsedFeed(podcast, listOf(ep(3), ep(2), ep(1))), subscribe = true)
        lib.markPlayed(ep(2).id, true)
        val unplayed = EpisodeFilter("f", "Unplayed")
        assertEquals(listOf("Episode 3", "Episode 1"), lib.filterEpisodes(unplayed).map { it.title })
        val short = EpisodeFilter("s", "Short", unplayedOnly = false, maxMinutes = 15)
        assertEquals(listOf("Episode 1"), lib.filterEpisodes(short).map { it.title })
        val recent = EpisodeFilter("r", "Recent", unplayedOnly = false, maxAgeDays = 2)
        assertEquals(listOf("Episode 3", "Episode 2"), lib.filterEpisodes(recent, now = 4 * 86_400_000L).map { it.title })
    }

    @Test
    fun autoplayFollowsTheShowsOrder() {
        val lib = library()
        lib.storeFeed(ParsedFeed(podcast.copy(serial = true), listOf(ep(3), ep(2), ep(1))), subscribe = true)
        assertEquals("Episode 2", lib.nextUnplayed(ep(1))?.title)
        lib.markPlayed(ep(2).id, true)
        assertEquals("Episode 3", lib.nextUnplayed(ep(1))?.title)
        assertNull(lib.nextUnplayed(ep(3)))
    }

    @Test
    fun unfollowedShowsAreForgottenUnlessUsed() {
        val lib = library()
        lib.storeFeed(ParsedFeed(podcast, listOf(ep(1))), subscribe = true)
        lib.setSubscribed(feedUrl, false)
        lib.updateState(ep(1)) { it.copy(starred = true) }
        assertFalse(lib.forgetIfUnused(feedUrl))
        lib.updateState(ep(1)) { it.copy(starred = false) }
        assertTrue(lib.forgetIfUnused(feedUrl))
        assertNull(lib.entry(feedUrl))
    }

    @Test
    fun searchResultsCanBeRemembered() {
        val lib = library()
        lib.remember(podcast, listOf(ep(5)))
        assertFalse(lib.isSubscribed(feedUrl))
        lib.playNow(ep(5))
        assertEquals("Episode 5", lib.current()?.title)
        // Following it later keeps the same episode (same id from the feed).
        assertTrue(lib.storeFeed(ParsedFeed(podcast, listOf(ep(6), ep(5))), subscribe = true).isEmpty())
        assertEquals(ep(5).id, lib.queue().single())
    }

    @Test
    fun parsesDirectoryAnswers() {
        val search = """{"resultCount":2,"results":[
            {"wrapperType":"track","kind":"podcast","collectionId":123,"collectionName":"Hard Fork","artistName":"NYT",
             "feedUrl":"https://feeds.example.com/hardfork","artworkUrl600":"https://img.example.com/600.jpg","primaryGenreName":"Technology","trackCount":150},
            {"wrapperType":"track","kind":"podcast","collectionId":124,"collectionName":"No feed"}]}"""
        val shows = Directory.parsePodcastSearch(search)
        assertEquals(1, shows.size)
        assertEquals("Hard Fork", shows[0].title)
        assertEquals("https://feeds.example.com/hardfork", shows[0].feedUrl)
        assertEquals(150, shows[0].episodeCount)

        val episodes = """{"results":[{"wrapperType":"podcastEpisode","kind":"podcast-episode","collectionId":123,
            "collectionName":"Hard Fork","feedUrl":"https://feeds.example.com/hardfork","trackName":"Guest week",
            "episodeUrl":"https://cdn.example.com/1.mp3","episodeGuid":"abc","releaseDate":"2025-06-10T04:00:00Z",
            "trackTimeMillis":3600000,"description":"With Sam","artworkUrl160":"https://img.example.com/160.jpg","episodeFileExtension":"mp3"}]}"""
        val found = Directory.parseEpisodeSearch(episodes).single()
        assertEquals("Guest week", found.episode.title)
        assertEquals(Episode.idFor("https://feeds.example.com/hardfork", "abc"), found.episode.id)
        assertEquals(3600L, found.episode.durationSec)
        assertEquals(1749528000000L, found.episode.publishedAt)
        assertEquals("Hard Fork", found.podcast.title)

        val chart = """{"feed":{"entry":[{"im:name":{"label":"Top Show"},"im:artist":{"label":"Someone"},
            "im:image":[{"label":"https://img.example.com/55x55bb.png","attributes":{"height":"55"}},
                        {"label":"https://img.example.com/170x170bb.png","attributes":{"height":"170"}}],
            "id":{"label":"https://podcasts.apple.com/us/podcast/x/id999","attributes":{"im:id":"999"}},
            "category":{"attributes":{"im:id":"1318","label":"Technology"}}}]}}"""
        val top = Directory.parseTopChart(chart).single()
        assertEquals(999L, top.appleId)
        assertEquals("Top Show", top.title)
        assertEquals("https://img.example.com/600x600bb.jpg", top.artworkUrl)
        assertEquals("Technology", top.genre)

        assertEquals(1528390055L, Directory.appleIdIn("https://podcasts.apple.com/us/podcast/hard-fork/id1528390055?i=1000"))
        assertNull(Directory.appleIdIn("https://example.com/feed"))
    }
}
