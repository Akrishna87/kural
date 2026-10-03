package io.github.akrishna87.podcasts.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.Load
import io.github.akrishna87.podcasts.Notifications
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.data.AutoAdd
import io.github.akrishna87.podcasts.feed.htmlToText

private enum class EpisodeView(val label: String) { ALL("All"), UNPLAYED("Unplayed"), DOWNLOADED("Downloaded"), STARRED("Starred") }

/** A show: what it is, follow it, its settings, and its episodes. */
@Composable
fun PodcastScreen(vm: PodcastViewModel, page: Screen.PodcastPage) {
    LaunchedEffect(page) { vm.loadPage(page) }
    val key = page.feedUrl ?: "apple:${page.listing?.appleId}"
    when (val load = vm.pageLoads[key] ?: Load.Loading) {
        is Load.Loading -> Column(Modifier.fillMaxSize()) {
            TopBar(page.listing?.title.orEmpty(), onBack = { vm.back() })
            Loading()
        }
        is Load.Failed -> Column(Modifier.fillMaxSize()) {
            TopBar(page.listing?.title.orEmpty(), onBack = { vm.back() })
            LoadFailed(load.message, { vm.retryPage(page) })
        }
        is Load.Ready -> ShowPage(vm, load.value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowPage(vm: PodcastViewModel, feedUrl: String) {
    val podcast = vm.pagePodcast(feedUrl) ?: return
    val entry = vm.entry(feedUrl)
    val following = entry?.subscribed == true
    val context = LocalContext.current
    val tint = artTint(vm, podcast.artworkUrl, podcast.title)
    var view by rememberSaveable { mutableStateOf(EpisodeView.ALL) }
    val newestFirst = entry?.settings?.newestFirst ?: !podcast.serial
    var aboutOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val all = vm.pageEpisodes(feedUrl)
    val shown = all
        .filter { e ->
            val s = vm.state(e.id)
            when (view) {
                EpisodeView.ALL -> true
                EpisodeView.UNPLAYED -> s?.played != true
                EpisodeView.DOWNLOADED -> s?.downloaded == true
                EpisodeView.STARRED -> s?.starred == true
            }
        }
        .let { if (newestFirst) it else it.sortedBy { e -> e.publishedAt } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.deep(0.25f), Palette.Background)))) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        if (following) {
                            IconButton(onClick = { vm.refresh(feedUrl) }) { Icon(Icons.Rounded.Refresh, "Refresh this show") }
                            IconButton(onClick = { vm.open(Screen.ShowSettings(feedUrl)) }) { Icon(Icons.Rounded.Tune, "Show settings") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuItem("Share show", Icons.Rounded.Share) {
                                    menu = false
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                                .putExtra(Intent.EXTRA_TEXT, "${podcast.title}\n${podcast.link ?: podcast.feedUrl}\n\nFeed: ${podcast.feedUrl}"),
                                            "Share show",
                                        ),
                                    )
                                }
                                if (following) {
                                    MenuItem("Mark all as played", Icons.Rounded.DoneAll) { menu = false; vm.markAllPlayed(feedUrl) }
                                    MenuItem("Unfollow", Icons.Rounded.RemoveCircleOutline) { menu = false; confirmUnfollow = true }
                                }
                                podcast.link?.let { link ->
                                    MenuItem("Website", Icons.Rounded.Language) {
                                        menu = false
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(podcast.artworkUrl, podcast.title, Modifier.size(180.dp), RoundedCornerShape(16.dp), titleSize = 18)
                        Spacer(Modifier.height(16.dp))
                        Text(podcast.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (podcast.author.isNotBlank()) {
                            Text(podcast.author, color = Palette.SubText, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val meta = listOfNotNull(
                            podcast.categories.firstOrNull(),
                            "${all.size} episodes",
                            if (podcast.serial) "Serial" else null,
                        ).joinToString("  ·  ")
                        Text(meta, color = Palette.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (following) {
                                OutlinedButton(onClick = { confirmUnfollow = true }) {
                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Following")
                                }
                            } else {
                                Button(onClick = { vm.subscribe(feedUrl) }, enabled = feedUrl !in vm.subscribing) {
                                    Icon(Icons.Rounded.Add, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Follow")
                                }
                            }
                            // Start a serial from the beginning, or the newest of anything else.
                            val start = if (podcast.serial) all.filter { it.type != "trailer" }.minByOrNull { it.publishedAt } else all.firstOrNull()
                            if (start != null) {
                                FilledTonalButton(onClick = { vm.play(start) }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (podcast.serial) "Play episode 1" else "Play latest")
                                }
                            }
                        }
                        if (following && entry != null && !entry.settings.notify) {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                vm.updateShowSettings(feedUrl) { it.copy(notify = true) }
                                vm.say("You'll be notified of new episodes")
                            }) {
                                Icon(Icons.Rounded.NotificationsNone, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Notify me of new episodes", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            val about = remember(podcast.description) { htmlToText(podcast.description) }
            if (about.isNotBlank()) {
                Text(
                    about,
                    color = Palette.SubText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = if (aboutOpen) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { aboutOpen = !aboutOpen }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (podcast.fundingUrl != null) {
                TextButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(podcast.fundingUrl))) } },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Palette.Coral, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(podcast.fundingLabel ?: "Support the show")
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Episodes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
                TextButton(onClick = {
                    if (entry != null) vm.updateShowSettings(feedUrl) { it.copy(newestFirst = !newestFirst) }
                    else vm.say("Follow the show to change its order")
                }) {
                    Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (newestFirst) "Newest first" else "Oldest first")
                }
            }
            ChipRow(EpisodeView.entries, view, { it.label }, { view = it })
        }

        if (shown.isEmpty()) {
            item { Text("No episodes here.", color = Palette.SubText, modifier = Modifier.padding(16.dp)) }
        }
        items(shown, key = { it.id }) { e -> EpisodeRow(vm, e, showShow = false) }
    }

    if (confirmUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            title = { Text("Unfollow “${podcast.title}”?") },
            text = { Text("Its new episodes stop coming in. Downloads, stars and bookmarks are kept.") },
            confirmButton = { TextButton(onClick = { vm.unsubscribe(feedUrl); confirmUnfollow = false }) { Text("Unfollow") } },
            dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text("Cancel") } },
        )
    }
}

