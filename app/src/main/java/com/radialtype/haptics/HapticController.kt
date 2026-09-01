package com.radialtype.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.radialtype.settings.SettingsManager

/**
 * Module 13 — Three independently toggleable haptic events:
 * - [pulseDeadzoneExit]: finger leaves the deadzone (ring NONE → INNER/OUTER).
 * - [pulseSecondaryEnter]: PRIMARY → SECONDARY transition.
 * - [pulseSecondaryRingOut]: secondary INNER → OUTER ring transition.
 *
 * The master [SettingsManager.hapticsEnabled] switch gates all pulses.
 * Each event also has its own sub-toggle. Vibration duration comes from
 * [SettingsManager.vibrationLengthMs].
 */
class HapticController(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun pulse() {
        if (!SettingsManager.hapticsEnabled) return
        val ms = SettingsManager.vibrationLengthMs.toLong()
        if (ms <= 0) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    fun pulseDeadzoneExit() {
        if (!SettingsManager.hapticDeadzoneExit) return
        pulse()
    }

    fun pulseSecondaryEnter() {
        if (!SettingsManager.hapticSecondaryEnter) return
        pulse()
    }

    fun pulseSecondaryRingOut() {
        if (!SettingsManager.hapticSecondaryRingOut) return
        pulse()
    }
}
