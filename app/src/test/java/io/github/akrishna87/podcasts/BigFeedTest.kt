package io.github.akrishna87.podcasts

import io.github.akrishna87.podcasts.feed.CleaningReader
import io.github.akrishna87.podcasts.feed.FeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class BigFeedTest {

    /** A feed generated as it's read, like a big show streaming in, without building it in memory. */
    private class GeneratedFeed(private val items: Int, private val notesChars: Int, private val oldestFirst: Boolean) : Reader() {
        private var part = -1
        private var current = ""
        private var pos = 0

        private fun next(): Boolean {
            part++
            current = when {
                part == 0 -> """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
<channel><title>How to Be Awesome at Your Job</title><itunes:author>Pete</itunes:author>"""
                part <= items -> {
                    val n = if (oldestFirst) part else items - part + 1
                    val day = 86_400L * n
                    val date = java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(1_500_000_000_000L + day * 1000))
                    "<item><title>Episode $n: Q&A &mdash; skills</title><guid>ep-$n</guid><pubDate>$date</pubDate>" +
                        "<enclosure url=\"https://cdn.example.com/$n.mp3?x=1&y=2\" length=\"1234567\" type=\"audio/mpeg\"/>" +
                        "<content:encoded><![CDATA[<p>" + "Notes &nbsp; with words. ".repeat(notesChars / 25) + "</p>]]></content:encoded></item>\n"
                }
                part == items + 1 -> "</channel></rss>"
                else -> return false
            }
            pos = 0
            return true
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            while (pos >= current.length) if (!next()) return -1
            val n = minOf(len, current.length - pos)
            current.toCharArray(cbuf, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() {}
    }

    @Test
    fun hugeFeedsKeepTheNewestEpisodes() {
        // About 30 MB: 1,500 episodes with 20,000 characters of notes each.
        val started = System.currentTimeMillis()
        val feed = FeedParser.parse(GeneratedFeed(1_500, 20_000, oldestFirst = true), "https://example.com/awesome")
        val took = System.currentTimeMillis() - started
        assertEquals("How to Be Awesome at Your Job", feed.podcast.title)
        assertEquals(FeedParser.MAX_EPISODES, feed.episodes.size)
        assertEquals("Episode 1500: Q&A — skills", feed.episodes.first().title)
        assertEquals("Episode 1001: Q&A — skills", feed.episodes.last().title)
        assertEquals("https://cdn.example.com/1500.mp3?x=1&y=2", feed.episodes.first().audioUrl)
        assertTrue(feed.episodes.first().description.length <= 12_000)
        assertTrue("took $took ms", took < 20_000)
    }

    @Test
    fun hugeNewestFirstFeeds() {
        val feed = FeedParser.parse(GeneratedFeed(1_200, 5_000, oldestFirst = false), "https://example.com/awesome")
        assertEquals(500, feed.episodes.size)
        assertEquals("Episode 1200: Q&A — skills", feed.episodes.first().title)
    }

    @Test
    fun messyFeedsStillRead() {
        val xml = """<?xml version="1.0"?>
<!DOCTYPE rss [ <!ENTITY foo "bar"> ]>
<rss version="2.0"><channel><title>Tom &amp; Jerry&rsquo;s &copy; Show &unknownthing; Q&A</title>
<item><title>One</title><enclosure url="https://e.com/1.mp3" type="application/octet-stream"/>
<description>Has <b>bold</b> HTML</description></item>
<item><title>Not audio</title><enclosure url="https://e.com/doc.pdf" type="application/pdf"/></item>
<item><title>No type</title><enclosure url="https://e.com/2.m4a"/></item>
</channel></rss>"""
        val feed = FeedParser.parse(xml, "https://e.com/feed")
        assertEquals("Tom & Jerry’s © Show Q&A", feed.podcast.title)
        assertEquals(listOf("One", "No type"), feed.episodes.map { it.title })
        assertEquals("audio/mpeg", feed.episodes[0].mimeType)
        assertEquals("audio/mp4", feed.episodes[1].mimeType)
        assertEquals("Has <b>bold</b> HTML", feed.episodes[0].description)
    }

    @Test
    fun feedsCutOffPartWayKeepWhatArrived() {
        val xml = """<rss version="2.0"><channel><title>Cut</title>
<item><title>A</title><enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
<item><title>B</title><enclosure url="https://e.com/b.mp"""
        assertEquals(listOf("A"), FeedParser.parse(xml, "https://e.com/feed").episodes.map { it.title })
    }

    @Test
    fun cleaningWorksAcrossReadBoundaries() {
        // Entities and CDATA markers straddling the reader's internal blocks.
        val sb = StringBuilder("<r>")
        repeat(20_000) { sb.append("a&nbsp;b &amp; c & d <![CDATA[&nbsp; & ]]>") }
        sb.append("</r>")
        val cleaned = CleaningReader(OneCharAtATime(sb.toString())).readText()
        val expected = StringBuilder("<r>")
        repeat(20_000) { expected.append("a b &amp; c &amp; d <![CDATA[&nbsp; & ]]>") }
        expected.append("</r>")
        assertEquals(expected.toString(), cleaned)
    }

    /** A reader that hands out odd-sized pieces, to shake out boundary bugs. */
    private class OneCharAtATime(text: String) : Reader() {
        private val inner = StringReader(text)
        private var turn = 0
        override fun read(cbuf: CharArray, off: Int, len: Int): Int = inner.read(cbuf, off, minOf(len, listOf(1, 7, 4093, 65_536)[turn++ % 4]))
        override fun close() {}
    }
}
