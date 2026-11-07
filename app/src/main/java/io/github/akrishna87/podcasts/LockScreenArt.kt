package io.github.akrishna87.podcasts

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The playing episode's artwork, full screen on the lock screen, as music players used to do
 * before Android 11 dropped it. It is set as the lock-screen wallpaper only, and taken away
 * again when playback ends, which puts the home screen wallpaper back on the lock screen.
 */
object LockScreenArt {
    /** Set while our artwork is on the lock screen, so we only ever clear our own. */
    private const val SHOWING = "lock_art_showing"

    suspend fun show(context: Context, artworkUrl: String) {
        if (Build.VERSION.SDK_INT < 24) return
        val wallpapers = WallpaperManager.getInstance(context)
        if (!wallpapers.isWallpaperSupported || !wallpapers.isSetWallpaperAllowed) return
        val screen = context.resources.displayMetrics
        val width = screen.widthPixels
        val height = maxOf(screen.heightPixels, width)
        val request = ImageRequest.Builder(context).data(artworkUrl).size(width).allowHardware(false).build()
        val art = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap() ?: return
        withContext(Dispatchers.Default + kotlinx.coroutines.NonCancellable) {
            val picture = compose(art, width, height)
            runCatching {
                wallpapers.setBitmap(picture, null, true, WallpaperManager.FLAG_LOCK)
                Settings.prefs(context).edit().putBoolean(SHOWING, true).apply()
            }
        }
    }

    /** Puts the lock screen back to the home screen wallpaper, if our artwork is on it. */
    fun clear(context: Context) {
        val prefs = Settings.prefs(context)
        if (Build.VERSION.SDK_INT < 24 || !prefs.getBoolean(SHOWING, false)) return
        runCatching { WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK) }
        prefs.edit().putBoolean(SHOWING, false).apply()
    }

    /**
     * A screen-sized picture: the square artwork across the full width, a little below the
     * middle so the clock stays clear, on a gradient of the artwork's own darkest colours.
     */
    private fun compose(art: Bitmap, width: Int, height: Int): Bitmap {
        val palette = Palette.from(art).generate()
        val top = palette.getDarkMutedColor(palette.getDarkVibrantColor(0xFF15121F.toInt()))
        val bottom = palette.getDarkVibrantColor(top)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) },
        )
        val side = width
        val y = ((height - side) * 0.55f).toInt()
        canvas.drawBitmap(art, null, Rect(0, y, side, y + side), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
