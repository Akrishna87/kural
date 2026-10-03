package io.github.akrishna87.podcasts.feed

import java.io.Reader

/**
 * Fixes the usual reasons strict XML parsers give up on podcast feeds, as the feed streams in,
 * so even huge feeds never have to sit in memory whole:
 * HTML-only entities (`&nbsp;`) become their characters, a bare `&` ("Q&A") becomes `&amp;`,
 * the DOCTYPE is dropped (so nothing is fetched) and control characters are removed.
 * CDATA sections pass through untouched.
 */
class CleaningReader(private val source: Reader) : Reader() {
    private val inBuf = CharArray(64 * 1024)
    /** Unprocessed characters carried over from the last block (a cut-off entity or marker). */
    private var carry = ""
    private val out = StringBuilder()
    private var outPos = 0
    private var eof = false
    private var started = false
    private var inCdata = false
    private var inDoctype = false
    private var doctypeBrackets = 0

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (outPos >= out.length) {
            if (eof && carry.isEmpty()) return -1
            fill()
        }
        val n = minOf(len, out.length - outPos)
        out.getChars(outPos, outPos + n, cbuf, off)
        outPos += n
        return n
    }

    override fun close() = source.close()

    private fun fill() {
        out.setLength(0)
        outPos = 0
        val sb = StringBuilder(carry)
        carry = ""
        if (!eof) {
            val n = source.read(inBuf)
            if (n < 0) eof = true else sb.appendRange(inBuf, 0, n)
        }
        var text = sb.toString()
        if (!started && text.isNotEmpty()) {
            started = true
            text = text.trimStart('﻿', ' ', '\t', '\r', '\n')
        }
        // Keep a possibly cut-off marker or entity at the end for the next round.
        if (!eof) {
            val cut = maxOf(text.length - 24, 0)
            val keepFrom = (cut until text.length).firstOrNull { text[it] == '&' || text[it] == '<' || text[it] == ']' }
            if (keepFrom != null) {
                carry = text.substring(keepFrom)
                text = text.substring(0, keepFrom)
            }
        }
        process(text)
    }

    private fun process(t: String) {
        var i = 0
        while (i < t.length) {
            if (inCdata) {
                val end = t.indexOf("]]>", i)
                if (end < 0) {
                    out.append(t, i, t.length)
                    return
                }
                out.append(t, i, end + 3)
                i = end + 3
                inCdata = false
                continue
            }
            if (inDoctype) {
                val c = t[i++]
                when (c) {
                    '[' -> doctypeBrackets++
                    ']' -> doctypeBrackets--
                    '>' -> if (doctypeBrackets <= 0) inDoctype = false
                }
                continue
            }
            val c = t[i]
            when {
                c == '<' && t.startsWith("<![CDATA[", i) -> {
                    out.append("<![CDATA[")
                    i += 9
                    inCdata = true
                }
                c == '<' && t.regionMatches(i, "<!DOCTYPE", 0, 9, ignoreCase = true) -> {
                    inDoctype = true
                    doctypeBrackets = 0
                    i += 9
                }
                c == '&' -> i = entity(t, i)
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> i++ // not allowed in XML 1.0
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /** Handles the entity (or bare ampersand) at [start]; returns where to carry on. */
    private fun entity(t: String, start: Int): Int {
        val m = ENTITY.matchAt(t, start)
        if (m == null) {
            out.append("&amp;")
            return start + 1
        }
        val e = m.groupValues[1]
        when {
            e.startsWith("#") || e in XML_ENTITIES -> out.append(m.value)
            else -> out.append(ENTITIES[e]?.let(::escape) ?: " ")
        }
        return m.range.last + 1
    }

    private fun escape(s: String) = when (s) {
        "&" -> "&amp;"
        "<" -> "&lt;"
        ">" -> "&gt;"
        else -> s
    }

    companion object {
        private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    }
}
