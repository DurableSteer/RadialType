package com.radialtype.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Singleton bridge between SharedPreferences and the runtime.
 * Getters hit the in-memory cache on every access, so UI changes
 * take effect immediately.
 */
object SettingsManager {

    // ── Preference keys (must match res/xml/preferences.xml) ─────
    const val KEY_DEBUG_MODE = "debug_mode"
    const val KEY_HAPTICS = "haptic_feedback"
    const val KEY_HAPTIC_DEADZONE_EXIT = "haptic_deadzone_exit"
    const val KEY_HAPTIC_SECONDARY_ENTER = "haptic_secondary_enter"
    const val KEY_HAPTIC_SECONDARY_RING_OUT = "haptic_secondary_ring_out"
    const val KEY_DWELL_DURATION = "dwell_duration"
    const val KEY_VIBRATION_LENGTH = "vibration_length"
    const val KEY_DOUBLE_TAP_DEADZONE = "double_tap_deadzone"
    const val KEY_DEADZONE_RADIUS = "deadzone_radius"
    const val KEY_DELETE_RATE = "delete_chars_per_mm"
    const val KEY_AUTO_SPACE = "auto_space"
    const val KEY_AUTO_CAPITALIZATION = "auto_capitalization"
    const val KEY_INNER_RING_RADIUS = "inner_ring_radius"
    const val KEY_OUTER_RING_RADIUS = "outer_ring_radius"
    const val KEY_CUSTOM_LAYOUT = "custom_layout_json"
    const val KEY_OPEN_LAYOUT_EDITOR = "open_layout_editor"
    const val KEY_ENABLE_KEYBOARD_BUTTON = "enable_keyboard_button"
    const val KEY_OVERLAY_PERMISSION = "overlay_permission"

    // ── Bounds & defaults ────────────────────────────────────────
    const val DWELL_MIN = 1
    const val DWELL_MAX = 800
    const val DWELL_DEFAULT = 75

    const val VIBRATION_MIN = 1
    const val VIBRATION_MAX = 150
    const val VIBRATION_DEFAULT = 10

    const val DOUBLE_TAP_MIN = 100
    const val DOUBLE_TAP_MAX = 600
    const val DOUBLE_TAP_DEFAULT = 300

    // Stored in tenths: slider 1..100 → 0.1..10.0 chars/mm.
    const val DELETE_RATE_MIN = 1
    const val DELETE_RATE_MAX = 100
    const val DELETE_RATE_DEFAULT = 10

    const val INNER_RING_MIN = 30f
    const val INNER_RING_MAX = 100f
    const val INNER_RING_DEFAULT = 40f

    const val OUTER_RING_MIN = 60f
    const val OUTER_RING_MAX = 200f
    const val OUTER_RING_DEFAULT = 100f
    
    const val DEADZONE_MIN = 5f
    const val DEADZONE_MAX = 50f
    const val DEADZONE_DEFAULT = 25f

    @Volatile
    private var appContext: Context? = null

    val isInitialized: Boolean get() = appContext != null

    private val prefs: SharedPreferences?
        get() = appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── Toggles ──────────────────────────────────────────────────

    var debugMode: Boolean
        get() = prefs?.getBoolean(KEY_DEBUG_MODE, true) ?: true
        set(value) = put { it.putBoolean(KEY_DEBUG_MODE, value) }

