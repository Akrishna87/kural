package io.github.akrishna87.podcasts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.podcasts.updater.AppUpdate
import io.github.akrishna87.podcasts.updater.AppUpdater
import io.github.akrishna87.podcasts.updater.UpdateCheck
import kotlinx.coroutines.launch

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val update: AppUpdate, val needsPermission: Boolean = false) : UpdateUi
    data class Downloading(val percent: Int) : UpdateUi
    data object Confirming : UpdateUi
    data class Message(val text: String) : UpdateUi
}

/**
 * Settings > App updates. Nothing here runs by itself: Podwave only asks github.com whether a
 * newer build is published when this button is tapped.
 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val updater = remember { AppUpdater(context, "Akrishna87", "kural", "podcasts-latest") }
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    val installed = remember { updater.installedVersionCode() }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Podwave build $installed", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            "Only when you tap the button below, Podwave asks github.com whether a newer build is published. " +
                "Nothing about you or your shows is sent. Android always asks you to confirm an update.",
            color = Palette.SubText,
            fontSize = 13.sp,
        )
        when (val s = ui) {
            UpdateUi.Idle -> Unit
            UpdateUi.Checking -> Text("Checking…", color = Palette.SubText, fontSize = 14.sp)
            UpdateUi.UpToDate -> Text("You have the latest build.", color = Palette.Violet, fontSize = 14.sp)
            is UpdateUi.Available -> {
                Text("Build ${s.update.versionCode} (${s.update.versionName}) is ready.", color = Palette.Violet, fontSize = 14.sp)
                if (s.needsPermission) {
                    Text(
                        "Allow Podwave to install apps on the screen that opened, go back, then tap Download and install again.",
                        color = Palette.SubText,
                        fontSize = 13.sp,
                    )
                }
            }
            is UpdateUi.Downloading -> {
                Text("Downloading… ${s.percent}%", color = Palette.SubText, fontSize = 14.sp)
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            UpdateUi.Confirming -> Text("Android will now ask you to confirm the update.", color = Palette.Violet, fontSize = 14.sp)
            is UpdateUi.Message -> Text(s.text, color = Palette.Violet, fontSize = 14.sp)
        }
        val busy = ui is UpdateUi.Checking || ui is UpdateUi.Downloading
        Button(
            enabled = !busy,
            onClick = {
                val current = ui
                if (current is UpdateUi.Available) {
                    if (!updater.canInstall()) {
                        updater.openInstallPermissionScreen()
                        ui = current.copy(needsPermission = true)
                    } else {
                        scope.launch {
                            ui = UpdateUi.Downloading(0)
                            ui = try {
                                updater.downloadAndInstall(current.update) { percent -> ui = UpdateUi.Downloading(percent) }
                                UpdateUi.Confirming
                            } catch (e: Exception) {
                                UpdateUi.Message("Couldn't download the update: ${e.message ?: "unknown error"}")
                            }
                        }
                    }
                } else {
                    scope.launch {
                        ui = UpdateUi.Checking
                        ui = when (val r = updater.checkForUpdate()) {
                            UpdateCheck.UpToDate -> UpdateUi.UpToDate
                            is UpdateCheck.Available -> UpdateUi.Available(r.update)
                            is UpdateCheck.Failed -> UpdateUi.Message("Couldn't check for updates. ${r.reason}.")
                        }
                    }
                }
            },
        ) { Text(if (ui is UpdateUi.Available) "Download and install" else "Check for updates") }
    }
}
