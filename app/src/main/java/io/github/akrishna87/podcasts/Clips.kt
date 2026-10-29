package io.github.akrishna87.podcasts

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.feed.Http
import java.io.File
import java.io.IOException

/** Cutting a part of an episode into an .m4a file to keep on the phone or share. */
@OptIn(UnstableApi::class)
object Clips {
    const val MIN_MS = 1_000L
    const val MAX_MS = 10 * 60_000L
    const val DEFAULT_MS = 30_000L
    /** Saved clips go here, where music apps and file managers find them. */
    const val FOLDER = "Music/Yap"

    /** The clip's first guess: the 30 seconds you just heard. */
    fun defaultRange(positionMs: Long, durationMs: Long): LongRange {
        val end = if (durationMs > 0) positionMs.coerceAtMost(durationMs) else positionMs
        if (end >= DEFAULT_MS) return (end - DEFAULT_MS)..end
        val longEnough = if (durationMs > 0) DEFAULT_MS.coerceAtMost(durationMs) else DEFAULT_MS
        return 0L..longEnough
    }

    /** "Show – Episode (12m30s).m4a", without characters that file systems or apps refuse. */
    fun fileName(show: String, episode: String, startMs: Long): String {
        fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        val at = startMs / 1000
        val stamp = if (at >= 3600) "${at / 3600}h${at % 3600 / 60}m${at % 60}s" else "${at / 60}m${at % 60}s"
        val title = listOf(clean(show), clean(episode)).filter { it.isNotEmpty() }.joinToString(" – ").ifEmpty { "Clip" }
        return "${title.take(90).trim()} ($stamp).m4a"
    }

    fun workDir(context: Context): File = File(context.cacheDir, "clips").apply { mkdirs() }

    /** The episode's part from [startMs] to [endMs], read from the download if there is one. */
    fun editedItem(context: Context, lib: Library, e: Episode, startMs: Long, endMs: Long): EditedMediaItem {
        val local = Downloads.localFile(context, lib, e.id)
        val item = MediaItem.Builder()
            .setUri(if (local != null) Uri.fromFile(local) else Uri.parse(e.audioUrl))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
            )
            .build()
        return EditedMediaItem.Builder(item).setRemoveVideo(true).build()
    }

    /**
     * Reads episodes like the player does, and lets MP3s without a seek table jump straight to the
     * clip's start instead of reading everything before it.
     */
    fun assetLoaderFactory(context: Context): DefaultAssetLoaderFactory {
        val http = DefaultHttpDataSource.Factory().setUserAgent(Http.USER_AGENT).setAllowCrossProtocolRedirects(true)
        val sources = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context, http),
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
        )
        return DefaultAssetLoaderFactory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            sources,
            DataSourceBitmapLoader(context),
        )
    }

    /** Copies a finished clip into [FOLDER]. Returns where it went, for the message. */
    fun saveToPhone(context: Context, clip: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, clip.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("The phone didn't let us save the clip")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("The phone didn't let us save the clip")).use { out ->
                    clip.inputStream().use { it.copyTo(out) }
                }
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return FOLDER
        }
        // Android 8 and 9 need a storage permission for shared folders, so keep it in the app's own Music folder.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: throw IOException("There's no storage to save to")
        clip.copyTo(File(dir, clip.name), overwrite = true)
        return "Android/data/${context.packageName}/files/Music"
    }

    /** A share sheet with the clip itself attached. */
    fun shareIntent(context: Context, clip: File, e: Episode, show: String, startMs: Long): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", clip)
        val text = buildString {
            append("A clip from “").append(e.title).append("”")
            if (show.isNotEmpty()) append(" — ").append(show)
            append(", at ").append(io.github.akrishna87.podcasts.feed.formatClock(startMs))
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, e.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(clip.name, uri)
        return Intent.createChooser(send, "Share clip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
