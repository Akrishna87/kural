package io.github.akrishna87.podcasts.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.akrishna87.podcasts.feed.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** A newer build than the one that is installed. */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val sizeBytes: Long,
)

/** What [AppUpdater.checkForUpdate] found. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val update: AppUpdate) : UpdateCheck
    /** [reason] is a short sentence that is fine to show to the user. */
    data class Failed(val reason: String) : UpdateCheck
}

/**
 * Checks GitHub for a newer build of this app and installs it.
 *
 * The build's workflow (.github/workflows/podcasts-apk.yml) publishes a small `version.json`
 * next to Podwave.apk in the `podcasts-latest` release. This class reads that file, compares its
 * `versionCode` (the CI run number, also passed to Gradle as VERSION_CODE) with the installed one,
 * downloads the APK, checks its SHA-256, and hands it to Android's installer. Android still asks
 * the user to tap "Update": a normal app cannot install silently, and Android only accepts the
 * update if it is for this package and signed with the same key as the installed app.
 *
 *     val updater = AppUpdater(context, "Akrishna87", "kural", "podcasts-latest")
 *     when (val result = updater.checkForUpdate()) {
 *         is UpdateCheck.Available -> {
 *             if (!updater.canInstall()) updater.openInstallPermissionScreen()
 *             else updater.downloadAndInstall(result.update) { percent -> /* show progress */ }
 *         }
 *         UpdateCheck.UpToDate -> { /* nothing to do */ }
 *         is UpdateCheck.Failed -> { /* show result.reason */ }
 *     }
 *
 * Call it while the app is on screen. All three suspend functions switch to Dispatchers.IO
 * themselves, so they are safe to call from viewModelScope or lifecycleScope.
 */
class AppUpdater(
    context: Context,
    private val owner: String,
    private val repo: String,
    private val tag: String,
) {
    private val context = context.applicationContext
    private val base get() = "https://github.com/$owner/$repo/releases/download/$tag/"

    /** The version code of the build that is running now. */
    fun installedVersionCode(): Long = packageInfo().let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else legacyVersionCode(it)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() = context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo) = info.versionCode.toLong()

    /** Asks GitHub whether a newer build than the one running is published. */
    suspend fun checkForUpdate(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(open(base + "version.json").inputStream.use { it.reader().readText() })
            val code = json.getLong("versionCode")
            if (code <= installedVersionCode()) return@withContext UpdateCheck.UpToDate
            UpdateCheck.Available(
                AppUpdate(
                    versionCode = code,
                    versionName = json.optString("versionName", code.toString()),
                    apkUrl = base + Uri.encode(json.getString("apk")),
                    sha256 = json.optString("sha256").ifEmpty { null },
                    sizeBytes = json.optLong("size", -1),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, or the release is being rebuilt right now. Try again later.
            Log.i(TAG, "No update info: $e")
            UpdateCheck.Failed(explain(e))
        }
    }

    private fun explain(e: Exception): String = when {
        e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ->
            "No connection to GitHub right now"
        e is IOException && e.message?.startsWith("HTTP 404") == true ->
            "No update has been published yet"
        e is IOException -> "Couldn't reach GitHub"
        else -> "Couldn't read the update information"
    }

    /** On Android 8+ the user must allow this app to install apps. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the Android screen where the user turns that permission on. */
    fun openInstallPermissionScreen() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Downloads [update], verifies it, and starts Android's installer. Throws IOException on failure. */
    suspend fun downloadAndInstall(update: AppUpdate, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)     // refuse an APK for any other app
            if (update.sizeBytes > 0) setSize(update.sizeBytes)
            if (Build.VERSION.SDK_INT >= 31) {
                // Skips the extra confirmation on Android 12+ when Android allows it for updates.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val conn = open(update.apkUrl)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    session.openWrite("update.apk", 0, if (total > 0) total else -1).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                            }
                        }
                        session.fsync(out)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (update.sha256 != null && !actual.equals(update.sha256, ignoreCase = true)) {
                    throw IOException("The download is damaged (checksum does not match)")
                }
                val resultIntent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // The system fills in extras on this PendingIntent, so it must be mutable on Android 12+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, resultIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            if (e is CancellationException) throw e
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true          // GitHub redirects release files to its CDN
            setRequestProperty("User-Agent", Http.USER_AGENT)
            if (responseCode != 200) throw IOException("HTTP $responseCode for $url")
        }

    private companion object {
        const val TAG = "AppUpdater"
    }
}

/** Receives the installer's answer. Android asks for confirmation through this. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                confirmationIntent(intent)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)           // the system's "Do you want to update this app?" screen
                }
            PackageInstaller.STATUS_SUCCESS -> Log.i("AppUpdater", "Update installed")
            else -> Log.w("AppUpdater", "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
