package io.github.akrishna87.podcasts

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.feed.Http
import java.io.File

/**
 * Episode downloads go through Android's download manager, so they carry on with the app closed,
 * retry by themselves and show their progress in the notification shade.
 */
object Downloads {
    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun root(context: Context): File = context.getExternalFilesDir("episodes") ?: File(context.filesDir, "episodes")

    fun file(context: Context, episodeId: String) = File(root(context), safe(episodeId) + ".media")

    /** The downloaded file, if it's really there. */
    fun localFile(context: Context, lib: Library, episodeId: String): File? {
        if (lib.state(episodeId)?.downloaded != true) return null
        return file(context, episodeId).takeIf { it.isFile && it.length() > 0 }
    }

    fun start(context: Context, lib: Library, e: Episode) {
        val state = lib.state(e.id)
        if (state?.downloaded == true || (state?.downloadId ?: 0L) != 0L) return
        root(context).mkdirs()
        file(context, e.id).delete()
        val wifiOnly = Settings.wifiOnly(Settings.prefs(context))
        val request = DownloadManager.Request(Uri.parse(e.audioUrl))
            .setTitle(e.title)
            .setDescription(lib.podcast(e.podcastId)?.title ?: "Podcast episode")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "episodes", safe(e.id) + ".media")
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)
            .addRequestHeader("User-Agent", Http.USER_AGENT)
        val id = manager(context).enqueue(request)
        lib.updateState(e) { it.copy(downloadId = id, downloaded = false) }
    }

    fun cancel(context: Context, lib: Library, episodeId: String) {
        val s = lib.state(episodeId) ?: return
        if (s.downloadId != 0L) manager(context).remove(s.downloadId)
        file(context, episodeId).delete()
        lib.updateState(episodeId) { it.copy(downloadId = 0, downloaded = false) }
    }

    /** Deletes a downloaded episode (or stops its download). */
    fun delete(context: Context, lib: Library, episodeId: String) = cancel(context, lib, episodeId)

    /** 0..1 for each downloading episode. */
    fun progress(context: Context, lib: Library): Map<String, Float> {
        val active = lib.states().filter { it.downloadId != 0L }
        if (active.isEmpty()) return emptyMap()
        val byDownload = active.associateBy { it.downloadId }
        val out = HashMap<String, Float>()
        manager(context).query(DownloadManager.Query().setFilterById(*active.map { it.downloadId }.toLongArray()))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val soFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val size = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) {
                val s = byDownload[c.getLong(idCol)] ?: continue
                val total = c.getLong(size)
                out[s.episodeId] = if (total > 0) (c.getLong(soFar).toFloat() / total).coerceIn(0f, 1f) else 0f
            }
        }
        return out
    }

    /**
     * Records downloads that finished or failed. Returns (finished, failed) episode ids.
     * Called by the screens every few seconds and by the background refresh.
     */
    fun reconcile(context: Context, lib: Library): Pair<List<String>, List<String>> {
        val active = lib.states().filter { it.downloadId != 0L }
        if (active.isEmpty()) return emptyList<String>() to emptyList()
        val status = HashMap<Long, Int>()
        manager(context).query(DownloadManager.Query().setFilterById(*active.map { it.downloadId }.toLongArray()))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val st = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            while (c.moveToNext()) status[c.getLong(idCol)] = c.getInt(st)
        }
        val done = ArrayList<String>()
        val failed = ArrayList<String>()
        for (s in active) {
            when (status[s.downloadId]) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (file(context, s.episodeId).isFile) {
                        lib.updateState(s.episodeId) { it.copy(downloadId = 0, downloaded = true) }
                        done += s.episodeId
                    } else {
                        lib.updateState(s.episodeId) { it.copy(downloadId = 0, downloaded = false) }
                        failed += s.episodeId
                    }
                }
                DownloadManager.STATUS_FAILED, null -> {
                    // Failed, or cancelled from the notification (then it's gone from the list).
                    if (status[s.downloadId] == DownloadManager.STATUS_FAILED) manager(context).remove(s.downloadId)
                    file(context, s.episodeId).delete()
                    lib.updateState(s.episodeId) { it.copy(downloadId = 0, downloaded = false) }
                    failed += s.episodeId
                }
            }
        }
        return done to failed
    }

    /** Space used by downloaded episodes. */
    fun bytesUsed(context: Context): Long = root(context).listFiles()?.sumOf { it.length() } ?: 0L
}