/** Per-show settings, like Pocket Casts' and Overcast's. */
@Composable
fun ShowSettingsScreen(vm: PodcastViewModel, podcastId: String) {
    val entry = vm.entry(podcastId) ?: return
    val s = entry.settings
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var pick by remember { mutableStateOf<String?>(null) }
    fun update(change: (io.github.akrishna87.podcasts.data.PodcastSettings) -> io.github.akrishna87.podcasts.data.PodcastSettings) =
        vm.updateShowSettings(podcastId, change)

    Column(Modifier.fillMaxSize()) {
        TopBar(entry.podcast.title, onBack = { vm.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomSpace.current)) {
            item { SettingsHeader("New episodes") }
            item {
                SettingRow("Add to Up Next", s.autoAdd.label) { pick = "autoAdd" }
                Toggle("Download automatically", s.autoDownload, "The newest few, as they come out") { on -> update { it.copy(autoDownload = on) } }
                Toggle("Notify me", s.notify) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    update { it.copy(notify = on) }
                }
                Toggle("Oldest first", !s.newestFirst, "For serials and courses: listen in order") { on -> update { it.copy(newestFirst = !on) } }
            }
            item { SettingsHeader("Skipping") }
            item {
                SettingRow("Skip intro", if (s.skipIntroSec == 0) "Off" else "First ${s.skipIntroSec} s") { pick = "intro" }
                SettingRow("Skip outro", if (s.skipOutroSec == 0) "Off" else "Last ${s.skipOutroSec} s") { pick = "outro" }
            }
            item { SettingsHeader("Playback effects") }
            item {
                Toggle("Custom effects for this show", s.customEffects, "Otherwise the app-wide speed and effects apply") { on -> update { it.copy(customEffects = on) } }
                if (s.customEffects) {
                    SettingRow("Speed", speedLabel(s.speed)) { pick = "speed" }
                    Toggle("Trim silence", s.trimSilence, "Shortens pauses without changing voices") { on -> update { it.copy(trimSilence = on) } }
                    Toggle("Volume boost", s.boost, "Lifts quiet voices; good for noisy places") { on -> update { it.copy(boost = on) } }
                }
            }
        }
    }

    when (pick) {
        "autoAdd" -> ChoiceDialog("Add new episodes to Up Next", AutoAdd.entries.map { it.label }, s.autoAdd.ordinal, { i ->
            update { it.copy(autoAdd = AutoAdd.entries[i]) }; pick = null
        }, { pick = null })
        "intro", "outro" -> {
            val choices = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
            val current = if (pick == "intro") s.skipIntroSec else s.skipOutroSec
            ChoiceDialog(
                if (pick == "intro") "Skip the first…" else "Skip the last…",
                choices.map { if (it == 0) "Off" else "$it seconds" },
                choices.indexOf(current),
                { i ->
                    val v = choices[i]
                    if (pick == "intro") update { it.copy(skipIntroSec = v) } else update { it.copy(skipOutroSec = v) }
                    pick = null
                },
                { pick = null },
            )
        }
        "speed" -> SpeedDialog(s.speed, { v -> update { it.copy(speed = v) } }, { pick = null })
    }
}

@Composable
fun SettingsHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Violet, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
fun SettingRow(label: String, value: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, color = Palette.SubText, fontSize = 13.sp)
        }
        Text(value, color = Palette.Violet, fontWeight = FontWeight.SemiBold)
    }
}
