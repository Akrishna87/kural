package io.github.akrishna87.podcasts.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A backup of everything you've set up in Podwave, as files in one folder (Download/Podwave):
 * "Podwave backup.json" with your shows, Up Next, history, bookmarks, filters, alerts, stats and
 * settings, and "episodes/<hash>.json" with each show's episodes (one file per show, so a change
 * to one show doesn't rewrite them all). Copy the folder to a new phone and restore from it there.
 * Downloaded audio isn't included.
 */
object BackupFile {
    const val NAME = "Podwave backup.json"
    const val APP = "Podwave"
    private const val FORMAT = 1

    /** The preference files backed up. */
    val PREFS = listOf("settings", "theme", "equaliser")

    /** Keys that describe this phone, not your choices, and aren't backed up or restored. */
    val PHONE_ONLY = mapOf("equaliser" to setOf("eq_info"))

    class Contents(
        val createdMs: Long,
        /** The library's files (see [Library.exportFiles]), name → text. */
        val library: Map<String, String>,
        /** Each preference file's values (Boolean, Int, Long, Float, String or Set<String>), by file name. */
        val prefs: Map<String, Map<String, Any>>,
    ) {
        private val entries: List<JSONObject> by lazy { BackupFile.array(library["podcasts.json"]).let { a -> List(a.length()) { a.optJSONObject(it) ?: JSONObject() } } }

        /** Shows you follow. */
        val shows: Int get() = entries.count { it.optBoolean("subscribed") }
        val upNext: Int get() = BackupFile.array(library["queue.json"]).length()
        val favourites: Int get() = BackupFile.array(library["states.json"]).let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optBoolean("star") == true } }
        val bookmarks: Int get() = BackupFile.array(library["bookmarks.json"]).length()

        /** Nothing worth keeping (a fresh install), so an older backup isn't replaced by it. */
        val isEmpty: Boolean get() = entries.isEmpty() && upNext == 0 && bookmarks == 0

        fun withTime(ms: Long) = Contents(ms, library, prefs)
    }

    private fun array(text: String?): JSONArray = try {
        if (text == null) JSONArray() else JSONArray(text)
    } catch (e: Exception) {
        JSONArray()
    }

    /** Every file of the backup: path in the backup folder → text. */
    fun write(c: Contents): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[NAME] = manifest(c)
        c.library.forEach { (name, text) -> if (name.startsWith("episodes/") && Library.isBackupFile(name)) out[name] = text }
        return out
    }

    /** The main file: everything but the shows' episodes, which are only listed. */
    fun manifest(c: Contents): String {
        val library = JSONObject()
        for (name in Library.BACKUP_FILES) {
            val text = c.library[name] ?: continue
            library.put(name, try { JSONTokener(text).nextValue() } catch (e: Exception) { text })
        }
        val settings = JSONObject()
        c.prefs.forEach { (file, values) -> settings.put(file, encodePrefs(values)) }
        return JSONObject()
            .put("app", APP)
            .put("format", FORMAT)
            .put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date(c.createdMs)))
            .put("createdMs", c.createdMs)
            .put("library", library)
            .put("episodes", JSONArray(c.library.keys.filter { it.startsWith("episodes/") && Library.isBackupFile(it) }))
            .put("settings", settings)
            .toString(1)
    }

    /**
     * Reads a backup: [text] is the main file, [episodes] reads one of the files it lists
     * ("episodes/<hash>.json"; null if it's missing). Returns null if [text] isn't a Podwave backup.
     */
    fun read(text: String, episodes: (String) -> String?): Contents? = try {
        val o = JSONObject(text.removePrefix("﻿"))
        if (o.optString("app") != APP || o.optInt("format", 0) > FORMAT) {
            null
        } else {
            val files = LinkedHashMap<String, String>()
            o.optJSONObject("library")?.let { lib ->
                for (name in Library.BACKUP_FILES) {
                    if (!lib.has(name)) continue
                    files[name] = lib.get(name).toString()
                }
            }
            o.optJSONArray("episodes")?.let { a ->
                for (i in 0 until a.length()) {
                    val name = a.optString(i)
                    if (Library.isBackupFile(name) && name !in files) episodes(name)?.let { files[name] = it }
                }
            }
            val prefs = LinkedHashMap<String, Map<String, Any>>()
            o.optJSONObject("settings")?.let { s ->
                for (file in PREFS) s.optJSONObject(file)?.let { prefs[file] = decodePrefs(it, PHONE_ONLY[file].orEmpty()) }
            }
            Contents(o.optLong("createdMs"), files, prefs)
        }
    } catch (e: Exception) {
        null
    }

    // ----- Preferences, with their types (an Int must come back as an Int, not a Long) -----

    fun encodePrefs(values: Map<String, Any?>, skip: Set<String> = emptySet()): JSONObject {
        val o = JSONObject()
        for ((k, v) in values.toSortedMap()) {
            if (k in skip) continue
            val typed = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.filterIsInstance<String>().sorted()))
                else -> null
            }
            typed?.let { o.put(k, it) }
        }
        return o
    }

    fun decodePrefs(o: JSONObject, skip: Set<String> = emptySet()): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (k in o.keys()) {
            if (k in skip) continue
            val e = o.optJSONObject(k) ?: continue
            val value: Any? = try {
                when (e.optString("t")) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    "s" -> e.getString("v")
                    "set" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> null
                }
            } catch (x: Exception) {
                null
            }
            value?.let { out[k] = it }
        }
        return out
    }
}
