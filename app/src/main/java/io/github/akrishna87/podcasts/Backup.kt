package io.github.akrishna87.podcasts

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.akrishna87.podcasts.data.BackupFile
import io.github.akrishna87.podcasts.ui.AccentColor
import io.github.akrishna87.podcasts.ui.ThemeMode
import io.github.akrishna87.podcasts.ui.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A backup that's been read from a folder you picked, waiting for you to confirm restoring it. */
class RestorePlan(
    /** Where it came from, as shown to you. */
    val source: String,
    val contents: BackupFile.Contents,
    /** The backup folder, to keep backing up into after restoring (so there's one backup, not two). */
    val adoptFolder: Uri?,
)

/**
 * Backs up your shows, Up Next, history and settings to Download/Podwave as you go (no internet
 * needed), and restores them from there, e.g. on a new phone after copying the folder over.
 */
object Backups {
    /** Goes up after each backup or restore, so Settings re-reads what it shows. */
    var version by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One backup or restore at a time. */
    private val lock = Mutex()
    private var job: Job? = null
    private var started = false
    /** Kept here: preferences only hold on to their listeners weakly. */
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Backs up after changes from now on (if automatic backup is on). Called when the app or player starts. */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> request(app) }
        prefsListener = listener
        BackupFile.PREFS.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener) }
        scope.launch { app.library().version.collect { request(app) } }
    }

    /** Backs up a little after the last change, so a burst of changes is written once. */
    fun request(context: Context, delayMs: Long = 10_000) {
        val app = context.applicationContext
        if (!BackupStorage(app).autoBackup) return
        synchronized(this) {
            job?.cancel()
            job = scope.launch {
                delay(delayMs)
                backUp(app, force = false)
            }
        }
    }

    /** Backs up now; returns what to tell you. */
    suspend fun backUpNow(context: Context): String {
        val app = context.applicationContext
        synchronized(this) { job?.cancel() }
        val storage = BackupStorage(app)
        val ok = withContext(Dispatchers.IO) { backUp(app, force = true) }
        return when {
            ok == null -> "Nothing to back up yet: follow a show first"
            ok == true -> "Backed up to ${storage.location}"
            else -> storage.lastError ?: "Couldn't save the backup"
        }
    }

    /** Null if there's nothing worth backing up (a fresh install), so an older backup isn't buried. */
    private suspend fun backUp(app: Context, force: Boolean): Boolean? {
        val result = lock.withLock {
            val contents = BackupFile.Contents(System.currentTimeMillis(), app.library().exportFiles(), snapshotPrefs(app))
            if (contents.isEmpty) return@withLock null
            val files = BackupFile.write(contents)
            // The time it was made changes every time; only rewrite the file when the rest does.
            val sameAs = mapOf(BackupFile.NAME to BackupFile.manifest(contents.withTime(0)))
            BackupStorage(app).writeAll(files, sameAs, force)
        }
        withContext(Dispatchers.Main) { version++ }
        return result
    }

    private fun snapshotPrefs(context: Context): Map<String, Map<String, Any>> = BackupFile.PREFS.associateWith { name ->
        val skip = BackupFile.PHONE_ONLY[name].orEmpty()
        val out = LinkedHashMap<String, Any>()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) -> if (v != null && k !in skip) out[k] = v }
        out
    }

    // ----- Restoring -----

    /**
     * Reads the backup in the folder you picked: the Podwave folder, or the folder above it (like
     * Download). Returns null if there's no Podwave backup there.
     */
    suspend fun read(context: Context, tree: Uri): RestorePlan? = withContext(Dispatchers.IO) {
        try {
            readFolder(context.applicationContext, tree)
        } catch (e: Exception) {
            null
        }
    }

    private fun readFolder(context: Context, tree: Uri): RestorePlan? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var docs = BackupStorage.list(context, tree, rootId)
        val picked = BackupStorage.displayPath(context, tree)
        var where = picked
        if (docs.none { isMainFile(it) }) {
            docs.firstOrNull { it.isDir && it.name.equals(BackupStorage.FOLDER, ignoreCase = true) }?.let { sub ->
                docs = BackupStorage.list(context, tree, DocumentsContract.getDocumentId(sub.uri))
                where = "$where/${sub.name}"
            }
        }
        val episodes: Map<String, Uri> by lazy {
            docs.firstOrNull { it.isDir && it.name == "episodes" }?.let { dir ->
                BackupStorage.list(context, tree, DocumentsContract.getDocumentId(dir.uri)).filter { !it.isDir }.associate { "episodes/${it.name}" to it.uri }
            }.orEmpty()
        }
        // The backup itself first, then copies made next to it ("Podwave backup (1).json"), newest first.
        val candidates = docs.filter { isMainFile(it) }
            .sortedWith(compareByDescending<BackupStorage.Doc> { it.name == BackupFile.NAME }.thenByDescending { it.modified })
        for (doc in candidates) {
            val text = BackupStorage.readText(context, doc.uri) ?: continue
            val contents = BackupFile.read(text) { name -> episodes[name]?.let { BackupStorage.readText(context, it) } } ?: continue
            // Only the folder you picked can be written to; a backup found one level down is read-only.
            return RestorePlan(where, contents, adoptFolder = if (where == picked) tree else null)
        }
        return null
    }

    private fun isMainFile(d: BackupStorage.Doc) =
        !d.isDir && d.name.startsWith("Podwave backup", ignoreCase = true) && d.name.endsWith(".json", ignoreCase = true)

    /**
     * Replaces the library and settings with the backup's. The player follows the restored Up
     * Next by itself; [onDone] gets what to tell you, on the main thread, so the screens can be
     * redrawn with the restored settings.
     */
    fun restore(context: Context, plan: RestorePlan, onDone: (String) -> Unit) {
        val app = context.applicationContext
        synchronized(this) { job?.cancel() }
        scope.launch {
            val ok = try {
                lock.withLock { restoreNow(app, plan) }
                true
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    ThemeSettings.update(app, themeMode(app), app.getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("wallpaperColors", false))
                    val accent = app.getSharedPreferences("theme", Context.MODE_PRIVATE).getString("accent", null)
                    ThemeSettings.setAccent(app, AccentColor.entries.firstOrNull { it.name == accent } ?: AccentColor.VIOLET)
                    Refresher.schedule(app)
                }
                version++
                val c = plan.contents
                onDone(
                    if (!ok) "Couldn't restore that backup"
                    else "Restored " + (if (c.shows == 1) "1 show" else "${c.shows} shows") + ", Up Next and your settings",
                )
            }
            if (ok) request(app, delayMs = 2_000)
        }
    }

    private fun themeMode(context: Context): ThemeMode {
        val name = context.getSharedPreferences("theme", Context.MODE_PRIVATE).getString("mode", null)
        return ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.DARK
    }

    private fun restoreNow(app: Context, plan: RestorePlan) {
        val lib = app.library()
        // Downloads under way belong to what's being replaced.
        lib.states().filter { it.downloadId != 0L }.forEach { Downloads.cancel(app, lib, it.episodeId) }
        lib.importFiles(plan.contents.library) { id -> Downloads.file(app, id).let { it.isFile && it.length() > 0 } }
        // Audio of episodes that aren't in the restored library would only take up space.
        val keep = lib.states().filter { it.downloaded }.mapTo(HashSet()) { Downloads.file(app, it.episodeId).name }
        Downloads.file(app, "x").parentFile?.listFiles()?.forEach { f -> if (f.isFile && f.name !in keep) f.delete() }
        for ((name, values) in plan.contents.prefs) {
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val keepKeys = BackupFile.PHONE_ONLY[name].orEmpty()
            val e = prefs.edit()
            prefs.all.keys.filter { it !in keepKeys }.forEach { e.remove(it) }
            for ((k, v) in values) {
                if (k in keepKeys) continue
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            e.commit()
        }
        // Keep backing up into the folder restored from, so there's one backup, not two.
        plan.adoptFolder?.let { BackupStorage(app).adopt(it) }
    }
}
