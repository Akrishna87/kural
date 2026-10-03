package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.LibraryTab
import io.github.akrishna87.podcasts.Load
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.Section
import io.github.akrishna87.podcasts.data.Bookmark
import io.github.akrishna87.podcasts.data.EpisodeFilter
import io.github.akrishna87.podcasts.feed.formatBytes
import io.github.akrishna87.podcasts.feed.formatClock

@Composable
fun LibraryScreen(vm: PodcastViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Library", onBack = null) {
            IconButton(onClick = { vm.open(Screen.StatsPage) }) { Icon(Icons.Rounded.Insights, "Listening stats") }
            IconButton(onClick = { vm.open(Screen.SettingsPage) }) { Icon(Icons.Rounded.Settings, "Settings") }
        }
        ChipRow(LibraryTab.entries, vm.libraryTab, { it.label }, { vm.libraryTab = it })
        Spacer(Modifier.height(8.dp))
        when (vm.libraryTab) {
            LibraryTab.SHOWS -> ShowsGrid(vm)
            LibraryTab.FILTERS -> FiltersList(vm)
            LibraryTab.DOWNLOADS -> EpisodeList(vm, vm.snap.downloads, header = {
                if (vm.snap.downloads.isNotEmpty()) {
                    Text(
                        "${vm.snap.downloads.size} episodes · ${formatBytes(vm.bytesUsed)} on this phone",
                        color = Palette.SubText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }) {
                EmptyState(Icons.Rounded.DownloadForOffline, "No downloads", "Download episodes to listen without a connection. Shows can download new episodes by themselves, too.")
            }
            LibraryTab.STARRED -> EpisodeList(vm, vm.snap.starred) {
                EmptyState(Icons.Rounded.StarOutline, "Nothing starred", "Star episodes you love or want to come back to.")
            }
            LibraryTab.BOOKMARKS -> BookmarksList(vm)
            LibraryTab.HISTORY -> EpisodeList(vm, vm.snap.history) {
                EmptyState(Icons.Rounded.History, "No history yet", "Episodes you play show up here.")
            }
        }
    }
}

@Composable
private fun ShowsGrid(vm: PodcastViewModel) {
    val shows = vm.snap.shows
    if (vm.snap.loaded && shows.isEmpty()) {
        EmptyState(
            Icons.Rounded.Podcasts,
            "No shows yet",
            "Shows you follow appear here, newest episode first.",
            action = "Find podcasts",
            onAction = { vm.selectSection(Section.DISCOVER) },
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalBottomSpace.current),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("${shows.size} show${if (shows.size == 1) "" else "s"}", color = Palette.SubText, modifier = Modifier.padding(4.dp))
        }
        // The badge counts each show's new episodes still in the inbox.
        val newByShow = vm.snap.newEpisodes.groupingBy { it.podcastId }.eachCount()
        items(shows, key = { it.entry.id }) { row ->
            ShowTile(row.entry.podcast.title, "", row.entry.podcast.artworkUrl, { vm.openPodcast(row.entry.id) }, badge = newByShow[row.entry.id] ?: 0)
        }
    }
}

@Composable
private fun EpisodeList(
    vm: PodcastViewModel,
    list: List<io.github.akrishna87.podcasts.data.Episode>,
    header: @Composable () -> Unit = {},
    empty: @Composable () -> Unit,
) {
    if (vm.snap.loaded && list.isEmpty()) {
        empty()
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item { header() }
        items(list, key = { it.id }) { e -> EpisodeRow(vm, e) }
    }
}

