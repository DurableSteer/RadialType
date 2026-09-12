package com.radialtype.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Singleton bridge between SharedPreferences and the runtime.
 * Getters hit the in-memory cache on every access, so UI changes
 * take effect immediately.
 *
 * Organization mirrors res/xml/preferences.xml, top to bottom:
 *   Rendering & display → Haptics → Typing behaviour → Launch feel
 *   (onset / projection / segment hysteresis) → Delete & cursor
 *   → Ring geometry & reach → Language & layout → Benchmark.
 */
object SettingsManager {

    // ════════════════════════════════════════════════════════════
    //  Preference keys (must match res/xml/preferences.xml)
    // ════════════════════════════════════════════════════════════

    // ── Rendering & display ──────────────────────────────────────
    const val KEY_DEBUG_MODE = "debug_mode"
    const val KEY_PERF_HUD = "perf_hud"
    const val KEY_FLOATING_LABEL_OFFSET = "floating_label_offset"
    const val KEY_FLOATING_FONT = "floating_font_size"
    const val KEY_MENU_FONT = "menu_font_size"
    const val KEY_MENU_SCALE = "menu_visual_scale"

    // ── Haptics ──────────────────────────────────────────────────
    const val KEY_HAPTICS = "haptic_feedback"
    const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
    const val KEY_HAPTIC_STYLE = "haptic_style"
    const val KEY_HAPTIC_TICK_INTENSITY = "haptic_tick_intensity"
    const val KEY_HAPTIC_PROGRESSIVE = "haptic_progressive_ticks"
    const val KEY_HAPTIC_DEADZONE_EXIT = "haptic_deadzone_exit"
    const val KEY_HAPTIC_SECONDARY_ENTER = "haptic_secondary_enter"
    const val KEY_HAPTIC_LABEL_TOUCH = "haptic_label_touch"
    const val KEY_HAPTIC_RING_CROSS = "haptic_ring_cross"
    const val KEY_HAPTIC_DELETE_TICK = "haptic_delete_tick"
    const val KEY_HAPTIC_CURSOR_TICK = "haptic_cursor_tick"
    const val KEY_VIBRATION_LENGTH = "vibration_length"

    // ── Typing behaviour ─────────────────────────────────────────
    const val KEY_DWELL_DURATION = "dwell_duration"
    const val KEY_DWELL_BEND_DELAY = "dwell_bend_delay"
    const val KEY_DWELL_GATE_ENABLED = "dwell_gate_enabled"
    const val KEY_DWELL_GATE_MODE = "dwell_gate_mode"
    const val KEY_DWELL_GATE_SPEED = "dwell_gate_speed"
    const val KEY_DOUBLE_TAP_DEADZONE = "double_tap_deadzone"
    const val KEY_MODE_LOCK_GRACE = "mode_lock_grace"
    const val KEY_SECONDARY_ABORT_EASE = "secondary_abort_ease"
    const val KEY_SECONDARY_ABORT_EASE_ENABLED = "secondary_abort_ease_enabled"
    const val KEY_AUTO_SPACE = "auto_space"
    const val KEY_AUTO_CAPITALIZATION = "auto_capitalization"

    // ── Launch feel: onset-weighted exit angle ────────────────────
    const val KEY_ONSET_ENABLED = "onset_exit_angle"
    const val KEY_ONSET_POSITION_WEIGHT = "onset_position_weight"
    const val KEY_ONSET_VELOCITY_WINDOW = "onset_velocity_window"
    const val KEY_ONSET_MIN_SPEED = "onset_min_speed"

    // ── Launch feel: radial exit projection ──────────────────────
    const val KEY_RADPROJ_ENABLED = "radproj_enabled"
    const val KEY_RADPROJ_HORIZON = "radproj_horizon"
    const val KEY_RADPROJ_MAX_SHIFT = "radproj_max_shift"

    // ── Launch feel: angular hysteresis ──────────────────────────
    const val KEY_SEGMENT_HYSTERESIS = "segment_hysteresis"

