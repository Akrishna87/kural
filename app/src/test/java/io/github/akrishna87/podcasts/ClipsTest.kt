package io.github.akrishna87.podcasts

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipsTest {
    @Test
    fun theFirstGuessIsWhatYouJustHeard() {
        assertEquals(90_000L..120_000L, Clips.defaultRange(120_000, 3_600_000))
        // Near the start, the first 30 seconds.
        assertEquals(0L..30_000L, Clips.defaultRange(12_000, 3_600_000))
        // A very short episode is clipped whole.
        assertEquals(0L..20_000L, Clips.defaultRange(5_000, 20_000))
        // Past the end (the duration came in late), it stops at the end.
        assertEquals(570_000L..600_000L, Clips.defaultRange(650_000, 600_000))
    }

    @Test
    fun fileNamesAreSafeAndSayWhereTheClipIsFrom() {
        assertEquals("Hard Fork – AI what now (12m30s).m4a", Clips.fileName("Hard Fork", "AI: what now?", 750_000))
        assertEquals("Hard Fork – AI what now (1h2m3s).m4a", Clips.fileName("Hard Fork", "AI / what\nnow?", 3_723_000))
        assertEquals("Clip (0m5s).m4a", Clips.fileName("", " ", 5_000))
    }
}
