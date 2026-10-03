package io.github.akrishna87.podcasts.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.Notifications
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.Settings
import io.github.akrishna87.podcasts.feed.formatBytes
import io.github.akrishna87.podcasts.feed.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: PodcastViewModel) {
    val p = vm.prefs()
    var pick by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importOpml) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri -> uri?.let(vm::exportOpml) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack = { vm.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomSpace.current)) {
            item { SettingsHeader("Playback") }
            item {
                SettingRow("Speed", speedLabel(Settings.speed(p)), "For shows without their own") { pick = "speed" }
                Toggle("Trim silence", Settings.trimSilence(p), "Shortens pauses without changing voices") { vm.setting(Settings.TRIM_SILENCE, it) }
                Toggle("Volume boost", Settings.boost(p), "Lifts quiet voices") { vm.setting(Settings.BOOST, it) }
                SettingRow("Skip back", "${Settings.skipBackSec(p)} s") { pick = "back" }
                SettingRow("Skip forward", "${Settings.skipForwardSec(p)} s") { pick = "forward" }
                Toggle(
                    "Headphone buttons skip time",
                    Settings.buttonsSkip(p),
                    "Next/previous on headphones, the car and the lock screen skip forward/back instead of changing episode",
                ) { vm.setting(Settings.BUTTONS_SKIP, it) }
                Toggle("Autoplay", Settings.autoplay(p), "When Up Next runs out, play the show's next episode") { vm.setting(Settings.AUTOPLAY, it) }
            }
            item { SettingsHeader("Downloads and storage") }
            item {
                Toggle("Download on Wi-Fi only", Settings.wifiOnly(p), "Saves your mobile data") { vm.setting(Settings.WIFI_ONLY, it) }
                Toggle("Delete played episodes", Settings.deletePlayed(p), "Frees space once you've finished an episode (starred and bookmarked ones are kept)") {
                    vm.setting(Settings.DELETE_PLAYED, it)
                }
                SettingRow("Downloaded episodes", formatBytes(vm.bytesUsed)) { vm.libraryTab = io.github.akrishna87.podcasts.LibraryTab.DOWNLOADS; vm.selectSection(io.github.akrishna87.podcasts.Section.LIBRARY) }
            }
            item { SettingsHeader("New episodes") }
            item {
                val hours = Settings.refreshHours(p)
                SettingRow("Check for new episodes", if (hours == 0) "Only when I open the app" else if (hours == 1) "Every hour" else "Every $hours hours") { pick = "refresh" }
                SettingRow("Alerts for guests and topics", "${vm.snap.alerts.size}") { vm.open(Screen.Alerts) }
            }
            item { SettingsHeader("Your shows") }
            item {
                SettingRow("Import from another app", "OPML", "Pocket Casts, Overcast, Apple Podcasts, AntennaPod and others can export one") { importer.launch(arrayOf("*/*")) }
                SettingRow("Export your shows", "OPML", "To move to another app or keep a backup") { exporter.launch("kural-shows.opml") }
                SettingRow("Listening stats", "") { vm.open(Screen.StatsPage) }
            }
            item { SettingsHeader("About") }
            item {
                Text(
                    "Kural (குரல், “voice”) finds shows through Apple's public podcast directory and reads each show's own feed. " +
                        "There's no account, no ads and no tracking: your shows, history and settings stay on this phone.",
                    color = Palette.SubText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    when (pick) {
        "speed" -> SpeedDialog(Settings.speed(p), { vm.setting(Settings.SPEED, it) }, { pick = null })
        "back", "forward" -> {
            val key = if (pick == "back") Settings.SKIP_BACK else Settings.SKIP_FORWARD
            val current = if (pick == "back") Settings.skipBackSec(p) else Settings.skipForwardSec(p)
            ChoiceDialog(
                if (pick == "back") "Skip back" else "Skip forward",
                Settings.SKIP_CHOICES.map { "$it seconds" },
                Settings.SKIP_CHOICES.indexOf(current),
                { vm.setting(key, Settings.SKIP_CHOICES[it]); pick = null },
                { pick = null },
            )
        }
        "refresh" -> ChoiceDialog(
            "Check for new episodes",
            Settings.REFRESH_CHOICES.map { if (it == 0) "Only when I open the app" else if (it == 1) "Every hour" else "Every $it hours" },
            Settings.REFRESH_CHOICES.indexOf(Settings.refreshHours(p)),
            { vm.setting(Settings.REFRESH_HOURS, Settings.REFRESH_CHOICES[it]); pick = null },
            { pick = null },
        )
    }
}

/** Follow a guest or topic: new episodes of any podcast that mention them. */
@Composable
fun AlertsScreen(vm: PodcastViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var term by remember { mutableStateOf("") }
    fun add() {
        if (term.isBlank()) return
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.addAlert(term)
        term = ""
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Guests & topics", onBack = { vm.back() }) {
            if (vm.snap.hits.isNotEmpty()) TextButton(onClick = vm::clearHits) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
            item {
                Text(
                    "Follow a person or a topic and Kural checks every podcast in the directory for new episodes that mention them, " +
                        "and tells you when one comes out. Great for favourite guests.",
                    color = Palette.SubText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    placeholder = { Text("A name or topic, e.g. Jane Goodall") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    trailingIcon = { IconButton(onClick = ::add, enabled = term.isNotBlank()) { Icon(Icons.Rounded.Add, "Follow") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                if (vm.snap.alerts.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.snap.alerts.forEach { a ->
                            InputChip(
                                selected = false,
                                onClick = { vm.searchPerson(a.term) },
                                label = { Text(a.term) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.Close,
                                        "Stop following ${a.term}",
                                        modifier = Modifier.size(18.dp).clip(CircleShape).clickableNoRipple { vm.removeAlert(a.term) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (vm.snap.hits.isNotEmpty()) {
                item { SectionTitle("Found for you") }
                items(vm.snap.hits, key = { it.episode.id }) { hit ->
                    Column {
                        Text(
                            "Mentions “${hit.term}”",
                            color = Palette.Gold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 84.dp, top = 6.dp),
                        )
                        EpisodeRow(vm, hit.episode)
                    }
                }
            } else if (vm.snap.alerts.isNotEmpty()) {
                item { Text("Nothing new yet. Kural looks each time it checks for new episodes.", color = Palette.SubText, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick))

/** How much you've listened, and how much time speed and trimmed silences saved. */
@Composable
fun StatsScreen(vm: PodcastViewModel) {
    val stats = vm.stats()
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Listening stats", onBack = { vm.back() }) { TextButton(onClick = { confirm = true }) { Text("Reset") } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomSpace.current)) {
            item {
                Text(
                    "Since " + SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(stats.since)),
                    color = Palette.SubText,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Listened", hours(stats.listenedMs), Palette.Violet, Modifier.weight(1f))
                    StatTile("Time saved", hours(stats.savedMs + stats.skippedMs), Palette.Green, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Episodes finished", "${stats.finished}", Palette.Coral, Modifier.weight(1f))
                    StatTile("Intros & outros skipped", hours(stats.skippedMs), Palette.Gold, Modifier.weight(1f))
                }
                Text(
                    "Time saved counts speed-ups, trimmed silences and skipped intros and outros.",
                    color = Palette.Faint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                SettingsHeader("Most listened")
            }
            val top = stats.byPodcast.entries.sortedByDescending { it.value }.take(10)
            val max = top.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
            items(top, key = { it.key }) { (id, ms) ->
                val title = vm.podcastTitle(id).ifBlank { "A show you no longer follow" }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(vm.entry(id)?.podcast?.artworkUrl, title, Modifier.size(40.dp), RoundedCornerShape(6.dp), titleSize = 6)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        ProgressLine(ms.toFloat() / max, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(hours(ms), color = Palette.SubText, fontSize = 13.sp)
                }
            }
            if (top.isEmpty()) item { Text("Play something and your stats start here.", color = Palette.SubText, modifier = Modifier.padding(vertical = 8.dp)) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset your stats?") },
            text = { Text("Your listening totals start again from today.") },
            confirmButton = { TextButton(onClick = { vm.resetStats(); confirm = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun hours(ms: Long): String = if (ms < 60_000) "0 min" else formatDuration(ms / 1000)

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, color = Palette.SubText, fontSize = 13.sp)
    }
}
