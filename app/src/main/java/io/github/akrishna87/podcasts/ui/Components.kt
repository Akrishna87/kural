package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.EpisodeState
import io.github.akrishna87.podcasts.feed.NoteLink
import io.github.akrishna87.podcasts.feed.RichText
import io.github.akrishna87.podcasts.feed.formatClock
import io.github.akrishna87.podcasts.feed.formatDuration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Space at the bottom of scrolling screens so the last row clears the mini player and tab bar. */
val LocalBottomSpace = compositionLocalOf { 0.dp }

/** Pairs of colours for artwork that hasn't loaded (or doesn't exist), chosen by title so they stay put. */
private val placeholderPairs = listOf(
    0xFF6D4AE0 to 0xFFFF7A6B,
    0xFF3949AB to 0xFF00ACC1,
    0xFF8E24AA to 0xFFD81B60,
    0xFFF4511E to 0xFFFFB300,
    0xFF00897B to 0xFF7CB342,
    0xFF5E35B1 to 0xFFEC407A,
    0xFF6D4C41 to 0xFFD4A373,
    0xFF1E88E5 to 0xFF26A69A,
)

private fun pairFor(key: String) = placeholderPairs[(key.hashCode() and 0x7fffffff) % placeholderPairs.size]

fun placeholderBrush(key: String): Brush = pairFor(key).let { (a, b) -> Brush.linearGradient(listOf(Color(a), Color(b))) }

fun placeholderColor(key: String): Color = Color(pairFor(key).first)

/** Square artwork over a coloured card with the show's name, in case the picture is missing. */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), titleSize: Int = 11) {
    Box(modifier.clip(shape).background(placeholderBrush(title)), contentAlignment = Alignment.Center) {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = titleSize.sp,
            lineHeight = (titleSize + 3).sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(6.dp),
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** "Today", "Yesterday", "Tuesday", "10 Jun", "10 Jun 2023". */
fun formatDate(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val day = 86_400_000L
    val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val today = cal.timeInMillis
    val thisYear = cal.get(Calendar.YEAR)
    val year = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)
    return when {
        ms >= today -> "Today"
        ms >= today - day -> "Yesterday"
        ms >= today - 6 * day -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
        year == thisYear -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

/** The episode's length, or what's left of it once started. */
fun lengthLabel(e: Episode, s: EpisodeState?): String {
    val total = s?.durationMs?.takeIf { it > 0 } ?: (e.durationSec * 1000)
    return when {
        s?.played == true -> "Played"
        s != null && s.positionMs > 0 && total > 0 -> formatDuration(((total - s.positionMs) / 1000).coerceAtLeast(60)) + " left"
        total > 0 -> formatDuration(total / 1000)
        else -> ""
    }
}

fun progressOf(e: Episode, s: EpisodeState?): Float {
    if (s == null || s.played || s.positionMs <= 0) return 0f
    val total = s.durationMs.takeIf { it > 0 } ?: (e.durationSec * 1000)
    return if (total > 0) (s.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** A thin bar for how far through an episode you are. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

/**
 * An episode in a list: artwork, title, date and length, how far you are, its download, and a
 * play button. The ⋮ menu has everything else.
 */
@Composable
fun EpisodeRow(
    vm: PodcastViewModel,
    e: Episode,
    showShow: Boolean = true,
    onClick: () -> Unit = { vm.openEpisode(e) },
    extraMenu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val s = vm.state(e.id)
    val played = s?.played == true
    val isCurrent = vm.currentId == e.id
    val progress = progressOf(e, s)
    val dl = vm.downloadProgress[e.id]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(vm.artworkOf(e), vm.podcastTitle(e.podcastId), Modifier.size(56.dp), RoundedCornerShape(8.dp), titleSize = 7)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showShow) {
                Text(
                    vm.podcastTitle(e.podcastId).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.SubText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                e.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = if (played) Palette.Faint else if (isCurrent) MaterialTheme.colorScheme.primary else Palette.Text,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s?.starred == true) StatusIcon(Icons.Rounded.Star, Palette.Gold, "Starred")
                when {
                    s?.downloaded == true -> StatusIcon(Icons.Rounded.DownloadDone, Palette.Green, "Downloaded")
                    dl != null || (s?.downloadId ?: 0L) != 0L -> StatusIcon(Icons.Rounded.Downloading, Palette.Violet, "Downloading")
                }
                if (s?.isNew == true) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Coral))
                    Spacer(Modifier.width(6.dp))
                }
                val meta = listOfNotNull(
                    formatDate(e.publishedAt).takeIf { it.isNotEmpty() },
                    lengthLabel(e, s).takeIf { it.isNotEmpty() },
                    if (e.type == "bonus") "Bonus" else if (e.type == "trailer") "Trailer" else null,
                    if (e.isVideo) "Video" else null,
                ).joinToString("  ·  ")
                Text(meta, color = Palette.SubText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress > 0f || dl != null) {
                Spacer(Modifier.height(6.dp))
                if (dl != null) ProgressLine(dl, Modifier.fillMaxWidth(0.85f), Palette.Green)
                else ProgressLine(progress, Modifier.fillMaxWidth(0.85f))
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            PlayButton(vm, e)
        }
        EpisodeMenu(vm, e, showShow, extraMenu)
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color, description: String) {
    Icon(icon, description, tint = tint, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(5.dp))
}

/** A round play/pause button for an episode. */
@Composable
fun PlayButton(vm: PodcastViewModel, e: Episode, size: Int = 40) {
    val isCurrent = vm.currentId == e.id
    val playing = isCurrent && vm.isPlaying
    IconButton(
        onClick = { if (isCurrent) vm.togglePlay() else vm.play(e) },
        modifier = Modifier.size((size + 8).dp),
    ) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause ${e.title}" else "Play ${e.title}",
                tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else Palette.Text,
                modifier = Modifier.size((size * 0.6f).dp),
            )
        }
    }
}

