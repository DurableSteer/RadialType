package com.radialtype.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Central haptic feedback provider for RadialType.
 *
 * Produces three physically distinct sensations:
 * - [pulseSegmentChange]  — very short tick (~20 ms) when moving between
 *                           segments within a ring.
 * - [pulseRingChange]     — medium pulse (~40 ms) when moving between the
 *                           inner and outer rings.
 * - [pulseModeChange]     — longer double-buzz pattern [0, 60, 30, 60] when
 *                           transitioning between PRIMARY and SECONDARY.
 *
 * API-level handling:
 * - API 31+ (S): `VibratorManager` is the canonical source of the default
 *   vibrator; `getSystemService(VIBRATOR_SERVICE)` is deprecated there.
 * - API 26–30: classic `Vibrator` lookup.
 *
 * Everything is a no-op when disabled via [setEnabled] or when the device
 * has no vibrator, so callers never need to null-check.
 *
 * @param context Any context (the IME service context works fine).
 */
class HapticController(context: Context) {

    companion object {
        /** Short tick for segment movement within a ring. */
        private const val SEGMENT_TICK_MS = 20L

        /** Medium pulse for ring transitions. */
        private const val RING_PULSE_MS = 40L

        /**
         * Mode-change pattern: wait, buzz 60 ms, pause 30 ms, buzz 60 ms.
         * The double-hit makes it unmistakably different from the single
         * ring pulse despite comparable total duration.
         */
        private val MODE_CHANGE_PATTERN = longArrayOf(0, 60, 30, 60)
    }

    private val vibrator: Vibrator? = acquireVibrator(context)

    /** Master switch, wired to the settings toggle. */
    @Volatile
    var isEnabled: Boolean = true
        private set

    /** Enables or disables all haptic output. */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    /**
     * Very short tick when the finger crosses a segment boundary.
     *
     * If a [View] is supplied, prefers `performHapticFeedback(KEYBOARD_TAP)`,
     * which respects the system "touch feedback" setting and needs no
     * vibration permission. Falls back to a ~20 ms one-shot otherwise.
     */
    fun pulseSegmentChange(view: View? = null) {
      if (!isEnabled) return
        if (view != null && view.isHapticFeedbackEnabled) {
          view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
          return
        }
      vibrateOneShot(SEGMENT_TICK_MS)
    }

    /** Medium pulse when the finger moves between rings. */
    fun pulseRingChange() {
        if (!isEnabled) return
        vibrateOneShot(RING_PULSE_MS)
    }

    /**
     * Longer double-buzz when transitioning between PRIMARY and SECONDARY
     * (either direction). Uses the pattern form for a distinctly heavier feel.
     */
    fun pulseModeChange() {
        if (!isEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(MODE_CHANGE_PATTERN, -1))
    }

    // ── Internals ────────────────────────────────────────────────

    private fun vibrateOneShot(durationMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * Resolves the default vibrator across API levels:
     * VibratorManager on API 31+, plain Vibrator below.
     */
    private fun acquireVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
