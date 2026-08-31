package com.radialtype.ui

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import com.radialtype.text.CharacterMap
import com.radialtype.text.InputDispatcher
import com.radialtype.text.SyllableProvider
import com.radialtype.settings.SettingsManager

/**
 * Owns the two-window setup: a small touchable pad and a lazy fullscreen
 * render layer. Module 11 adds the InputDispatcher; Module 12 ensures
 * SettingsManager is initialized so runtime settings work immediately.
 */
class RadialOverlayController(
    private val context: Context,
    inputConnectionProvider: () -> android.view.inputmethod.InputConnection?
) {

    companion object {
        private const val ZONE_CENTER_X_FROM_RIGHT = 0.45f
        private const val ZONE_CENTER_Y_FROM_BOTTOM = 0.33f
        private const val ZONE_WIDTH_CM = 3.0f
        private const val ZONE_HEIGHT_CM = 4.5f
    }

    private val wm =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val characterMap = CharacterMap(context)
    private val syllableProvider = SyllableProvider(context)

    val renderer = RadialRenderer(context, characterMap, syllableProvider)

    private val inputDispatcher = InputDispatcher(inputConnectionProvider)

    private val overlayView = RadialOverlayView(context, renderer)
    val padView = RadialKeyboardView(context)

    private var padAdded = false
    private var overlayAdded = false

    init {
        // Ensure SettingsManager has a context for SharedPreferences reads
        if (!SettingsManager.isInitialized) {
            SettingsManager.init(context)
        }
        padView.inputDispatcher = inputDispatcher
        padView.onRenderFrame = { data -> onFrame(data) }
        renderer.debugMode = true
    }

    private fun onFrame(data: RadialRenderData) {
        val active = data.state != com.radialtype.engine.TouchStateMachine.TouchState.IDLE
        if (active && !overlayAdded) addOverlayWindow()
        overlayView.setData(data)
        if (data.state == com.radialtype.engine.TouchStateMachine.TouchState.IDLE && overlayAdded) {
            removeOverlayWindow()
        }
    }

    fun show() {
        if (padAdded) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w("RadialOverlay", "Overlay permission missing")
            return
        }

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()

        val rxPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_MM, ZONE_WIDTH_CM * 10f / 2f, dm
        )
        val ryPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_MM, ZONE_HEIGHT_CM * 10f / 2f, dm
        )

        val zoneCX = screenW * (1f - ZONE_CENTER_X_FROM_RIGHT)
        val zoneCY = screenH * (1f - ZONE_CENTER_Y_FROM_BOTTOM)

        renderer.setTouchZone(zoneCX, zoneCY, rxPx, ryPx)

        padView.configureZone(rxPx, ryPx)
        padView.screenOffsetX = zoneCX - rxPx
        padView.screenOffsetY = zoneCY - ryPx

        val padParams = WindowManager.LayoutParams(
            (rxPx * 2f).toInt(),
            (ryPx * 2f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (zoneCX - rxPx).toInt()
            y = (zoneCY - ryPx).toInt()
        }
        wm.addView(padView, padParams)
        padAdded = true
    }

    private fun addOverlayWindow() {
        if (overlayAdded) return
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(overlayView, overlayParams)
        overlayAdded = true
    }

    private fun removeOverlayWindow() {
        if (!overlayAdded) return
        runCatching { wm.removeView(overlayView) }
        overlayAdded = false
    }

    fun hide() {
        removeOverlayWindow()
        if (padAdded) {
            runCatching { wm.removeView(padView) }
            padAdded = false
        }
    }

    fun release() = hide()
}
