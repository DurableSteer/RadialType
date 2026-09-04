package com.radialtype.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import com.radialtype.settings.SettingsManager

/**
 * Module 13 / Package 1 — Central haptic dispatch.
 *
 * Every pulse is described by a [Profile] (duration + base amplitude) and
 * rendered through a single path that honours the configured style:
 * - LEGACY: custom one-shot waves, scaled by the global intensity slider.
 * - SYSTEM (SDK 29+): OEM predefined effects performed on [hostView];
 *   any refusal or missing view falls back to LEGACY rendering.
 *
 * Event catalogue: deadzoneExit, secondaryEnter, secondaryRingOut,
 * deleteTick (+ its progressive crescendo). Gating is per-event with the
 * master switch on top.
 */
class HapticController(private val context: Context) {

    private data class Profile(val durationMs: Long, val amplitude: Int)

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Set by RadialOverlayController — enables the system-effect path. */
    var hostView: android.view.View? = null

    /** Progressive-tick crescendo counter; reset on selection collapse. */
    private var tickStep = 0

    // ── Rendering core ───────────────────────────────────────────

    /** Global intensity slider as a multiplicative scale (0.08–1.0). */
    private fun intensityScale(): Float =
        SettingsManager.hapticIntensity / 255f

    private fun oneShot(durationMs: Long, amplitude: Int) {
        if (durationMs <= 0) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (amplitudeCapable) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, MAX_AMPLITUDE)))
            } else {
                // Binary actuator: play at full effect, encode the requested
                // strength in DURATION instead — intensity slider scales time.
                val scaled = (durationMs * intensityScale()).toLong().coerceIn(8L, durationMs * 2)
                v.vibrate(VibrationEffect.createOneShot(scaled, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    /**
     * Renders one pulse honouring the configured style. System styles
     * route through the host view's haptic engine (OEM-calibrated,
     * SDK 29+); any miss falls back to the legacy one-shot scaled by the
     * intensity slider.
     */
    private fun render(profile: Profile, systemConstant: Int) {
        if (!SettingsManager.hapticsEnabled) return

        val style = SettingsManager.hapticStyle
        if (style != SettingsManager.HAPTIC_STYLE_LEGACY &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val view = hostView
            val ok = view != null && runCatching {
                view.performHapticFeedback(systemConstant)
            }.getOrDefault(false)
            if (ok) return
            // Fall through to legacy rendering on any refusal.
        }

        oneShot(profile.durationMs,
                (profile.amplitude * intensityScale()).toInt())
    }

    private fun systemConstantFor(style: String): Int = when (style) {
        SettingsManager.HAPTIC_STYLE_HEAVY ->
            HapticFeedbackConstants.CONFIRM          // firm, deliberate
        else -> HapticFeedbackConstants.VIRTUAL_KEY  // crisp, light
    }
    
    private val amplitudeCapable: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (vibrator?.hasAmplitudeControl() == true)
    }

    // Public event API
    private fun ringPulse() {
        val style = SettingsManager.hapticStyle
        render(
            Profile(SettingsManager.vibrationLengthMs.toLong(), MAX_AMPLITUDE),
            systemConstantFor(style)
        )
    }

    fun pulseDeadzoneExit() {
        if (!SettingsManager.hapticDeadzoneExit) return
        render(ringProfile(), systemConstant())
    }

    fun pulseSecondaryEnter() {
        if (!SettingsManager.hapticSecondaryEnter) return
        render(ringProfile(), systemConstant())
    }

    fun pulseSecondaryRingOut() {
        if (!SettingsManager.hapticSecondaryRingOut) return
        render(ringProfile(), systemConstant())
    }
    
    /**
     * The finger entered a cell that has content — the "richer" navigation
     * tick. Rendered like the ring pulses (style-aware, intensity-scaled)
     * but shorter, so it reads as a tap on a thing rather than a ring event.
     */
    fun pulseLabelTouch() {
        if (!SettingsManager.hapticLabelTouch) return
        render(Profile(LABEL_TOUCH_MS, MAX_AMPLITUDE), systemConstant())
    }

    /** Light click when the finger crosses the inner/outer ring boundary. */
    fun pulseRingCross() {
        if (!SettingsManager.hapticRingCross) return
        render(Profile(SEPARATOR_CROSS_MS, SEPARATOR_AMPLITUDE), systemConstant())
    }

    fun pulseDeleteTick() {
        if (!SettingsManager.hapticDeleteTick) return
        if (!SettingsManager.hapticsEnabled) return
        val base = SettingsManager.hapticTickIntensity
        val step = if (SettingsManager.hapticProgressiveTicks) {
            tickStep++
            tickStep * STEP_BOOST
        } else 0
        val amplitude = (base + step).coerceIn(1, MAX_AMPLITUDE)
        render(Profile(DELETE_TICK_MS, amplitude), systemConstant())
    }

    fun resetDeleteTicks() { tickStep = 0 }

    // ── Aliases used above; keep exactly one definition each ─────
    // (The three spellings below are the SAME functions — during the
    // merge above they were referenced under inconsistent names.
    // Keeping one canonical set avoids the Module 13 copy-paste trap.)

    private fun ringProfile() = Profile(
        SettingsManager.vibrationLengthMs.toLong(), MAX_AMPLITUDE
    )

    private fun systemConstant(): Int = when (SettingsManager.hapticStyle) {
        SettingsManager.HAPTIC_STYLE_HEAVY -> HapticFeedbackConstants.CONFIRM
        else -> HapticFeedbackConstants.VIRTUAL_KEY
    }

    companion object {
        private const val DELETE_TICK_MS = 18L
        private const val MAX_AMPLITUDE = 255
        private const val STEP_BOOST = 24
        private const val LABEL_TOUCH_MS = 24L
        private const val SEPARATOR_CROSS_MS = 10L
        private const val SEPARATOR_AMPLITUDE = 128   // half strength vs label touch
    }
}
