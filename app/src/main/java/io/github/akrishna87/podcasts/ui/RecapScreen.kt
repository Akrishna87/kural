package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.data.PartOfDay
import io.github.akrishna87.podcasts.data.Recap
import io.github.akrishna87.podcasts.data.RecapPeriod
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** The play log, read again every few seconds while shown so listening going on now adds up. */
@Composable
private fun rememberPlayLog(vm: PodcastViewModel) = run {
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            beat++
        }
    }
    remember(vm.tick, beat) { vm.lib.playLog() }
}

private fun recapOf(vm: PodcastViewModel, log: List<io.github.akrishna87.podcasts.data.PlayDay>, period: RecapPeriod) =
    Recap.of(log, period, showTitle = { vm.lib.podcast(it)?.title }, episodeTitle = { null })

/**
 * Your recap: how much you listened this month, last month, this year or ever; your top shows
 * and episodes; time saved; and when you listen most. The all-time totals the app has always
 * kept are at the bottom. (Screen.StatsPage, opened from Home and the Library.)
 */
@Composable
fun StatsScreen(vm: PodcastViewModel) {
    var period by rememberSaveable { mutableStateOf(RecapPeriod.THIS_MONTH) }
    val log = rememberPlayLog(vm)
    val recap = remember(log, period) { recapOf(vm, log, period) }
    val stats = vm.stats()
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Your recap", onBack = { vm.back() }) { TextButton(onClick = { confirm = true }) { Text("Reset") } }
        ChipRow(RecapPeriod.entries, period, { it.label }, { period = it })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalBottomSpace.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Headline(recap, period) }
            if (recap.isEmpty) {
                item {
                    Text(
                        "Nothing here yet. Your recap counts everything you listen to, with the app open or closed, " +
                            "from when you updated Podwave.",
                        color = Palette.SubText,
                        fontSize = 14.sp,
                    )
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Time saved", hours(recap.timeSaved), Palette.Green, Modifier.weight(1f))
                        StatTile("Episodes finished", "${recap.finished}", Palette.Coral, Modifier.weight(1f))
                    }
                    Text(
                        "Time saved counts speed-ups, trimmed silences and skipped intros and outros" +
                            (if (recap.skippedMs >= 60_000) " (${hours(recap.skippedMs)} of those)." else "."),
                        color = Palette.Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                recap.topShows.firstOrNull()?.let { top ->
                    val eps = vm.episodesOf(top.podcastId)
                    val latest = eps.firstOrNull { vm.state(it.id)?.played != true && it.type != "trailer" } ?: eps.firstOrNull()
                    if (latest != null) {
                        item {
                            FilledTonalButton(onClick = { vm.play(latest); vm.showPlayer = true }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Play the latest from ${top.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                item {
                    TopList("Top shows", recap.topShows, vm, onClick = { vm.openPodcast(it.podcastId) })
                }
                item {
                    TopList("Top episodes", recap.topEpisodes, vm, onClick = { t -> vm.episode(t.id)?.let(vm::openEpisode) })
                }
                item { WhenYouListen(recap) }
            }
            item { AllTime(stats) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset your recap?") },
            text = { Text("Your recap and listening totals start again from today.") },
            confirmButton = { TextButton(onClick = { vm.resetStats(); confirm = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun periodTitle(period: RecapPeriod): String {
    val today = LocalDate.now()
    return when (period) {
        RecapPeriod.THIS_MONTH -> today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        RecapPeriod.LAST_MONTH -> today.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        RecapPeriod.THIS_YEAR -> today.year.toString()
        RecapPeriod.ALL_TIME -> "All time"
    }
}

@Composable
private fun Headline(recap: Recap, period: RecapPeriod) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BrandGradient).padding(20.dp)) {
        Text(periodTitle(period), color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(Recap.duration(recap.ms), color = Color.White, fontWeight = FontWeight.Black, fontSize = 36.sp)
        Text("of listening", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            plural(recap.episodes, "episode") + " · " + plural(recap.shows, "show") + " · " + plural(recap.daysListened, "day"),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
private fun TopList(title: String, entries: List<Recap.Top>, vm: PodcastViewModel, onClick: (Recap.Top) -> Unit) {
    if (entries.isEmpty()) return
    val max = entries.first().ms.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Elevated).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        entries.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().clickable { onClick(t) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", Modifier.width(22.dp), color = Palette.SubText, fontWeight = FontWeight.Bold)
                val art = vm.entry(t.podcastId)?.podcast?.artworkUrl
                Artwork(art, t.name, Modifier.size(44.dp), RoundedCornerShape(6.dp), titleSize = 6)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.detail.isNotEmpty()) Text(t.detail, color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressLine(t.ms.toFloat() / max, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Text(hours(t.ms), color = Palette.SubText, fontSize = 13.sp)
            }
        }
    }
}

/** The days of the week and the time of day you listen most, as bars. */
@Composable
private fun WhenYouListen(recap: Recap) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Elevated).padding(16.dp)) {
        Text("When you listen", style = MaterialTheme.typography.titleMedium)
        val day = recap.busiestDay?.getDisplayName(TextStyle.FULL, Locale.getDefault())
        Text(
            "You listened on ${plural(recap.daysListened, "day")}" +
                (if (day != null) ", most on ${day}s" else "") + (recap.partOfDay?.let { " ${it.label}" } ?: "") + ".",
            color = Palette.SubText,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        val dayMax = recap.byWeekday.max().coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            recap.byWeekday.forEachIndexed { i, ms ->
                val name = DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault())
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().height((72f * ms / dayMax).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(4.dp))
                            .background(if (ms == dayMax && ms > 0) Palette.Violet else Palette.Violet.copy(alpha = 0.3f)),
                    )
                    Text(name, color = Palette.SubText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val partMax = recap.byPart.max().coerceAtLeast(1)
        PartOfDay.entries.forEachIndexed { i, part ->
            val ms = recap.byPart[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(part.short, Modifier.width(84.dp), fontSize = 13.sp)
                ProgressLine(ms.toFloat() / partMax, Modifier.weight(1f), if (ms == partMax) Palette.Coral else Palette.Coral.copy(alpha = 0.5f))
                Text(hours(ms), Modifier.width(76.dp).padding(start = 8.dp), color = Palette.SubText, fontSize = 12.sp)
            }
        }
    }
}

/** The running totals Podwave has always kept, since the last reset. */
@Composable
private fun AllTime(stats: io.github.akrishna87.podcasts.data.Stats) {
    Column {
        SettingsHeader("All your listening")
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
    }
}

/** This month's listening in a line on Home, opening the full recap. Shows nothing until you've listened. */
@Composable
fun HomeRecapCard(vm: PodcastViewModel, onClick: () -> Unit) {
    val log = remember(vm.tick) { vm.lib.playLog() }
    if (log.isEmpty()) return
    val month = remember(log) { recapOf(vm, log, RecapPeriod.THIS_MONTH) }
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Text("Your ${periodTitle(RecapPeriod.THIS_MONTH)} so far", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            if (month.isEmpty) "Nothing yet this month. See what you listened to before."
            else "${Recap.duration(month.ms)} of listening" + (month.topShows.firstOrNull()?.let { " · most of all ${it.name}" } ?: ""),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text("See your recap ›", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

private fun hours(ms: Long): String = if (ms < 60_000) "0 min" else io.github.akrishna87.podcasts.feed.formatDuration(ms / 1000)

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, color = Palette.SubText, fontSize = 13.sp)
    }
}
