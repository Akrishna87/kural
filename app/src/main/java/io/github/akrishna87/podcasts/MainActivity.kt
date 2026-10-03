package io.github.akrishna87.podcasts

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.akrishna87.podcasts.ui.KuralApp
import io.github.akrishna87.podcasts.ui.KuralTheme

class MainActivity : ComponentActivity() {
    private val vm: PodcastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark, so keep the status and navigation bar icons light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.handleIntent(intent)
        setContent {
            KuralTheme {
                KuralApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }
}
