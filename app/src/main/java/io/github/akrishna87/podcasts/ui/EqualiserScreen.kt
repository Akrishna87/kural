package io.github.akrishna87.podcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.Equaliser
import io.github.akrishna87.podcasts.EqualiserState
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import java.util.Locale
import kotlin.math.roundToInt

private fun hzLabel(hz: Int): String = when {
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k >= 10 || k == k.roundToInt().toFloat()) "${k.roundToInt()}k" else String.format(Locale.US, "%.1fk", k)
    }
    else -> "$hz"
}

private fun dbLabel(mb: Int): String {
    val db = mb / 100f
    return when {
        db > 0.05f -> String.format(Locale.US, "+%.1f", db)
        db < -0.05f -> String.format(Locale.US, "%.1f", db)
        else -> "0"
    }
}

/** The equaliser and bass boost, for every show. Opened from the player's sound options and from Settings. */
@Composable
fun EqualiserScreen(vm: PodcastViewModel, page: Screen.EqualiserPage) {
    val eq = vm.equaliser
    val info = eq.info
    val choice = eq.choice
    val close: () -> Unit = {
        vm.back()
        if (page.fromPlayer && vm.currentId != null) vm.showPlayer = true
    }
    BackHandler(onBack = close)

    Column(Modifier.fillMaxSize()) {
        TopBar("Equaliser", onBack = close) {
            Switch(
                checked = choice.enabled,
                onCheckedChange = eq::setEnabled,
                enabled = info?.available == true,
                modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = "Equaliser on/off" },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = LocalBottomSpace.current),
        ) {
            when {
                info == null -> Note("Play an episode and the equaliser will be ready.")
                !info.available -> Note(
                    "This phone doesn't let apps use its built-in equaliser. " +
                        "If it has its own sound settings (often under Settings → Sound → Sound quality and effects), those still apply to Podwave.",
                )
                else -> EqControls(eq, info, choice)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp), color = Palette.SubText, textAlign = TextAlign.Center)
}

@Composable
private fun ColumnScope.EqControls(eq: EqualiserState, info: Equaliser.Info, choice: Equaliser.Choice) {
    val levels = eq.levels()
    val active = choice.enabled
    val accent = if (active) Palette.Violet else Palette.Faint

    Text(
        if (active) "Shape the sound for every show: lift voices, tame boomy hosts. Changes apply straight away."
        else "Turn the equaliser on to change the sound. It applies to every show.",
        color = Palette.SubText,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    SettingsHeader("Presets")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            PresetChip("Custom", selected = choice.preset !in info.presets.indices, enabled = active) {
                eq.setBand(0, levels.firstOrNull() ?: 0)
            }
        }
        itemsIndexed(info.presets) { i, (name, _) ->
            PresetChip(name, selected = choice.preset == i, enabled = active) { eq.selectPreset(i) }
        }
    }

    SettingsHeader("Bands")
    // dB readout above each band
    Row(Modifier.fillMaxWidth()) {
        levels.forEach { level ->
            Text(dbLabel(level), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = if (active) Palette.Text else Palette.Faint, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(6.dp))
    EqCurve(
        levels = levels,
        min = info.minLevel,
        max = info.maxLevel,
        accent = accent,
        onChange = { band, level -> eq.setBand(band, level) },
        modifier = Modifier.fillMaxWidth().height(240.dp),
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        info.bandsHz.forEach { hz ->
            Text(hzLabel(hz) + "Hz", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = Palette.SubText)
        }
    }

    if (info.bassBoost) {
        SettingsHeader("Bass boost")
        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: (choice.bass / 1000f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { eq.setBass((it * 1000).roundToInt()) }
                dragging = null
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Palette.Highlight),
            modifier = Modifier.semantics { contentDescription = "Bass boost" },
        )
    }

    Spacer(Modifier.height(16.dp))
    TextButton(onClick = eq::reset, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Reset to flat", color = Palette.SubText)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected && enabled,
        onClick = onClick,
        label = { Text(name) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * All the bands in one picture: a smooth curve through each band's level with a handle per band.
 * Drag (or tap) anywhere in a band's column to set its level.
 */
@Composable
private fun EqCurve(
    levels: List<Int>,
    min: Int,
    max: Int,
    accent: Color,
    onChange: (band: Int, level: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val n = levels.size.coerceAtLeast(1)
    val span = (max - min).coerceAtLeast(1)
    fun levelAt(y: Float, height: Float, pad: Float): Int {
        val f = ((y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
        // Snap to whole dB steps.
        return (((max - f * span) / 100f).roundToInt() * 100).coerceIn(min, max)
    }
    Canvas(
        modifier
            .semantics { contentDescription = "Equaliser bands" }
            .pointerInput(n, min, max) {
                val pad = 14.dp.toPx()
                detectTapGestures { o ->
                    val band = (o.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                    change(band, levelAt(o.y, size.height.toFloat(), pad))
                }
            }
            .pointerInput(n, min, max) {
                val pad = 14.dp.toPx()
                var band = 0
                detectDragGestures(
                    onDragStart = { o -> band = (o.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1) },
                ) { changeEvent, _ ->
                    change(band, levelAt(changeEvent.position.y, size.height.toFloat(), pad))
                }
            },
    ) {
        val pad = 14.dp.toPx()
        val colW = size.width / n
        fun x(i: Int) = colW * (i + 0.5f)
        fun y(level: Int) = pad + (max - level).toFloat() / span * (size.height - 2 * pad)

        // Zero line and band guides.
        drawLine(Palette.Text.copy(alpha = 0.12f), Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
        for (i in 0 until n) {
            drawLine(Palette.Text.copy(alpha = 0.08f), Offset(x(i), pad), Offset(x(i), size.height - pad), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        if (levels.isEmpty()) return@Canvas

        // Smooth curve through the band levels, with a soft fill under it.
        val points = levels.mapIndexed { i, l -> Offset(x(i), y(l)) }
        val curve = Path().apply {
            moveTo(0f, points.first().y)
            lineTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
            lineTo(size.width, points.last().y)
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(curve, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { p ->
            drawCircle(Color.White, radius = 9.dp.toPx(), center = p)
            drawCircle(accent, radius = 5.dp.toPx(), center = p)
        }
    }
}
