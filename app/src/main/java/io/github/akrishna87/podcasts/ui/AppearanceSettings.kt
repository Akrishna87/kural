package io.github.akrishna87.podcasts.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings → Appearance: dark, light or match the phone; the accent colour; Material You colours. */
@Composable
fun AppearanceSettings() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Theme")
            Text("The player always stays dark, tinted by the show's artwork", color = Palette.SubText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = ThemeSettings.mode == mode,
                        onClick = { ThemeSettings.update(context, mode = mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.semantics { contentDescription = "Theme ${mode.label}" },
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Accent colour")
            if (ThemeSettings.wallpaperColors && Build.VERSION.SDK_INT >= 31) {
                Text("Using your wallpaper's colours. Turn that off below to pick one.", color = Palette.SubText, fontSize = 13.sp)
            } else {
                Text("For buttons and highlights", color = Palette.SubText, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AccentColor.entries.forEach { a ->
                        val chosen = ThemeSettings.accent == a
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (Palette.isDark) a.dark else a.light)
                                .selectable(selected = chosen, role = Role.RadioButton) { ThemeSettings.setAccent(context, a) }
                                .semantics { contentDescription = "Accent ${a.label}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (chosen) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = if (Palette.isDark) Color.Black else Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            Toggle(
                "Colours from your wallpaper",
                ThemeSettings.wallpaperColors,
                "Use your phone's Material You colours for buttons and highlights",
            ) { ThemeSettings.update(context, wallpaperColors = it) }
        }
    }
}