    // ── Delete & cursor ──────────────────────────────────────────
    const val KEY_DELETE_RATE = "delete_chars_per_mm"
    const val KEY_DELETE_DEADZONE = "delete_deadzone_radius"
    const val KEY_CURSOR_SENS_H = "cursor_sens_h"
    const val KEY_CURSOR_SENS_V = "cursor_sens_v"
    const val KEY_CURSOR_DEADZONE = "cursor_deadzone_radius"

    // ── Ring geometry & reach ─────────────────────────────────────
    const val KEY_DEADZONE_RADIUS = "deadzone_radius"
    const val KEY_INNER_RING_RADIUS = "inner_ring_radius"
    const val KEY_OUTER_RING_RADIUS = "outer_ring_radius"
    const val KEY_REACH_PREFIX = "reach_"
    const val KEY_HAND_PRESET = "hand_preset"

    // ── Language & layout ────────────────────────────────────────
    const val KEY_LANGUAGE_PRIMARY = "language_primary"
    const val KEY_LANGUAGE_SECONDARY = "language_secondary"
    const val KEY_LANGUAGE_MIX_RATIO = "language_mix_ratio"
    const val KEY_REGENERATE_LAYOUT = "regenerate_layout"
    const val KEY_CUSTOM_LAYOUT = "custom_layout_json"
    const val KEY_OPEN_LAYOUT_EDITOR = "open_layout_editor"

    // ── Benchmark ────────────────────────────────────────────────
    const val KEY_OPEN_BENCHMARK = "open_benchmark"
    const val KEY_OPEN_BENCH_HISTORY = "open_bench_history"
    const val KEY_BENCH_REPEAT_SEED = "bench_repeat_seed"
    const val KEY_BENCH_LAST_SEED = "bench_last_seed"
    const val KEY_BENCH_TRIALS = "bench_trials_per_target"

    // ── Activation ───────────────────────────────────────────────
    const val KEY_ENABLE_KEYBOARD_BUTTON = "enable_keyboard_button"

    // ════════════════════════════════════════════════════════════
    //  Bounds & defaults
    // ════════════════════════════════════════════════════════════

    // ── Gate modes: how the dwell fire is earned. ─────────────────
    // stillness = classic below-ceiling-for-duration (the 84% baseline)
    // bend      = fires dwellMs after the detected speed MINIMUM
    // hybrid    = either leg fires
    const val GATE_MODE_STILLNESS = "stillness"
    const val GATE_MODE_BEND = "bend"
    const val GATE_MODE_HYBRID = "hybrid"
    const val GATE_MODE_DEFAULT = GATE_MODE_HYBRID

    const val DWELL_MIN = 1
    const val DWELL_MAX = 800
    const val DWELL_DEFAULT = 90
    
     // Bend-leg fire delay (ms) after a qualifying valley. Independent
    // of the stillness duration so the stillness leg keeps its latency
    // while bend fires land after the settle tail.
    const val DWELL_BEND_DELAY_MIN = 0
    const val DWELL_BEND_DELAY_MAX = 400
    const val DWELL_BEND_DELAY_DEFAULT = 80

    const val DWELL_GATE_SPEED_MIN = 1        // 0.01 dp/ms
    const val DWELL_GATE_SPEED_MAX = 50       // 0.50 dp/ms
    const val DWELL_GATE_SPEED_DEFAULT = 10   // 0.10 dp/ms

    const val DOUBLE_TAP_MIN = 100
    const val DOUBLE_TAP_MAX = 600
    const val DOUBLE_TAP_DEFAULT = 150

    // Stored in tenths: slider 1..100 → 0.1..10.0 chars/mm.
    const val DELETE_RATE_MIN = 1
    const val DELETE_RATE_MAX = 100
    const val DELETE_RATE_DEFAULT = 5

    const val CURSOR_SENS_MIN = 1    // tenths: 0.1
    const val CURSOR_SENS_MAX = 100  // tenths: 10.0
    const val CURSOR_SENS_H_DEFAULT = 10   // columns/mm
    const val CURSOR_SENS_V_DEFAULT = 10   // lines/cm

