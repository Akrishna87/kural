package io.github.akrishna87.podcasts.feed

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/** A fetched page. [notModified] means the feed hasn't changed since the last visit. */
class HttpResult(val body: String, val finalUrl: String, val etag: String?, val lastModified: String?, val notModified: Boolean)

/** Plain HTTP(S) requests for feeds, the directory, chapters and transcripts. */
object Http {
    const val USER_AGENT = "Kural/1.0 (Android podcast app; +https://github.com/Akrishna87/Akrishna87)"
    private const val MAX_BYTES = 25_000_000

    /**
     * Fetches [url], following redirects (podcast hosts chain several through tracking services,
     * sometimes over plain http). Sends [etag] / [lastModified] so unchanged feeds come back empty.
     */
    fun fetch(url: String, etag: String? = null, lastModified: String? = null, timeoutMs: Int = 25_000): HttpResult {
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
            try {
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val location = c.getHeaderField("Location") ?: throw IOException("Redirect without a location from $current")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code == 304) return HttpResult("", current, etag, lastModified, notModified = true)
                if (code !in 200..299) throw IOException("HTTP $code from ${URL(current).host}")
                val raw = c.inputStream.let { if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it }.use { input ->
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
                return HttpResult(decode(raw, c.contentType), current, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), notModified = false)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects from $url")
    }

    fun get(url: String, timeoutMs: Int = 25_000): String = fetch(url, timeoutMs = timeoutMs).body

    /** Bytes to text: the charset from the header, else the XML declaration, else UTF-8. */
    private fun decode(bytes: ByteArray, contentType: String?): String {
        val fromHeader = contentType?.let { Regex("charset=\"?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val fromXml = Regex("<\\?xml[^>]*encoding=[\"']([\\w.:-]+)[\"']").find(head)?.groupValues?.get(1)
        val name = fromXml ?: fromHeader ?: "UTF-8"
        val cs = try { Charset.forName(name) } catch (e: Exception) { Charsets.UTF_8 }
        return String(bytes, cs)
    }
}
