package io.github.akrishna87.podcasts.feed

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/** A fetched page. [notModified] means the feed hasn't changed since the last visit. */
class HttpResult(val body: String, val finalUrl: String, val etag: String?, val lastModified: String?, val notModified: Boolean)

/** Something read straight off the network; [value] is null when [notModified]. */
class Streamed<T>(val value: T?, val etag: String?, val lastModified: String?, val notModified: Boolean)

/** Plain HTTP(S) requests for feeds, the directory, chapters and transcripts. */
object Http {
    const val USER_AGENT = "Kural/1.0 (Android podcast app; +https://github.com/Akrishna87/Akrishna87)"
    /** Small things (directory answers, chapters, transcripts) are read whole, up to this size. */
    private const val MAX_BYTES = 25_000_000
    /** Feeds are streamed, so they can be much bigger; this only stops endless downloads. */
    private const val MAX_STREAM_BYTES = 300_000_000L

    /**
     * Opens [url], following redirects (podcast hosts chain several through tracking services,
     * sometimes over plain http). Sends [etag] / [lastModified] so unchanged feeds come back empty.
     * Returns null for "not modified".
     */
    private fun open(url: String, etag: String?, lastModified: String?, timeoutMs: Int): HttpURLConnection? {
        var current = url
        repeat(8) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, application/json, text/*;q=0.9, */*;q=0.8")
            c.setRequestProperty("Accept-Encoding", "gzip")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            if (lastModified != null) c.setRequestProperty("If-Modified-Since", lastModified)
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw e
            }
            if (code in 300..399 && code != 304) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location == null) throw IOException("Redirect without a location from $current")
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code == 304) {
                c.disconnect()
                return null
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code from ${URL(current).host}")
            }
            return c
        }
        throw IOException("Too many redirects from $url")
    }

    private fun body(c: HttpURLConnection): InputStream =
        c.inputStream.let { if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(it, 64 * 1024) else it }

    fun fetch(url: String, etag: String? = null, lastModified: String? = null, timeoutMs: Int = 25_000): HttpResult {
        val c = open(url, etag, lastModified, timeoutMs) ?: return HttpResult("", url, etag, lastModified, notModified = true)
        try {
            val raw = body(c).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("The file is too large")
                }
                out.toByteArray()
            }
            return HttpResult(decode(raw, c.contentType), c.url.toString(), c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), notModified = false)
        } finally {
            c.disconnect()
        }
    }

    fun get(url: String, timeoutMs: Int = 25_000): String = fetch(url, timeoutMs = timeoutMs).body

    /**
     * Reads [url] as text as it downloads, handing the stream to [read]: for feeds, which can be
     * tens of megabytes. Returns a not-modified result if the server says nothing changed.
     */
    fun <T> stream(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        timeoutMs: Int = 30_000,
        read: (Reader) -> T,
    ): Streamed<T> {
        val c = open(url, etag, lastModified, timeoutMs) ?: return Streamed(null, etag, lastModified, notModified = true)
        try {
            val input = BufferedInputStream(Capped(body(c), MAX_STREAM_BYTES), 64 * 1024)
            val charset = charsetOf(input, c.contentType)
            val value = InputStreamReader(input, charset).use { read(it) }
            return Streamed(value, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), notModified = false)
        } finally {
            c.disconnect()
        }
    }

    /** The charset from the header, else the XML declaration, else UTF-8. */
    private fun charsetOf(input: BufferedInputStream, contentType: String?): Charset {
        input.mark(512)
        val head = ByteArray(200)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        input.reset()
        return pickCharset(String(head, 0, n, Charsets.ISO_8859_1), contentType)
    }

    private fun pickCharset(head: String, contentType: String?): Charset {
        val fromHeader = contentType?.let { Regex("charset=\"?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val fromXml = Regex("<\\?xml[^>]*encoding=[\"']([\\w.:-]+)[\"']").find(head)?.groupValues?.get(1)
        val name = fromXml ?: fromHeader ?: "UTF-8"
        return try { Charset.forName(name) } catch (e: Exception) { Charsets.UTF_8 }
    }

    /** Bytes to text: the charset from the header, else the XML declaration, else UTF-8. */
    private fun decode(bytes: ByteArray, contentType: String?): String =
        String(bytes, pickCharset(String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1), contentType))

    /** Stops a download that goes on far longer than any real feed. */
    private class Capped(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++count > limit) throw IOException("The feed is too large")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("The feed is too large")
            }
            return n
        }
    }
}
