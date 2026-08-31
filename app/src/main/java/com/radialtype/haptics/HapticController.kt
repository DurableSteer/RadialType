package com.radialtype.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.radialtype.settings.SettingsManager

/**
 * Central haptic feedback provider for RadialType.
 *
 * Exactly TWO pulses exist in the entire interaction:
 *
 * 1. [pulseSecondaryEnter] — fires when PRIMARY → SECONDARY (dwell
 *    completes and the syllable menu opens).
 * 2. [pulseSecondaryRingOut] — fires on the secondary menu only,
 *    when the finger crosses from the INNER ring to the OUTER ring.
 *
 * Both pulse durations come from the "Vibration length" setting.
 * The master haptics toggle gates everything.
 */
class HapticController(context: Context) {

    private val vibrator: Vibrator? = acquireVibrator(context)

    @Volatile
    var isEnabled: Boolean = true
        private set

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    private val effectiveEnabled: Boolean
        get() = isEnabled &&
            (!SettingsManager.isInitialized || SettingsManager.hapticsEnabled)

    /** Fires when the dwell timer fires and PRIMARY → SECONDARY. */
    fun pulseSecondaryEnter() {
        if (!effectiveEnabled) return
        oneShot(SettingsManager.vibrationLengthMs.toLong())
    }

    /**
     * Fires when, on the SECONDARY menu, the finger moves from
     * INNER to OUTER. Does NOT fire on the primary menu.
     */
    fun pulseSecondaryRingOut() {
        if (!effectiveEnabled) return
        oneShot(SettingsManager.vibrationLengthMs.toLong())
    }

    // ── Internals ────────────────────────────────────────────────

    private fun oneShot(durationMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

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
