package io.github.akrishna87.podcasts

import android.content.Context
import android.content.SharedPreferences
import io.github.akrishna87.podcasts.data.Library
import io.github.akrishna87.podcasts.data.PodcastSettings

/** App-wide settings, shared by the screens (which change them) and the player and refresh (which follow them). */
object Settings {
    const val PREFS = "settings"

    const val SPEED = "speed"
    const val TRIM_SILENCE = "trim_silence"
    const val BOOST = "boost"
    const val SKIP_BACK = "skip_back"
    const val SKIP_FORWARD = "skip_forward"
    /** Headphone, car and notification next/previous buttons skip time instead of changing episode. */
    const val BUTTONS_SKIP = "buttons_skip"
    const val AUTOPLAY = "autoplay"
    const val DELETE_PLAYED = "delete_played"
    const val WIFI_ONLY = "wifi_only"
    const val REFRESH_HOURS = "refresh_hours"
    const val COUNTRY = "country"

    val SKIP_CHOICES = listOf(5, 10, 15, 30, 45, 60, 90)
    val REFRESH_CHOICES = listOf(0, 1, 3, 6, 12, 24)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun speed(p: SharedPreferences) = p.getFloat(SPEED, 1f)
    fun trimSilence(p: SharedPreferences) = p.getBoolean(TRIM_SILENCE, false)
    fun boost(p: SharedPreferences) = p.getBoolean(BOOST, false)
    fun skipBackSec(p: SharedPreferences) = p.getInt(SKIP_BACK, 10)
    fun skipForwardSec(p: SharedPreferences) = p.getInt(SKIP_FORWARD, 30)
    fun buttonsSkip(p: SharedPreferences) = p.getBoolean(BUTTONS_SKIP, true)
    fun autoplay(p: SharedPreferences) = p.getBoolean(AUTOPLAY, false)
    fun deletePlayed(p: SharedPreferences) = p.getBoolean(DELETE_PLAYED, true)
    fun wifiOnly(p: SharedPreferences) = p.getBoolean(WIFI_ONLY, true)
    fun refreshHours(p: SharedPreferences) = p.getInt(REFRESH_HOURS, 6)

    /** Speed, trim silence and boost for a show: its own if it has custom effects, else the app's. */
    data class Effects(val speed: Float, val trimSilence: Boolean, val boost: Boolean)

    fun effectsFor(p: SharedPreferences, show: PodcastSettings?): Effects =
        if (show != null && show.customEffects) Effects(show.speed, show.trimSilence, show.boost)
        else Effects(speed(p), trimSilence(p), boost(p))
}

fun Context.library(): Library = Library.get(filesDir)

/** The sleep timer, set by the screens and carried out by the player. */
object SleepTimer {
    private fun prefs(context: Context) = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    /** Pause at this time (ms since 1970), or 0. */
    fun until(context: Context): Long = prefs(context).getLong("until", 0L)

    fun endOfEpisode(context: Context): Boolean = prefs(context).getBoolean("eoe", false)

    fun endOfChapter(context: Context): Boolean = prefs(context).getBoolean("eoc", false)

    /** The last timer's length, and when it went off, so pressing play soon after sets it again. */
    fun lastMinutes(context: Context): Int = prefs(context).getInt("last", 0)

    fun firedAt(context: Context): Long = prefs(context).getLong("fired", 0L)

    const val END_OF_EPISODE = -1
    const val END_OF_CHAPTER = -2

    /** [minutes] > 0, [END_OF_EPISODE], [END_OF_CHAPTER], or 0 for off. */
    fun set(context: Context, minutes: Int) {
        prefs(context).edit()
            .putLong("until", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L)
            .putBoolean("eoe", minutes == END_OF_EPISODE)
            .putBoolean("eoc", minutes == END_OF_CHAPTER)
            .apply { if (minutes > 0) putInt("last", minutes) }
            .putLong("fired", 0L)
            .apply()
    }

    /** Adds [minutes] to a running timer. */
    fun extend(context: Context, minutes: Int) {
        val until = until(context)
        if (until <= 0) return set(context, minutes)
        prefs(context).edit().putLong("until", until + minutes * 60_000L).apply()
    }

    /** The timer went off: clear it, remembering when, for the "play again to restart" grace period. */
    fun fired(context: Context) {
        prefs(context).edit()
            .putLong("until", 0L).putBoolean("eoe", false).putBoolean("eoc", false)
            .putLong("fired", System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) = set(context, 0)

    fun isOn(context: Context) = until(context) > 0 || endOfEpisode(context) || endOfChapter(context)
}
