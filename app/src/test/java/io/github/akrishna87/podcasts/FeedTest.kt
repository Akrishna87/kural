package io.github.akrishna87.podcasts

import io.github.akrishna87.podcasts.feed.ChaptersJson
import io.github.akrishna87.podcasts.feed.FeedParser
import io.github.akrishna87.podcasts.feed.NoteLink
import io.github.akrishna87.podcasts.feed.Opml
import io.github.akrishna87.podcasts.feed.TranscriptParser
import io.github.akrishna87.podcasts.feed.indexAt
import io.github.akrishna87.podcasts.feed.parseClock
import io.github.akrishna87.podcasts.feed.parseDate
import io.github.akrishna87.podcasts.feed.parseDurationSec
import io.github.akrishna87.podcasts.feed.showNotes
import io.github.akrishna87.podcasts.data.Podcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {

    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
     xmlns:podcast="https://podcastindex.org/namespace/1.0"
     xmlns:psc="http://podlove.org/simple-chapters"
     xmlns:content="http://purl.org/rss/1.0/modules/content/"
     xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>Tech &amp; Talk Q&A</title>
    <link>https://example.com/show</link>
    <atom:link href="https://example.com/feed.xml" rel="self" type="application/rss+xml"/>
    <description>A show about things&nbsp;and stuff.</description>
    <itunes:author>Jane Host</itunes:author>
    <itunes:image href="https://example.com/art.jpg"/>
    <itunes:type>serial</itunes:type>
    <itunes:category text="Technology"><itunes:category text="Tech News"/></itunes:category>
    <podcast:funding url="https://example.com/support">Support the show</podcast:funding>
    <podcast:person role="host" img="https://example.com/jane.jpg">Jane Host</podcast:person>
    <item>
      <title>Episode 2: Guests</title>
      <guid isPermaLink="false">ep-2</guid>
      <pubDate>Tue, 10 Jun 2025 04:00:00 GMT</pubDate>
      <enclosure url="https://cdn.example.com/ep2.mp3" length="12345678" type="audio/mpeg"/>
      <itunes:duration>1:02:03</itunes:duration>
      <itunes:episode>2</itunes:episode>
      <itunes:season>1</itunes:season>
      <itunes:explicit>yes</itunes:explicit>
      <itunes:image href="/ep2.jpg"/>
      <description>Short one</description>
      <content:encoded><![CDATA[<p>We talk with <a href="https://guest.example.com">a guest</a>.</p><ul><li>(00:00) Intro</li><li>12:34 Main topic</li><li>1:01:00 Wrap up at 10:30 am</li></ul>]]></content:encoded>
      <podcast:chapters url="https://example.com/ep2.chapters.json" type="application/json+chapters"/>
      <podcast:transcript url="https://example.com/ep2.vtt" type="text/vtt"/>
      <podcast:transcript url="https://example.com/ep2.json" type="application/json" language="en"/>
      <podcast:person role="guest" href="https://guest.example.com">Sam Guest</podcast:person>
    </item>
    <item>
      <title>Episode 1</title>
      <pubDate>Mon, 2 Jun 2025 09:30:00 -0700</pubDate>
      <enclosure url="http://cdn.example.com/ep1.m4a" type="audio/x-m4a" length="0"/>
      <itunes:duration>754</itunes:duration>
      <description>First &amp; best</description>
      <psc:chapters version="1.2">
        <psc:chapter start="00:00:00.000" title="Hello"/>
        <psc:chapter start="05:30" title="Middle &amp; more" href="https://example.com/m"/>
      </psc:chapters>
    </item>
    <item>
      <title>A blog post without audio</title>
      <guid>post-1</guid>
    </item>
  </channel>
</rss>"""

    @Test
    fun readsTheShow() {
        val feed = FeedParser.parse(rss, "https://example.com/feed.xml")
        val p = feed.podcast
        assertEquals("Tech & Talk Q&A", p.title)
        assertEquals("Jane Host", p.author)
        assertEquals("https://example.com/art.jpg", p.artworkUrl)
        assertTrue(p.serial)
        assertEquals(listOf("Technology", "Tech News"), p.categories)
        assertEquals("https://example.com/support", p.fundingUrl)
        assertEquals("Support the show", p.fundingLabel)
        assertEquals("https://example.com/show", p.link)
    }

    @Test
    fun readsEpisodes() {
        val feed = FeedParser.parse(rss, "https://example.com/feed.xml")
        assertEquals(2, feed.episodes.size)
        val (two, one) = feed.episodes
        assertEquals("Episode 2: Guests", two.title)
        assertEquals("ep-2", two.guid)
        assertEquals(3723L, two.durationSec)
        assertEquals(12345678L, two.sizeBytes)
        assertEquals(2, two.number)
        assertEquals(1, two.season)
        assertTrue(two.explicit)
        assertEquals("https://example.com/ep2.jpg", two.artworkUrl)
        assertTrue(two.description.contains("guest.example.com"))
        assertEquals("https://example.com/ep2.chapters.json", two.chaptersUrl)
        assertEquals(2, two.transcripts.size)
        assertEquals("application/json", two.bestTranscript?.type)
        assertEquals(listOf("Sam Guest"), two.persons.map { it.name })
        assertEquals("guest", two.persons[0].role)
        assertEquals(1749528000000L, two.publishedAt)

        assertEquals("http://cdn.example.com/ep1.m4a", one.guid) // no guid: the audio address stands in
        assertEquals(754L, one.durationSec)
        assertEquals(0L, one.sizeBytes)
        assertEquals("audio/x-m4a", one.mimeType)
        assertEquals(listOf(0L, 330_000L), one.chapters.map { it.startMs })
        assertEquals("Middle & more", one.chapters[1].title)
        // The show's host stands in when an episode names nobody.
        assertEquals(listOf("Jane Host"), one.persons.map { it.name })
    }

    @Test
    fun idsAreStableAcrossHttpAndHttps() {
        val a = FeedParser.parse(rss, "https://example.com/feed.xml").episodes[0]
        val b = FeedParser.parse(rss, "http://example.com/feed.xml/").episodes[0]
        assertEquals(a.id, b.id)
    }

    @Test
    fun readsAtom() {
        val atom = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Atom Cast</title>
  <author><name>Ann</name></author>
  <logo>https://example.com/logo.png</logo>
  <entry>
    <id>urn:1</id>
    <title>First</title>
    <published>2024-03-01T10:00:00Z</published>
    <link rel="enclosure" href="https://example.com/1.mp3" type="audio/mpeg" length="100000"/>
    <summary>Hi</summary>
  </entry>
</feed>"""
        val feed = FeedParser.parse(atom, "https://example.com/atom")
        assertEquals("Atom Cast", feed.podcast.title)
        assertEquals("Ann", feed.podcast.author)
        assertEquals(1, feed.episodes.size)
        assertEquals("https://example.com/1.mp3", feed.episodes[0].audioUrl)
        assertEquals(1709287200000L, feed.episodes[0].publishedAt)
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsHtmlPages() {
        FeedParser.parse("<html><body>Not a feed</body></html>", "https://example.com")
    }

    @Test
    fun parsesDates() {
        assertEquals(1749528000000L, parseDate("Tue, 10 Jun 2025 04:00:00 GMT"))
        assertEquals(1749528000000L, parseDate("Tue, 10 Jun 2025 00:00:00 EDT"))
        assertEquals(1749528000000L, parseDate("10 Jun 2025 04:00:00 +0000"))
        assertEquals(1749528000000L, parseDate("Tuesday, 10 June 2025 04:00:00 GMT"))
        assertEquals(1749528000000L, parseDate("2025-06-10T04:00:00Z"))
        assertEquals(1749528000000L, parseDate("2025-06-10T06:00:00.000+02:00"))
        assertEquals(1749528000000L, parseDate("Tue, 10 Jun 2025 04:00 GMT"))
        assertEquals(0L, parseDate("not a date"))
    }

    @Test
    fun parsesClocks() {
        assertEquals(3_723_000L, parseClock("1:02:03"))
        assertEquals(754_000L, parseClock("12:34"))
        assertEquals(1_500L, parseClock("00:00:01.500"))
        assertEquals(1_500L, parseClock("00:00:01,500"))
        assertEquals(-1L, parseClock("abc"))
        assertEquals(3723L, parseDurationSec("3723"))
        assertEquals(0L, parseDurationSec(""))
    }

    @Test
    fun showNotesHaveLinksAndTimestamps() {
        val html = "<p>We talk with <a href=\"https://guest.example.com\">a guest</a>.</p>" +
            "<ul><li>(00:00) Intro</li><li>12:34 Main topic</li><li>1:01:00 Wrap up at 10:30 am</li></ul>" +
            "<p>More at https://example.com/notes.</p>"
        val notes = showNotes(html, durationMs = 3_723_000)
        assertTrue(notes.text, notes.text.startsWith("We talk with a guest."))
        assertTrue(notes.text.contains("• 12:34 Main topic"))
        val web = notes.spans.filter { it.link is NoteLink.Web }
        assertEquals(listOf("a guest", "https://example.com/notes"), web.map { notes.text.substring(it.start, it.end) })
        assertEquals(listOf(0L, 754_000L, 3_660_000L), notes.timestamps)
        val ts = notes.spans.first { it.link == NoteLink.Time(754_000) }
        assertEquals("12:34", notes.text.substring(ts.start, ts.end))
    }

    @Test
    fun plainTextNotesKeepTheirLines() {
        val notes = showNotes("Line one\nLine two at 5:00\n\n\n\nEnd")
        assertEquals("Line one\nLine two at 5:00\n\nEnd", notes.text)
        assertEquals(listOf(300_000L), notes.timestamps)
    }

    @Test
    fun timestampsBeyondTheEpisodeAreIgnored() {
        assertTrue(showNotes("See 59:00", durationMs = 600_000).timestamps.isEmpty())
    }

    @Test
    fun readsJsonChapters() {
        val json = """{"version":"1.2.0","chapters":[
            {"startTime":0,"title":"Intro","img":"art/1.jpg"},
            {"startTime":95.5,"title":"Silent picture","toc":false},
            {"startTime":120,"title":"Topic","url":"https://example.com/t"}]}"""
        val ch = ChaptersJson.parse(json, "https://example.com/ep/chapters.json")
        assertEquals(listOf(0L, 120_000L), ch.map { it.startMs })
        assertEquals("https://example.com/ep/art/1.jpg", ch[0].imageUrl)
        assertEquals("https://example.com/t", ch[1].url)
        assertEquals(-1, ch.indexAt(-5))
        assertEquals(0, ch.indexAt(119_999))
        assertEquals(1, ch.indexAt(500_000))
    }

    @Test
    fun readsVtt() {
        val vtt = """WEBVTT

1
00:00:00.000 --> 00:00:02.000
<v Jane>Hello and welcome

00:00:02.000 --> 00:00:04.500
<v Jane>to the show.

00:00:05.000 --> 00:00:07.000
<v Sam>Thanks for having me!
"""
        val t = TranscriptParser.parse(vtt, "text/vtt")
        assertTrue(t.timed)
        assertEquals(2, t.lines.size)
        assertEquals("Hello and welcome to the show.", t.lines[0].text)
        assertEquals("Jane", t.lines[0].speaker)
        assertEquals("Sam", t.lines[1].speaker)
        assertEquals(5_000L, t.lines[1].startMs)
        assertEquals(0, t.indexAt(3_000))
        assertEquals(1, t.indexAt(6_000))
    }

    @Test
    fun readsSrt() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nJANE: Hi there.\r\n\r\n2\r\n00:00:10,000 --> 00:00:12,000\r\nSecond line\r\n"
        val t = TranscriptParser.parse(srt, "application/x-subrip")
        assertEquals(listOf("Hi there.", "Second line"), t.lines.map { it.text })
        assertEquals("JANE", t.lines[0].speaker)
        assertEquals(10_000L, t.lines[1].startMs)
    }

    @Test
    fun readsJsonTranscriptsWordByWord() {
        val json = """{"version":"1.0.0","segments":[
            {"speaker":"Jane","startTime":0.0,"endTime":0.4,"body":"Hello"},
            {"speaker":"Jane","startTime":0.4,"endTime":0.8,"body":"world"},
            {"speaker":"Jane","startTime":0.8,"endTime":0.9,"body":"."},
            {"speaker":"Sam","startTime":1.0,"endTime":1.5,"body":"Hi"}]}"""
        val t = TranscriptParser.parse(json, "application/json")
        assertEquals(listOf("Hello world.", "Hi"), t.lines.map { it.text })
        assertEquals(listOf("Jane", "Sam"), t.lines.map { it.speaker })
    }

    @Test
    fun readsHtmlTranscriptsUntimed() {
        val t = TranscriptParser.parse("<p>First para.</p><p>Second &amp; last.</p>", "text/html")
        assertFalse(t.timed)
        assertEquals(listOf("First para.", "Second & last."), t.lines.map { it.text })
    }

    @Test
    fun opmlRoundTrip() {
        val xml = Opml.write(
            listOf(
                Podcast("https://a.example.com/feed", "A & B"),
                Podcast("https://b.example.com/rss", "<Bee>", link = "https://b.example.com"),
            ),
        )
        val back = Opml.parse(xml)
        assertEquals(listOf("A & B", "<Bee>"), back.map { it.title })
        assertEquals(listOf("https://a.example.com/feed", "https://b.example.com/rss"), back.map { it.feedUrl })
    }

    @Test
    fun opmlFromOtherAppsWithFolders() {
        val xml = """<?xml version="1.0"?><opml version="1.0"><head><title>x</title></head><body>
            <outline text="News"><outline type="rss" text="Daily" xmlUrl="https://d.example.com/feed"/></outline>
            <outline text="No url"/>
            <outline type="rss" title="Weekly" xmlUrl="http://w.example.com/feed"/>
            </body></opml>"""
        val list = Opml.parse(xml)
        assertEquals(listOf("Daily", "Weekly"), list.map { it.title })
        assertNotNull(list.firstOrNull { it.feedUrl == "http://w.example.com/feed" })
        assertNull(list.firstOrNull { it.title == "No url" })
    }
}
