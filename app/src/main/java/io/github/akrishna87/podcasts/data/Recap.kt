package io.github.akrishna87.podcasts.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** The part of the day you were listening in, by the phone's clock. */
enum class PartOfDay(val label: String, val short: String) {
    MORNING("in the morning", "Morning"),
    AFTERNOON("in the afternoon", "Afternoon"),
    EVENING("in the evening", "Evening"),
    NIGHT("at night", "Night");

    companion object {
        fun of(hour: Int): PartOfDay = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

/**
 * One day's listening to one episode, for the recap. The player adds to it as you listen
 * (with the app closed too), so the log stays one small entry per episode per day.
 */
data class PlayDay(
    /** The day, by the phone's clock. */
    val day: LocalDate,
    val podcastId: String,
    val episodeId: String,
    /** Names as they were, so the recap still reads right after a show is unfollowed. */
    val podcastTitle: String = "",
    val episodeTitle: String = "",
    /** Time spent listening. */
    val ms: Long = 0,
    /** Time saved by speed and trimmed silences: audio heard minus time spent. */
    val savedMs: Long = 0,
    /** Intros and outros skipped. */
    val skippedMs: Long = 0,
    /** The episode was finished that day. */
    val finished: Boolean = false,
    /** Listening time by [PartOfDay], in its order. */
    val parts: List<Long> = List(PartOfDay.entries.size) { 0L },
) {
    val key: String get() = keyOf(day, episodeId)

    fun toJson(): JSONObject = JSONObject()
        .put("d", day.toString()).put("p", podcastId).put("e", episodeId)
        .put("pt", podcastTitle).put("et", episodeTitle)
        .put("ms", ms).put("saved", savedMs).put("skipped", skippedMs).put("finished", finished)
        .put("parts", JSONArray(parts))

    companion object {
        fun keyOf(day: LocalDate, episodeId: String) = "$day|$episodeId"

        fun fromJson(o: JSONObject): PlayDay {
            val a = o.optJSONArray("parts")
            return PlayDay(
                day = LocalDate.parse(o.getString("d")),
                podcastId = o.getString("p"),
                episodeId = o.optString("e"),
                podcastTitle = o.optString("pt"),
                episodeTitle = o.optString("et"),
                ms = o.optLong("ms"),
                savedMs = o.optLong("saved"),
                skippedMs = o.optLong("skipped"),
                finished = o.optBoolean("finished"),
                parts = List(PartOfDay.entries.size) { i -> a?.optLong(i) ?: 0L },
            )
        }
    }
}

/** The stretch of time a recap covers. */
enum class RecapPeriod(val label: String) {
    THIS_MONTH("This month"), LAST_MONTH("Last month"), THIS_YEAR("This year"), ALL_TIME("All time");

    /** Whether [day] falls in this period, seen from [today]. */
    fun contains(day: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        val month = today.withDayOfMonth(1)
        return when (this) {
            THIS_MONTH -> !day.isBefore(month) && day.isBefore(month.plusMonths(1))
            LAST_MONTH -> !day.isBefore(month.minusMonths(1)) && day.isBefore(month)
            THIS_YEAR -> day.year == today.year
            ALL_TIME -> true
        }
    }
}

/** Your listening over a period: how much, what most, and when. */
class Recap(
    val ms: Long,
    val savedMs: Long,
    val skippedMs: Long,
    val finished: Int,
    val episodes: Int,
    val shows: Int,
    val daysListened: Int,
    val topShows: List<Top>,
    val topEpisodes: List<Top>,
    /** Listening time on each day of the week, Monday first. */
    val byWeekday: List<Long>,
    /** Listening time in each [PartOfDay], in its order. */
    val byPart: List<Long>,
) {
    /** A show or an episode, with how long it was played and on how many days. */
    data class Top(val id: String, val podcastId: String, val name: String, val detail: String, val ms: Long, val count: Int)

    val isEmpty: Boolean get() = ms <= 0 && finished == 0

    /** Speed, trimmed silences and skipped intros and outros together. */
    val timeSaved: Long get() = savedMs + skippedMs

    /** The day of the week with the most listening. */
    val busiestDay: DayOfWeek? get() = byWeekday.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.let { DayOfWeek.of(it.index + 1) }

    /** The part of the day with the most listening. */
    val partOfDay: PartOfDay? get() = byPart.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.let { PartOfDay.entries[it.index] }

    companion object {
        /**
         * Adds up [log] for [period] as seen from [today]. Names come from [showTitle] and
         * [episodeTitle] where they're still known; otherwise what was saved when it played.
         */
        fun of(
            log: List<PlayDay>,
            period: RecapPeriod,
            today: LocalDate = LocalDate.now(),
            showTitle: (String) -> String? = { null },
            episodeTitle: (String) -> String? = { null },
            top: Int = 5,
        ): Recap {
            val days = log.filter { period.contains(it.day, today) }
            fun showName(d: PlayDay) = showTitle(d.podcastId)?.takeIf { it.isNotBlank() } ?: d.podcastTitle

            val topShows = days.groupBy { it.podcastId }.map { (id, ds) ->
                val name = showName(ds.maxBy { it.day }).ifBlank { "A show you no longer follow" }
                val eps = ds.map { it.episodeId }.distinct().size
                Top(id, id, name, if (eps == 1) "1 episode" else "$eps episodes", ds.sumOf { it.ms }, eps)
            }.filter { it.ms > 0 }.sortedWith(compareByDescending<Top> { it.ms }.thenByDescending { it.count }.thenBy { it.name.lowercase() }).take(top)

            val topEpisodes = days.groupBy { it.episodeId }.map { (id, ds) ->
                val last = ds.maxBy { it.day }
                val name = episodeTitle(id)?.takeIf { it.isNotBlank() } ?: last.episodeTitle
                Top(id, last.podcastId, name.ifBlank { "An episode" }, showName(last), ds.sumOf { it.ms }, ds.size)
            }.filter { it.ms > 0 }.sortedWith(compareByDescending<Top> { it.ms }.thenBy { it.name.lowercase() }).take(top)

            val byWeekday = LongArray(7)
            val byPart = LongArray(PartOfDay.entries.size)
            for (d in days) {
                byWeekday[d.day.dayOfWeek.value - 1] += d.ms
                d.parts.forEachIndexed { i, v -> if (i < byPart.size) byPart[i] += v }
            }
            return Recap(
                ms = days.sumOf { it.ms },
                savedMs = days.sumOf { it.savedMs },
                skippedMs = days.sumOf { it.skippedMs },
                finished = days.count { it.finished },
                episodes = days.filter { it.ms > 0 }.map { it.episodeId }.distinct().size,
                shows = days.filter { it.ms > 0 }.map { it.podcastId }.distinct().size,
                daysListened = days.filter { it.ms > 0 }.map { it.day }.distinct().size,
                topShows = topShows,
                topEpisodes = topEpisodes,
                byWeekday = byWeekday.toList(),
                byPart = byPart.toList(),
            )
        }

        /** "12 h 5 min", "45 min" or "0 min". */
        fun duration(ms: Long): String {
            val min = ms / 60_000
            return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
        }
    }
}
