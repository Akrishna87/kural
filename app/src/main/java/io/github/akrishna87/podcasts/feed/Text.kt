package io.github.akrishna87.podcasts.feed

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A link inside show notes: a web address, or a moment in the episode to jump to. */
sealed interface NoteLink {
    data class Web(val url: String) : NoteLink
    data class Time(val ms: Long) : NoteLink
}

data class NoteSpan(val start: Int, val end: Int, val link: NoteLink)

/** Show notes as plain paragraphs, with their links and timestamps marked. */
data class RichText(val text: String, val spans: List<NoteSpan>) {
    val timestamps: List<Long> get() = spans.mapNotNull { (it.link as? NoteLink.Time)?.ms }
}

private val BLOCK_TAGS = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "tr", "table", "section", "pre")
private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val HREF = Regex("href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
private val BARE_URL = Regex("https?://[^\\s<>\"'()]+[^\\s<>\"'().,;:!?]")

/**
 * "(12:34)", "1:02:03", "at 5:07": clock times in show notes. Hours are optional; minutes and
 * seconds must look like a clock, and times followed by am/pm are left alone.
 */
private val TIMESTAMP = Regex("(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\d:])(?!\\s?[aApP]\\.?[mM])")

/** Turns HTML (or plain text) show notes into readable text with clickable links and timestamps. */
fun showNotes(html: String, durationMs: Long = 0): RichText {
    if (html.isBlank()) return RichText("", emptyList())
    val sb = StringBuilder()
    val spans = ArrayList<NoteSpan>()
    var linkStart = -1
    var linkHref: String? = null
    var listDepth = 0
    var pos = 0
    val isHtml = Regex("<[a-zA-Z/!]").containsMatchIn(html)

    fun appendText(raw: String) {
        if (raw.isEmpty()) return
        val t = decodeEntities(if (isHtml) raw.replace(Regex("\\s+"), " ") else raw).replace('\u00A0', ' ')
        // Collapse the space after a line break.
        if (sb.isEmpty() || sb.last() == '\n') sb.append(t.trimStart(' ')) else sb.append(t)
    }

    fun newline(count: Int) {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isEmpty()) return
        var have = 0
        var i = sb.length - 1
        while (i >= 0 && sb[i] == '\n') { have++; i-- }
        repeat((count - have).coerceAtLeast(0)) { sb.append('\n') }
    }

    if (isHtml) {
        for (m in TAG.findAll(html)) {
            appendText(html.substring(pos, m.range.first))
            pos = m.range.last + 1
            if (m.value.startsWith("<!--")) continue
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            when {
                name == "br" -> newline(1)
                name == "a" && !closing -> {
                    val h = HREF.find(m.groupValues[3])
                    linkHref = h?.let { decodeEntities(it.groupValues[2].ifEmpty { it.groupValues[3] }.ifEmpty { it.groupValues[4] }) }
                    linkStart = sb.length
                }
                name == "a" && closing -> {
                    val href = linkHref
                    if (href != null && linkStart in 0 until sb.length && (href.startsWith("http://") || href.startsWith("https://"))) {
                        spans += NoteSpan(linkStart, sb.length, NoteLink.Web(href))
                    }
                    linkHref = null
                    linkStart = -1
                }
                name == "li" && !closing -> {
                    newline(1)
                    sb.append("• ")
                }
                name == "ul" || name == "ol" -> {
                    listDepth = (listDepth + if (closing) -1 else 1).coerceAtLeast(0)
                    newline(if (closing) 2 else 1)
                }
                name == "li" -> newline(1)
                name in BLOCK_TAGS -> newline(if (listDepth > 0) 1 else 2)
                name == "script" || name == "style" -> Unit
            }
        }
        appendText(html.substring(pos))
    } else {
        appendText(html)
    }

    // Tidy whitespace without moving the link spans: trim each line's end, cap blank lines.
    val text = sb.toString()
    val (clean, map) = tidy(text)
    val moved = spans.mapNotNull { s ->
        val a = map[s.start]
        val b = map[s.end]
        if (b > a) NoteSpan(a, b, s.link) else null
    }.toMutableList()

    // Bare web addresses that weren't already links.
    for (m in BARE_URL.findAll(clean)) {
        if (moved.none { m.range.first < it.end && m.range.last >= it.start }) {
            moved += NoteSpan(m.range.first, m.range.last + 1, NoteLink.Web(m.value))
        }
    }
    // Timestamps, outside links, that fall inside the episode.
    for (m in TIMESTAMP.findAll(clean)) {
        if (moved.any { m.range.first < it.end && m.range.last >= it.start }) continue
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].toInt()
        if (sec >= 60 || (m.groupValues[1].isNotEmpty() && min >= 60)) continue
        val ms = ((h * 3600L) + min * 60L + sec) * 1000L
        if (durationMs > 0 && ms > durationMs + 60_000) continue
        moved += NoteSpan(m.range.first, m.range.last + 1, NoteLink.Time(ms))
    }
    return RichText(clean, moved.sortedBy { it.start })
}

