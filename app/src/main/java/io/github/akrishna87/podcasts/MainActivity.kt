package io.github.akrishna87.podcasts

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.akrishna87.podcasts.ui.PodwaveApp
import io.github.akrishna87.podcasts.ui.PodwaveTheme
import io.github.akrishna87.podcasts.ui.ThemeSettings

class MainActivity : ComponentActivity() {
    private val vm: PodcastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status-bar icons to start with; the app switches them when the theme is light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.handleIntent(intent)
        ThemeSettings.load(this)
        Backups.start(this)
        setContent {
            PodwaveTheme(ThemeSettings.mode, ThemeSettings.wallpaperColors, ThemeSettings.accent) {
                PodwaveApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }
}