    var hapticsEnabled: Boolean
        get() = prefs?.getBoolean(KEY_HAPTICS, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTICS, value) }
        
    var hapticDeadzoneExit: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_DEADZONE_EXIT, false) ?: false
        set(value) = put { it.putBoolean(KEY_HAPTIC_DEADZONE_EXIT, value) }

    /** Haptic pulse on PRIMARY → SECONDARY transition. */
    var hapticSecondaryEnter: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_SECONDARY_ENTER, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_SECONDARY_ENTER, value) }

    /** Haptic pulse on secondary INNER → OUTER ring transition. */
    var hapticSecondaryRingOut: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_SECONDARY_RING_OUT, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_SECONDARY_RING_OUT, value) }

    var autoSpaceEnabled: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_SPACE, false) ?: false
        set(value) = put { it.putBoolean(KEY_AUTO_SPACE, value) }

    var autoCapitalization: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_CAPITALIZATION, true) ?: true
        set(value) = put { it.putBoolean(KEY_AUTO_CAPITALIZATION, value) }

    // ── Numeric settings ─────────────────────────────────────────

    var dwellDurationMs: Int
        get() = clamp(prefs?.getInt(KEY_DWELL_DURATION, DWELL_DEFAULT) ?: DWELL_DEFAULT,
            DWELL_MIN, DWELL_MAX)
        set(value) = put { it.putInt(KEY_DWELL_DURATION, clamp(value, DWELL_MIN, DWELL_MAX)) }

    var vibrationLengthMs: Int
        get() = clamp(prefs?.getInt(KEY_VIBRATION_LENGTH, VIBRATION_DEFAULT) ?: VIBRATION_DEFAULT,
            VIBRATION_MIN, VIBRATION_MAX)
        set(value) = put { it.putInt(KEY_VIBRATION_LENGTH, clamp(value, VIBRATION_MIN, VIBRATION_MAX)) }

    /** Max interval (ms) between the two taps of the deadzone delete gesture. */
    var doubleTapDeadzoneMs: Int
        get() = clamp(prefs?.getInt(KEY_DOUBLE_TAP_DEADZONE, DOUBLE_TAP_DEFAULT)
            ?: DOUBLE_TAP_DEFAULT, DOUBLE_TAP_MIN, DOUBLE_TAP_MAX)
        set(value) = put {
            it.putInt(KEY_DOUBLE_TAP_DEADZONE, clamp(value, DOUBLE_TAP_MIN, DOUBLE_TAP_MAX))
        }

    /** Characters deleted per millimetre of horizontal swipe in DELETE mode. */
    var deleteCharsPerMm: Float
        get() = clamp(prefs?.getInt(KEY_DELETE_RATE, DELETE_RATE_DEFAULT) ?: DELETE_RATE_DEFAULT,
            DELETE_RATE_MIN, DELETE_RATE_MAX) / 10f
        set(value) = put {
            it.putInt(KEY_DELETE_RATE, (value * 10f).toInt()
                .coerceIn(DELETE_RATE_MIN, DELETE_RATE_MAX))
        }
    /** Radius of the centre deadzone (dp) — ring NONE. Independent of inner ring. */
    var deadzoneRadius: Float
        get() = clamp(prefs?.getInt(KEY_DEADZONE_RADIUS, DEADZONE_DEFAULT.toInt())
            ?: DEADZONE_DEFAULT.toInt(),
            DEADZONE_MIN.toInt(), DEADZONE_MAX.toInt()).toFloat()
        set(value) = put {
            it.putInt(KEY_DEADZONE_RADIUS, clamp(value.toInt(),
                DEADZONE_MIN.toInt(), DEADZONE_MAX.toInt()))
        }

    var innerRingRadius: Float
        get() = clamp(prefs?.getInt(KEY_INNER_RING_RADIUS, INNER_RING_DEFAULT.toInt())
            ?: INNER_RING_DEFAULT.toInt(),
            INNER_RING_MIN.toInt(), INNER_RING_MAX.toInt()).toFloat()
        set(value) = put {
            it.putInt(KEY_INNER_RING_RADIUS, clamp(value.toInt(),
                INNER_RING_MIN.toInt(), INNER_RING_MAX.toInt()))
        }

    var outerRingRadius: Float
        get() = clamp(prefs?.getInt(KEY_OUTER_RING_RADIUS, OUTER_RING_DEFAULT.toInt())
            ?: OUTER_RING_DEFAULT.toInt(),
            OUTER_RING_MIN.toInt(), OUTER_RING_MAX.toInt()).toFloat()
        set(value) = put {
            it.putInt(KEY_OUTER_RING_RADIUS, clamp(value.toInt(),
                OUTER_RING_MIN.toInt(), OUTER_RING_MAX.toInt()))
        }

    val outerRingMaxRadius: Float
        get() = outerRingRadius + 60f

    // ── Custom layout ────────────────────────────────────────────

    /** Raw JSON for the active layout; empty string = built-in/asset default. */
    var customLayoutJson: String
        get() = prefs?.getString(KEY_CUSTOM_LAYOUT, "") ?: ""
        set(value) = put { it.putString(KEY_CUSTOM_LAYOUT, value) }

    // ── Helpers ──────────────────────────────────────────────────

    private inline fun put(block: (SharedPreferences.Editor) -> Unit) {
        prefs?.let { p ->
            val e = p.edit()
            block(e)
            e.apply()
        }
    }

    private fun clamp(v: Int, min: Int, max: Int): Int = v.coerceIn(min, max)
}