    const val CURSOR_DEADZONE_MIN = 2
    const val CURSOR_DEADZONE_MAX = 40
    const val CURSOR_DEADZONE_DEFAULT = 12

    const val MODE_GRACE_MIN = 0
    const val MODE_GRACE_MAX = 300
    const val MODE_GRACE_DEFAULT = 40
    
    // Widening of the effective deadzone while the SECONDARY menu is
    // open (dp). Makes "pull back to center to cancel" forgiving —
    // the finger finds the deadzone ease-dp before it geometrically
    // exists. 0 = today's exact-deadzone behavior.
    const val SECONDARY_ABORT_EASE_MIN = 0
    const val SECONDARY_ABORT_EASE_MAX = 80
    const val SECONDARY_ABORT_EASE_DEFAULT = 30

    // ── Haptics ──────────────────────────────────────────────────
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
    const val HAPTIC_STYLE_DEFAULT = HAPTIC_STYLE_CLICK

    // ── Ring geometry & reach ─────────────────────────────────────
    const val DEADZONE_MIN = 1f
    const val DEADZONE_MAX = 60f
    const val DEADZONE_DEFAULT = 5f

    const val INNER_RING_MIN = 20f
    const val INNER_RING_MAX = 140f
    const val INNER_RING_DEFAULT = 60f

    const val OUTER_RING_MIN = 40f
    const val OUTER_RING_MAX = 240f
    const val OUTER_RING_DEFAULT = 80f

    // Per-direction reach, percent. 100 = full reach (longest axis of
    // the shape), 50 = half. Values are RELATIVE: the getter
    // normalizes the maximum to 1.0, so "all 60" is a circle.
    const val REACH_MIN = 50
    const val REACH_MAX = 100
    const val REACH_DEFAULT = 100

    const val PRESET_RIGHT = "right"
    const val PRESET_LEFT = "left"
    const val PRESET_CIRCLE = "circle"

    // ── Launch feel: onset-weighted exit angle ────────────────────
    // Master toggle for the velocity-blended launch direction.
    // Position weight is stored as a percent (0..100): how much of the
    // exit-angle blend comes from the positional bearing vs. travel
    // direction. Velocity window is milliseconds of look-back. The speed
    // floor is stored in hundredths of dp/ms (slider 1..50 → 0.01..0.50);
    // exits slower than the floor use pure position — it separates
    // deliberate slow entries from fast flicks.
    const val ONSET_POSITION_MIN = 0        // 0%  = pure velocity
    const val ONSET_POSITION_MAX = 100      // 100% = pure position (off, effectively)
    const val ONSET_POSITION_DEFAULT = 30

    const val ONSET_WINDOW_MIN = 16
    const val ONSET_WINDOW_MAX = 120
    const val ONSET_WINDOW_DEFAULT = 40

    const val ONSET_SPEED_MIN = 1           // 0.01 dp/ms
    const val ONSET_SPEED_MAX = 50          // 0.50 dp/ms
    const val ONSET_SPEED_DEFAULT = 5       // 0.05 dp/ms

    // ── Launch feel: radial exit projection (velocity-scaled) ────
    // Commits are classified where the flick was HEADED: the finger's
    // radius is projected along its radial velocity at lift, capped.
    // Long flicks stop undershooting into the inner ring; slow
    // steering (v≈0) projects nothing and stays exact.
    const val RADPROJ_HORIZON_MIN = 0         // ms
    const val RADPROJ_HORIZON_MAX = 120       // ms
    const val RADPROJ_HORIZON_DEFAULT = 32

    const val RADPROJ_SHIFT_MIN = 0           // dp
    const val RADPROJ_SHIFT_MAX = 40          // dp
    const val RADPROJ_SHIFT_DEFAULT = 12

    // ── Launch feel: angular hysteresis (stored in tenths of a degree) ──
    const val SEGMENT_HYSTERESIS_MIN = 0   // 0.0°
    const val SEGMENT_HYSTERESIS_MAX = 50  // 5.0°
    const val SEGMENT_HYSTERESIS_DEFAULT = 0 // 2.0°

