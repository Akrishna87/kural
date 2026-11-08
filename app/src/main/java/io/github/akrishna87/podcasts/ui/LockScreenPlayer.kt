package io.github.akrishna87.podcasts.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Settings
import io.github.akrishna87.podcasts.feed.formatClock
import kotlinx.coroutines.delay

/**
 * Whether the phone is locked right now: checked the moment the app comes on screen (so waking
 * the phone never shows your library first), then twice a second while it stays there.
 */
@Composable
fun rememberPhoneLocked(): Boolean {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locked by produceState(keyguard.isKeyguardLocked, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = keyguard.isKeyguardLocked
                delay(500)
            }
        }
    }
    return locked
}

/** Lets the app's window show over the lock screen (or stops it). */
fun Activity.showOverLockScreen(on: Boolean) {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(on)
    } else {
        @Suppress("DEPRECATION")
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }
}

/**
 * What shows over the lock screen: the artwork full width, the episode, a heart, the seek bar
 * and the controls, and nothing else (no library, Up Next or settings until the phone is unlocked).
 */
@Composable
fun LockScreenPlayer(vm: PodcastViewModel) {
    val context = LocalContext.current
    val e = vm.current ?: return
    val show = vm.podcastTitle(e.podcastId)
    val art = vm.artworkOf(e)
    val tint = artTint(vm, art, show)
    val duration = vm.durationMs
    val prefs = vm.prefs()
    BackHandler {} // Back does nothing here; unlock to leave.
    // Dark in every theme, tinted by the artwork.
    AlwaysDark {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(tint.deep(0.15f), tint.deep(0.7f), Color(0xFF070709))))
                .semantics { contentDescription = "Lock screen player" },
        ) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("PODWAVE", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(0.6f))
                Artwork(
                    art,
                    show,
                    Modifier.fillMaxWidth().aspectRatio(1f).shadow(30.dp, RoundedCornerShape(14.dp)),
                    RoundedCornerShape(14.dp),
                    titleSize = 26,
                )
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Same width as the heart, so the title stays centred.
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = Color.White)
                        Text(show, color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val loved = vm.state(e.id)?.starred == true
                    IconButton(onClick = { vm.toggleStar(e) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (loved) "Remove from Favourites" else "Add to Favourites",
                            tint = if (loved) LocalAppColors.current.heart else Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                var dragging by remember { mutableStateOf<Float?>(null) }
                val fraction = if (duration > 0) vm.positionMs.toFloat() / duration else 0f
                Slider(
                    value = dragging ?: fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { vm.seekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    enabled = duration > 0,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatClock(vm.positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (duration > 0) "−" + formatClock(duration - vm.positionMs) else "", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = vm::seekBack, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Rounded.Replay, "Back ${Settings.skipBackSec(prefs)} seconds", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    Surface(onClick = vm::togglePlay, shape = CircleShape, color = Color.White, contentColor = Color.Black, modifier = Modifier.size(78.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (vm.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                    IconButton(onClick = vm::seekForward, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Rounded.FastForward, "Forward ${Settings.skipForwardSec(prefs)} seconds", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val activity = context as? Activity ?: return@TextButton
                    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(activity, null)
                }) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock to open Podwave", color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}
