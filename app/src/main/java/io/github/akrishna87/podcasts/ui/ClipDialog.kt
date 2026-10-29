package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.Clips
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.feed.formatClock

/**
 * Cut the part of an episode you liked: drag the ends (or nudge them, or set one to where the
 * episode is now), preview it, then save the clip to the phone or share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDialog(vm: PodcastViewModel, e: Episode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val show = vm.podcastTitle(e.podcastId)
    val duration = vm.durationMs.takeIf { it > 0 } ?: (e.durationSec * 1000)
    val first = remember { Clips.defaultRange(vm.positionMs, duration) }
    var start by remember { mutableLongStateOf(first.first) }
    var end by remember { mutableLongStateOf(first.last) }
    // The slider spans a few minutes either side of the clip, so a drag moves by seconds, not minutes.
    var window by remember { mutableStateOf(windowAround(start, end, duration)) }
    var previewing by remember { mutableStateOf(false) }
    val making = vm.clipProgress
    val limit = if (duration > 0) duration else Long.MAX_VALUE

    fun setRange(newStart: Long, newEnd: Long, movedStart: Boolean) {
        var s = newStart.coerceIn(0, limit)
        var en = newEnd.coerceIn(0, limit)
        // Keep the length between the shortest and longest clip, moving the end that wasn't touched.
        val length = (en - s).coerceIn(Clips.MIN_MS, Clips.MAX_MS)
        if (movedStart) en = (s + length).coerceAtMost(limit) else s = (en - length).coerceAtLeast(0)
        if (en - s < Clips.MIN_MS) { if (movedStart) s = (en - Clips.MIN_MS).coerceAtLeast(0) else en = (s + Clips.MIN_MS).coerceAtMost(limit) }
        start = s
        end = en
        if (s < window.first || en > window.last) window = windowAround(s, en, duration)
    }

    LaunchedEffect(previewing) {
        // The preview pauses by itself at the clip's end.
        if (previewing) kotlinx.coroutines.delay(1_000) // time to start playing
        while (previewing) {
            if (!vm.isPlaying) previewing = false
            kotlinx.coroutines.delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { if (previewing) vm.stopPreview() } }

    AlertDialog(
        onDismissRequest = { if (making == null) onDismiss() },
        title = { Text("Clip this part") },
        text = {
            Column(Modifier.verticalScrollable()) {
                Text(e.title, color = Palette.SubText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("${formatClock(start)} – ${formatClock(end)}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(lengthText(end - start), color = Palette.SubText)
                RangeSlider(
                    value = start.toFloat()..end.toFloat(),
                    onValueChange = { r ->
                        val s = r.start.toLong()
                        val en = r.endInclusive.toLong()
                        setRange(s, en, movedStart = kotlin.math.abs(s - start) > kotlin.math.abs(en - end))
                    },
                    valueRange = window.first.toFloat()..window.last.toFloat(),
                    enabled = making == null,
                )
                EdgeRow("Start", start, enabled = making == null, onNudge = { setRange(start + it, end, movedStart = true) }) {
                    setRange(vm.positionMs, end, movedStart = true)
                }
                EdgeRow("End", end, enabled = making == null, onNudge = { setRange(start, end + it, movedStart = false) }) {
                    setRange(start, vm.positionMs, movedStart = false)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (previewing) vm.stopPreview() else vm.previewClip(start, end)
                        previewing = !previewing
                    },
                    enabled = making == null,
                ) {
                    Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (previewing) "Stop preview" else "Preview clip")
                }
                if (making != null) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { making / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Making your clip… $making%", color = Palette.SubText, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text(
                        if (android.os.Build.VERSION.SDK_INT >= 29) "Saved clips go to ${Clips.FOLDER}, where music apps and file managers find them."
                        else "Saved clips go to Yap's own Music folder.",
                        color = Palette.Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.makeClip(e, start, end) { vm.saveClip(it); onDismiss() } }, enabled = making == null) {
                    Text("Save to phone")
                }
                TextButton(
                    onClick = {
                        vm.makeClip(e, start, end) {
                            context.startActivity(Clips.shareIntent(context, it, e, show, start))
                            onDismiss()
                        }
                    },
                    enabled = making == null,
                ) { Text("Share clip") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (making != null) vm.cancelClip() else onDismiss() }) { Text("Cancel") }
        },
    )
}

/** One end of the clip: nudge it a few seconds, or set it to where the episode is now. */
@Composable
private fun EdgeRow(label: String, at: Long, enabled: Boolean, onNudge: (Long) -> Unit, onNow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Palette.SubText, modifier = Modifier.width(44.dp), fontSize = 14.sp)
        IconButton(onClick = { onNudge(-5_000) }, enabled = enabled) { Icon(Icons.Rounded.Replay5, "$label 5 seconds earlier") }
        Text(formatClock(at), fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 56.dp))
        IconButton(onClick = { onNudge(5_000) }, enabled = enabled) { Icon(Icons.Rounded.Forward5, "$label 5 seconds later") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onNow, enabled = enabled) { Text("Now") }
    }
}

private fun windowAround(start: Long, end: Long, duration: Long): LongRange {
    val pad = 3 * 60_000L
    val from = (start - pad).coerceAtLeast(0)
    val to = if (duration > 0) (end + pad).coerceAtMost(duration) else end + pad
    return from..to.coerceAtLeast(from + Clips.MIN_MS)
}

private fun lengthText(ms: Long): String {
    val s = ((ms + 500) / 1000).toInt()
    return when {
        s < 60 -> "$s seconds"
        s % 60 == 0 -> "${s / 60} min"
        else -> "${s / 60} min ${s % 60} s"
    }
}
