package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Section
import io.github.akrishna87.podcasts.feed.formatDuration
import kotlin.math.roundToInt

/**
 * Up Next: the episode playing now, then what plays after it. Drag ≡ to reorder, swipe left to
 * remove. Episodes leave the list once finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextScreen(vm: PodcastViewModel) {
    val queue = vm.snap.queue
    val now = queue.firstOrNull()
    val upcoming = queue.drop(1)
    val rowHeight = 80.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    fun dropTarget(from: Int) = (from + (dragOffset / rowPx).roundToInt()).coerceIn(0, (upcoming.size - 1).coerceAtLeast(0))
    val target = dragFrom?.let { dropTarget(it) }
    var confirmClear by remember { mutableStateOf(false) }

    val leftMs = upcoming.sumOf { e ->
        val s = vm.state(e.id)
        val total = s?.durationMs?.takeIf { it > 0 } ?: (e.durationSec * 1000)
        (total - (s?.positionMs ?: 0)).coerceAtLeast(0)
    } + (if (now != null) (vm.durationMs - vm.positionMs).coerceAtLeast(0) else 0)
    val speed = vm.effects().speed

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            TopBar("Up Next", onBack = null) {
                if (upcoming.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
            if (queue.isNotEmpty() && leftMs > 0) {
                Text(
                    "${queue.size} episode${if (queue.size == 1) "" else "s"} · " + formatDuration(leftMs / 1000) + " left" +
                        if (speed != 1f) " (${formatDuration((leftMs / speed / 1000).toLong())} at ${speedLabel(speed)})" else "",
                    color = Palette.SubText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (now == null) {
            item {
                EmptyState(
                    Icons.Rounded.QueueMusic,
                    "Nothing up next",
                    "Use “Play next” or “Play last” on any episode to line it up. Shows can also add new episodes here by themselves (in a show's settings).",
                    action = "Find something to play",
                    onAction = { vm.selectSection(Section.DISCOVER) },
                )
            }
            return@LazyColumn
        }
        item {
            Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Palette.SubText, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            EpisodeRow(vm, now, onClick = { vm.showPlayer = true })
            Text(
                if (upcoming.isEmpty()) "NOTHING AFTER THIS" else "PLAYING AFTER",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.SubText,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            if (upcoming.size > 1) {
                Text(
                    "Drag ≡ to reorder, swipe left to remove.",
                    color = Palette.Faint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
        }
        itemsIndexed(upcoming, key = { _, e -> e.id }) { pos, e ->
            val from = dragFrom
            val shift = when {
                from == null || target == null -> 0f
                pos == from -> dragOffset
                from < target && pos in (from + 1)..target -> -rowPx
                from > target && pos in target until from -> rowPx
                else -> 0f
            }
            var removed by remember { mutableStateOf(false) }
            val dismiss = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        if (!removed) {
                            removed = true
                            vm.removeFromQueue(e.id)
                        }
                        true
                    } else {
                        false
                    }
                },
            )
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                modifier = Modifier.zIndex(if (pos == from) 1f else 0f).graphicsLayer { translationY = shift },
                backgroundContent = {
                    if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFFB3261E)).padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, tint = Color.White)
                        }
                    }
                },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .background(if (pos == from) Palette.Elevated2 else Palette.Background)
                        .clickable { vm.openEpisode(e) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        "Reorder ${e.title}",
                        tint = Palette.SubText,
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(pos, upcoming.size) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragFrom = pos; dragOffset = 0f },
                                    onDragEnd = {
                                        val start = dragFrom
                                        val end = start?.let { dropTarget(it) }
                                        dragFrom = null
                                        dragOffset = 0f
                                        if (start != null && end != null && start != end) vm.moveUpNext(start, end)
                                    },
                                    onDragCancel = { dragFrom = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                }
                            }
                            .padding(12.dp),
                    )
                    Artwork(vm.artworkOf(e), vm.podcastTitle(e.podcastId), Modifier.size(52.dp), RoundedCornerShape(8.dp), titleSize = 7)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
                        Text(
                            listOf(vm.podcastTitle(e.podcastId), lengthLabel(e, vm.state(e.id))).filter { it.isNotEmpty() }.joinToString("  ·  "),
                            color = Palette.SubText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PlayButton(vm, e)
                    EpisodeMenu(vm, e) { close ->
                        MenuItem("Move to top", Icons.Rounded.VerticalAlignTop) { vm.moveUpNext(pos, 0); close() }
                        MenuItem("Move to bottom", Icons.Rounded.VerticalAlignBottom) { vm.moveUpNext(pos, upcoming.size - 1); close() }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Up Next?") },
            text = { Text("The episode playing now stays. The ${upcoming.size} after it are taken off the list (not deleted).") },
            confirmButton = { TextButton(onClick = { vm.clearUpNext(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
