package io.github.akrishna87.podcasts

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.Library

/** An episode as the player sees it: played from the phone once downloaded, streamed otherwise. */
@OptIn(UnstableApi::class)
fun mediaItemFor(context: Context, lib: Library, e: Episode): MediaItem {
    val local = Downloads.localFile(context, lib, e.id)
    val uri = if (local != null) Uri.fromFile(local) else Uri.parse(e.audioUrl)
    val podcast = lib.podcast(e.podcastId)
    return MediaItem.Builder()
        .setMediaId(e.id)
        .setUri(uri)
        // A MediaController strips the uri before handing items to the session; this copy survives.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(e.title)
                .setArtist(podcast?.title ?: "")
                .setAlbumTitle(podcast?.title ?: "")
                .setAlbumArtist(podcast?.author ?: "")
                .setArtworkUri((e.artworkUrl ?: podcast?.artworkUrl)?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

/** Restores the playable uri on items that came through a MediaController. */
fun MediaItem.withPlayableUri(): MediaItem =
    if (localConfiguration != null) this
    else requestMetadata.mediaUri?.let { buildUpon().setUri(it).build() } ?: this
