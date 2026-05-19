package com.mg4.winclose

import android.content.Context

/** Mode de fermeture par vitre */
enum class WindowMode { AUTO, PULSED, OFF }

/** Moment de déclenchement du délai de fermeture */
enum class DelayTrigger { DOOR_OPEN, DOOR_CLOSE }

/**
 * Réglages avancés persistés.
 */
object Settings {
    private const val PREF_SPEED          = "speed_threshold_kmh"
    private const val PREF_TIME           = "time_threshold_min"
    private const val PREF_DELAY          = "trigger_delay_sec"
    private const val PREF_WINDOW_PREFIX  = "window_mode_"
    private const val PREF_DELAY_TRIGGER  = "delay_trigger"
    private const val PREF_BEEP_ENABLED   = "beep_enabled"
    private const val PREF_BEEP_VOLUME    = "beep_volume"

    const val DEFAULT_SPEED_KMH     = 20
    const val DEFAULT_TIME_MIN      = 5
    const val DEFAULT_DELAY_SEC     = 5
    val   DEFAULT_WINDOW_MODE       = WindowMode.PULSED
    val   DEFAULT_DELAY_TRIGGER     = DelayTrigger.DOOR_CLOSE
    const val DEFAULT_BEEP_ENABLED  = false
    const val DEFAULT_BEEP_VOLUME   = 50

    const val MIN_SPEED_KMH = 5;   const val MAX_SPEED_KMH = 40
    const val MIN_TIME_MIN  = 1;   const val MAX_TIME_MIN  = 15
    const val MIN_DELAY_SEC = 0;   const val MAX_DELAY_SEC = 30
    const val MIN_BEEP_VOL  = 0;   const val MAX_BEEP_VOL  = 100

    /** Mapping position → areaId SAIC */
    const val AREA_FL = 0   // Avant gauche (conducteur)
    const val AREA_FR = 1   // Avant droit
    const val AREA_RL = 2   // Arrière gauche
    const val AREA_RR = 3   // Arrière droit
    val ALL_AREAS = listOf(AREA_FL, AREA_FR, AREA_RL, AREA_RR)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LocaleHelper.PREF_NAME, Context.MODE_PRIVATE)

    // ── Thresholds ──────────────────────────────────────────────────────────────
    fun getSpeedKmh(ctx: Context): Int = prefs(ctx).getInt(PREF_SPEED, DEFAULT_SPEED_KMH)
    fun getTimeMin(ctx: Context): Int  = prefs(ctx).getInt(PREF_TIME,  DEFAULT_TIME_MIN)
    fun getDelaySec(ctx: Context): Int = prefs(ctx).getInt(PREF_DELAY, DEFAULT_DELAY_SEC)

    fun setSpeedKmh(ctx: Context, v: Int) = prefs(ctx).edit().putInt(PREF_SPEED, v).apply()
    fun setTimeMin(ctx: Context, v: Int)  = prefs(ctx).edit().putInt(PREF_TIME,  v).apply()
    fun setDelaySec(ctx: Context, v: Int) = prefs(ctx).edit().putInt(PREF_DELAY, v).apply()

    // ── Delay trigger ────────────────────────────────────────────────────────────
    fun getDelayTrigger(ctx: Context): DelayTrigger {
        val name = prefs(ctx).getString(PREF_DELAY_TRIGGER, null) ?: return DEFAULT_DELAY_TRIGGER
        return try { DelayTrigger.valueOf(name) } catch (_: Exception) { DEFAULT_DELAY_TRIGGER }
    }
    fun setDelayTrigger(ctx: Context, t: DelayTrigger) =
        prefs(ctx).edit().putString(PREF_DELAY_TRIGGER, t.name).apply()

    // ── Beep ─────────────────────────────────────────────────────────────────────
    fun isBeepEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_BEEP_ENABLED, DEFAULT_BEEP_ENABLED)
    fun setBeepEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(PREF_BEEP_ENABLED, v).apply()

    fun getBeepVolume(ctx: Context): Int =
        prefs(ctx).getInt(PREF_BEEP_VOLUME, DEFAULT_BEEP_VOLUME)
    fun setBeepVolume(ctx: Context, v: Int) =
        prefs(ctx).edit().putInt(PREF_BEEP_VOLUME, v).apply()

    // ── Per-window mode ──────────────────────────────────────────────────────────
    fun getWindowMode(ctx: Context, area: Int): WindowMode {
        val name = prefs(ctx).getString("$PREF_WINDOW_PREFIX$area", null) ?: return DEFAULT_WINDOW_MODE
        return try { WindowMode.valueOf(name) } catch (_: Exception) { DEFAULT_WINDOW_MODE }
    }
    fun setWindowMode(ctx: Context, area: Int, mode: WindowMode) {
        prefs(ctx).edit().putString("$PREF_WINDOW_PREFIX$area", mode.name).apply()
    }

    // ── Reset ────────────────────────────────────────────────────────────────────
    fun resetDefaults(ctx: Context) {
        val e = prefs(ctx).edit()
            .putInt(PREF_SPEED, DEFAULT_SPEED_KMH)
            .putInt(PREF_TIME,  DEFAULT_TIME_MIN)
            .putInt(PREF_DELAY, DEFAULT_DELAY_SEC)
            .putString(PREF_DELAY_TRIGGER, DEFAULT_DELAY_TRIGGER.name)
            .putBoolean(PREF_BEEP_ENABLED, DEFAULT_BEEP_ENABLED)
            .putInt(PREF_BEEP_VOLUME, DEFAULT_BEEP_VOLUME)
        ALL_AREAS.forEach { e.putString("$PREF_WINDOW_PREFIX$it", DEFAULT_WINDOW_MODE.name) }
        e.apply()
    }
}
