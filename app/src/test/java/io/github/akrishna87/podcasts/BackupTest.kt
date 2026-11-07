package io.github.akrishna87.podcasts

import io.github.akrishna87.podcasts.data.BackupFile
import io.github.akrishna87.podcasts.data.Episode
import io.github.akrishna87.podcasts.data.EpisodeFilter
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.data.Podcast
import io.github.akrishna87.podcasts.feed.ParsedFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val feedA = "https://example.com/a.xml"
    private val feedB = "https://example.com/b.xml"

    private fun ep(feed: String, n: Int) = Episode(
        id = Episode.idFor(feed, "g$n"),
        podcastId = feed,
        guid = "g$n",
        title = "${feed.substringAfterLast('/')} episode $n",
        audioUrl = "https://example.com/$n.mp3",
        publishedAt = n * 86_400_000L,
        durationSec = n * 600L,
    )

    private fun library(name: String) = Library(tmp.newFolder(name), writer = null)

    /** A library with something of everything in it. */
    private fun filled(): Library {
        val lib = library("phone1")
        lib.storeFeed(ParsedFeed(Podcast(feedA, "Show A"), listOf(ep(feedA, 3), ep(feedA, 2), ep(feedA, 1))), subscribe = true)
        lib.storeFeed(ParsedFeed(Podcast(feedB, "Show B"), listOf(ep(feedB, 2), ep(feedB, 1))), subscribe = true)
        lib.playNow(ep(feedA, 1), play = false)
        lib.playLast(ep(feedB, 2))
        lib.playLast(ep(feedA, 3))
        lib.savePosition(ep(feedA, 1).id, 123_000, 600_000)
        lib.markPlayed(ep(feedB, 1).id, true)
        lib.updateState(ep(feedA, 2)) { it.copy(starred = true) }
        lib.addBookmark(ep(feedA, 1), 60_000, "Good bit")
        lib.saveFilter(EpisodeFilter("mine", "My filter", podcastIds = listOf(feedB)))
        lib.addAlert("Jane Goodall")
        lib.addListening(feedA, 60_000, 90_000)
        return lib
    }

    private val prefs: Map<String, Map<String, Any>> = mapOf(
        "settings" to mapOf("speed" to 1.5f, "skip_back" to 15, "last_refresh" to 1_700_000_000_000L, "autoplay" to true, "country" to "in"),
        "theme" to mapOf("mode" to "LIGHT", "accent" to "ROSE"),
        "equaliser" to mapOf("eq_enabled" to true, "eq_levels" to "100,-200,0", "tags" to setOf("a", "b")),
    )

    /** Backs [lib] up, as files in a folder, and reads them back. */
    private fun roundTrip(lib: Library, at: Long = 1_000L): BackupFile.Contents {
        val files = BackupFile.write(BackupFile.Contents(at, lib.exportFiles(), prefs))
        assertTrue(files.containsKey(BackupFile.NAME))
        return BackupFile.read(files.getValue(BackupFile.NAME)) { files[it] } ?: error("the backup couldn't be read back")
    }

    @Test
    fun everythingComesBackOnAnotherPhone() {
        val before = filled()
        val backup = roundTrip(before)
        assertEquals(2, backup.shows)
        assertEquals(3, backup.upNext)
        assertEquals(1, backup.favourites)
        assertEquals(1, backup.bookmarks)

        val after = library("phone2")
        after.storeFeed(ParsedFeed(Podcast("https://example.com/other.xml", "Other show"), listOf(ep("https://example.com/other.xml", 1))), subscribe = true)
        val queueVersion = after.queueVersion.value
        after.importFiles(backup.library)

        assertEquals(listOf("Show A", "Show B"), after.subscriptions().map { it.podcast.title })
        assertEquals(before.queue(), after.queue())
        assertTrue("the player should hear about the new Up Next", after.queueVersion.value > queueVersion)
        assertEquals(ep(feedA, 1).id, after.current()?.id)
        assertEquals(123_000L, after.state(ep(feedA, 1).id)?.positionMs)
        assertEquals(true, after.state(ep(feedB, 1).id)?.played)
        assertEquals(listOf(ep(feedA, 2).id), after.starred().map { it.first.id })
        assertEquals(listOf("Good bit"), after.bookmarks().map { it.note })
        assertEquals(before.filters(), after.filters())
        assertEquals(listOf("Jane Goodall"), after.alerts().map { it.term })
        assertEquals(before.stats(), after.stats())
        assertEquals(before.episodes(feedA).map { it.title }, after.episodes(feedA).map { it.title })
        assertEquals(before.episodes(feedB).map { it.title }, after.episodes(feedB).map { it.title })

        // It's all saved, too: a fresh start reads the same library.
        val reopened = Library(File(tmp.root, "phone2"), writer = null)
        assertEquals(before.queue(), reopened.queue())
        assertEquals(listOf("Show A", "Show B"), reopened.subscriptions().map { it.podcast.title })
        assertEquals(before.episodes(feedB).map { it.id }, reopened.episodes(feedB).map { it.id })
        assertTrue(File(tmp.root, "phone2/episodes").listFiles()!!.size == 2)
    }

    @Test
    fun settingsKeepTheirTypes() {
        val backup = roundTrip(filled())
        assertEquals(prefs, backup.prefs)
        val speed = backup.prefs.getValue("settings").getValue("speed")
        assertTrue(speed is Float)
        assertTrue(backup.prefs.getValue("settings").getValue("skip_back") is Int)
        assertTrue(backup.prefs.getValue("settings").getValue("last_refresh") is Long)
    }

    @Test
    fun phoneOnlySettingsArentRestored() {
        val o = BackupFile.encodePrefs(mapOf("eq_info" to "{\"bands\":[]}", "eq_enabled" to true))
        val back = BackupFile.decodePrefs(o, BackupFile.PHONE_ONLY.getValue("equaliser"))
        assertEquals(mapOf("eq_enabled" to true), back)
    }

    @Test
    fun downloadsArentRestoredWithoutTheirAudio() {
        val lib = filled()
        lib.updateState(ep(feedA, 2)) { it.copy(downloaded = true) }
        lib.updateState(ep(feedA, 3)) { it.copy(downloaded = true) }
        lib.updateState(ep(feedB, 2)) { it.copy(downloadId = 42) }
        val backup = roundTrip(lib)

        val other = library("phone2")
        other.importFiles(backup.library) { id -> id == ep(feedA, 3).id }
        assertFalse(other.state(ep(feedA, 2).id)!!.downloaded)
        assertTrue(other.state(ep(feedA, 2).id)!!.starred)
        assertTrue(other.state(ep(feedA, 3).id)!!.downloaded)
        assertEquals(0L, other.state(ep(feedB, 2).id)!!.downloadId)
        assertEquals(listOf(ep(feedA, 3).id), other.downloads().map { it.first.id })
        // And that's saved.
        assertEquals(listOf(ep(feedA, 3).id), Library(File(tmp.root, "phone2"), writer = null).downloads().map { it.first.id })
    }

    @Test
    fun onlyTheLibrarysOwnFilesAreWritten() {
        val lib = library("phone")
        lib.importFiles(mapOf("../evil.json" to "[]", "episodes/../../x.json" to "[]", "podcasts.json" to "[]"))
        assertFalse(File(tmp.root, "evil.json").exists())
        assertFalse(File(tmp.root, "x.json").exists())
        assertTrue(File(tmp.root, "phone/podcasts.json").exists())
    }

    @Test
    fun aFreshInstallHasNothingToBackUp() {
        val fresh = BackupFile.Contents(1L, library("fresh").exportFiles(), prefs)
        assertTrue(fresh.isEmpty)
        assertFalse(BackupFile.Contents(1L, filled().exportFiles(), prefs).isEmpty)
    }

    @Test
    fun onlyTheTimeChangesBetweenBackupsOfTheSameLibrary() {
        val lib = filled()
        val c1 = BackupFile.Contents(1_000L, lib.exportFiles(), prefs)
        val c2 = BackupFile.Contents(2_000L, lib.exportFiles(), prefs)
        assertNotEquals(BackupFile.manifest(c1), BackupFile.manifest(c2))
        assertEquals(BackupFile.manifest(c1.withTime(0)), BackupFile.manifest(c2.withTime(0)))
        lib.updateState(ep(feedB, 1)) { it.copy(starred = true) }
        val c3 = BackupFile.Contents(3_000L, lib.exportFiles(), prefs)
        assertNotEquals(BackupFile.manifest(c1.withTime(0)), BackupFile.manifest(c3.withTime(0)))
    }

    @Test
    fun otherFilesArentBackups() {
        assertNull(BackupFile.read("{\"app\":\"Isaialai\",\"format\":1}") { null })
        assertNull(BackupFile.read("not json") { null })
        assertNull(BackupFile.read("{\"app\":\"Podwave\",\"format\":99}") { null })
    }

    @Test
    fun aMissingShowFileStillRestoresTheRest() {
        val files = BackupFile.write(BackupFile.Contents(1L, filled().exportFiles(), prefs))
        val dropped = files.keys.first { it.startsWith("episodes/") }
        val backup = BackupFile.read(files.getValue(BackupFile.NAME)) { if (it == dropped) null else files[it] }!!
        val other = library("phone2")
        other.importFiles(backup.library)
        assertEquals(2, other.subscriptions().size)
    }
}
