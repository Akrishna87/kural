package io.github.akrishna87.podcasts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.Section
import io.github.akrishna87.podcasts.data.Episode

/**
 * Home opens straight onto your own shows, not on promotions: what you're partway through,
 * what's up next, what's new from the shows you follow, and mentions of the names you watch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: PodcastViewModel) {
    val snap = vm.snap
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importOpml) }
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refreshAll, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Kural", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::refreshAll) { Icon(Icons.Rounded.Refresh, "Check for new episodes") }
                    IconButton(onClick = { vm.open(Screen.Alerts) }) { Icon(Icons.Rounded.NotificationsActive, "Alerts") }
                    IconButton(onClick = { vm.open(Screen.SettingsPage) }) { Icon(Icons.Rounded.Settings, "Settings") }
                }
                vm.importing?.let {
                    Text(it, color = Palette.SubText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }

            if (snap.loaded && snap.shows.isEmpty() && snap.queue.isEmpty() && snap.inProgress.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Podcasts,
                        "Welcome to Kural",
                        "Follow a few shows and their new episodes will land here. No account, no ads.",
                        action = "Find podcasts",
                        onAction = { vm.selectSection(Section.DISCOVER) },
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Bring your shows from another app (OPML)")
                        }
                    }
                }
                return@LazyColumn
            }

            val continuing = listOfNotNull(vm.current) + snap.inProgress.filter { it.id != vm.currentId }
            if (continuing.isNotEmpty()) {
                item { SectionTitle("Continue listening") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(continuing.take(12), key = { it.id }) { e -> ContinueCard(vm, e) }
                    }
                }
            }

            val upcoming = snap.queue.drop(1)
            if (upcoming.isNotEmpty()) {
                item { SectionTitle("Up Next", action = "See all ${upcoming.size}") { vm.selectSection(Section.UP_NEXT) } }
                items(upcoming.take(3), key = { "q" + it.id }) { e -> EpisodeRow(vm, e) }
            }

            if (snap.hits.isNotEmpty()) {
                item { SectionTitle("Mentions you're following", action = "See all") { vm.open(Screen.Alerts) } }
                items(snap.hits.take(3), key = { "h" + it.episode.id }) { hit ->
                    EpisodeRow(vm, hit.episode, onClick = { vm.openEpisode(hit.episode) })
                }
            }

            item {
                SectionTitle(
                    "New episodes",
                    action = if (snap.newEpisodes.isNotEmpty()) "Clear all" else null,
                ) { vm.dismissNew(snap.newEpisodes.map { it.id }) }
            }
            if (snap.newEpisodes.isEmpty()) {
                item {
                    Text(
                        if (snap.shows.isEmpty()) "Follow shows to see their new episodes here."
                        else "You're all caught up. Pull down to check for new episodes.",
                        color = Palette.SubText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "Play, queue or dismiss each one. Nothing here plays until you say so.",
                        color = Palette.SubText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(snap.newEpisodes, key = { "n" + it.id }) { e -> InboxRow(vm, e) }
            }
        }
    }
}

/** A new episode with one-tap triage: up next, later, or not for me. */
@Composable
private fun InboxRow(vm: PodcastViewModel, e: Episode) {
    Column {
        EpisodeRow(vm, e)
        Row(Modifier.padding(start = 84.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TriageChip("Play next", Icons.Rounded.PlaylistPlay) { vm.playNext(e) }
            TriageChip("Play last", Icons.Rounded.PlaylistAdd) { vm.playLast(e) }
            TriageChip("Dismiss", Icons.Rounded.Close) { vm.dismissNew(listOf(e.id)) }
        }
    }
}

@Composable
private fun TriageChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.07f)).clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Palette.SubText)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A big card for an episode you're partway through. */
@Composable
private fun ContinueCard(vm: PodcastViewModel, e: Episode) {
    val s = vm.state(e.id)
    val art = vm.artworkOf(e)
    val show = vm.podcastTitle(e.podcastId)
    val tint = artTint(vm, art, show)
    val isCurrent = vm.currentId == e.id
    Column(
        Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(tint.deep(0.45f), tint.deep(0.75f))))
            .clickable { if (isCurrent) vm.showPlayer = true else vm.openEpisode(e) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(art, show, Modifier.size(64.dp), RoundedCornerShape(10.dp), titleSize = 8)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(show.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val fraction = if (isCurrent && vm.durationMs > 0) vm.positionMs.toFloat() / vm.durationMs else progressOf(e, s)
                ProgressLine(fraction, Modifier.fillMaxWidth(), Color.White)
                Spacer(Modifier.height(4.dp))
                Text(lengthLabel(e, s), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
            }
            Spacer(Modifier.width(8.dp))
            PlayButton(vm, e, size = 38)
        }
    }
}
