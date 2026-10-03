package io.github.akrishna87.podcasts

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.akrishna87.podcasts.data.Episode

/**
 * What Android Auto shows: Up Next, new episodes, ones you're partway through, downloads, and
 * every show you follow. Picking an episode plays it through Up Next, as on the phone.
 */
class CarLibrary(private val context: Context) {

    companion object {
        const val ROOT = "root"
        private const val UP_NEXT = "upnext"
        private const val NEW = "new"
        private const val IN_PROGRESS = "progress"
        private const val DOWNLOADS = "downloads"
        private const val SHOWS = "shows"
        private const val SHOW = "show:"
    }

    private val lib get() = context.library()

    val root: MediaItem get() = folder(ROOT, "Kural", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** The items inside [parentId], or null if there's no such folder. */
    fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(UP_NEXT, "Up Next", MediaMetadata.MEDIA_TYPE_PLAYLIST),
            folder(NEW, "New episodes", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            folder(IN_PROGRESS, "In progress", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            folder(DOWNLOADS, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            folder(SHOWS, "Shows", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
        )
        parentId == UP_NEXT -> lib.queueEpisodes().map(::playable)
        parentId == NEW -> lib.newEpisodes().take(100).map { playable(it.first) }
        parentId == IN_PROGRESS -> lib.inProgress().take(100).map { playable(it.first) }
        parentId == DOWNLOADS -> lib.downloads().filter { it.second.downloaded }.map { playable(it.first) }
        parentId == SHOWS -> lib.subscriptions().sortedBy { it.podcast.title.lowercase() }.map { e ->
            folder(SHOW + e.id, e.podcast.title, MediaMetadata.MEDIA_TYPE_PODCAST, e.podcast.author, e.podcast.artworkUrl)
        }
        parentId.startsWith(SHOW) -> {
            val id = parentId.removePrefix(SHOW)
            val oldestFirst = lib.entry(id)?.settings?.newestFirst == false
            lib.episodes(id).let { if (oldestFirst) it.reversed() else it }.take(100).map(::playable)
        }
        else -> null
    }

    fun item(id: String): MediaItem? {
        if (id == ROOT) return root
        children(ROOT)?.firstOrNull { it.mediaId == id }?.let { return it }
        return lib.episode(id)?.let(::playable)
    }

    /** "Play <show> on Kural": that show's newest unplayed episode, or an episode whose title matches. */
    fun search(query: String): Episode? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return lib.current() ?: lib.queueEpisodes().firstOrNull() ?: lib.newEpisodes().firstOrNull()?.first
        val show = lib.subscriptions().firstOrNull { it.podcast.title.lowercase().contains(q) }
        if (show != null) {
            return lib.episodes(show.id).firstOrNull { lib.state(it.id)?.played != true } ?: lib.episodes(show.id).firstOrNull()
        }
        return lib.searchEpisodes(q, limit = 1).firstOrNull()
    }

    private fun playable(e: Episode): MediaItem {
        val p = lib.podcast(e.podcastId)
        return MediaItem.Builder()
            .setMediaId(e.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setSubtitle(p?.title)
                    .setArtist(p?.title)
                    .setArtworkUri((e.artworkUrl ?: p?.artworkUrl)?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            )
            .build()
    }

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null, art: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(art?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build(),
            )
            .build()
}
