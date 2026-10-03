package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.akrishna87.podcasts.Load
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Person
import io.github.akrishna87.podcasts.feed.Transcript
import io.github.akrishna87.podcasts.feed.formatClock
import io.github.akrishna87.podcasts.feed.formatBytes
import io.github.akrishna87.podcasts.feed.showNotes

@Composable
fun EpisodeScreen(vm: PodcastViewModel, page: Screen.EpisodePage) {
    val e = vm.episode(page.episodeId) ?: page.episode
    if (e == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Episode", onBack = { vm.back() })
            Text("This episode is no longer in its feed.", color = Palette.SubText, modifier = Modifier.padding(16.dp))
        }
        return
    }
    EpisodeDetails(vm, e)
}

@Composable
private fun EpisodeDetails(vm: PodcastViewModel, e: Episode) {
    val context = LocalContext.current
    val s = vm.state(e.id)
    val show = vm.podcastTitle(e.podcastId)
    val art = vm.artworkOf(e)
    val tint = artTint(vm, art, show)
    val isCurrent = vm.currentId == e.id
    val duration = if (isCurrent && vm.durationMs > 0) vm.durationMs else (s?.durationMs?.takeIf { it > 0 } ?: e.durationSec * 1000)
    val notes = remember(e.description, duration) { showNotes(e.description, duration) }
    LaunchedEffect(e.id) { vm.ensureChapters(e) }
    val chapters = vm.chaptersOf(e)
    val bookmarks = vm.bookmarksOf(e.id)
    var showTranscript by remember { mutableStateOf(false) }
    val dl = vm.downloadProgress[e.id]

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.deep(0.3f), Palette.Background)))) {
                Column(Modifier.fillMaxWidth()) {
                    TopBar("", onBack = { vm.back() }) { EpisodeMenu(vm, e) }
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(art, show, Modifier.size(96.dp), RoundedCornerShape(12.dp), titleSize = 10)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                show,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { vm.openPodcast(e.podcastId) },
                            )
                            val meta = listOfNotNull(
                                formatDate(e.publishedAt).takeIf { it.isNotEmpty() },
                                e.season?.let { se -> e.number?.let { "S$se E$it" } ?: "Season $se" } ?: e.number?.let { "Episode $it" },
                                lengthLabel(e, s).takeIf { it.isNotEmpty() },
                                e.sizeBytes.takeIf { it > 0 }?.let(::formatBytes),
                            ).joinToString("  ·  ")
                            Text(meta, color = Palette.SubText, fontSize = 13.sp)
                        }
                    }
                    Text(
                        e.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    )
                    val progress = if (isCurrent && vm.durationMs > 0) vm.positionMs.toFloat() / vm.durationMs else progressOf(e, s)
                    if (progress > 0f) ProgressLine(progress, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val playing = isCurrent && vm.isPlaying
                        Button(onClick = { if (isCurrent) vm.togglePlay() else vm.play(e) }, modifier = Modifier.height(48.dp)) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    playing -> "Pause"
                                    isCurrent -> "Resume"
                                    s?.inProgress == true -> "Resume"
                                    s?.played == true -> "Play again"
                                    else -> "Play"
                                },
                            )
                        }
                        val queued = vm.inQueue(e.id)
                        RoundAction(
                            if (queued) Icons.Rounded.PlaylistAddCheck else Icons.Rounded.PlaylistAdd,
                            if (queued) "In Up Next" else "Add to Up Next",
                            active = queued,
                        ) { if (queued) vm.removeFromQueue(e.id) else vm.playLast(e) }
                        RoundAction(
                            when {
                                s?.downloaded == true -> Icons.Rounded.DownloadDone
                                dl != null || (s?.downloadId ?: 0L) != 0L -> Icons.Rounded.Downloading
                                else -> Icons.Rounded.Download
                            },
                            when {
                                s?.downloaded == true -> "Remove download"
                                dl != null || (s?.downloadId ?: 0L) != 0L -> "Cancel download"
                                else -> "Download"
                            },
                            active = s?.downloaded == true,
                        ) { if (s?.downloaded == true || (s?.downloadId ?: 0L) != 0L) vm.deleteDownload(e.id) else vm.download(e) }
                        RoundAction(
                            if (s?.starred == true) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            if (s?.starred == true) "Remove star" else "Star",
                            active = s?.starred == true,
                        ) { vm.toggleStar(e) }
                        RoundAction(Icons.Rounded.Share, "Share") { context.startActivity(vm.shareIntent(e)) }
                    }
                }
            }
        }

        if (e.persons.isNotEmpty()) {
            item { SectionTitle("Hosts & guests") }
            item { PeopleRow(vm, e.persons) }
        }

        if (chapters.isNotEmpty()) {
            item { SectionTitle("Chapters") }
            val current = if (isCurrent) vm.chapterIndex(chapters) else -1
            itemsIndexed(chapters, key = { i, c -> "c$i${c.startMs}" }) { i, c ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.playAt(e, c.startMs) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatClock(c.startMs),
                        color = if (i == current) Palette.Coral else Palette.SubText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.width(64.dp),
                    )
                    if (c.imageUrl != null) {
                        AsyncImage(c.imageUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        c.title.ifBlank { "Chapter ${i + 1}" },
                        color = if (i == current) MaterialTheme.colorScheme.primary else Palette.Text,
                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (e.bestTranscript != null) {
            item {
                SectionTitle("Transcript", action = if (showTranscript) "Hide" else "Show") {
                    showTranscript = !showTranscript
                    if (showTranscript) vm.loadTranscript(e)
                }
            }
            if (showTranscript) {
                when (val t = vm.transcripts[e.id] ?: Load.Loading) {
                    is Load.Loading -> item { Loading() }
                    is Load.Failed -> item { LoadFailed(t.message, { vm.transcripts.remove(e.id); vm.loadTranscript(e) }) }
                    is Load.Ready -> transcriptLines(vm, e, t.value)
                }
            }
        }

        if (bookmarks.isNotEmpty()) {
            item { SectionTitle("Your bookmarks") }
            items(bookmarks, key = { it.id }) { b -> BookmarkRow(vm, b, showEpisode = false) }
        }

        if (notes.text.isNotBlank()) {
            item { SectionTitle("Show notes") }
            item {
                if (notes.timestamps.isNotEmpty()) {
                    Text(
                        "Tap a time to jump there.",
                        color = Palette.Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                NotesText(notes, onTime = { ms -> vm.playAt(e, ms) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.transcriptLines(vm: PodcastViewModel, e: Episode, t: Transcript) {
    items(t.lines.size) { i ->
        val line = t.lines[i]
        Column(
            Modifier.fillMaxWidth()
                .clickable(enabled = line.startMs >= 0) { vm.playAt(e, line.startMs) }
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            val label = listOfNotNull(line.speaker, line.startMs.takeIf { it >= 0 }?.let(::formatClock)).joinToString("  ·  ")
            if (label.isNotEmpty()) Text(label, color = Palette.Coral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(line.text, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

/** People in an episode: tap one to find every episode they're in, on any podcast. */
@Composable
private fun PeopleRow(vm: PodcastViewModel, people: List<Person>) {
    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        people.forEach { p ->
            Column(
                Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.searchPerson(p.name) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(placeholderBrush(p.name)), contentAlignment = Alignment.Center) {
                    Text(p.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontWeight = FontWeight.Bold)
                    if (p.imageUrl != null) AsyncImage(p.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(4.dp))
                Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.role.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = Palette.SubText)
            }
        }
    }
    Text(
        "Tap a name to find their other appearances.",
        color = Palette.Faint,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)),
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Palette.Text)
    }
}
