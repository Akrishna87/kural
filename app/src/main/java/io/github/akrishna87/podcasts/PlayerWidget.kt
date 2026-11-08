package io.github.akrishna87.podcasts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import java.util.concurrent.Executors

/**
 * Home-screen widget: the episode's artwork, title and show, with skip back, play/pause and skip
 * forward (using your skip lengths). Tapping the artwork or title opens the app. With nothing in
 * Up Next it says so and opens the app.
 *
 * While [PlaybackService] runs it [attach]es its player and the widget follows it; otherwise the
 * widget shows the front of Up Next from the library, paused.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context.applicationContext, goAsync())
    }

    /** What the widget shows; null means nothing is loaded. */
    internal data class State(val title: String, val show: String, val art: String?, val playing: Boolean)

    companion object {
        const val ACTION_PLAY_PAUSE = "io.github.akrishna87.podcasts.widget.PLAY_PAUSE"
        const val ACTION_BACK = "io.github.akrishna87.podcasts.widget.BACK"
        const val ACTION_FORWARD = "io.github.akrishna87.podcasts.widget.FORWARD"

        /** Artwork size in pixels, and its corner radius (about 12dp at the widget's 56dp). */
        private const val ART_PX = 192
        private const val ART_RADIUS_PX = 40f

        private val main = Handler(Looper.getMainLooper())
        internal val worker = Executors.newSingleThreadExecutor()

        // Everything below is only touched on the main thread.

        /** The service's player while it runs, for the widget's buttons. */
        internal var player: Player? = null
            private set
        private var listener: Player.Listener? = null
        private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        private var lastState: State? = null
        private var artKey: String? = null
        private var artBitmap: Bitmap? = null
        private var artRequest: Disposable? = null

        /** Called by the service once its player exists: the widget follows it from now on. */
        fun attach(context: Context, p: Player) {
            val app = context.applicationContext
            player?.let { old -> listener?.let(old::removeListener) }
            player = p
            val l = object : Player.Listener {
                override fun onEvents(changed: Player, events: Player.Events) {
                    if (events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_MEDIA_METADATA_CHANGED,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED,
                        )
                    ) {
                        render(app, stateOf(changed))
                    }
                }
            }
            listener = l
            p.addListener(l)
            render(app, stateOf(p))
        }

        /** Called by the service as it stops: the widget keeps the episode, paused. */
        fun detach(context: Context) {
            val p = player ?: return
            val last = stateOf(p)?.copy(playing = false)
            listener?.let(p::removeListener)
            listener = null
            player = null
            render(context.applicationContext, last)
        }

        private fun stateOf(p: Player): State? {
            val item = p.currentMediaItem ?: return null
            val md = item.mediaMetadata
            return State(
                title = md.title?.toString().orEmpty(),
                show = (md.artist ?: md.albumTitle)?.toString().orEmpty(),
                art = md.artworkUri?.toString(),
                playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE,
            )
        }

        /** Without the service: the front of Up Next, read from the library off the main thread. */
        private fun refresh(app: Context, pending: BroadcastReceiver.PendingResult?) {
            worker.execute {
                val fromLibrary = runCatching { fromLibrary(app) }.getOrNull()
                main.post {
                    try {
                        val p = player
                        render(app, if (p != null) stateOf(p) else fromLibrary)
                    } finally {
                        pending?.finish()
                    }
                }
            }
        }

        private fun fromLibrary(app: Context): State? {
            val lib = app.library()
            val id = lib.queue().firstOrNull() ?: return null
            val e = lib.episode(id) ?: return null
            val podcast = lib.podcast(e.podcastId)
            return State(e.title, podcast?.title.orEmpty(), e.artworkUrl ?: podcast?.artworkUrl, playing = false)
        }

        private fun hasWidgets(app: Context) =
            AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, PlayerWidget::class.java)).isNotEmpty()

        private fun render(app: Context, s: State?) {
            lastState = s
            if (!hasWidgets(app)) return
            watchSkipLengths(app)
            val url = s?.art
            if (url != artKey) {
                artRequest?.dispose()
                artRequest = null
                artKey = url
                artBitmap = null
                if (url != null) loadArt(app, url)
            }
            val manager = AppWidgetManager.getInstance(app)
            manager.updateAppWidget(ComponentName(app, PlayerWidget::class.java), views(app, s, artBitmap))
        }

        private fun loadArt(app: Context, url: String) {
            val request = ImageRequest.Builder(app)
                .data(url)
                .size(ART_PX)
                .allowHardware(false) // the bitmap is sent to the launcher, so it has to be a normal one
                .transformations(RoundedCornersTransformation(ART_RADIUS_PX))
                .target(
                    onSuccess = { drawable ->
                        if (artKey == url) {
                            artBitmap = drawable.toBitmap()
                            render(app, lastState)
                        }
                    },
                    onError = { if (artKey == url) artKey = null }, // try again next time
                )
                .build()
            artRequest = app.imageLoader.enqueue(request)
        }

        /** The skip buttons show your skip lengths, so redraw when they change. */
        private fun watchSkipLengths(app: Context) {
            if (prefsListener != null) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Settings.SKIP_BACK || key == Settings.SKIP_FORWARD) main.post { render(app, lastState) }
            }
            prefsListener = l // the preferences only hold it weakly
            Settings.prefs(app).registerOnSharedPreferenceChangeListener(l)
        }

        private fun views(app: Context, s: State?, art: Bitmap?): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_player)
            val openApp = PendingIntent.getActivity(
                app,
                10,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, openApp)

            if (s == null) {
                v.setTextViewText(R.id.widget_title, app.getString(R.string.widget_empty))
                v.setTextViewText(R.id.widget_show, app.getString(R.string.widget_tap_to_open))
                v.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
                v.setViewVisibility(R.id.widget_controls, View.GONE)
                return v
            }

            v.setViewVisibility(R.id.widget_controls, View.VISIBLE)
            v.setTextViewText(R.id.widget_title, s.title.ifEmpty { app.getString(R.string.app_name) })
            v.setTextViewText(R.id.widget_show, s.show.ifEmpty { app.getString(R.string.widget_tap_to_open) })
            if (art != null) v.setImageViewBitmap(R.id.widget_art, art)
            else v.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)

            v.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            v.setContentDescription(R.id.widget_play, app.getString(if (s.playing) R.string.widget_pause else R.string.widget_play))

            val prefs = Settings.prefs(app)
            val back = Settings.skipBackSec(prefs)
            val forward = Settings.skipForwardSec(prefs)
            v.setTextViewText(R.id.widget_back_sec, back.toString())
            v.setTextViewText(R.id.widget_forward_sec, forward.toString())
            v.setContentDescription(R.id.widget_back, app.getString(R.string.widget_back_seconds, back))
            v.setContentDescription(R.id.widget_forward, app.getString(R.string.widget_forward_seconds, forward))

            v.setOnClickPendingIntent(R.id.widget_back, action(app, ACTION_BACK, 11))
            v.setOnClickPendingIntent(R.id.widget_play, action(app, ACTION_PLAY_PAUSE, 12))
            v.setOnClickPendingIntent(R.id.widget_forward, action(app, ACTION_FORWARD, 13))
            return v
        }

        private fun action(app: Context, action: String, request: Int): PendingIntent =
            PendingIntent.getBroadcast(
                app,
                request,
                Intent(app, WidgetActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/** The widget's buttons. They work with the app closed too. */
@OptIn(UnstableApi::class)
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = PlayerWidget.player
        if (player != null) {
            when (intent.action) {
                PlayerWidget.ACTION_PLAY_PAUSE ->
                    if (player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                // The service's player skips by your skip lengths.
                PlayerWidget.ACTION_BACK -> player.seekBack()
                PlayerWidget.ACTION_FORWARD -> player.seekForward()
            }
            return
        }
        when (intent.action) {
            // Nothing running: "play" picks up the front of Up Next, as a headphone press would.
            PlayerWidget.ACTION_PLAY_PAUSE -> {
                val press = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                MediaButtonReceiver().onReceive(context, press)
            }
            // Nothing running: move your saved place, so the episode starts there when you press play.
            PlayerWidget.ACTION_BACK, PlayerWidget.ACTION_FORWARD -> {
                val app = context.applicationContext
                val back = intent.action == PlayerWidget.ACTION_BACK
                val pending = goAsync()
                PlayerWidget.worker.execute {
                    try {
                        nudge(app, back)
                    } catch (e: Exception) {
                        // a skip that couldn't be saved isn't worth a crash
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun nudge(app: Context, back: Boolean) {
        val lib = app.library()
        val id = lib.queue().firstOrNull() ?: return
        val s = lib.state(id) ?: return
        val prefs = Settings.prefs(app)
        val delta = if (back) -Settings.skipBackSec(prefs) * 1000L else Settings.skipForwardSec(prefs) * 1000L
        var target = (s.positionMs + delta).coerceAtLeast(0)
        if (s.durationMs > 0) target = target.coerceAtMost((s.durationMs - 1_000).coerceAtLeast(0))
        lib.savePosition(id, target, s.durationMs, notify = true)
        lib.flush()
    }
}
