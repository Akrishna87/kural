package io.github.akrishna87.podcasts.feed

import io.github.akrishna87.podcasts.data.Chapter
import io.github.akrishna87.podcasts.data.objects
import org.json.JSONObject

/** Podcasting 2.0 JSON chapters (application/json+chapters). */
object ChaptersJson {
    fun parse(json: String, baseUrl: String = ""): List<Chapter> {
        val root = JSONObject(json)
        return root.optJSONArray("chapters").objects()
            // "toc": false marks silent chapters (pictures only), which aren't for the list.
            .filter { it.optBoolean("toc", true) }
            .mapNotNull { c ->
                val start = c.optDouble("startTime", Double.NaN)
                if (start.isNaN() || start < 0) return@mapNotNull null
                Chapter(
                    startMs = (start * 1000).toLong(),
                    title = c.optString("title").trim(),
                    url = c.optString("url").takeIf { it.startsWith("http") },
                    imageUrl = c.optString("img").takeIf { it.isNotBlank() }?.let { if (baseUrl.isEmpty()) it else resolveUrl(baseUrl, it) },
                )
            }
            .sortedBy { it.startMs }
    }
}

/** The chapter playing at [positionMs], by index; -1 before the first. */
fun List<Chapter>.indexAt(positionMs: Long): Int {
    var found = -1
    for (i in indices) if (this[i].startMs <= positionMs) found = i else break
    return found
}
