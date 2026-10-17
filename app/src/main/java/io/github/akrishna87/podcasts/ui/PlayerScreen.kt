package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.akrishna87.podcasts.Load
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.SPEEDS
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.Section
import io.github.akrishna87.podcasts.Settings
import io.github.akrishna87.podcasts.SleepTimer
import io.github.akrishna87.podcasts.data.Chapter
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.feed.Transcript
import io.github.akrishna87.podcasts.feed.formatClock
import io.github.akrishna87.podcasts.feed.showNotes
import kotlin.math.roundToInt

private enum class PlayerTab(val label: String) { NOW("Now playing"), CHAPTERS("Chapters"), TRANSCRIPT("Transcript"), NOTES("Notes") }

/** The full player. */
@Composable
fun PlayerScreen(vm: PodcastViewModel) {
    val e = vm.current ?: return
    val show = vm.podcastTitle(e.podcastId)
    val art = vm.artworkOf(e)
    val tint = artTint(vm, art, show)
    LaunchedEffect(e.id) { vm.ensureChapters(e) }
    val chapters = vm.chaptersOf(e)
    val chapter = vm.chapterIndex(chapters)
    var tab by remember(e.id) { mutableStateOf(PlayerTab.NOW) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val context = LocalContext.current
    val prefs = vm.prefs()
    val back = Settings.skipBackSec(prefs)
    val forward = Settings.skipForwardSec(prefs)
    val fx = vm.effects()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint.deep(0.35f), tint.deep(0.8f), Palette.Background)))
            // Keep taps from reaching the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPlayer = false }, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close player", modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f).clickable { vm.openPodcast(e.podcastId) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    Text(show, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.offset(x = 12.dp)) {
                    EpisodeMenu(vm, e) { close ->
                        MenuItem("Episode details", Icons.Rounded.Info) { close(); vm.openEpisode(e) }
                        MenuItem("Skip to next episode", Icons.Rounded.SkipNext) { close(); vm.nextEpisode() }
                    }
                }
            }

            // Tabs for what fills the middle.
            val tabs = PlayerTab.entries.filter {
                when (it) {
                    PlayerTab.CHAPTERS -> chapters.isNotEmpty()
                    PlayerTab.TRANSCRIPT -> e.bestTranscript != null
                    PlayerTab.NOTES -> e.description.isNotBlank()
                    else -> true
                }
            }
            if (tabs.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEach { t ->
                        val on = t == tab
                        Text(
                            t.label,
                            Modifier
                                .clip(CircleShape)
                                .background(if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    tab = t
                                    if (t == PlayerTab.TRANSCRIPT) vm.loadTranscript(e)
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (tab) {
                    PlayerTab.NOW -> NowPanel(vm, e, art, show, chapters.getOrNull(chapter))
                    PlayerTab.CHAPTERS -> ChaptersPanel(vm, chapters, chapter)
                    PlayerTab.TRANSCRIPT -> TranscriptPanel(vm, e)
                    PlayerTab.NOTES -> NotesPanel(vm, e)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(e.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            chapters.getOrNull(chapter)?.let { c ->
                Text(c.title, color = Palette.Gold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))

            val duration = vm.durationMs
            val shown = dragging ?: if (duration > 0) (vm.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
                modifier = Modifier.semantics { contentDescription = "Position in the episode" },
            )
            Row(Modifier.fillMaxWidth()) {
                val pos = if (dragging != null) (shown * duration).toLong() else vm.positionMs
                Text(formatClock(pos), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (duration > 0) {
                    val left = ((duration - pos) / fx.speed).toLong()
                    Text("-" + formatClock(left) + if (fx.speed != 1f) " at ${speedLabel(fx.speed)}" else "", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chapters.isNotEmpty()) {
                    IconButton(onClick = { vm.previousChapter(chapters) }) { Icon(Icons.Rounded.SkipPrevious, "Previous chapter", modifier = Modifier.size(30.dp)) }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                IconButton(onClick = vm::seekBack, modifier = Modifier.size(60.dp)) {
                    Icon(skipBackIcon(back), "Back $back seconds", modifier = Modifier.size(38.dp))
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(onClickLabel = if (vm.isPlaying) "Pause" else "Play", onClick = vm::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (vm.isBuffering) {
                        CircularProgressIndicator(Modifier.size(34.dp), color = Color.Black, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (vm.isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                IconButton(onClick = vm::seekForward, modifier = Modifier.size(60.dp)) {
                    Icon(skipForwardIcon(forward), "Forward $forward seconds", modifier = Modifier.size(38.dp))
                }
                if (chapters.isNotEmpty()) {
                    IconButton(onClick = { vm.nextChapter(chapters) }, enabled = chapter < chapters.size - 1) {
                        Icon(Icons.Rounded.SkipNext, "Next chapter", modifier = Modifier.size(30.dp))
                    }
                } else {
                    IconButton(onClick = vm::nextEpisode) { Icon(Icons.Rounded.SkipNext, "Next episode", modifier = Modifier.size(30.dp)) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlayerPill(Icons.Rounded.Speed, speedLabel(fx.speed), "Playback speed", active = fx.speed != 1f) { dialog = "speed" }
                PlayerPill(
                    Icons.Rounded.Bedtime,
                    when {
                        vm.sleepEndOfEpisode -> "Episode"
                        vm.sleepEndOfChapter -> "Chapter"
                        vm.sleepLeftMs > 0 -> formatClock(vm.sleepLeftMs)
                        else -> null
                    },
                    "Sleep timer",
                    active = vm.sleepEndOfEpisode || vm.sleepEndOfChapter || vm.sleepLeftMs > 0,
                ) { dialog = "sleep" }
                PlayerPill(Icons.Rounded.GraphicEq, null, "Trim silence and volume boost", active = fx.trimSilence || fx.boostLevel > 0) { dialog = "effects" }
                PlayerPill(Icons.Rounded.BookmarkAdd, null, "Bookmark this moment") { dialog = "bookmark" }
                PlayerPill(Icons.Rounded.QueueMusic, (vm.snap.queue.size - 1).takeIf { it > 0 }?.toString(), "Up Next") {
                    vm.selectSection(Section.UP_NEXT)
                }
            }
        }
    }

    when (dialog) {
        "speed" -> SpeedDialog(fx.speed, vm::changeSpeed, { dialog = null }, note = if (vm.showHasCustomEffects()) "For “$show” only" else "For every show without its own speed")
        "sleep" -> SleepDialog(vm, hasChapters = chapters.isNotEmpty()) { dialog = null }
        "effects" -> EffectsDialog(vm, show) { dialog = null }
        "bookmark" -> {
            var note by remember { mutableStateOf("") }
            val at = remember { vm.positionMs }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Bookmark at ${formatClock(at)}") },
                text = {
                    OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text("A note (optional)") }, minLines = 2)
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.lib.addBookmark(e, at, note)
                        vm.say("Bookmarked at ${formatClock(at)}")
                        dialog = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        context.startActivity(vm.shareIntent(e, at))
                        dialog = null
                    }) { Text("Share moment") }
                },
            )
        }
    }
}

private fun skipBackIcon(sec: Int): ImageVector = when (sec) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

private fun skipForwardIcon(sec: Int): ImageVector = when (sec) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

@Composable
private fun NowPanel(vm: PodcastViewModel, e: Episode, art: String?, show: String, chapter: Chapter?) {
    val image = chapter?.imageUrl ?: art
    Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).padding(8.dp), contentAlignment = Alignment.Center) {
        Artwork(image, show, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)), RoundedCornerShape(18.dp), titleSize = 22)
        val url = chapter?.url
        if (url != null) {
            val context = LocalContext.current
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    .clickable { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Chapter link", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChaptersPanel(vm: PodcastViewModel, chapters: List<Chapter>, current: Int) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        itemsIndexed(chapters) { i, c ->
            val on = i == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { vm.seekTo(c.startMs) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (c.imageUrl != null) {
                    AsyncImage(c.imageUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                }
                Text(c.title.ifBlank { "Chapter ${i + 1}" }, Modifier.weight(1f), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatClock(c.startMs), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            }
        }
    }
}

/** The transcript, following along: the line being spoken is lit, and tapping a line jumps there. */
@Composable
private fun TranscriptPanel(vm: PodcastViewModel, e: Episode) {
    LaunchedEffect(e.id) { vm.loadTranscript(e) }
    when (val t = vm.transcripts[e.id] ?: Load.Loading) {
        is Load.Loading -> CircularProgressIndicator(color = Color.White)
        is Load.Failed -> LoadFailed(t.message, { vm.transcripts.remove(e.id); vm.loadTranscript(e) })
        is Load.Ready -> TranscriptLines(vm, t.value)
    }
}

@Composable
private fun TranscriptLines(vm: PodcastViewModel, t: Transcript) {
    val state = rememberLazyListState()
    val current = t.indexAt(vm.positionMs + 300)
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(current, follow) {
        if (follow && current >= 0) state.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(t.lines) { i, line ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = line.startMs >= 0) { vm.seekTo(line.startMs); follow = true }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    if (line.speaker != null && (i == 0 || t.lines[i - 1].speaker != line.speaker)) {
                        Text(line.speaker, color = Palette.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        line.text,
                        fontSize = 19.sp,
                        lineHeight = 27.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            !t.timed -> Color.White.copy(alpha = 0.9f)
                            i == current -> Color.White
                            i < current -> Color.White.copy(alpha = 0.55f)
                            else -> Color.White.copy(alpha = 0.35f)
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
        if (t.timed) {
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow along") })
            }
        }
    }
}

@Composable
private fun NotesPanel(vm: PodcastViewModel, e: Episode) {
    val notes = remember(e.description, vm.durationMs > 0) { showNotes(e.description, vm.durationMs) }
    Column(Modifier.fillMaxSize().verticalScrollable()) {
        NotesText(notes, onTime = vm::seekTo)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PlayerPill(icon: ImageVector, label: String?, description: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f))
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = if (active) MaterialTheme.colorScheme.primary else Palette.Text)
        if (label != null) {
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Speed from 0.5× to 3×, in small steps, with the usual choices one tap away. */
@Composable
fun SpeedDialog(current: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit, note: String? = null) {
    var value by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed") },
        text = {
            Column {
                Text(speedLabel(value), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { value = (value - 0.05f).coerceAtLeast(0.5f).roundTo(); onChange(value) }) { Icon(Icons.Rounded.Remove, "Slower") }
                    Slider(
                        value = value,
                        onValueChange = { value = it.roundTo() },
                        onValueChangeFinished = { onChange(value) },
                        valueRange = 0.5f..3f,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { value = (value + 0.05f).coerceAtMost(3f).roundTo(); onChange(value) }) { Icon(Icons.Rounded.Add, "Faster") }
                }
                Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f, 2.5f).forEach { s ->
                        FilterChip(selected = value == s, onClick = { value = s; onChange(s) }, label = { Text(speedLabel(s)) })
                    }
                }
                if (note != null) Text(note, color = Palette.SubText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Rounds to the nearest 0.05. */
private fun Float.roundTo(): Float = ((this * 20).roundToInt() / 20f).coerceIn(SPEEDS.first(), SPEEDS.last())

@Composable
private fun SleepDialog(vm: PodcastViewModel, hasChapters: Boolean, onDismiss: () -> Unit) {
    val on = vm.sleepEndOfEpisode || vm.sleepEndOfChapter || vm.sleepLeftMs > 0
    val choices = buildList {
        add(5 to "5 minutes"); add(10 to "10 minutes"); add(15 to "15 minutes"); add(30 to "30 minutes"); add(45 to "45 minutes"); add(60 to "1 hour")
        if (hasChapters) add(SleepTimer.END_OF_CHAPTER to "End of this chapter")
        add(SleepTimer.END_OF_EPISODE to "End of this episode")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column(Modifier.verticalScrollable()) {
                if (on) {
                    Text(
                        when {
                            vm.sleepEndOfEpisode -> "Pausing at the end of this episode."
                            vm.sleepEndOfChapter -> "Pausing at the end of this chapter."
                            else -> "Pausing in ${formatClock(vm.sleepLeftMs)}. The sound fades out over the last few seconds."
                        },
                        color = Palette.SubText,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        if (vm.sleepLeftMs > 0) OutlinedButton(onClick = { vm.extendSleep() }) { Text("+5 min") }
                        OutlinedButton(onClick = { vm.setSleep(0); onDismiss() }) { Text("Turn off") }
                    }
                }
                choices.forEach { (minutes, label) ->
                    Text(
                        label,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.setSleep(minutes); onDismiss() }.padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
                Text(
                    "Fell asleep too soon? Press play within 5 minutes of it stopping and the same timer starts again.",
                    color = Palette.Faint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EffectsDialog(vm: PodcastViewModel, show: String, onDismiss: () -> Unit) {
    val fx = vm.effects()
    val custom = vm.showHasCustomEffects()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sound") },
        text = {
            Column {
                Toggle("Trim silence", fx.trimSilence, "Shortens pauses, so episodes finish sooner") { vm.setTrimSilence(it) }
                BoostPicker(fx.boostLevel, vm::setBoost)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Toggle("Only for “$show”", custom, "Keep this show's speed and sound separate from the rest") { vm.setCustomEffects(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
