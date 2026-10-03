package io.github.akrishna87.podcasts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.akrishna87.podcasts.data.Chapter
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.feed.indexAt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Owns the player. Media3 turns the session into the media notification, lock-screen controls,
 * headphone/car/Bluetooth buttons and Android Auto, and keeps playback going in the background.
 *
 * The player follows Up Next in the [Library]: its playlist is always Up Next, with the episode
 * at the front playing. Screens change Up Next; this service keeps the player in step, saves your
 * place, applies each show's speed and effects, skips intros and outros, runs the sleep timer and
 * keeps your listening stats.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val FADE_MS = 10_000L
        /** Volume boost, in millibels: about what Overcast's Voice Boost adds to quiet shows. */
        private const val BOOST_MB = 900
    }

    private lateinit var lib: Library
    private lateinit var prefs: SharedPreferences
    private lateinit var exo: ExoPlayer
    private lateinit var player: PodcastPlayer
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()
    private var loudness: LoudnessEnhancer? = null
    private val carLibrary by lazy { CarLibrary(this) }
    private val libraryExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /** The episode the player had last, to know which one just finished. */
    private var lastId: String? = null
    private var lastTickAt = 0L
    private var lastTickPos = -1L
    private var lastChapter = -1
    private var syncing = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Settings.SKIP_BACK, Settings.SKIP_FORWARD -> session?.setMediaButtonPreferences(skipButtons())
            else -> applyEffects()
        }
    }

    override fun onCreate() {
        super.onCreate()
        lib = library()
        prefs = Settings.prefs(this)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // streaming keeps Wi-Fi awake too
            .build()
        // A fixed audio session so the volume boost can attach to it.
        val audioSession = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        exo.setAudioSessionId(audioSession)
        loudness = try {
            LoudnessEnhancer(audioSession)
        } catch (e: Exception) {
            null // some phones have no loudness effect
        }
        player = PodcastPlayer(exo)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(skipButtons())
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePosition(notify = true)
                lastTickPos = -1
                if (isPlaying) maybeRestartSleepTimer()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = lastId
                lastId = mediaItem?.mediaId
                lastChapter = -1
                // The player moved on by itself: the one before is finished.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null && previous != mediaItem?.mediaId) {
                    finished(previous)
                }
                applyEffects()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exo.currentMediaItem?.mediaId?.let { finishCurrent(completed = true) }
                }
                if (playbackState == Player.STATE_READY) savePosition(notify = false)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "End of episode" sleep timer: the player has just paused at the end.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    SleepTimer.fired(this@PlaybackService)
                    exo.pauseAtEndOfMediaItems = false
                    finishCurrent(completed = true)
                }
            }

            override fun onTracksChanged(tracks: Tracks) = readFileChapters(tracks)

            override fun onPlayerError(error: PlaybackException) {
                savePosition(notify = true)
                // A downloaded file that's gone missing: stream it instead.
                val id = exo.currentMediaItem?.mediaId ?: return
                val uri = exo.currentMediaItem?.localConfiguration?.uri
                if (uri?.scheme == "file") {
                    lib.updateState(id) { it.copy(downloaded = false) }
                    reloadQueue(play = true)
                }
            }
        })

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        scope.launch { lib.queueVersion.collect { syncQueue() } }
        scope.launch { lib.version.collect { applyEffects() } }
        scope.launch {
            while (isActive) {
                delay(500)
                tick()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps an episode playing if it's playing; otherwise shut down.
        if (!exo.playWhenReady || exo.mediaItemCount == 0 || exo.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        savePosition(notify = false)
        lib.flush()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        session?.release()
        session = null
        loudness?.release()
        exo.release()
        libraryExecutor.shutdown()
        super.onDestroy()
    }

    // ----- Following Up Next -----

    /** Makes the player's playlist match Up Next. */
    private fun syncQueue() {
        if (syncing) return
        syncing = true
        try {
            val ids = lib.queue()
            if (ids.isEmpty()) {
                if (exo.mediaItemCount > 0) {
                    savePosition(notify = false)
                    exo.stop()
                    exo.clearMediaItems()
                }
                lib.consumePlayRequest()
                return
            }
            val current = exo.currentMediaItem?.mediaId
            if (current != ids[0]) {
                // A different episode at the front: load the whole queue, starting where you left it.
                savePosition(notify = false)
                val wasPlaying = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.playbackState != Player.STATE_IDLE
                val items = ids.mapNotNull { id -> lib.episode(id)?.let { mediaItemFor(this, lib, it) } }
                if (items.isEmpty() || items[0].mediaId != ids[0]) {
                    // The front episode is missing from the library: drop it and try the next.
                    lib.stopCurrent()
                    return
                }
                lastId = ids[0]
                lastChapter = -1
                exo.setMediaItems(items, 0, startPosition(ids[0]))
                exo.prepare()
                applyEffects()
                if (lib.consumePlayRequest() || wasPlaying) exo.play() else exo.pause()
                return
            }
            // Same episode in front: drop finished ones before it and match what comes after.
            val index = exo.currentMediaItemIndex
            if (index > 0) exo.removeMediaItems(0, index)
            val have = (1 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }
            val want = ids.drop(1)
            if (have != want) {
                if (exo.mediaItemCount > 1) exo.removeMediaItems(1, exo.mediaItemCount)
                exo.addMediaItems(want.mapNotNull { id -> lib.episode(id)?.let { mediaItemFor(this, lib, it) } })
            }
            if (lib.consumePlayRequest()) {
                if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
                if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0, startPosition(ids[0]))
                exo.play()
            }
        } finally {
            syncing = false
        }
    }

    /** Rebuilds the playlist (after a download finished or vanished), keeping the place. */
    private fun reloadQueue(play: Boolean) {
        val ids = lib.queue()
        if (ids.isEmpty()) return
        val pos = if (exo.currentMediaItem?.mediaId == ids[0]) exo.currentPosition else startPosition(ids[0])
        val items = ids.mapNotNull { id -> lib.episode(id)?.let { mediaItemFor(this, lib, it) } }
        if (items.isEmpty()) return
        exo.setMediaItems(items, 0, pos)
        exo.prepare()
        if (play) exo.play()
    }

    /** Where to start an episode: where you left it, past the show's intro, or the start if you'd finished it. */
    private fun startPosition(id: String): Long {
        val s = lib.state(id)
        val e = lib.episode(id)
        val duration = s?.durationMs?.takeIf { it > 0 } ?: ((e?.durationSec ?: 0) * 1000)
        var pos = s?.positionMs ?: 0L
        if (s?.played == true || (duration > 0 && pos > duration - 5_000)) pos = 0
        val intro = (e?.let { lib.entry(it.podcastId)?.settings?.skipIntroSec } ?: 0) * 1000L
        if (intro > 0 && pos < intro && (duration == 0L || intro < duration / 2)) {
            lib.addSkipped(intro - pos)
            pos = intro
        }
        return pos
    }

    /** The front episode ended (or was skipped): it leaves Up Next and the next one starts. */
    private fun finishCurrent(completed: Boolean) {
        val id = exo.currentMediaItem?.mediaId ?: return
        if (completed) savePosition(notify = false)
        if (lib.finishFront(id, completed) && completed) afterFinished(id)
    }

    /** An episode the player already moved past. */
    private fun finished(id: String) {
        if (lib.finishFront(id, completed = true)) afterFinished(id)
    }

    private fun afterFinished(id: String) {
        if (Settings.deletePlayed(prefs) && lib.state(id)?.downloaded == true && lib.bookmarks(id).isEmpty() && lib.state(id)?.starred != true) {
            Downloads.delete(this, lib, id)
        }
        // Autoplay: with nothing left in Up Next, carry on with the show's next episode.
        if (lib.queue().isEmpty() && Settings.autoplay(prefs)) {
            lib.episode(id)?.let { lib.nextUnplayed(it) }?.let { next ->
                if (SleepTimer.firedAt(this) < System.currentTimeMillis() - 2_000) lib.playNow(next, play = true)
                else lib.playNow(next, play = false)
            }
        }
    }

    private fun savePosition(notify: Boolean) {
        val item = exo.currentMediaItem ?: return
        if (exo.playbackState == Player.STATE_IDLE && exo.currentPosition == 0L) return
        val duration = exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0 else it }
        lib.savePosition(item.mediaId, exo.currentPosition, duration, notify)
    }

    // ----- Effects -----

    private fun applyEffects() {
        if (!::exo.isInitialized) return
        val id = exo.currentMediaItem?.mediaId
        val podcastId = id?.let { lib.state(it)?.podcastId }
        val fx = Settings.effectsFor(prefs, podcastId?.let { lib.entry(it)?.settings })
        if (exo.playbackParameters.speed != fx.speed) exo.setPlaybackSpeed(fx.speed)
        if (exo.skipSilenceEnabled != fx.trimSilence) exo.skipSilenceEnabled = fx.trimSilence
        loudness?.let {
            try {
                it.setTargetGain(if (fx.boost) BOOST_MB else 0)
                it.enabled = fx.boost
            } catch (e: Exception) {
                // the effect was taken over by another app
            }
        }
    }

    // ----- Every half second -----

    private fun tick() {
        val item = exo.currentMediaItem ?: return
        val now = SystemClock.elapsedRealtime()
        val pos = exo.currentPosition
        val playing = exo.isPlaying

        // Stats: time spent against audio heard. Seeks show up as jumps and are left out.
        if (playing && lastTickPos >= 0) {
            val wall = now - lastTickAt
            val heard = pos - lastTickPos
            if (wall in 1..5_000 && heard in 0..(wall * 5)) {
                lib.state(item.mediaId)?.podcastId?.let { lib.addListening(it, wall, heard) }
            }
        }
        lastTickAt = now
        lastTickPos = if (playing) pos else -1

        if (playing && (now / 500) % 10 == 0L) savePosition(notify = false)

        // Skip the outro.
        val duration = exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0 else it }
        val podcastId = lib.state(item.mediaId)?.podcastId
        val outro = (podcastId?.let { lib.entry(it)?.settings?.skipOutroSec } ?: 0) * 1000L
        if (playing && outro > 0 && duration > outro * 2 && pos >= duration - outro) {
            lib.addSkipped(duration - pos)
            finishCurrent(completed = true)
            return
        }

        sleepTick(item.mediaId, pos, playing)
    }

    private fun sleepTick(id: String, pos: Long, playing: Boolean) {
        val until = SleepTimer.until(this)
        if (until > 0 && playing) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) {
                exo.pause()
                SleepTimer.fired(this)
                exo.volume = 1f
            } else if (left < FADE_MS) {
                exo.volume = (left.toFloat() / FADE_MS).coerceIn(0.05f, 1f)
            }
        } else if (exo.volume < 1f) {
            exo.volume = 1f
        }
        val eoe = SleepTimer.endOfEpisode(this)
        if (exo.pauseAtEndOfMediaItems != eoe) exo.pauseAtEndOfMediaItems = eoe

        // "End of chapter": pause when playback crosses into the next chapter.
        val chapters = chaptersOf(id)
        val chapter = if (chapters.isEmpty()) -1 else chapters.indexAt(pos)
        if (SleepTimer.endOfChapter(this) && playing && lastChapter >= 0 && chapter == lastChapter + 1) {
            exo.pause()
            SleepTimer.fired(this)
        }
        lastChapter = chapter
    }

    private fun chaptersOf(id: String): List<Chapter> {
        val e = lib.episode(id) ?: return emptyList()
        return e.chapters.ifEmpty { lib.state(id)?.fileChapters.orEmpty() }
    }

    /** Pressing play within five minutes of the sleep timer going off sets it again. */
    private fun maybeRestartSleepTimer() {
        if (SleepTimer.isOn(this)) return
        val fired = SleepTimer.firedAt(this)
        val minutes = SleepTimer.lastMinutes(this)
        if (fired > 0 && minutes > 0 && System.currentTimeMillis() - fired < 5 * 60_000L) SleepTimer.set(this, minutes)
    }

    /** Chapters written into the audio file (ID3 CHAP frames), for episodes whose feed has none. */
    private fun readFileChapters(tracks: Tracks) {
        val id = exo.currentMediaItem?.mediaId ?: return
        val e = lib.episode(id) ?: return
        if (e.chapters.isNotEmpty() || lib.state(id)?.fileChapters?.isNotEmpty() == true) return
        val found = ArrayList<Chapter>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until metadata.length()) {
                    val frame = metadata.get(j) as? ChapterFrame ?: continue
                    var title = ""
                    for (k in 0 until frame.subFrameCount) {
                        val sub = frame.getSubFrame(k)
                        if (sub is TextInformationFrame && sub.id == "TIT2") title = sub.values.firstOrNull().orEmpty()
                    }
                    found += Chapter(frame.startTimeMs.toLong(), title.ifBlank { "Chapter ${found.size + 1}" })
                }
            }
        }
        if (found.size >= 2) lib.updateState(e) { it.copy(fileChapters = found.distinctBy { c -> c.startMs }.sortedBy { c -> c.startMs }) }
    }

    // ----- Buttons -----

    private fun skipButtons(): List<CommandButton> {
        val back = Settings.skipBackSec(prefs)
        val forward = Settings.skipForwardSec(prefs)
        return listOf(
            CommandButton.Builder(
                when (back) {
                    5 -> CommandButton.ICON_SKIP_BACK_5
                    10 -> CommandButton.ICON_SKIP_BACK_10
                    15 -> CommandButton.ICON_SKIP_BACK_15
                    30 -> CommandButton.ICON_SKIP_BACK_30
                    else -> CommandButton.ICON_SKIP_BACK
                },
            )
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName("Back $back seconds")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(
                when (forward) {
                    5 -> CommandButton.ICON_SKIP_FORWARD_5
                    10 -> CommandButton.ICON_SKIP_FORWARD_10
                    15 -> CommandButton.ICON_SKIP_FORWARD_15
                    30 -> CommandButton.ICON_SKIP_FORWARD_30
                    else -> CommandButton.ICON_SKIP_FORWARD
                },
            )
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("Forward $forward seconds")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )
    }

    /**
     * The player as the outside world sees it: skips use your skip lengths, and the
     * next/previous buttons on headphones and in the car skip time (or change episode if you
     * turned that off), because podcasts don't have tracks to skip.
     */
    private inner class PodcastPlayer(p: ExoPlayer) : ForwardingPlayer(p) {
        private val extra = Player.Commands.Builder()
            .addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()

        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
            for (i in 0 until extra.size()) b.add(extra.get(i))
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = extra.contains(command) || super.isCommandAvailable(command)

        override fun getSeekBackIncrement(): Long = Settings.skipBackSec(prefs) * 1000L

        override fun getSeekForwardIncrement(): Long = Settings.skipForwardSec(prefs) * 1000L

        override fun seekBack() = seekBy(-seekBackIncrement)

        override fun seekForward() = seekBy(seekForwardIncrement)

        private fun seekBy(delta: Long) {
            val d = exo.duration
            val target = (exo.currentPosition + delta).coerceAtLeast(0).let { if (d != C.TIME_UNSET && d > 0) it.coerceAtMost(d - 1_000) else it }
            exo.seekTo(target)
        }

        override fun seekToNext() = if (Settings.buttonsSkip(prefs)) seekForward() else nextEpisode()

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() = if (Settings.buttonsSkip(prefs)) seekBack() else exo.seekTo(0)

        override fun seekToPreviousMediaItem() = seekToPrevious()

        override fun hasNextMediaItem(): Boolean = true

        override fun hasPreviousMediaItem(): Boolean = true

        private fun nextEpisode() {
            val id = exo.currentMediaItem?.mediaId ?: return
            savePosition(notify = false)
            if (lib.queue().size > 1) {
                lib.playNow(lib.episode(lib.queue()[1]) ?: return, play = exo.playWhenReady)
                lib.removeFromQueue(id)
            }
        }
    }

    // ----- Session: who may do what, Android Auto, resuming -----

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (isOwnApp(controller) || isCar(session, controller)) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .build()
            }
            // Headphones, watches and lock-screen controls: play, pause, skip and seek. The phone's
            // own media controls may also resume the last episode; other apps can't change the queue.
            val commands = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        private fun isOwnApp(controller: MediaSession.ControllerInfo) = controller.uid == Process.myUid()

        private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
            session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

        private fun mayBrowse(session: MediaSession, controller: MediaSession.ControllerInfo) =
            isOwnApp(controller) || isCar(session, controller)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            if (!mayBrowse(session, browser)) Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            else Futures.immediateFuture(LibraryResult.ofItem(carLibrary.root, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!mayBrowse(session, browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return libraryExecutor.submit(Callable<LibraryResult<ImmutableList<MediaItem>>> {
                val all = carLibrary.children(parentId)
                    ?: return@Callable LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_BAD_VALUE)
                val from = (page.coerceAtLeast(0).toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
                val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            })
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!mayBrowse(session, browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return libraryExecutor.submit(Callable<LibraryResult<MediaItem>> {
                carLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            })
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.mapNotNull { item ->
                    if (item.localConfiguration != null || item.requestMetadata.mediaUri != null) item.withPlayableUri()
                    else lib.episode(item.mediaId)?.let { mediaItemFor(this@PlaybackService, lib, it) }
                }.toMutableList(),
            )

        /** Picking an episode in the car (or asking for one by voice) plays it through Up Next. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            val episode: Episode? = when {
                query != null -> carLibrary.search(query)
                single != null -> lib.episode(single.mediaId)
                else -> null
            }
            if (episode == null) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            lib.playNow(episode, play = false)
            lastId = episode.id
            val items = lib.queue().mapNotNull { id -> lib.episode(id)?.let { mediaItemFor(this@PlaybackService, lib, it) } }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, startPosition(episode.id)))
        }

        /** A headphone/car "play" press after the app was closed picks up the episode you were on. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val ids = lib.queue()
            val items = ids.mapNotNull { id -> lib.episode(id)?.let { mediaItemFor(this@PlaybackService, lib, it) } }
            if (items.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            lastId = items[0].mediaId
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, startPosition(items[0].mediaId)))
        }
    }
}
