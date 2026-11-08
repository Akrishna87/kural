package io.github.akrishna87.podcasts

import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.data.PartOfDay
import io.github.akrishna87.podcasts.data.PlayDay
import io.github.akrishna87.podcasts.data.Podcast
import io.github.akrishna87.podcasts.data.Recap
import io.github.akrishna87.podcasts.data.RecapPeriod
import io.github.akrishna87.podcasts.feed.ParsedFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RecapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ep(show: String, n: Int) = Episode(
        id = Episode.idFor(show, "g$n"),
        podcastId = show,
        guid = "g$n",
        title = "$show episode $n",
        audioUrl = "https://example.com/$n.mp3",
        publishedAt = n * 86_400_000L,
        durationSec = 600L,
    )

    private fun at(date: String, hour: Int) = ZonedDateTime.of(LocalDate.parse(date).atTime(hour, 15), ZoneOffset.UTC)

    private fun day(date: String, show: String, episode: String, minutes: Long, hour: Int = 9) = PlayDay(
        day = LocalDate.parse(date),
        podcastId = show,
        episodeId = episode,
        podcastTitle = show,
        episodeTitle = episode,
        ms = minutes * 60_000,
        parts = PartOfDay.entries.map { if (it == PartOfDay.of(hour)) minutes * 60_000 else 0L },
    )

    @Test
    fun monthBoundariesAreRespected() {
        val today = LocalDate.parse("2026-03-15")
        val log = listOf(
            day("2026-01-31", "a", "a1", 10),
            day("2026-02-01", "a", "a1", 20),
            day("2026-02-28", "a", "a2", 30),
            day("2026-03-01", "b", "b1", 40),
            day("2026-03-31", "b", "b1", 50),
            day("2025-12-31", "b", "b2", 60),
        )
        assertEquals(90 * 60_000L, Recap.of(log, RecapPeriod.THIS_MONTH, today).ms)
        assertEquals(50 * 60_000L, Recap.of(log, RecapPeriod.LAST_MONTH, today).ms)
        assertEquals(150 * 60_000L, Recap.of(log, RecapPeriod.THIS_YEAR, today).ms)
        assertEquals(210 * 60_000L, Recap.of(log, RecapPeriod.ALL_TIME, today).ms)
        // Last month from January is December of the year before.
        assertEquals(60 * 60_000L, Recap.of(log, RecapPeriod.LAST_MONTH, LocalDate.parse("2026-01-05")).ms)
        val march = Recap.of(log, RecapPeriod.THIS_MONTH, today)
        assertEquals(1, march.episodes)
        assertEquals(2, march.daysListened)
    }

    @Test
    fun topShowsAreOrderedByListeningTime() {
        val log = listOf(
            day("2026-03-02", "short", "s1", 5),
            day("2026-03-02", "long", "l1", 30),
            day("2026-03-03", "long", "l2", 30),
            day("2026-03-03", "middle", "m1", 45),
            day("2026-03-04", "tie-b", "t1", 5),
        )
        val recap = Recap.of(log, RecapPeriod.ALL_TIME, LocalDate.parse("2026-03-10"), showTitle = { if (it == "long") "The Long Show" else null })
        assertEquals(listOf("long", "middle", "short", "tie-b"), recap.topShows.map { it.id })
        assertEquals("The Long Show", recap.topShows.first().name)
        assertEquals(2, recap.topShows.first().count)
        assertEquals(listOf("m1", "l1", "l2", "s1", "t1"), recap.topEpisodes.map { it.id })
        assertEquals(2, Recap.of(log, RecapPeriod.ALL_TIME, LocalDate.parse("2026-03-10"), top = 2).topShows.size)
    }

    @Test
    fun timeOfDayAndWeekdayBuckets() {
        assertEquals(PartOfDay.NIGHT, PartOfDay.of(4))
        assertEquals(PartOfDay.MORNING, PartOfDay.of(5))
        assertEquals(PartOfDay.MORNING, PartOfDay.of(11))
        assertEquals(PartOfDay.AFTERNOON, PartOfDay.of(12))
        assertEquals(PartOfDay.AFTERNOON, PartOfDay.of(16))
        assertEquals(PartOfDay.EVENING, PartOfDay.of(17))
        assertEquals(PartOfDay.EVENING, PartOfDay.of(20))
        assertEquals(PartOfDay.NIGHT, PartOfDay.of(21))
        assertEquals(PartOfDay.NIGHT, PartOfDay.of(0))

        // 2026-03-03 is a Tuesday, 2026-03-07 a Saturday.
        val log = listOf(
            day("2026-03-03", "a", "a1", 20, hour = 19),
            day("2026-03-07", "a", "a2", 15, hour = 8),
            day("2026-03-07", "a", "a3", 10, hour = 23),
        )
        val recap = Recap.of(log, RecapPeriod.THIS_MONTH, LocalDate.parse("2026-03-20"))
        assertEquals(DayOfWeek.SATURDAY, recap.busiestDay)
        assertEquals(PartOfDay.EVENING, recap.partOfDay)
        assertEquals(20 * 60_000L, recap.byWeekday[DayOfWeek.TUESDAY.value - 1])
        assertEquals(25 * 60_000L, recap.byWeekday[DayOfWeek.SATURDAY.value - 1])
        assertEquals(listOf(15L, 0L, 20L, 10L).map { it * 60_000 }, recap.byPart)

        val empty = Recap.of(log, RecapPeriod.LAST_MONTH, LocalDate.parse("2026-03-20"))
        assertTrue(empty.isEmpty)
        assertNull(empty.busiestDay)
        assertNull(empty.partOfDay)
    }

    @Test
    fun theLibraryLogsListeningPerEpisodePerDayAndKeepsIt() {
        val lib = Library(tmp.root, writer = null)
        val show = "https://example.com/feed"
        lib.storeFeed(ParsedFeed(Podcast(show, "Test Show"), listOf(ep(show, 2), ep(show, 1))), subscribe = true)
        val e1 = ep(show, 1)
        lib.playNow(e1)
        // Two stretches the same morning at 1.5x, one the next evening.
        lib.addListening(show, wallMs = 60_000, audioMs = 90_000, episodeId = e1.id, at = at("2026-03-02", 8))
        lib.addListening(show, wallMs = 60_000, audioMs = 90_000, episodeId = e1.id, at = at("2026-03-02", 9))
        lib.addListening(show, wallMs = 30_000, audioMs = 30_000, episodeId = e1.id, at = at("2026-03-03", 18))
        lib.addSkipped(20_000, e1.id, at = at("2026-03-03", 18))
        assertTrue(lib.finishFront(e1.id, completed = true))

        val log = lib.playLog()
        // Finishing is logged on the day it happens (today, by the real clock).
        assertEquals(3, log.size)
        assertTrue(log.last().finished)
        assertEquals("Test Show", log.first().podcastTitle)
        assertEquals("${show} episode 1", log.first().episodeTitle)
        assertEquals(120_000L, log.first().ms)
        assertEquals(60_000L, log.first().savedMs)
        assertEquals(20_000L, log[1].skippedMs)
        // The stats keep their all-time totals as before.
        assertEquals(150_000L, lib.stats().listenedMs)

        val again = Library(tmp.root, writer = null)
        val recap = Recap.of(again.playLog(), RecapPeriod.THIS_MONTH, LocalDate.parse("2026-03-20"))
        assertEquals(150_000L, recap.ms)
        assertEquals(80_000L, recap.timeSaved)
        assertEquals(1, recap.episodes)
        assertEquals(2, recap.daysListened)
        assertEquals(listOf(show), recap.topShows.map { it.id })
        assertEquals(PartOfDay.MORNING, recap.partOfDay)
        assertEquals(DayOfWeek.MONDAY, recap.busiestDay)

        again.resetStats()
        assertTrue(again.playLog().isEmpty())
    }
}