@Composable
private fun BookmarksList(vm: PodcastViewModel) {
    val list = vm.snap.bookmarks
    if (vm.snap.loaded && list.isEmpty()) {
        EmptyState(Icons.Rounded.BookmarkBorder, "No bookmarks", "In the player, tap the bookmark to save a moment, with a note if you like.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        items(list, key = { it.id }) { b -> BookmarkRow(vm, b, showEpisode = true) }
    }
}

/** A saved moment: tap to play from it. */
@Composable
fun BookmarkRow(vm: PodcastViewModel, b: Bookmark, showEpisode: Boolean) {
    val e = vm.episode(b.episodeId)
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { e?.let { vm.playAt(it, b.positionMs) } }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Coral.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(formatClock(b.positionMs), color = Palette.Coral, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.note.ifBlank { "Bookmark" }, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (showEpisode && e != null) {
                Text("${e.title} · ${vm.podcastTitle(e.podcastId)}", color = Palette.SubText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for this bookmark", tint = Palette.SubText) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem("Edit note", Icons.Rounded.Edit) { editing = true; menu = false }
                if (e != null) MenuItem("Share this moment", Icons.Rounded.Share) { context.startActivity(vm.shareIntent(e, b.positionMs)); menu = false }
                MenuItem("Delete", Icons.Rounded.DeleteOutline) { vm.removeBookmark(b.id); menu = false }
            }
        }
    }
    if (editing) {
        var note by remember { mutableStateOf(b.note) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Bookmark at ${formatClock(b.positionMs)}") },
            text = { OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text("A note (optional)") }) },
            confirmButton = { TextButton(onClick = { vm.editBookmark(b.id, note); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

// ----- Filters -----

@Composable
private fun FiltersList(vm: PodcastViewModel) {
    var editing by remember { mutableStateOf<EpisodeFilter?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Text(
                "Smart playlists that keep themselves up to date: pick shows and rules, and matching episodes appear.",
                color = Palette.SubText,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(vm.snap.filters, key = { it.id }) { f ->
            ListItem(
                headlineContent = { Text(f.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(describe(vm, f), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Violet.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FilterList, contentDescription = null, tint = Palette.Violet)
                    }
                },
                modifier = Modifier.clickable { vm.open(Screen.FilterPage(f.id)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            TextButton(onClick = { editing = vm.newFilter() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New filter")
            }
        }
    }
    editing?.let { f -> FilterEditor(vm, f, onDone = { editing = null }) }
}

private fun describe(vm: PodcastViewModel, f: EpisodeFilter): String {
    val parts = ArrayList<String>()
    parts += if (f.podcastIds.isEmpty()) "All shows" else if (f.podcastIds.size == 1) vm.lib.podcast(f.podcastIds[0])?.title ?: "1 show" else "${f.podcastIds.size} shows"
    if (f.unplayedOnly) parts += "unplayed"
    if (f.inProgressOnly) parts += "in progress"
    if (f.downloadedOnly) parts += "downloaded"
    if (f.starredOnly) parts += "starred"
    if (f.maxAgeDays > 0) parts += "last ${f.maxAgeDays} days"
    if (f.maxMinutes > 0) parts += "under ${f.maxMinutes} min"
    return parts.joinToString(" · ")
}

/** One filter's episodes. */
@Composable
fun FilterScreen(vm: PodcastViewModel, filterId: String) {
    val f = vm.snap.filters.firstOrNull { it.id == filterId }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(f, vm.tick) { f?.let(vm::loadFilter) }
    Column(Modifier.fillMaxSize()) {
        TopBar(f?.name ?: "Filter", onBack = { vm.back() }) {
            if (f != null) IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Tune, "Edit filter") }
        }
        if (f == null) return@Column
        Text(describe(vm, f), color = Palette.SubText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        when (val r = vm.filterResults[f.id] ?: Load.Loading) {
            is Load.Loading -> Loading()
            is Load.Failed -> LoadFailed(r.message, { vm.loadFilter(f) })
            is Load.Ready -> if (r.value.isEmpty()) {
                EmptyState(Icons.Rounded.FilterList, "Nothing matches", "Episodes from your shows that match this filter will appear here.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
                    item {
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            TextButton(onClick = { r.value.forEach(vm::playLast) }) {
                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add all to Up Next")
                            }
                        }
                    }
                    items(r.value, key = { it.id }) { e -> EpisodeRow(vm, e) }
                }
            }
        }
    }
    if (editing && f != null) FilterEditor(vm, f, onDone = { editing = false })
}

/** Name, shows and rules for a filter. */
@Composable
private fun FilterEditor(vm: PodcastViewModel, initial: EpisodeFilter, onDone: () -> Unit) {
    var f by remember { mutableStateOf(initial) }
    val isNew = vm.snap.filters.none { it.id == initial.id }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "New filter" else "Edit filter") },
        text = {
            Column(Modifier.verticalScrollable()) {
                OutlinedTextField(value = f.name, onValueChange = { f = f.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                Toggle("Unplayed only", f.unplayedOnly) { f = f.copy(unplayedOnly = it) }
                Toggle("In progress only", f.inProgressOnly) { f = f.copy(inProgressOnly = it) }
                Toggle("Downloaded only", f.downloadedOnly) { f = f.copy(downloadedOnly = it) }
                Toggle("Starred only", f.starredOnly) { f = f.copy(starredOnly = it) }
                Spacer(Modifier.height(8.dp))
                Text("Released", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(0, 1, 7, 14, 30), f.maxAgeDays, { if (it == 0) "Any time" else if (it == 1) "Today" else "$it days" }, { f = f.copy(maxAgeDays = it) }, Modifier.padding(vertical = 4.dp))
                Text("Length", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(0, 15, 30, 45, 60), f.maxMinutes, { if (it == 0) "Any" else "Under $it min" }, { f = f.copy(maxMinutes = it) }, Modifier.padding(vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
                Text("Shows", style = MaterialTheme.typography.labelLarge)
                Toggle("All shows I follow", f.podcastIds.isEmpty()) { all -> f = f.copy(podcastIds = if (all) emptyList() else vm.snap.shows.map { it.entry.id }) }
                if (f.podcastIds.isNotEmpty()) {
                    vm.snap.shows.forEach { row ->
                        val on = row.entry.id in f.podcastIds
                        Row(Modifier.fillMaxWidth().clickable {
                            f = f.copy(podcastIds = if (on) f.podcastIds - row.entry.id else f.podcastIds + row.entry.id)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Text(row.entry.podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.saveFilter(f.copy(name = f.name.ifBlank { "My filter" }, podcastIds = f.podcastIds)); onDone() },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { vm.deleteFilter(f.id); onDone() }) { Text("Delete", color = Palette.Coral) }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun Toggle(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, color = Palette.SubText, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
