package io.github.akrishna87.podcasts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.Load
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.SearchMode
import io.github.akrishna87.podcasts.feed.Category
import io.github.akrishna87.podcasts.feed.DirectoryPodcast

/** Find shows: top charts, categories, and search across shows and every episode of every podcast. */
@Composable
fun DiscoverScreen(vm: PodcastViewModel) {
    LaunchedEffect(Unit) { vm.loadDiscover() }
    val focus = LocalFocusManager.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importOpml) }
    var addByUrl by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Text(
                "Discover",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Shows, episodes, people or topics") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) { Icon(Icons.Rounded.Close, "Clear search") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    vm.search()
                }),
            )
            Spacer(Modifier.height(10.dp))
            ChipRow(SearchMode.entries, vm.searchMode, { it.label }, vm::setMode)
        }

        if (vm.searchedFor != null) {
            searchResults(vm)
            return@LazyColumn
        }

        item { SectionTitle("Top shows") }
        item {
            when (val top = vm.topShows) {
                is Load.Loading -> Loading()
                is Load.Failed -> LoadFailed(top.message, vm::retryDiscover)
                is Load.Ready -> LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(top.value.take(25), key = { it.appleId }) { p ->
                        ShowTile(p.title, p.author, p.artworkUrl, { vm.openListing(p) }, Modifier.width(132.dp))
                    }
                }
            }
        }

        item { SectionTitle("Browse by category") }
        item {
            val cats = vm.categories
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { c -> CategoryTile(c, Modifier.weight(1f)) { vm.open(Screen.CategoryPage(c)) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item { SectionTitle("More ways to add shows") }
        item {
            ListItem(
                headlineContent = { Text("Add by feed address") },
                supportingContent = { Text("Any RSS feed, including private and members-only feeds") },
                leadingContent = { Icon(Icons.Rounded.RssFeed, contentDescription = null, tint = Palette.Coral) },
                modifier = Modifier.clickable { addByUrl = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Import from another app") },
                supportingContent = { Text("An OPML file from Pocket Casts, Overcast, Apple Podcasts, AntennaPod…") },
                leadingContent = { Icon(Icons.Rounded.FileUpload, contentDescription = null, tint = Palette.Violet) },
                modifier = Modifier.clickable { importer.launch(arrayOf("*/*")) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Follow a guest or topic") },
                supportingContent = { Text("Get told when any podcast mentions them") },
                leadingContent = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = Palette.Gold) },
                modifier = Modifier.clickable { vm.open(Screen.Alerts) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }

    if (addByUrl) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addByUrl = false },
            title = { Text("Add by feed address") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { addByUrl = false; vm.openLink(url) }),
                )
            },
            confirmButton = { TextButton(onClick = { addByUrl = false; vm.openLink(url) }, enabled = url.isNotBlank()) { Text("Open") } },
            dismissButton = { TextButton(onClick = { addByUrl = false }) { Text("Cancel") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(vm: PodcastViewModel) {
    if (vm.searching) {
        item { Loading() }
        return
    }
    vm.searchError?.let { message ->
        item { LoadFailed(message, vm::search) }
        return
    }
    when (vm.searchMode) {
        SearchMode.SHOWS -> {
            if (vm.showResults.isEmpty()) item { NoResults(vm) }
            items(vm.showResults, key = { "s" + it.appleId }) { p -> ShowResultRow(vm, p) }
        }
        SearchMode.EPISODES -> {
            if (vm.episodeResults.isEmpty()) item { NoResults(vm) }
            else item {
                Text(
                    "Episodes from every podcast that mention “${vm.searchedFor}”",
                    color = Palette.SubText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(vm.episodeResults, key = { "e" + it.episode.id }) { r -> EpisodeRow(vm, r.episode) }
            if (vm.episodeResults.isNotEmpty()) item {
                TextButton(onClick = { vm.addAlert(vm.searchedFor.orEmpty()) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.NotificationAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tell me about new episodes that mention “${vm.searchedFor}”")
                }
            }
        }
        SearchMode.MY_EPISODES -> {
            if (vm.myResults.isEmpty()) item { NoResults(vm) }
            items(vm.myResults, key = { "m" + it.id }) { e -> EpisodeRow(vm, e) }
        }
    }
}

@Composable
private fun NoResults(vm: PodcastViewModel) {
    Text(
        when (vm.searchMode) {
            SearchMode.SHOWS -> "No shows found for “${vm.searchedFor}”. Try Episodes to search inside every podcast."
            SearchMode.EPISODES -> "No episodes found for “${vm.searchedFor}”."
            SearchMode.MY_EPISODES -> "Nothing in the shows you follow mentions “${vm.searchedFor}”."
        },
        color = Palette.SubText,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun ShowResultRow(vm: PodcastViewModel, p: DirectoryPodcast) {
    val following = p.feedUrl?.let { vm.entry(it)?.subscribed } == true || p.feedUrl?.let { url -> vm.snap.shows.any { it.entry.podcast.feedUrl == url } } == true
    Row(
        Modifier.fillMaxWidth().clickable { vm.openListing(p) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(p.artworkUrl, p.title, Modifier.size(64.dp), RoundedCornerShape(10.dp), titleSize = 8)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.author, color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(p.genre.takeIf { it.isNotBlank() }, p.episodeCount.takeIf { it > 0 }?.let { "$it episodes" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = Palette.Faint, fontSize = 12.sp)
        }
        val feed = p.feedUrl
        if (feed != null) {
            IconButton(onClick = { if (!following) vm.subscribe(feed) }) {
                Icon(
                    if (following) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    if (following) "Following ${p.title}" else "Follow ${p.title}",
                    tint = if (following) Palette.Green else Palette.SubText,
                )
            }
        }
    }
}

private val categoryColors = listOf(
    0xFF6D4AE0, 0xFFE0567A, 0xFF1E88E5, 0xFF00897B, 0xFFF4511E, 0xFF8E24AA, 0xFF43A047, 0xFFD81B60, 0xFF3949AB, 0xFFFB8C00,
)

@Composable
private fun CategoryTile(c: Category, modifier: Modifier, onClick: () -> Unit) {
    val color = Color(categoryColors[(c.id / 3) % categoryColors.size])
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(color, color.deep(0.35f))))
            .clickable(onClickLabel = c.name, onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The top shows in one category. */
@Composable
fun CategoryScreen(vm: PodcastViewModel, c: Category) {
    LaunchedEffect(c.id) { vm.loadCategory(c) }
    Column(Modifier.fillMaxSize()) {
        TopBar(c.name, onBack = { vm.back() })
        when (val chart = vm.categoryCharts[c.id] ?: Load.Loading) {
            is Load.Loading -> Loading()
            is Load.Failed -> LoadFailed(chart.message, { vm.loadCategory(c, force = true) })
            is Load.Ready -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = LocalBottomSpace.current),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Most popular in ${c.name} right now", color = Palette.SubText, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp))
                }
                items(chart.value, key = { it.appleId }) { p ->
                    Box {
                        ShowTile(p.title, p.author, p.artworkUrl, { vm.openListing(p) })
                        Box(
                            Modifier.padding(10.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${chart.value.indexOf(p) + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