/** Trims line ends and runs of blank lines; returns the text and where each old offset moved to. */
private fun tidy(text: String): Pair<String, IntArray> {
    val map = IntArray(text.length + 1)
    val out = StringBuilder()
    var i = 0
    var newlines = 0
    // Skip leading whitespace.
    while (i < text.length && text[i].isWhitespace()) { map[i] = 0; i++ }
    while (i < text.length) {
        val c = text[i]
        // Where character i lands (or would have landed): the output length before it.
        map[i] = out.length
        if (c == '\n') {
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
            newlines++
            if (newlines <= 2) out.append('\n')
        } else if (c == ' ' && (out.isEmpty() || out.last() == '\n' || out.last() == ' ')) {
            // drop
        } else {
            newlines = 0
            out.append(c)
        }
        i++
    }
    map[text.length] = out.length
    var end = out.length
    while (end > 0 && out[end - 1].isWhitespace()) end--
    out.setLength(end)
    for (k in map.indices) if (map[k] > end) map[k] = end
    return out.toString() to map
}

/** Plain text from HTML, for short summaries. */
fun htmlToText(html: String): String = showNotes(html).text

/** A one-paragraph summary for lists. */
fun summary(html: String, max: Int = 220): String {
    val t = htmlToText(html).replace(Regex("\\s+"), " ").trim()
    return if (t.length <= max) t else t.take(max).substringBeforeLast(' ').trimEnd(',', '.', ';', ':') + "…"
}

// ----- Times, dates and sizes -----

/** "1:02:03", "12:34", "1:02:03.500", "754", "754.2" → milliseconds; -1 if it isn't a time. */
fun parseClock(s: String?): Long {
    if (s.isNullOrBlank()) return -1
    val t = s.trim()
    if (':' in t) {
        val parts = t.split(':')
        if (parts.size > 3) return -1
        var total = 0.0
        for (p in parts) {
            val v = p.trim().replace(',', '.').toDoubleOrNull() ?: return -1
            total = total * 60 + v
        }
        return (total * 1000).toLong()
    }
    return t.replace(',', '.').toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1
}

/** itunes:duration in seconds, written as seconds or clock time. */
fun parseDurationSec(s: String?): Long = parseClock(s).let { if (it < 0) 0 else it / 1000 }

private val RFC822_PATTERNS = listOf(
    "EEE, d MMM yyyy HH:mm:ss Z",
    "EEE, d MMM yyyy HH:mm:ss z",
    "d MMM yyyy HH:mm:ss Z",
    "d MMM yyyy HH:mm:ss z",
    "EEE, d MMM yyyy HH:mm Z",
    "EEE, d MMM yyyy HH:mm z",
    "d MMM yyyy HH:mm Z",
    "EEE, d MMM yyyy HH:mm:ss",
    "d MMM yyyy HH:mm:ss",
    "EEE, d MMM yyyy",
    "d MMM yyyy",
)

private val ISO_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mmXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd",
)

private val ZONES = mapOf(
    "UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000",
    "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
    "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
    "BST" to "+0100", "CET" to "+0100", "CEST" to "+0200", "IST" to "+0530", "AEST" to "+1000", "AEDT" to "+1100",
)

/** Feed dates: RFC 822 as RSS asks, ISO 8601 as Atom uses, and the usual mistakes. 0 if unreadable. */
fun parseDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0
    var s = raw.trim().replace(Regex("\\s+"), " ")
    if (Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) {
        s = s.replace(Regex("Z$"), "+00:00").replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        // Fractions of a second with more or fewer than three digits.
        s = s.replace(Regex("\\.(\\d+)(?=[+-]|$)")) { m -> "." + m.groupValues[1].padEnd(3, '0').take(3) }
        for (p in ISO_PATTERNS) tryParse(s, p)?.let { return it }
        return 0
    }
    // Named zones → numeric offsets; drop a trailing "(UTC)" style comment; fix "Sept", "Thurs".
    s = s.replace(Regex("\\(.*\\)$"), "").trim()
    s = s.replace(Regex("\\b([A-Z]{1,4})$")) { m -> ZONES[m.groupValues[1]] ?: m.value }
    s = s.replace(Regex("\\b(Sept|June|July|March|April)\\b")) { it.value.take(3) }
    s = s.replace(Regex("^(Tues|Thurs|Thur|Wednes|Satur|Sun|Mon|Fri)[a-z]*,?", RegexOption.IGNORE_CASE)) { m -> m.value.take(3) + "," }
    s = s.replace(",,", ",")
    for (p in RFC822_PATTERNS) tryParse(s, p)?.let { return it }
    // Day name without a comma.
    val noDay = s.replace(Regex("^[A-Za-z]{3},? "), "")
    for (p in RFC822_PATTERNS.filter { !it.startsWith("EEE") }) tryParse(noDay, p)?.let { return it }
    return 0
}

private fun tryParse(s: String, pattern: String): Long? = try {
    val f = SimpleDateFormat(pattern, Locale.US)
    f.isLenient = false
    if (!pattern.contains('Z') && !pattern.contains('z') && !pattern.contains('X')) f.timeZone = TimeZone.getTimeZone("UTC")
    val pos = java.text.ParsePosition(0)
    val d = f.parse(s, pos)
    if (d != null && pos.index == s.length) d.time else null
} catch (e: Exception) {
    null
}

/** "1:02:03" or "12:34". */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "1 h 5 min", "42 min", "50 s". */
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        m > 0 -> "$m min"
        else -> "$seconds s"
    }
}

/** "312 MB", "1.2 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Resolves a possibly relative address against the page it came from. */
fun resolveUrl(base: String, href: String): String = try {
    java.net.URL(java.net.URL(base), href.trim()).toString()
} catch (e: Exception) {
    href.trim()
}
