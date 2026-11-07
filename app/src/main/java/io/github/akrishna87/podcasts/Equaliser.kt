package io.github.akrishna87.podcasts

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * The equaliser and bass boost, the same for every show. The screen changes the settings here;
 * [EqualiserEffects], owned by [PlaybackService], applies them to the player's audio session.
 * Kept in their own preferences so they don't mix with the per-show effects in [Settings].
 */
object Equaliser {
    const val PREFS = "equaliser"
    /** Written by the player: what this phone's equaliser can do (see [Info]). */
    const val KEY_INFO = "eq_info"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_LEVELS = "eq_levels"
    private const val KEY_BASS = "bass"

    /** What this phone's equaliser can do, written by the player once it has created the effect. */
    data class Info(
        val available: Boolean,
        /** Centre frequency of each band, in Hz. */
        val bandsHz: List<Int> = emptyList(),
        /** Lowest and highest band level, in millibels (100 mB = 1 dB). */
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        /** Built-in presets: name and the band levels they set. */
        val presets: List<Pair<String, List<Int>>> = emptyList(),
        val bassBoost: Boolean = false,
    )

    data class Choice(
        val enabled: Boolean = false,
        /** Index of a built-in preset, or -1 for your own band levels. */
        val preset: Int = 0,
        val levels: List<Int> = emptyList(),
        /** Bass boost strength, 0–1000. */
        val bass: Int = 0,
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readInfo(p: SharedPreferences): Info? = try {
        p.getString(KEY_INFO, null)?.let { s ->
            val o = JSONObject(s)
            val bands = o.getJSONArray("bands")
            val presets = o.getJSONArray("presets")
            Info(
                available = o.getBoolean("available"),
                bandsHz = List(bands.length()) { bands.getInt(it) },
                minLevel = o.optInt("min", -1500),
                maxLevel = o.optInt("max", 1500),
                presets = List(presets.length()) { i ->
                    val pr = presets.getJSONObject(i)
                    val lv = pr.getJSONArray("levels")
                    pr.getString("name") to List(lv.length()) { lv.getInt(it) }
                },
                bassBoost = o.optBoolean("bass", false),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun writeInfo(p: SharedPreferences, info: Info) {
        val o = JSONObject()
            .put("available", info.available)
            .put("bands", JSONArray(info.bandsHz))
            .put("min", info.minLevel)
            .put("max", info.maxLevel)
            .put("bass", info.bassBoost)
            .put("presets", JSONArray(info.presets.map { (name, levels) -> JSONObject().put("name", name).put("levels", JSONArray(levels)) }))
        p.edit().putString(KEY_INFO, o.toString()).apply()
    }

    fun read(p: SharedPreferences): Choice = Choice(
        enabled = p.getBoolean(KEY_ENABLED, false),
        preset = p.getInt(KEY_PRESET, 0),
        levels = p.getString(KEY_LEVELS, "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
        bass = p.getInt(KEY_BASS, 0),
    )

    fun write(p: SharedPreferences, c: Choice) {
        p.edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putInt(KEY_PRESET, c.preset)
            .putString(KEY_LEVELS, c.levels.joinToString(","))
            .putInt(KEY_BASS, c.bass)
            .apply()
    }
}

/**
 * The phone's equaliser and bass boost, attached to the player's audio session (next to the
 * volume boost). Follows the saved choice as it changes, until [release].
 */
class EqualiserEffects(context: Context, audioSession: Int) {
    private val prefs = Equaliser.prefs(context)
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Equaliser.KEY_INFO) applyChoice()
    }

    init {
        try {
            val eq = Equalizer(0, audioSession)
            equalizer = eq
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val presets = (0 until eq.numberOfPresets.toInt()).map { p ->
                eq.usePreset(p.toShort())
                eq.getPresetName(p.toShort()) to (0 until bands).map { eq.getBandLevel(it.toShort()).toInt() }
            }
            bassBoost = try {
                BassBoost(0, audioSession).takeIf { it.strengthSupported }
            } catch (e: Exception) {
                null
            }
            Equaliser.writeInfo(
                prefs,
                Equaliser.Info(
                    available = true,
                    bandsHz = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 }, // milliHertz → Hz
                    minLevel = range[0].toInt(),
                    maxLevel = range[1].toInt(),
                    presets = presets,
                    bassBoost = bassBoost != null,
                ),
            )
        } catch (e: Exception) {
            // Some phones (and emulators) have no equaliser effect.
            equalizer?.release()
            equalizer = null
            Equaliser.writeInfo(prefs, Equaliser.Info(available = false))
        }
        applyChoice()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun applyChoice() {
        val c = Equaliser.read(prefs)
        equalizer?.let { eq ->
            try {
                val range = eq.bandLevelRange
                if (c.preset in 0 until eq.numberOfPresets) {
                    eq.usePreset(c.preset.toShort())
                } else {
                    c.levels.forEachIndexed { band, level ->
                        if (band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                }
                eq.setEnabled(c.enabled)
            } catch (e: Exception) {
                // the effect was taken over by another app; nothing to do
            }
        }
        bassBoost?.let { b ->
            try {
                b.setStrength(c.bass.coerceIn(0, 1000).toShort())
                b.setEnabled(c.enabled && c.bass > 0)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        equalizer?.release()
        bassBoost?.release()
        equalizer = null
        bassBoost = null
    }
}

/** The equaliser as the screens see it: what the phone offers and your choice, kept up to date. */
class EqualiserState(context: Context) {
    private val prefs = Equaliser.prefs(context)

    /** What the phone's equaliser offers; null until the player has started once. */
    var info by mutableStateOf(Equaliser.readInfo(prefs)); private set
    var choice by mutableStateOf(Equaliser.read(prefs)); private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        info = Equaliser.readInfo(p)
        choice = Equaliser.read(p)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun close() = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun update(c: Equaliser.Choice) {
        choice = c
        Equaliser.write(prefs, c)
    }

    /** The band levels in effect: the chosen preset's, or your own. */
    fun levels(): List<Int> {
        val i = info ?: return emptyList()
        val c = choice
        return if (c.preset in i.presets.indices) i.presets[c.preset].second
        else List(i.bandsHz.size) { c.levels.getOrElse(it) { 0 } }
    }

    fun setEnabled(on: Boolean) = update(choice.copy(enabled = on))

    fun selectPreset(index: Int) {
        val levels = info?.presets?.getOrNull(index)?.second ?: levels()
        update(choice.copy(preset = index, levels = levels, enabled = true))
    }

    fun setBand(band: Int, level: Int) {
        val levels = levels().toMutableList()
        if (band !in levels.indices) return
        levels[band] = level
        update(choice.copy(preset = -1, levels = levels, enabled = true))
    }

    fun setBass(strength: Int) = update(choice.copy(bass = strength.coerceIn(0, 1000), enabled = true))

    fun reset() {
        val i = info ?: return
        val flat = i.presets.indexOfFirst { it.second.all { level -> level == 0 } }
        update(Equaliser.Choice(choice.enabled, preset = flat, levels = List(i.bandsHz.size) { 0 }, bass = 0))
    }
}