/** Everything you can do with an episode. */
@Composable
fun EpisodeMenu(
    vm: PodcastViewModel,
    e: Episode,
    showShow: Boolean = true,
    extra: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More for ${e.title}", tint = Palette.SubText) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val s = vm.state(e.id)
            val close = { open = false }
            extra?.invoke(this, close)
            if (vm.inQueue(e.id)) {
                MenuItem("Remove from Up Next", Icons.Rounded.PlaylistRemove) { vm.removeFromQueue(e.id); close() }
            } else {
                MenuItem("Play next", Icons.Rounded.PlaylistPlay) { vm.playNext(e); close() }
                MenuItem("Play last", Icons.Rounded.PlaylistAdd) { vm.playLast(e); close() }
            }
            when {
                s?.downloaded == true -> MenuItem("Remove download", Icons.Rounded.DeleteOutline) { vm.deleteDownload(e.id); close() }
                (s?.downloadId ?: 0L) != 0L -> MenuItem("Cancel download", Icons.Rounded.Close) { vm.deleteDownload(e.id); close() }
                else -> MenuItem("Download", Icons.Rounded.Download) { vm.download(e); close() }
            }
            if (s?.played == true) MenuItem("Mark as unplayed", Icons.Rounded.RemoveDone) { vm.markPlayed(e, false); close() }
            else MenuItem("Mark as played", Icons.Rounded.DoneAll) { vm.markPlayed(e, true); close() }
            if (s?.starred == true) MenuItem("Remove star", Icons.Rounded.StarOutline) { vm.toggleStar(e); close() }
            else MenuItem("Star", Icons.Rounded.Star) { vm.toggleStar(e); close() }
            if (s?.isNew == true) MenuItem("Dismiss from New", Icons.Rounded.Archive) { vm.dismissNew(listOf(e.id)); close() }
            MenuItem("Share", Icons.Rounded.Share) { context.startActivity(vm.shareIntent(e)); close() }
            if (showShow) MenuItem("Go to show", Icons.Rounded.Podcasts) { vm.openPodcast(e.podcastId); close() }
        }
    }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

/** A show's artwork with its name underneath, for grids and rows. */
@Composable
fun ShowTile(title: String, author: String, artworkUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box {
            Artwork(artworkUrl, title, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(10.dp), titleSize = 12)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Palette.Coral).padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(if (badge > 99) "99+" else "$badge", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
        if (author.isNotBlank()) Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.SubText, fontSize = 12.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Palette.SubText) }
    }
}

/** A screen's title with a back arrow and optional actions. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun LoadFailed(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Palette.SubText)
        Spacer(Modifier.width(10.dp))
        Text(message, color = Palette.SubText, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
    }
}

/** A friendly empty screen with a hint of what to do. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.Elevated2), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Palette.Violet, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Palette.SubText, textAlign = TextAlign.Center, fontSize = 14.sp)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** A row of choice chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScrollIfNeeded().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
fun Modifier.horizontalScrollIfNeeded(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

/** Show notes with tappable links and timestamps (which jump the episode to that moment). */
@Composable
fun NotesText(notes: RichText, onTime: (Long) -> Unit, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val timeColor = Palette.Coral
    val text: AnnotatedString = remember(notes) {
        buildAnnotatedString {
            append(notes.text)
            for (s in notes.spans) {
                when (val l = s.link) {
                    is NoteLink.Web -> addLink(
                        LinkAnnotation.Url(l.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                        s.start,
                        s.end,
                    )
                    is NoteLink.Time -> addLink(
                        LinkAnnotation.Clickable(
                            "t${l.ms}",
                            TextLinkStyles(SpanStyle(color = timeColor, fontWeight = FontWeight.Bold)),
                        ) { onTime(l.ms) },
                        s.start,
                        s.end,
                    )
                }
            }
        }
    }
    Text(text, modifier = modifier, color = Palette.Text.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 22.sp)
}

/** A list of radio choices in a dialog. */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScrollable()) {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(i) }.padding(vertical = 2.dp)
                            .semantics { contentDescription = label },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun Modifier.verticalScrollable(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

/** The artwork's colour, or a stand-in picked from the show's name until it's known. */
@Composable
fun artTint(vm: PodcastViewModel, url: String?, title: String): Color {
    LaunchedEffect(url) { vm.loadArtColor(url) }
    return vm.artColor(url) ?: placeholderColor(title)
}

/** "1.0×", "1.25×". */
fun speedLabel(s: Float): String {
    val rounded = Math.round(s * 100) / 100f
    return (if (rounded == rounded.toInt().toFloat()) String.format(Locale.US, "%.1f", rounded) else String.format(Locale.US, "%.2f", rounded).trimEnd('0')) + "×"
}

/** "12:34 / 45:00". */
fun clockPair(pos: Long, dur: Long) = if (dur > 0) "${formatClock(pos)} / ${formatClock(dur)}" else formatClock(pos)
