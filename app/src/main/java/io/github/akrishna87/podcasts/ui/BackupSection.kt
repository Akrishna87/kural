package io.github.akrishna87.podcasts.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.BackupStorage
import io.github.akrishna87.podcasts.Backups
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.RestorePlan
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings → Backup and restore: automatic backup to Download/Podwave, and restoring from it. */
@Composable
fun BackupSection(vm: PodcastViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { BackupStorage(context) }
    var auto by remember { mutableStateOf(storage.autoBackup) }
    var explain by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun backUp() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                vm.say(Backups.backUpNow(context))
            } finally {
                busy = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val read = Backups.read(context, uri)
                if (read == null) vm.say("There's no Podwave backup in that folder") else plan = read
            }
        }
    }
    // Android 9 and older: saving in Download needs the storage permission.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) backUp() else vm.say("Without storage access, Podwave can't save a backup in Download")
    }

    @Suppress("UNUSED_VARIABLE")
    val refresh = Backups.version // re-read the status below after each backup
    val needsPermission = storage.needsPermission()
    val error = storage.lastError
    val last = storage.lastBackupAt

    Toggle(
        "Automatic backup",
        auto,
        "Keeps a copy of your shows, Up Next, history, bookmarks and settings in ${storage.location}, " +
            "updated as you go. No internet needed. Downloaded episodes aren't included.",
    ) {
        auto = it
        storage.autoBackup = it
        if (it) Backups.request(context, delayMs = 0)
    }
    SettingRow(
        if (needsPermission) "Allow storage access and back up" else "Back up now",
        if (busy) "…" else "",
        when {
            error != null -> error
            last > 0 -> "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
            else -> "No backup yet"
        },
    ) {
        if (needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else backUp()
    }
    SettingRow(
        "Restore from backup",
        "",
        "New phone? Copy the ${BackupStorage.FOLDER} folder from Download over (with a file manager or USB cable), then restore it here",
    ) { explain = true }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Restore from a backup") },
            text = {
                Text(
                    "Choose the ${BackupStorage.FOLDER} folder (it's in Download, or wherever you copied it), " +
                        "tap “Use this folder”, then “Allow”.\n\n" +
                        "Restoring replaces the shows you follow, Up Next, your history, bookmarks and settings " +
                        "on this phone with the ones in the backup. Downloaded episodes aren't in a backup.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    try {
                        folderPicker.launch(BackupStorage.pickerStart)
                    } catch (e: ActivityNotFoundException) {
                        vm.say("This phone has no folder picker")
                    }
                }) { Text("Choose folder") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } },
        )
    }

    plan?.let { p ->
        val c = p.contents
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Restore this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val made = if (c.createdMs > 0) " · made " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.createdMs)) else ""
                    Text("From ${p.source}$made", color = Palette.SubText, fontSize = 13.sp)
                    val lines = buildList {
                        add(if (c.shows == 1) "1 show you follow" else "${c.shows} shows you follow")
                        if (c.upNext > 0) add(if (c.upNext == 1) "1 episode in Up Next" else "${c.upNext} episodes in Up Next")
                        if (c.favourites > 0) add(if (c.favourites == 1) "1 favourite" else "${c.favourites} favourites")
                        if (c.bookmarks > 0) add(if (c.bookmarks == 1) "1 bookmark" else "${c.bookmarks} bookmarks")
                        if (c.prefs.isNotEmpty()) add("Your settings")
                    }
                    lines.forEach { Text("•  $it", fontWeight = FontWeight.SemiBold) }
                    Text(
                        "This replaces your shows, Up Next and settings on this phone.",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    plan = null
                    if (vm.isPlaying) vm.togglePlay()
                    val activity = context.findActivity()
                    Backups.restore(context, p) { message ->
                        vm.say(message)
                        // Draw every screen again with the restored settings.
                        if (activity != null && !activity.isFinishing && !activity.isDestroyed) activity.recreate()
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("Cancel") } },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