    // ── Rendering & display ──────────────────────────────────────
    const val MENU_SCALE_MIN = 100       // % — 100 = drawn = classified
    const val MENU_SCALE_MAX = 200
    const val MENU_SCALE_DEFAULT = 160
    
    const val FLOATING_OFFSET_MIN = 60
    const val FLOATING_OFFSET_MAX = 320
    const val FLOATING_OFFSET_DEFAULT = 180

    const val FLOATING_FONT_MIN = 12
    const val FLOATING_FONT_MAX = 64
    const val FLOATING_FONT_DEFAULT = 36

    const val MENU_FONT_MIN = 10
    const val MENU_FONT_MAX = 40
    const val MENU_FONT_DEFAULT = 20

    // ── Language & layout ────────────────────────────────────────
    // (0 = 100% secondary language, 100 = 100% primary language).
    const val LANGUAGE_MIX_MIN = 0
    const val LANGUAGE_MIX_MAX = 100
    const val LANGUAGE_MIX_DEFAULT = 60

    // ════════════════════════════════════════════════════════════
    //  Infrastructure
    // ════════════════════════════════════════════════════════════

    @Volatile
    private var appContext: Context? = null

    val isInitialized: Boolean get() = appContext != null

    private val prefs: SharedPreferences?
        get() = appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ════════════════════════════════════════════════════════════
    //  Rendering & display
    // ════════════════════════════════════════════════════════════

    var debugMode: Boolean
        get() = prefs?.getBoolean(KEY_DEBUG_MODE, true) ?: true
        set(value) = put { it.putBoolean(KEY_DEBUG_MODE, value) }

    var perfHud: Boolean
        get() = prefs?.getBoolean(KEY_PERF_HUD, false) ?: false
        set(value) = put { it.putBoolean(KEY_PERF_HUD, value) }

    /** Floating label height above the finger (px). */
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
    
    /**
     * Render-only menu scale (percent). Every radius drawn by
     * RadialRenderer is multiplied by this value; GeometryEngine
     * classification radii are untouched, so finger targets stay
     * where muscle memory put them. Visual legibility preference
     * over calibration transparency — off (=100) by default.
     */
    var menuVisualScalePct: Int
        get() = clamp(prefs?.getInt(KEY_MENU_SCALE, MENU_SCALE_DEFAULT)
            ?: MENU_SCALE_DEFAULT, MENU_SCALE_MIN, MENU_SCALE_MAX)
        set(value) = put {
            it.putInt(KEY_MENU_SCALE, clamp(value, MENU_SCALE_MIN, MENU_SCALE_MAX))
        }

    /** Scale factor the renderer multiplies drawn radii by (1.0 = off). */
    val menuVisualScale: Float
        get() = menuVisualScalePct / 100f

    // ════════════════════════════════════════════════════════════
    //  Haptics
    // ════════════════════════════════════════════════════════════

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

    /** One of HAPTIC_STYLE_* — how ring/tick pulses are rendered. */
    var hapticStyle: String
        get() = prefs?.getString(KEY_HAPTIC_STYLE, HAPTIC_STYLE_DEFAULT)
            ?: HAPTIC_STYLE_DEFAULT
        set(value) = put { it.putString(KEY_HAPTIC_STYLE, value) }

    /** Delete-tick amplitude override (actuator amplitude 10–255). */
    var hapticTickIntensity: Int
        get() = clamp(prefs?.getInt(KEY_HAPTIC_TICK_INTENSITY, TICK_INTENSITY_DEFAULT)
            ?: TICK_INTENSITY_DEFAULT, TICK_INTENSITY_MIN, TICK_INTENSITY_MAX)
        set(value) = put {
            it.putInt(KEY_HAPTIC_TICK_INTENSITY, clamp(value, TICK_INTENSITY_MIN, TICK_INTENSITY_MAX))
        }

    /** Duration of each haptic pulse (ms). */
    var vibrationLengthMs: Int
        get() = clamp(prefs?.getInt(KEY_VIBRATION_LENGTH, VIBRATION_DEFAULT) ?: VIBRATION_DEFAULT,
            VIBRATION_MIN, VIBRATION_MAX)
        set(value) = put { it.putInt(KEY_VIBRATION_LENGTH, clamp(value, VIBRATION_MIN, VIBRATION_MAX)) }

