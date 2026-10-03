package io.github.akrishna87.podcasts.feed

import io.github.akrishna87.podcasts.data.Podcast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** OPML: the file every podcast app can import and export, so your shows can move with you. */
object Opml {
    data class Outline(val title: String, val feedUrl: String)

    fun parse(xml: String): List<Outline> {
        val doc = parseXml(xml)
        return doc.documentElement.descendants("outline").mapNotNull { o ->
            val url = o.getAttribute("xmlUrl").ifBlank { o.getAttribute("xmlurl") }.ifBlank { o.getAttribute("url") }.trim()
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return@mapNotNull null
            Outline(o.getAttribute("title").ifBlank { o.getAttribute("text") }.trim(), url)
        }.distinctBy { it.feedUrl }
    }

    fun write(podcasts: List<Podcast>, now: Date = Date()): String {
        val date = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(now)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n")
        sb.append("  <head>\n    <title>Kural subscriptions</title>\n    <dateCreated>").append(date).append("</dateCreated>\n  </head>\n")
        sb.append("  <body>\n")
        for (p in podcasts) {
            sb.append("    <outline type=\"rss\" text=\"").append(escape(p.title)).append("\" title=\"").append(escape(p.title))
                .append("\" xmlUrl=\"").append(escape(p.feedUrl)).append('"')
            p.link?.let { sb.append(" htmlUrl=\"").append(escape(it)).append('"') }
            sb.append(" />\n")
        }
        sb.append("  </body>\n</opml>\n")
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
