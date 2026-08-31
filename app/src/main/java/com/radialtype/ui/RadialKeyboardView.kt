package com.radialtype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.TouchStateMachine
import com.radialtype.engine.TouchStateMachine.TouchState
import com.radialtype.haptics.HapticController
import com.radialtype.settings.SettingsManager
import com.radialtype.text.CharacterMap
import com.radialtype.text.InputDispatcher
import com.radialtype.text.SelectionTracker
import com.radialtype.text.SyllableProvider

/**
 * The small, touchable pad that starts radial gestures.
 *
 * Haptic feedback (Module 13 redesign):
 * - PRIMARY → SECONDARY state change: [HapticController.pulseSecondaryEnter]
 * - INNER → OUTER ring change WHILE in SECONDARY: [HapticController.pulseSecondaryRingOut]
 * - No other events produce haptics.
 */
class RadialKeyboardView(
    context: Context
) : View(context) {

    companion object {
        private const val TAG = "RadialKeyboardView"
    }

    var onRenderFrame: ((RadialRenderData) -> Unit)? = null
    var inputDispatcher: InputDispatcher? = null

    val haptics = HapticController(context)

    private var lastHapticState: TouchState = TouchState.IDLE

    private val characterMap = CharacterMap(context)
    private val syllableProvider = SyllableProvider(context)
    val selectionTracker = SelectionTracker(characterMap, syllableProvider)

    private var zoneRX = 0f
    private var zoneRY = 0f
    private var zoneReady = false

    var screenOffsetX: Float = 0f
    var screenOffsetY: Float = 0f

    private val zoneHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x597AA2F7.toInt()
        pathEffect = DashPathEffect(
            floatArrayOf(8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density), 0f
        )
    }

    val state: TouchState get() = touchStateMachine.state
    val anchorX: Float get() = touchStateMachine.anchorX
    val anchorY: Float get() = touchStateMachine.anchorY
    val currentX: Float get() = touchStateMachine.currentX
    val currentY: Float get() = touchStateMachine.currentY

    fun configureZone(rxPx: Float, ryPx: Float) {
        zoneRX = rxPx
        zoneRY = ryPx
        zoneReady = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!zoneReady) return
        val inset = 2f
        canvas.drawOval(
            inset, inset,
            width - inset, height - inset,
            zoneHintPaint
        )
    }

    private val touchStateMachine: TouchStateMachine = TouchStateMachine(
        geometryEngine = GeometryEngine(),
        density = context.resources.displayMetrics.density
    ).apply {
        onStateChanged = { newState ->
            Log.d(TAG, "State -> $newState")

            // Haptic 1 of 2: PRIMARY → SECONDARY
            if (lastHapticState == TouchState.PRIMARY && newState == TouchState.SECONDARY) {
                haptics.pulseSecondaryEnter()
            }
            lastHapticState = newState

            selectionTracker.update(currentRing, currentSegment)
            selectionTracker.updateState(newState)
            pushFrame()
        }
        onPositionChanged = {
            selectionTracker.update(currentRing, currentSegment)
            pushFrame()
        }
        onCommit = {
            val label = selectionTracker.currentLabel()
            Log.d(TAG, "Commit (seg=$currentSegment ring=$currentRing label=$label)")
            if (label.isNotEmpty()) inputDispatcher?.commit(label)
        }
        onRingChanged = { ring ->
            Log.d(TAG, "Ring changed -> $ring")

            // Haptic 2 of 2: INNER → OUTER on the SECONDARY menu only
            if (state == TouchState.SECONDARY &&
                previousRing == GeometryEngine.Ring.INNER &&
                ring == GeometryEngine.Ring.OUTER
            ) {
                haptics.pulseSecondaryRingOut()
            }
        }
        onSegmentChanged = { segment ->
            Log.d(TAG, "Segment changed -> $segment")
            // No haptic on segment changes
            selectionTracker.update(currentRing, currentSegment)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && zoneReady) {
            val dx = event.x - width / 2f
            val dy = event.y - height / 2f
            val nx = dx / zoneRX
            val ny = dy / zoneRY
            if (nx * nx + ny * ny > 1f) return false
            // Refresh all settings (radii + dwell) at gesture start
            touchStateMachine.refreshFromSettings()
        }
        return touchStateMachine.onTouchEvent(event)
    }

    fun pushFrame() {
        onRenderFrame?.invoke(
            RadialRenderData(
                state = touchStateMachine.state,
                anchorX = touchStateMachine.anchorX + screenOffsetX,
                anchorY = touchStateMachine.anchorY + screenOffsetY,
                secondaryAnchorX = touchStateMachine.secondaryAnchorX + screenOffsetX,
                secondaryAnchorY = touchStateMachine.secondaryAnchorY + screenOffsetY,
                currentX = touchStateMachine.currentX + screenOffsetX,
                currentY = touchStateMachine.currentY + screenOffsetY,
                ring = selectionTracker.currentRing,
                segment = selectionTracker.currentSegment,
                primaryChar = selectionTracker.currentPrimaryChar,
                label = selectionTracker.currentLabel()
            )
        )
    }
}
