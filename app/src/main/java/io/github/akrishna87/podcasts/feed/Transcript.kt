package io.github.akrishna87.podcasts.feed

import io.github.akrishna87.podcasts.data.objects
import org.json.JSONObject

/** A line of a transcript. Untimed transcripts (HTML, plain text) have [startMs] -1. */
data class TranscriptLine(val startMs: Long, val endMs: Long, val speaker: String?, val text: String)

class Transcript(val lines: List<TranscriptLine>) {
    val timed: Boolean get() = lines.any { it.startMs >= 0 }

    /** The line being spoken at [positionMs]; -1 if none yet. */
    fun indexAt(positionMs: Long): Int {
        if (!timed) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/**
 * Reads the transcript formats the Podcasting 2.0 namespace allows: JSON, WebVTT, SRT, HTML and
 * plain text. Short timed pieces (often one word each) are joined into readable sentences.
 */
object TranscriptParser {
    fun parse(body: String, type: String): Transcript {
        val t = type.lowercase()
        val trimmed = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        val raw = when {
            t.contains("json") || trimmed.startsWith("{") -> json(trimmed)
            t.contains("vtt") || trimmed.startsWith("WEBVTT") -> cues(trimmed)
            t.contains("srt") || t.contains("subrip") || Regex("^\\d+\\s*\\r?\\n\\d").containsMatchIn(trimmed) -> cues(trimmed)
            t.contains("html") || trimmed.startsWith("<") -> untimed(htmlToText(trimmed))
            else -> untimed(trimmed)
        }
        return Transcript(if (raw.any { it.startMs >= 0 }) merge(raw) else raw)
    }

    private fun json(body: String): List<TranscriptLine> =
        JSONObject(body).optJSONArray("segments").objects().mapNotNull { s ->
            val text = s.optString("body").trim()
            if (text.isEmpty()) return@mapNotNull null
            TranscriptLine(
                (s.optDouble("startTime", 0.0) * 1000).toLong(),
                (s.optDouble("endTime", 0.0) * 1000).toLong(),
                s.optString("speaker").trim().takeIf { it.isNotEmpty() },
                text,
            )
        }

    private val ARROW = Regex("^\\s*([0-9:.,]+)\\s*-->\\s*([0-9:.,]+)")
    private val VOICE = Regex("^<v(?:\\.[^\\s>]*)?\\s+([^>]+)>")

    /** WebVTT and SRT: blocks of "start --> end" followed by text lines. */
    private fun cues(body: String): List<TranscriptLine> {
        val out = ArrayList<TranscriptLine>()
        val blocks = body.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines()
            val timeAt = lines.indexOfFirst { ARROW.containsMatchIn(it) }
            if (timeAt < 0) continue
            val m = ARROW.find(lines[timeAt]) ?: continue
            val start = parseClock(m.groupValues[1])
            val end = parseClock(m.groupValues[2])
            if (start < 0) continue
            var speaker: String? = null
            val text = lines.drop(timeAt + 1).joinToString(" ") { line ->
                var l = line.trim()
                VOICE.find(l)?.let { v ->
                    speaker = v.groupValues[1].trim()
                    l = l.substring(v.range.last + 1)
                }
                // "SPEAKER: words" is a common way to name the speaker in SRT files.
                Regex("^([A-Z][A-Za-z .'-]{1,30}):\\s+").find(l)?.let { s ->
                    if (speaker == null) speaker = s.groupValues[1].trim()
                    l = l.substring(s.range.last + 1)
                }
                l
            }.replace(Regex("<[^>]+>"), "").let(::decodeEntities).replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) out += TranscriptLine(start, end, speaker, text)
        }
        return out
    }

    private fun untimed(text: String): List<TranscriptLine> =
        text.split(Regex("\n\\s*\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            .map { TranscriptLine(-1, -1, null, it) }

    /**
     * Joins pieces into lines that end a sentence (or a speaker's turn), so word-by-word
     * transcripts read like text. A long pause also starts a new line.
     */
    private fun merge(pieces: List<TranscriptLine>): List<TranscriptLine> {
        val out = ArrayList<TranscriptLine>()
        var cur: TranscriptLine? = null
        for (p in pieces.sortedBy { it.startMs }) {
            val c = cur
            if (c == null) { cur = p; continue }
            val speakerChanged = p.speaker != null && p.speaker != c.speaker
            val gap = p.startMs - c.endMs
            val endsSentence = c.text.endsWith('.') || c.text.endsWith('?') || c.text.endsWith('!') || c.text.endsWith('…')
            if (speakerChanged || gap > 2_500 || (endsSentence && c.text.length > 60) || c.text.length > 320) {
                out += c
                cur = p
            } else {
                cur = c.copy(endMs = maxOf(c.endMs, p.endMs), text = join(c.text, p.text))
            }
        }
        cur?.let { out += it }
        return out
    }

    private fun join(a: String, b: String): String =
        if (b.firstOrNull()?.let { it in ".,;:!?'’)" } == true) a + b else "$a $b"
}
