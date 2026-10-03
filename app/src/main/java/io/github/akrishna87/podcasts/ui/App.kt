package io.github.akrishna87.podcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.PodcastViewModel
import io.github.akrishna87.podcasts.Screen
import io.github.akrishna87.podcasts.Section

@Composable
fun KuralApp(vm: PodcastViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.showPlayer || vm.screens.isNotEmpty() || vm.section != Section.HOME) {
        if (!vm.back()) vm.selectSection(Section.HOME)
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasPlayer = vm.currentId != null
    val bottomSpace = navInset + 64.dp + (if (hasPlayer) 68.dp else 0.dp) + 16.dp

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        CompositionLocalProvider(LocalBottomSpace provides bottomSpace) {
            AnimatedContent(
                targetState = vm.screens.lastOrNull() to vm.section,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { (screen, section) ->
                when (screen) {
                    is Screen.PodcastPage -> PodcastScreen(vm, screen)
                    is Screen.EpisodePage -> EpisodeScreen(vm, screen)
                    is Screen.ShowSettings -> ShowSettingsScreen(vm, screen.podcastId)
                    is Screen.CategoryPage -> CategoryScreen(vm, screen.category)
                    is Screen.FilterPage -> FilterScreen(vm, screen.filterId)
                    Screen.Alerts -> AlertsScreen(vm)
                    Screen.SettingsPage -> SettingsScreen(vm)
                    Screen.StatsPage -> StatsScreen(vm)
                    null -> when (section) {
                        Section.HOME -> HomeScreen(vm)
                        Section.UP_NEXT -> UpNextScreen(vm)
                        Section.DISCOVER -> DiscoverScreen(vm)
                        Section.LIBRARY -> LibraryScreen(vm)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            MiniPlayer(vm)
            BottomBar(vm)
        }

        AnimatedVisibility(
            visible = vm.showPlayer && hasPlayer,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            PlayerScreen(vm)
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (vm.showPlayer) navInset + 16.dp else bottomSpace - 8.dp),
        )
    }
}

@Composable
private fun BottomBar(vm: PodcastViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Background.copy(alpha = 0.94f), Palette.Background))),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val atRoot = vm.screens.isEmpty()
            val queued = (vm.snap.queue.size - 1).coerceAtLeast(0)
            NavItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, vm.section == Section.HOME && atRoot) { vm.selectSection(Section.HOME) }
            NavItem("Up Next", Icons.Rounded.QueueMusic, Icons.Outlined.QueueMusic, vm.section == Section.UP_NEXT && atRoot, badge = queued) {
                vm.selectSection(Section.UP_NEXT)
            }
            NavItem("Discover", Icons.Rounded.Explore, Icons.Outlined.Explore, vm.section == Section.DISCOVER && atRoot) { vm.selectSection(Section.DISCOVER) }
            NavItem("Library", Icons.Rounded.VideoLibrary, Icons.Outlined.VideoLibrary, vm.section == Section.LIBRARY && atRoot) {
                vm.selectSection(Section.LIBRARY)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, selectedIcon: ImageVector, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val tint = if (selected) Palette.Text else Palette.Faint
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(badge = {
            if (badge > 0) Badge(containerColor = Palette.Coral) { Text(if (badge > 99) "99+" else "$badge") }
        }) {
            Icon(if (selected) selectedIcon else icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** The episode in the player, above the tabs. Tap it for the full player. */
@Composable
private fun MiniPlayer(vm: PodcastViewModel) {
    val e = vm.current ?: return
    val art = vm.artworkOf(e)
    val tint = artTint(vm, art, vm.podcastTitle(e.podcastId))
    val fraction = if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 2.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tint.deep(0.62f))
                .clickable(onClickLabel = "Open the player") { vm.showPlayer = true },
        ) {
            Row(
                Modifier.fillMaxWidth().height(62.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Artwork(art, vm.podcastTitle(e.podcastId), Modifier.size(46.dp), RoundedCornerShape(8.dp), titleSize = 6)
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(vm.podcastTitle(e.podcastId), color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                IconButton(onClick = vm::seekBack) { Icon(Icons.Rounded.Replay10, "Skip back", tint = Color.White) }
                IconButton(onClick = vm::togglePlay) {
                    if (vm.isBuffering) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
                    } else {
                        Icon(
                            if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (vm.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
            Spacer(Modifier.height(3.dp))
        }
    }
}
