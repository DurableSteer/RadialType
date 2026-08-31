package com.radialtype.ui

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.TouchStateMachine
import com.radialtype.engine.TouchStateMachine.TouchState
import com.radialtype.haptics.HapticController
import com.radialtype.text.CharacterMap
import com.radialtype.text.SelectionTracker
import com.radialtype.text.SyllableProvider

/**
 * The small, touchable pad that starts radial gestures.
 *
 * This view lives in its own small WindowManager window, sized exactly to
 * the ergonomic first-touch ovoid's bounding box. ACTION_DOWN must land
 * inside the ovoid (ellipse test inside the window rect); once a gesture
 * begins, Android delivers every subsequent MOVE / UP to this view no
 * matter where the finger travels — the radial geometry is anchored to
 * the touch-down point, not the pad bounds.
 *
 * All rendering happens on the separate fullscreen [RadialOverlayView]:
 * on every frame this pad builds a [RadialRenderData] with coordinates
 * translated to screen space and pushes it via [onRenderFrame].
 */
class RadialKeyboardView(
    context: Context
) : View(context) {

    companion object {
        private const val TAG = "RadialKeyboardView"
    }

    /** Pushes a render snapshot to the fullscreen overlay, every frame. */
    var onRenderFrame: ((RadialRenderData) -> Unit)? = null

    /** Wired by the IME to commit text into the focused field. */
    var onCommitText: ((String) -> Unit)? = null

    val haptics = HapticController(context)

    private var lastHapticState: TouchState = TouchState.IDLE

    private val characterMap = CharacterMap(context)
    private val syllableProvider = SyllableProvider(context)
    val selectionTracker = SelectionTracker(characterMap, syllableProvider)

    // Ovoid semi-axes in px, set by the controller.
    private var zoneRX = 0f
    private var zoneRY = 0f
    private var zoneReady = false

    /** Screen-space position of this pad's top-left corner. */
    var screenOffsetX: Float = 0f
    var screenOffsetY: Float = 0f
    
    // Dashed ovoid hint, drawn inside the pad window itself so it is
    // visible at idle without needing the fullscreen overlay.
    private val zoneHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x597AA2F7.toInt()   // Tokyo Night cyan, translucent
        pathEffect = DashPathEffect(
            floatArrayOf(8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density), 0f
        )
    }

    // ── Exposed getters ──────────────────────────────────────────

    val state: TouchState get() = touchStateMachine.state
    val anchorX: Float get() = touchStateMachine.anchorX
    val anchorY: Float get() = touchStateMachine.anchorY
    val currentX: Float get() = touchStateMachine.currentX
    val currentY: Float get() = touchStateMachine.currentY

    /** Semi-axes of the activation ovoid in px (controller sets these). */
    fun configureZone(rxPx: Float, ryPx: Float) {
        zoneRX = rxPx
        zoneRY = ryPx
        zoneReady = true
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!zoneReady) return
        // The pad window equals the ovoid's bounding box, so the ellipse
        // fills the view. A slight inset keeps the stroke inside bounds.
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
            Log.d(TAG, "State → $newState")
            val relevant = (lastHapticState == TouchState.PRIMARY && newState == TouchState.SECONDARY) ||
                           (lastHapticState == TouchState.SECONDARY && newState == TouchState.PRIMARY)
            if (relevant) haptics.pulseModeChange()
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
            if (label.isNotEmpty()) onCommitText?.invoke(label)
        }
        onRingChanged = { ring ->
            Log.d(TAG, "Ring changed → $ring")
            val prev = previousRing
            if (prev != GeometryEngine.Ring.NONE && ring != GeometryEngine.Ring.NONE) {
                haptics.pulseRingChange()
            }
        }
        onSegmentChanged = { segment ->
            Log.d(TAG, "Segment changed → $segment")
            haptics.pulseSegmentChange(this@RadialKeyboardView)
            selectionTracker.update(currentRing, currentSegment)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    // ── Touch handling ───────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && zoneReady) {
            val dx = event.x - width / 2f
            val dy = event.y - height / 2f
            val nx = dx / zoneRX
            val ny = dy / zoneRY
            if (nx * nx + ny * ny > 1f) return false
        }
        return touchStateMachine.onTouchEvent(event)
    }

    // ── Frame push to the fullscreen overlay ─────────────────────

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
