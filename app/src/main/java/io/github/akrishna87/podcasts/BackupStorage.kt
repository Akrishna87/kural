package io.github.akrishna87.podcasts

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File

/**
 * Where backups go: the "Podwave" folder in Download, which any file manager can copy to another
 * phone. After restoring from a folder, backups carry on in that folder instead, so a reinstalled
 * app updates the same files rather than making "(1)" copies next to them.
 *
 * Files are written through Android's media library (Android 10+), directly (Android 9 and older,
 * which needs the storage permission), or through the folder access granted when restoring.
 */
class BackupStorage(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoBackup: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(on) = prefs.edit().putBoolean(KEY_AUTO, on).apply()

    val lastBackupAt: Long get() = prefs.getLong(KEY_LAST_AT, 0L)
    val lastError: String? get() = prefs.getString(KEY_ERROR, null)

    /** The folder backups go to, as shown in the app. */
    val location: String
        get() = tree()?.let { displayPath(app, it) } ?: "Download/$FOLDER"

    fun tree(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        ?.takeIf { t -> app.contentResolver.persistedUriPermissions.any { it.uri == t && it.isWritePermission } }

    /** Android 9 and older can only write to Download with the storage permission. */
    fun needsPermission(): Boolean =
        tree() == null && Build.VERSION.SDK_INT < 29 &&
            app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /**
     * Writes [files] (path inside the backup folder, e.g. "episodes/1a2b3c4d5e6f7a8b.json", to
     * contents). Files written last time but not in [files] (shows you no longer have) are
     * removed; nothing else in the folder is touched. Unchanged files aren't rewritten unless
     * [force]; [sameAs] gives, for files whose text changes every time (the time they were made),
     * the text to compare instead. Slow: call off the main thread.
     */
    fun writeAll(files: Map<String, String>, sameAs: Map<String, String>, force: Boolean): Boolean {
        val hashes = JSONObject(prefs.getString(KEY_HASHES, "{}") ?: "{}")
        val uris = JSONObject(prefs.getString(KEY_URIS, "{}") ?: "{}")
        val previous = hashes.keys().asSequence().toList()
        val tree = tree()
        try {
            if (needsPermission()) throw IllegalStateException("Allow storage access to keep a backup")
            for ((path, text) in files) {
                val hash = (sameAs[path] ?: text).hashCode().toString() + ":" + text.length
                if (!force && hashes.optString(path) == hash) continue
                val bytes = text.toByteArray()
                when {
                    tree != null -> writeToTree(tree, path, bytes)
                    Build.VERSION.SDK_INT >= 29 -> uris.put(path, writeToDownloads(path, bytes, uris.optString(path).ifEmpty { null }))
                    else -> uris.put(path, writeLegacy(path, bytes, uris.optString(path).ifEmpty { null }))
                }
                hashes.put(path, hash)
            }
            for (path in previous) {
                if (path in files) continue
                when {
                    tree != null -> deleteFromTree(tree, path)
                    Build.VERSION.SDK_INT >= 29 -> uris.optString(path).ifEmpty { null }?.let { deleteQuietly(Uri.parse(it)) }
                    else -> uris.optString(path).ifEmpty { null }?.let { File(it).delete() }
                }
                hashes.remove(path)
                uris.remove(path)
            }
            prefs.edit()
                .putString(KEY_HASHES, hashes.toString())
                .putString(KEY_URIS, uris.toString())
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .remove(KEY_ERROR)
                .apply()
            return true
        } catch (e: Exception) {
            prefs.edit()
                .putString(KEY_HASHES, hashes.toString())
                .putString(KEY_URIS, uris.toString())
                .putString(KEY_ERROR, if (e is IllegalStateException) e.message else "Couldn't save the backup")
                .apply()
            return false
        }
    }

    /**
     * Uses [newTree] (a folder you restored from) for backups from now on. Files this app made in
     * Download/Podwave before (e.g. after following a show on a fresh install) are removed, so
     * only one backup is left.
     */
    fun adopt(newTree: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                newTree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            return // read-only folder: keep backing up to Download
        }
        val old = tree()
        if (old != null && old != newTree) {
            try {
                app.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // already gone
            }
        }
        if (old == null) {
            val uris = JSONObject(prefs.getString(KEY_URIS, "{}") ?: "{}")
            uris.keys().forEach { k ->
                val made = uris.getString(k)
                if (Build.VERSION.SDK_INT >= 29) deleteQuietly(Uri.parse(made)) else File(made).delete()
            }
        }
        prefs.edit().putString(KEY_TREE, newTree.toString()).remove(KEY_HASHES).remove(KEY_URIS).apply()
    }

    // ----- Writing -----

    @RequiresApi(29)
    private fun writeToDownloads(path: String, bytes: ByteArray, existing: String?): String {
        val resolver = app.contentResolver
        if (existing != null) {
            try {
                resolver.openOutputStream(Uri.parse(existing), "wt")?.use { it.write(bytes) }?.let { return existing }
            } catch (e: Exception) {
                // deleted, or made by an earlier install: write a new one
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, path.substringAfterLast('/'))
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/" + path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Couldn't save the backup")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't save the backup")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            deleteQuietly(uri)
            throw e
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun downloadDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /**
     * Writes to Download/Podwave on Android 9 and older; returns the file written. A file this
     * install didn't write (an old backup from before reinstalling) is never overwritten: a
     * "(2)" copy is made next to it instead, until you restore from the old one.
     */
    private fun writeLegacy(path: String, bytes: ByteArray, existing: String?): String {
        var file = existing?.let(::File) ?: File(downloadDir(), path)
        if (existing == null) {
            val base = file.nameWithoutExtension
            var n = 2
            while (file.exists()) file = File(file.parentFile, "$base ($n).${file.extension}").also { n++ }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        // So file managers on a computer (over USB) see it straight away.
        MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), null, null)
        return file.absolutePath
    }

    private fun writeToTree(tree: Uri, path: String, bytes: ByteArray) {
        val resolver = app.contentResolver
        var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (part in path.split('/').dropLast(1)) {
            dir = child(tree, dir, part)?.uri
                ?: DocumentsContract.createDocument(resolver, dir, DocumentsContract.Document.MIME_TYPE_DIR, part)
                ?: throw IllegalStateException("Couldn't save the backup in $location")
        }
        val name = path.substringAfterLast('/')
        val target = child(tree, dir, name)?.uri
            ?: DocumentsContract.createDocument(resolver, dir, MIME, name)
            ?: throw IllegalStateException("Couldn't save the backup in $location")
        val out = try {
            resolver.openOutputStream(target, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(target, "w") // providers that don't know "truncate"
        }
        out?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't save the backup in $location")
    }

    private fun deleteFromTree(tree: Uri, path: String) {
        try {
            var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (part in path.split('/').dropLast(1)) dir = child(tree, dir, part)?.uri ?: return
            child(tree, dir, path.substringAfterLast('/'))?.let { DocumentsContract.deleteDocument(app.contentResolver, it.uri) }
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            app.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // already gone, or not ours
        }
    }

    private fun child(tree: Uri, dir: Uri, name: String): Doc? =
        list(app, tree, DocumentsContract.getDocumentId(dir)).firstOrNull { it.name == name }

    /** A file or folder inside a picked folder. */
    class Doc(val name: String, val uri: Uri, val isDir: Boolean, val modified: Long)

    companion object {
        const val FOLDER = "Podwave"
        private const val MIME = "application/json"
        private const val PREFS = "backup"
        private const val KEY_AUTO = "auto"
        private const val KEY_TREE = "tree"
        private const val KEY_HASHES = "hashes"
        private const val KEY_URIS = "uris"
        private const val KEY_LAST_AT = "last_at"
        private const val KEY_ERROR = "error"
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

        /** Where the folder picker opens when restoring: Download/Podwave, if it's there. */
        val pickerStart: Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Download/$FOLDER")

        /** A picked folder as you'd say it, e.g. "Download/Podwave". */
        fun displayPath(context: Context, tree: Uri): String {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            if (tree.authority == EXTERNAL_STORAGE) {
                val volume = docId.substringBefore(':')
                val path = docId.substringAfter(':', "").trim('/')
                val root = if (volume == "primary") "" else "Memory card"
                return listOf(root, path).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { "Phone storage" }
            }
            return try {
                context.contentResolver.query(
                    DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (e: Exception) {
                null
            } ?: "the folder you chose"
        }

        /** The files and folders directly inside [dirId] of [tree]. */
        fun list(context: Context, tree: Uri, dirId: String): List<Doc> {
            val out = ArrayList<Doc>()
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), projection, null, null, null)?.use { c: Cursor ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out += Doc(
                        name = c.getString(1).orEmpty(),
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        modified = if (c.isNull(3)) 0L else c.getLong(3),
                    )
                }
            }
            return out
        }

        /** Text of a picked file, or null if it can't be read or is too big to be a backup. */
        fun readText(context: Context, uri: Uri): String? = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readUpTo(MAX_FILE + 1)
                if (bytes.size > MAX_FILE) null else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }

        /** 64 MB: far more than any show's episodes, small enough not to run out of memory. */
        private const val MAX_FILE = 64 * 1024 * 1024

        private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
