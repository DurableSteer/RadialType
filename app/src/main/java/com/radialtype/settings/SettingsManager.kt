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
    const val KEY_HAPTIC_STYLE = "haptic_style"
    const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
    const val KEY_HAPTIC_TICK_INTENSITY = "haptic_tick_intensity"
    const val KEY_HAPTIC_PROGRESSIVE = "haptic_progressive_ticks"
    const val KEY_HAPTIC_PROGRESSIVE_DELETE = "haptic_progressive_delete"
    const val KEY_HAPTIC_DEADZONE_EXIT = "haptic_deadzone_exit"
    const val KEY_HAPTIC_SECONDARY_ENTER = "haptic_secondary_enter"
    const val KEY_HAPTIC_SECONDARY_RING_OUT = "haptic_secondary_ring_out"
    const val KEY_HAPTIC_LABEL_TOUCH = "haptic_label_touch"
    const val KEY_HAPTIC_RING_CROSS = "haptic_ring_cross"
    const val KEY_HAPTIC_DELETE_TICK = "haptic_delete_tick"
    const val KEY_DWELL_DURATION = "dwell_duration"
    const val KEY_VIBRATION_LENGTH = "vibration_length"
    const val KEY_DOUBLE_TAP_DEADZONE = "double_tap_deadzone"
    const val KEY_DEADZONE_RADIUS = "deadzone_radius"
    const val KEY_DELETE_RATE = "delete_chars_per_mm"
    const val KEY_CURSOR_SENS_H = "cursor_sensitivity_h"
    const val KEY_CURSOR_SENS_V = "cursor_sensitivity_v"
    const val KEY_CURSOR_DEADZONE = "cursor_deadzone_radius"
    const val KEY_DELETE_DEADZONE = "delete_deadzone_radius"
    const val KEY_MODE_LOCK_GRACE = "mode_lock_grace"
    const val KEY_AUTO_SPACE = "auto_space"
    const val KEY_AUTO_CAPITALIZATION = "auto_capitalization"
    const val KEY_INNER_RING_RADIUS = "inner_ring_radius"
    const val KEY_OUTER_RING_RADIUS = "outer_ring_radius"
    const val KEY_CUSTOM_LAYOUT = "custom_layout_json"
    const val KEY_OPEN_LAYOUT_EDITOR = "open_layout_editor"
    const val KEY_ENABLE_KEYBOARD_BUTTON = "enable_keyboard_button"
    const val KEY_PERF_HUD = "perf_hud"
    const val KEY_RING_HYSTERESIS = "ring_hysteresis"
    const val KEY_SEGMENT_HYSTERESIS = "segment_hysteresis"
    const val KEY_FLOATING_LABEL_OFFSET = "floating_label_offset"
    const val KEY_FLOATING_FONT = "floating_font_size"
    const val KEY_MENU_FONT = "menu_font_size"
    const val KEY_SUPPRESSION_WINDOW = "suppression_window"
    const val KEY_LANGUAGE_PRIMARY = "language_primary"
    const val KEY_LANGUAGE_SECONDARY = "language_secondary"
    const val KEY_LANGUAGE_MIX_RATIO = "language_mix_ratio"
    const val KEY_REGENERATE_LAYOUT = "regenerate_layout"

    // ── Bounds & defaults ────────────────────────────────────────
    const val DWELL_MIN = 1
    const val DWELL_MAX = 800
    const val DWELL_DEFAULT = 50

    const val VIBRATION_MIN = 1
    const val VIBRATION_MAX = 150
    const val VIBRATION_DEFAULT = 10
    
    // Intensity (raw actuator amplitude, 1–255).
    const val HAPTIC_INTENSITY_MIN = 20
    const val HAPTIC_INTENSITY_MAX = 255
    const val HAPTIC_INTENSITY_DEFAULT = 160

    // Delete tick intensity — defaults below the ring-pulse level so the
    // ratchet stays lighter than navigation pulses.
    const val TICK_INTENSITY_MIN = 10
    const val TICK_INTENSITY_MAX = 255
    const val TICK_INTENSITY_DEFAULT = 96

    // Haptic style: "legacy" = custom one-shots, "click"/"heavy_click" =
    // system predefined effects (SDK 29+, graceful fallback below).
    const val HAPTIC_STYLE_LEGACY = "legacy"
    const val HAPTIC_STYLE_CLICK = "system_click"
    const val HAPTIC_STYLE_HEAVY = "system_heavy"
    const val HAPTIC_STYLE_DEFAULT = HAPTIC_STYLE_LEGACY

    const val DOUBLE_TAP_MIN = 100
    const val DOUBLE_TAP_MAX = 600
    const val DOUBLE_TAP_DEFAULT = 150

    // Stored in tenths: slider 1..100 → 0.1..10.0 chars/mm.
    const val DELETE_RATE_MIN = 1
    const val DELETE_RATE_MAX = 100
    const val DELETE_RATE_DEFAULT = 5
    
    const val CURSOR_SENS_MIN = 1    // tenths: 0.1
    const val CURSOR_SENS_MAX = 100  // tenths: 10.0
    const val CURSOR_SENS_H_DEFAULT = 20   // 2.0 columns/mm
    const val CURSOR_SENS_V_DEFAULT = 10   // 1.0 lines per ... see below
    
    const val CURSOR_DEADZONE_MIN = 2
    const val CURSOR_DEADZONE_MAX = 40
    const val CURSOR_DEADZONE_DEFAULT = 12

    const val MODE_GRACE_MIN = 0
    const val MODE_GRACE_MAX = 300
    const val MODE_GRACE_DEFAULT = 120

    const val INNER_RING_MIN = 40f
    const val INNER_RING_MAX = 140f
    const val INNER_RING_DEFAULT = 50f

    const val OUTER_RING_MIN = 80f
    const val OUTER_RING_MAX = 240f
    const val OUTER_RING_DEFAULT = 80f

    const val DEADZONE_MIN = 10f
    const val DEADZONE_MAX = 60f
    const val DEADZONE_DEFAULT = 20f
    
    // ── Hysteresis bounds (stored in tenths) ─────────────────────
    const val RING_HYSTERESIS_MIN = 0      // 0.0 dp
    const val RING_HYSTERESIS_MAX = 160    // 16.0 dp
    const val RING_HYSTERESIS_DEFAULT = 80 // 8.0 dp (legacy constant)

    const val SEGMENT_HYSTERESIS_MIN = 0   // 0.0°
    const val SEGMENT_HYSTERESIS_MAX = 50  // 5.0°
    const val SEGMENT_HYSTERESIS_DEFAULT = 20 // 2.0° per Module 13 spec

    const val FLOATING_OFFSET_MIN = 60
    const val FLOATING_OFFSET_MAX = 320
    const val FLOATING_OFFSET_DEFAULT = 180

    const val FLOATING_FONT_MIN = 12
    const val FLOATING_FONT_MAX = 64
    const val FLOATING_FONT_DEFAULT = 36

    const val MENU_FONT_MIN = 10
    const val MENU_FONT_MAX = 40
    const val MENU_FONT_DEFAULT = 20
    
    // Post-ring-change segment suppression window (ms).
    const val SUPPRESSION_MIN = 0
    const val SUPPRESSION_MAX = 200
    const val SUPPRESSION_DEFAULT = 30

    // (0 = 100% secondary language, 100 = 100% primary language).
    const val LANGUAGE_MIX_MIN = 0
    const val LANGUAGE_MIX_MAX = 100
    const val LANGUAGE_MIX_DEFAULT = 60

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
        
    var perfHud: Boolean
        get() = prefs?.getBoolean(KEY_PERF_HUD, false) ?: false
        set(value) = put { it.putBoolean(KEY_PERF_HUD, value) }

    var hapticsEnabled: Boolean
        get() = prefs?.getBoolean(KEY_HAPTICS, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTICS, value) }
        
    /** Master intensity for all custom pulses (actuator amplitude 20–255). */
    var hapticIntensity: Int
        get() = clamp(prefs?.getInt(KEY_HAPTIC_INTENSITY, HAPTIC_INTENSITY_DEFAULT)
            ?: HAPTIC_INTENSITY_DEFAULT, HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        set(value) = put {
            it.putInt(KEY_HAPTIC_INTENSITY, clamp(value, HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX))
        }

    /** Delete-tick amplitude override (actuator amplitude 10–255). */
    var hapticTickIntensity: Int
        get() = clamp(prefs?.getInt(KEY_HAPTIC_TICK_INTENSITY, TICK_INTENSITY_DEFAULT)
            ?: TICK_INTENSITY_DEFAULT, TICK_INTENSITY_MIN, TICK_INTENSITY_MAX)
        set(value) = put {
            it.putInt(KEY_HAPTIC_TICK_INTENSITY, clamp(value, TICK_INTENSITY_MIN, TICK_INTENSITY_MAX))
        }

    /** One of HAPTIC_STYLE_* — how ring/tick pulses are rendered. */
    var hapticStyle: String
        get() = prefs?.getString(KEY_HAPTIC_STYLE, HAPTIC_STYLE_CLICK)
            ?: HAPTIC_STYLE_CLICK
        set(value) = put { it.putString(KEY_HAPTIC_STYLE, value) }

    /** Rising-amplitude delete ticks (crescendo within one gesture). */
    var hapticProgressiveTicks: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_PROGRESSIVE, false) ?: false
        set(value) = put { it.putBoolean(KEY_HAPTIC_PROGRESSIVE, value) }

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
    
    /** Tick when the finger lands on a cell that has a label. */
    var hapticLabelTouch: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_LABEL_TOUCH, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_LABEL_TOUCH, value) }

    /** Light click on crossing between the inner and outer ring. */
    var hapticRingCross: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_RING_CROSS, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_RING_CROSS, value) }
    
    /** Sub-toggle for the delete-mode per-character tick. */
    var hapticDeleteTick: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_DELETE_TICK, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_DELETE_TICK, value) }

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
    /** Grace window (ms) after NUMBER/SYMBOL lock during which the menu
     *  follows the finger but accepts no selection. 0 disables. */
    var modeLockGraceMs: Int
        get() = clamp(prefs?.getInt(KEY_MODE_LOCK_GRACE, MODE_GRACE_DEFAULT)
            ?: MODE_GRACE_DEFAULT, MODE_GRACE_MIN, MODE_GRACE_MAX)
        set(value) = put {
            it.putInt(KEY_MODE_LOCK_GRACE, clamp(value, MODE_GRACE_MIN, MODE_GRACE_MAX))
        }
    
    var floatingLabelOffsetPx: Int
        get() = clamp(prefs?.getInt(KEY_FLOATING_LABEL_OFFSET, FLOATING_OFFSET_DEFAULT)
            ?: FLOATING_OFFSET_DEFAULT, FLOATING_OFFSET_MIN, FLOATING_OFFSET_MAX)
        set(value) = put {
            it.putInt(KEY_FLOATING_LABEL_OFFSET, clamp(value, FLOATING_OFFSET_MIN, FLOATING_OFFSET_MAX))
        }

    /** Floating label font size (sp), 12–64. */
    var floatingFontSizeSp: Int
        get() = clamp(prefs?.getInt(KEY_FLOATING_FONT, FLOATING_FONT_DEFAULT)
            ?: FLOATING_FONT_DEFAULT, FLOATING_FONT_MIN, FLOATING_FONT_MAX)
        set(value) = put {
            it.putInt(KEY_FLOATING_FONT, clamp(value, FLOATING_FONT_MIN, FLOATING_FONT_MAX))
        }

    /** Menu cell label font size (sp), 10–40. */
    var menuLabelSizeSp: Int
        get() = clamp(prefs?.getInt(KEY_MENU_FONT, MENU_FONT_DEFAULT)
            ?: MENU_FONT_DEFAULT, MENU_FONT_MIN, MENU_FONT_MAX)
        set(value) = put {
            it.putInt(KEY_MENU_FONT, clamp(value, MENU_FONT_MIN, MENU_FONT_MAX))
        }

    /** Ring hysteresis band (dp), 0.0–16.0. */
    var ringHysteresisDp: Float
        get() = clamp(prefs?.getInt(KEY_RING_HYSTERESIS, RING_HYSTERESIS_DEFAULT)
            ?: RING_HYSTERESIS_DEFAULT,
            RING_HYSTERESIS_MIN, RING_HYSTERESIS_MAX) / 10f
        set(value) = put {
            it.putInt(KEY_RING_HYSTERESIS, (value * 10f).toInt()
                .coerceIn(RING_HYSTERESIS_MIN, RING_HYSTERESIS_MAX))
        }

    /** Angular deadzone around segment boundaries (degrees), 0.0–5.0. */
    var segmentHysteresisDeg: Float
        get() = clamp(prefs?.getInt(KEY_SEGMENT_HYSTERESIS, SEGMENT_HYSTERESIS_DEFAULT)
            ?: SEGMENT_HYSTERESIS_DEFAULT,
            SEGMENT_HYSTERESIS_MIN, SEGMENT_HYSTERESIS_MAX) / 10f
        set(value) = put {
            it.putInt(KEY_SEGMENT_HYSTERESIS, (value * 10f).toInt()
                .coerceIn(SEGMENT_HYSTERESIS_MIN, SEGMENT_HYSTERESIS_MAX))
        }
    
    /** Segment-suppression window after a ring change (ms), 0–200. */
    var suppressionWindowMs: Int
        get() = clamp(prefs?.getInt(KEY_SUPPRESSION_WINDOW, SUPPRESSION_DEFAULT)
            ?: SUPPRESSION_DEFAULT, SUPPRESSION_MIN, SUPPRESSION_MAX)
        set(value) = put {
            it.putInt(KEY_SUPPRESSION_WINDOW, clamp(value, SUPPRESSION_MIN, SUPPRESSION_MAX))
        }
        
    /** Characters deleted per millimetre of horizontal swipe in DELETE mode. */
    var deleteCharsPerMm: Float
        get() = clamp(prefs?.getInt(KEY_DELETE_RATE, DELETE_RATE_DEFAULT) ?: DELETE_RATE_DEFAULT,
            DELETE_RATE_MIN, DELETE_RATE_MAX) / 10f
        set(value) = put {
            it.putInt(KEY_DELETE_RATE, (value * 10f).toInt()
                .coerceIn(DELETE_RATE_MIN, DELETE_RATE_MAX))
        }
    
    /** Horizontal cursor speed: columns per mm of drag (tenths, 1–100). */
    var cursorColumnsPerMm: Float
        get() = (prefs?.getInt(KEY_CURSOR_SENS_H, 20) ?: 20)
            .coerceIn(1, 100) / 10f
        set(value) = put {
            it.putInt(KEY_CURSOR_SENS_H, (value * 10f).toInt().coerceIn(1, 100))
        }

    /** Vertical cursor speed: lines per cm of drag (tenths, 1–100). */
    var cursorLinesPerCm: Float
        get() = (prefs?.getInt(KEY_CURSOR_SENS_V, 10) ?: 10)
            .coerceIn(1, 100) / 10f
        set(value) = put {
            it.putInt(KEY_CURSOR_SENS_V, (value * 10f).toInt().coerceIn(1, 100))
        }

    /** Neutral radius (dp) around the entry point before cursor movement begins. */
    var cursorDeadzoneDp: Float
        get() = (prefs?.getInt(KEY_CURSOR_DEADZONE, 12) ?: 12)
            .coerceIn(2, 40).toFloat()
        set(value) = put {
            it.putInt(KEY_CURSOR_DEADZONE, value.toInt().coerceIn(2, 40))
        }

    /** Travel (dp) before the delete selection first arms. */
    var deleteDeadzoneDp: Float
        get() = (prefs?.getInt(KEY_DELETE_DEADZONE, 6) ?: 6)
            .coerceIn(2, 40).toFloat()
        set(value) = put {
            it.putInt(KEY_DELETE_DEADZONE, value.toInt().coerceIn(2, 40))
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

    // ── Language packs (Module 15) ───────────────────────────────

    /** ISO code of the primary language pack (asset `langs/<code>.json`). */
    var languagePrimary: String
        get() = prefs?.getString(KEY_LANGUAGE_PRIMARY, "en") ?: "en"
        set(value) = put { it.putString(KEY_LANGUAGE_PRIMARY, value) }

    /** ISO code of the optional secondary pack; "" = single-language mode. */
    var languageSecondary: String
        get() = prefs?.getString(KEY_LANGUAGE_SECONDARY, "") ?: ""
        set(value) = put { it.putString(KEY_LANGUAGE_SECONDARY, value) }

    /** Blend weight of the PRIMARY language, 0..100 percent. */
    var languageMixPercent: Int
        get() = clamp(prefs?.getInt(KEY_LANGUAGE_MIX_RATIO, 60) ?: 60,
            LANGUAGE_MIX_MIN, LANGUAGE_MIX_MAX)
        set(value) = put { it.putInt(KEY_LANGUAGE_MIX_RATIO, clamp(value, LANGUAGE_MIX_MIN, LANGUAGE_MIX_MAX)) }

    /** Primary-language weight as a 0.0–1.0 fraction, for [LayoutArranger.blend]. */
    val languageMixRatio: Float
        get() = languageMixPercent / 100f

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
