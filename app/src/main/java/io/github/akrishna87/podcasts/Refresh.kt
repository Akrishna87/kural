package io.github.akrishna87.podcasts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.akrishna87.podcasts.data.AlertHit
import io.github.akrishna87.podcasts.data.AutoAdd
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.PodcastEntry
import io.github.akrishna87.podcasts.feed.Directory
import io.github.akrishna87.podcasts.feed.FeedParser
import io.github.akrishna87.podcasts.feed.Http
import io.github.akrishna87.podcasts.feed.ParsedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Reads feeds: on demand, when you pull to refresh, and in the background on a schedule. */
object Refresher {

    class Result(val newEpisodes: Int, val failed: Int)

    /** Fetches and reads a feed, following the address's redirects. */
    suspend fun fetchFeed(url: String): ParsedFeed = withContext(Dispatchers.IO) {
        val r = Http.fetch(url)
        FeedParser.parse(r.body, url)
    }

    /** Follows a show: reads its feed and stores it as subscribed. */
    suspend fun subscribe(context: Context, url: String): PodcastEntry = withContext(Dispatchers.IO) {
        val lib = context.library()
        val existing = lib.findByFeed(url)
        val feedUrl = existing?.id ?: url
        val r = Http.fetch(feedUrl)
        lib.storeFeed(FeedParser.parse(r.body, feedUrl), subscribe = true, etag = r.etag, lastModified = r.lastModified)
        lib.entry(feedUrl)!!
    }

    /** Refreshes every followed show (or just [only]); handles new episodes as each show's settings say. */
    suspend fun refreshAll(context: Context, only: Collection<String>? = null, notify: Boolean = true): Result = withContext(Dispatchers.IO) {
        val lib = context.library()
        val shows = lib.subscriptions().filter { only == null || it.id in only }
        val gate = Semaphore(4)
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val fresh = coroutineScope {
            shows.map { entry ->
                async {
                    gate.withPermit {
                        try {
                            entry to refreshOne(context, entry)
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            entry to emptyList()
                        }
                    }
                }
            }.awaitAll()
        }
        var count = 0
        for ((entry, episodes) in fresh) {
            if (episodes.isEmpty()) continue
            count += episodes.size
            handleNew(context, entry, episodes, notify)
        }
        Downloads.reconcile(context, lib)
        if (only == null) checkAlerts(context, fresh.flatMap { it.second }, notify)
        Result(count, failed.get())
    }

    private fun refreshOne(context: Context, entry: PodcastEntry): List<Episode> {
        val lib = context.library()
        val r = Http.fetch(entry.id, etag = entry.etag, lastModified = entry.lastModified)
        if (r.notModified) {
            lib.markRefreshed(entry.id)
            return emptyList()
        }
        return lib.storeFeed(FeedParser.parse(r.body, entry.id), etag = r.etag, lastModified = r.lastModified)
    }

    /** New episodes go to the inbox, and to Up Next, the phone and a notification if the show asks. */
    private fun handleNew(context: Context, entry: PodcastEntry, episodes: List<Episode>, notify: Boolean) {
        val lib = context.library()
        // Only real releases: not old episodes a feed suddenly re-published.
        val recent = episodes.filter { it.publishedAt == 0L || it.publishedAt > entry.subscribedAt - 7 * 86_400_000L }
            .sortedBy { it.publishedAt }
        if (recent.isEmpty()) return
        lib.markNew(recent)
        val s = entry.settings
        when (s.autoAdd) {
            AutoAdd.TOP -> recent.forEach { lib.playNext(it) } // oldest first, so the newest ends up first
            AutoAdd.BOTTOM -> recent.forEach { lib.playLast(it) }
            AutoAdd.OFF -> Unit
        }
        if (s.autoDownload) recent.takeLast(3).forEach { runCatching { Downloads.start(context, lib, it) } }
        if (notify && s.notify) {
            val newest = recent.last()
            val text = if (recent.size == 1) newest.title else "${newest.title} and ${recent.size - 1} more"
            Notifications.show(context, entry.podcast.title, text, newest.id, "new:${entry.id}")
        }
    }

    /** Alerts: names and topics you follow, searched across every podcast in the directory. */
    private fun checkAlerts(context: Context, freshFromShows: List<Episode>, notify: Boolean) {
        val lib = context.library()
        for (alert in lib.alerts()) {
            val since = alert.lastCheckedAt.takeIf { it > 0 } ?: (System.currentTimeMillis() - 7 * 86_400_000L)
            val now = System.currentTimeMillis()
            val found = ArrayList<AlertHit>()
            val needle = alert.term.lowercase()
            // New episodes of shows you follow that mention it.
            freshFromShows.filter { it.title.lowercase().contains(needle) || it.description.lowercase().contains(needle) }
                .forEach { e -> lib.podcast(e.podcastId)?.let { found += AlertHit(alert.term, e, it, now) } }
            // And any podcast in the directory.
            try {
                Directory.searchEpisodes("\"${alert.term}\"", limit = 50)
                    .filter { it.episode.publishedAt > since - 86_400_000L && mentions(it.episode, needle) }
                    .forEach { found += AlertHit(alert.term, it.episode, it.podcast, now) }
            } catch (e: Exception) {
                continue // try again next time; don't move the "checked" mark
            }
            val added = lib.addHits(found)
            lib.alertChecked(alert.term, now)
            if (notify && added.isNotEmpty()) {
                val first = added.first()
                val text = if (added.size == 1) "${first.episode.title} · ${first.podcast.title}" else "${added.size} new episodes mention them"
                Notifications.show(context, "“${alert.term}”", text, first.episode.id, "alert:${alert.term}", alert = true)
            }
        }
    }

    private fun mentions(e: Episode, needle: String): Boolean =
        e.title.lowercase().contains(needle) || e.description.lowercase().contains(needle)

    // ----- Background schedule -----

    private const val WORK = "refresh"

    /** Sets up (or changes, or stops) the background refresh to match the setting. */
    fun schedule(context: Context) {
        val hours = Settings.refreshHours(Settings.prefs(context))
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Refresher.refreshAll(applicationContext)
        return Result.success()
    }
}

object Notifications {
    private const val NEW = "new_episodes"
    private const val ALERTS = "alerts"
    const val EXTRA_EPISODE = "episode"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NEW, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Topic and guest alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(context: Context, title: String, text: String, episodeId: String, tag: String, alert: Boolean = false) {
        if (!canNotify(context)) return
        channels(context)
        val open = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_EPISODE, episodeId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, if (alert) ALERTS else NEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag, 1, n)
        } catch (e: SecurityException) {
            // Permission taken away in the meantime.
        }
    }
}