    /** Rising-amplitude delete ticks (crescendo within one gesture). */
    var hapticProgressiveTicks: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_PROGRESSIVE, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_PROGRESSIVE, value) }

    var hapticDeadzoneExit: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_DEADZONE_EXIT, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_DEADZONE_EXIT, value) }

    /** Haptic pulse on PRIMARY → SECONDARY transition. */
    var hapticSecondaryEnter: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_SECONDARY_ENTER, false) ?: false
        set(value) = put { it.putBoolean(KEY_HAPTIC_SECONDARY_ENTER, value) }

    /** Tick when the finger lands on a cell that has a label. */
    var hapticLabelTouch: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_LABEL_TOUCH, false) ?: false
        set(value) = put { it.putBoolean(KEY_HAPTIC_LABEL_TOUCH, value) }

    /** Light click on crossing between the inner and outer ring. */
    var hapticRingCross: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_RING_CROSS, false) ?: false
        set(value) = put { it.putBoolean(KEY_HAPTIC_RING_CROSS, value) }

    /** Sub-toggle for the delete-mode per-character tick. */
    var hapticDeleteTick: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_DELETE_TICK, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_DELETE_TICK, value) }
    
    /**
     * Cursor haptic parity (Package 0.4): one tick per net column/line
     * crossing during CURSOR drags. Independent of the delete toggle —
     * they share the tick AMPLITUDE (hapticTickIntensity) but can be
     * switched separately. Defaults to true, mirroring the delete tick.
     */
    var hapticCursorTick: Boolean
        get() = prefs?.getBoolean(KEY_HAPTIC_CURSOR_TICK, true) ?: true
        set(value) = put { it.putBoolean(KEY_HAPTIC_CURSOR_TICK, value) }

    // ════════════════════════════════════════════════════════════
    //  Typing behaviour
    // ════════════════════════════════════════════════════════════

    var dwellDurationMs: Int
        get() = clamp(prefs?.getInt(KEY_DWELL_DURATION, DWELL_DEFAULT) ?: DWELL_DEFAULT,
            DWELL_MIN, DWELL_MAX)
        set(value) = put { it.putInt(KEY_DWELL_DURATION, clamp(value, DWELL_MIN, DWELL_MAX)) }
    
    var dwellBendDelayMs: Int
        get() = clamp(prefs?.getInt(KEY_DWELL_BEND_DELAY, DWELL_BEND_DELAY_DEFAULT)
            ?: DWELL_BEND_DELAY_DEFAULT, DWELL_BEND_DELAY_MIN, DWELL_BEND_DELAY_MAX)
        set(value) = put {
            it.putInt(KEY_DWELL_BEND_DELAY,
                clamp(value, DWELL_BEND_DELAY_MIN, DWELL_BEND_DELAY_MAX))
        }

    /** When on, dwell only completes if the finger stays below the speed ceiling. */
    var dwellGateEnabled: Boolean
        get() = prefs?.getBoolean(KEY_DWELL_GATE_ENABLED, true) ?: true
        set(value) = put { it.putBoolean(KEY_DWELL_GATE_ENABLED, value) }

    /** Which detector earns the dwell fire: stillness / bend / hybrid. */
    var dwellGateMode: String
        get() = prefs?.getString(KEY_DWELL_GATE_MODE, GATE_MODE_DEFAULT)
            ?: GATE_MODE_DEFAULT
        set(value) = put { it.putString(KEY_DWELL_GATE_MODE, value) }

    /** Stillness ceiling (dp/ms): faster motion restarts the dwell clock. */
    var dwellGateMaxSpeedDpPerMs: Float
        get() = clamp(prefs?.getInt(KEY_DWELL_GATE_SPEED, DWELL_GATE_SPEED_DEFAULT)
            ?: DWELL_GATE_SPEED_DEFAULT,
            DWELL_GATE_SPEED_MIN, DWELL_GATE_SPEED_MAX) / 100f
        set(value) = put {
            it.putInt(KEY_DWELL_GATE_SPEED,
                clamp((value * 100f).toInt(), DWELL_GATE_SPEED_MIN, DWELL_GATE_SPEED_MAX))
        }

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
    
    /** Extra deadzone radius (dp) while the secondary menu is open. */
    var secondaryAbortEaseDp: Float
        get() = clamp(prefs?.getInt(KEY_SECONDARY_ABORT_EASE, SECONDARY_ABORT_EASE_DEFAULT)
            ?: SECONDARY_ABORT_EASE_DEFAULT,
            SECONDARY_ABORT_EASE_MIN, SECONDARY_ABORT_EASE_MAX).toFloat()
        set(value) = put {
            it.putInt(KEY_SECONDARY_ABORT_EASE, clamp(value.toInt(),
                SECONDARY_ABORT_EASE_MIN, SECONDARY_ABORT_EASE_MAX))
        }
    
        /** Master toggle for the retreat-gated abort ease. */
    val secondaryAbortEaseEnabled: Boolean
        get() = prefs?.getBoolean(KEY_SECONDARY_ABORT_EASE_ENABLED, true) ?: true

    var autoSpaceEnabled: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_SPACE, false) ?: false
        set(value) = put { it.putBoolean(KEY_AUTO_SPACE, value) }

    var autoCapitalization: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_CAPITALIZATION, true) ?: true
        set(value) = put { it.putBoolean(KEY_AUTO_CAPITALIZATION, value) }

    // ════════════════════════════════════════════════════════════
    //  Launch feel: onset-weighted exit angle
    // ════════════════════════════════════════════════════════════

    /** Blend travel direction into the deadzone-exit angle so curved
     *  flick launches pick the intended spoke. Retained specifically for
     *  large-deadzone users, where the blind flight before the first
     *  resolved sample is long enough for launch direction to matter. */
    var onsetExitAngleEnabled: Boolean
        get() = prefs?.getBoolean(KEY_ONSET_ENABLED, true) ?: true
        set(value) = put { it.putBoolean(KEY_ONSET_ENABLED, value) }

    /** Position share (%) of the exit-angle blend; remainder is velocity. */
    var onsetPositionWeight: Float
        get() = clamp(prefs?.getInt(KEY_ONSET_POSITION_WEIGHT, ONSET_POSITION_DEFAULT)
            ?: ONSET_POSITION_DEFAULT, ONSET_POSITION_MIN, ONSET_POSITION_MAX) / 100f
        set(value) = put {
            it.putInt(KEY_ONSET_POSITION_WEIGHT,
                clamp((value * 100f).toInt(), ONSET_POSITION_MIN, ONSET_POSITION_MAX))
        }

    /** Look-back window (ms) for the exit-velocity estimate. */
    var onsetVelocityWindowMs: Int
        get() = clamp(prefs?.getInt(KEY_ONSET_VELOCITY_WINDOW, ONSET_WINDOW_DEFAULT)
            ?: ONSET_WINDOW_DEFAULT, ONSET_WINDOW_MIN, ONSET_WINDOW_MAX)
        set(value) = put {
            it.putInt(KEY_ONSET_VELOCITY_WINDOW,
                clamp(value, ONSET_WINDOW_MIN, ONSET_WINDOW_MAX))
        }

    /** Exits slower than this (dp/ms) skip the velocity term. */
    var onsetMinSpeedDpPerMs: Float
        get() = clamp(prefs?.getInt(KEY_ONSET_MIN_SPEED, ONSET_SPEED_DEFAULT)
            ?: ONSET_SPEED_DEFAULT, ONSET_SPEED_MIN, ONSET_SPEED_MAX) / 100f
        set(value) = put {
            it.putInt(KEY_ONSET_MIN_SPEED,
                clamp((value * 100f).toInt(), ONSET_SPEED_MIN, ONSET_SPEED_MAX))
        }

    // ════════════════════════════════════════════════════════════
    //  Launch feel: radial exit projection
    // ════════════════════════════════════════════════════════════

    /** Commit classified where the flick was headed (radial projection). */
    var radprojEnabled: Boolean
        get() = prefs?.getBoolean(KEY_RADPROJ_ENABLED, false) ?: false
        set(value) = put { it.putBoolean(KEY_RADPROJ_ENABLED, value) }

    /** Forward projection horizon (ms) at commit. */
    var radprojHorizonMs: Int
        get() = clamp(prefs?.getInt(KEY_RADPROJ_HORIZON, RADPROJ_HORIZON_DEFAULT)
            ?: RADPROJ_HORIZON_DEFAULT, RADPROJ_HORIZON_MIN, RADPROJ_HORIZON_MAX)
        set(value) = put {
            it.putInt(KEY_RADPROJ_HORIZON,
                clamp(value, RADPROJ_HORIZON_MIN, RADPROJ_HORIZON_MAX))
        }

    /** Cap on how far the projection may move the classified radius (dp). */
    var radprojMaxShiftDp: Float
        get() = clamp(prefs?.getInt(KEY_RADPROJ_MAX_SHIFT, RADPROJ_SHIFT_DEFAULT)
            ?: RADPROJ_SHIFT_DEFAULT, RADPROJ_SHIFT_MIN, RADPROJ_SHIFT_MAX).toFloat()
        set(value) = put {
            it.putInt(KEY_RADPROJ_MAX_SHIFT,
                clamp(value.toInt(), RADPROJ_SHIFT_MIN, RADPROJ_SHIFT_MAX))
        }

    // ════════════════════════════════════════════════════════════
    //  Launch feel: angular hysteresis
    // ════════════════════════════════════════════════════════════

    /** Angular deadzone around segment boundaries (degrees), 0.0–5.0. */
    var segmentHysteresisDeg: Float
        get() = clamp(prefs?.getInt(KEY_SEGMENT_HYSTERESIS, SEGMENT_HYSTERESIS_DEFAULT)
            ?: SEGMENT_HYSTERESIS_DEFAULT,
            SEGMENT_HYSTERESIS_MIN, SEGMENT_HYSTERESIS_MAX) / 10f
        set(value) = put {
            it.putInt(KEY_SEGMENT_HYSTERESIS, (value * 10f).toInt()
                .coerceIn(SEGMENT_HYSTERESIS_MIN, SEGMENT_HYSTERESIS_MAX))
        }

    // ════════════════════════════════════════════════════════════
    //  Delete & cursor
    // ════════════════════════════════════════════════════════════

    /** Characters deleted per millimetre of horizontal swipe in DELETE mode. */
    var deleteCharsPerMm: Float
        get() = clamp(prefs?.getInt(KEY_DELETE_RATE, DELETE_RATE_DEFAULT) ?: DELETE_RATE_DEFAULT,
            DELETE_RATE_MIN, DELETE_RATE_MAX) / 10f
        set(value) = put {
            it.putInt(KEY_DELETE_RATE, (value * 10f).toInt()
                .coerceIn(DELETE_RATE_MIN, DELETE_RATE_MAX))
        }

    /** Travel (dp) before the delete selection first arms. */
    var deleteDeadzoneDp: Float
        get() = (prefs?.getInt(KEY_DELETE_DEADZONE, 6) ?: 6)
            .coerceIn(2, 40).toFloat()
        set(value) = put {
            it.putInt(KEY_DELETE_DEADZONE, value.toInt().coerceIn(2, 40))
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
        get() = (prefs?.getInt(KEY_CURSOR_DEADZONE, CURSOR_DEADZONE_DEFAULT)
            ?: CURSOR_DEADZONE_DEFAULT)
            .coerceIn(CURSOR_DEADZONE_MIN, CURSOR_DEADZONE_MAX).toFloat()
        set(value) = put {
            it.putInt(KEY_CURSOR_DEADZONE, value.toInt()
                .coerceIn(CURSOR_DEADZONE_MIN, CURSOR_DEADZONE_MAX))
        }

    // ════════════════════════════════════════════════════════════
    //  Ring geometry & reach
    // ════════════════════════════════════════════════════════════

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

    fun keyForReach(segment: Int): String = KEY_REACH_PREFIX + segment

    /** Raw percent (REACH_MIN..REACH_MAX) for segment 0..7. */
    fun reachValue(segment: Int): Int {
        val seg = segment.coerceIn(0, 7)
        return clamp(
            prefs?.getInt(keyForReach(seg), REACH_DEFAULT) ?: REACH_DEFAULT,
            REACH_MIN, REACH_MAX
        )
    }

    /**
     * The 8-entry reach profile, each entry in 0.5..1.0, normalized so
     * the MAXIMUM is always 1.0 (sliders express relative compression;
     * overall menu scale stays governed by the outer-radius slider).
     * Defensive: any missing value falls back to full reach.
     */
    val reachProfile: FloatArray
        get() {
            val raw = IntArray(8) { reachValue(it) }
            val max = raw.maxOrNull() ?: REACH_MAX
            return FloatArray(8) { raw[it] / max.toFloat() }
        }

    /** Writes percent values for all 8 directions (values clamped). */
    fun setReachProfile(percentValues: IntArray) {
        put {
            for (i in 0 until 8) {
                it.putInt(keyForReach(i),
                    percentValues.getOrElse(i) { REACH_DEFAULT }.coerceIn(REACH_MIN, REACH_MAX))
            }
        }
    }

    /**
     * Seed profiles for the handedness preset. Right thumb: pivot at
     * bottom-right, so reach is shortest toward the thumb base (E/SE)
     * and longest toward W/NW. Left thumb is the mirror. Circle is
     * the legacy perfect annulus.
     */
    fun presetProfile(presetId: String): IntArray = when (presetId) {
        PRESET_LEFT  -> intArrayOf(100, 90, 80, 70, 65, 78, 90, 100)
        PRESET_CIRCLE -> IntArray(8) { REACH_MAX }
        else          -> intArrayOf(90, 80, 90, 100, 90, 80, 90, 100)
    }

    // ════════════════════════════════════════════════════════════
    //  Language & layout
    // ════════════════════════════════════════════════════════════

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
        get() = clamp(prefs?.getInt(KEY_LANGUAGE_MIX_RATIO, LANGUAGE_MIX_DEFAULT)
            ?: LANGUAGE_MIX_DEFAULT, LANGUAGE_MIX_MIN, LANGUAGE_MIX_MAX)
        set(value) = put {
            it.putInt(KEY_LANGUAGE_MIX_RATIO,
                clamp(value, LANGUAGE_MIX_MIN, LANGUAGE_MIX_MAX))
        }

    /** Primary-language weight as a 0.0–1.0 fraction, for [LayoutArranger.blend]. */
    val languageMixRatio: Float
        get() = languageMixPercent / 100f

    /** Raw JSON for the active layout; empty string = built-in/asset default. */
    var customLayoutJson: String
        get() = prefs?.getString(KEY_CUSTOM_LAYOUT, "") ?: ""
        set(value) = put { it.putString(KEY_CUSTOM_LAYOUT, value) }

    // ════════════════════════════════════════════════════════════
    //  Benchmark
    // ════════════════════════════════════════════════════════════

    val benchRepeatSeed: Boolean
        get() = prefs?.getBoolean(KEY_BENCH_REPEAT_SEED, false) ?: false

    var benchLastSeed: Long
        get() = prefs?.getString(KEY_BENCH_LAST_SEED, null)?.toLongOrNull() ?: 0L
        set(value) { prefs?.edit()?.putString(KEY_BENCH_LAST_SEED, value.toString())?.apply() }

    val benchTrialsPerTarget: Int
        get() = prefs?.getString(KEY_BENCH_TRIALS, null)?.toIntOrNull() ?: 6

    // ════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════

    private inline fun put(block: (SharedPreferences.Editor) -> Unit) {
        prefs?.let { p ->
            val e = p.edit()
            block(e)
            e.apply()
        }
    }

    private fun clamp(v: Int, min: Int, max: Int): Int = v.coerceIn(min, max)
}
