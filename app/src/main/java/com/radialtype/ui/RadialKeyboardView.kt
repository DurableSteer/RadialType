package com.radialtype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.TouchStateMachine
import com.radialtype.engine.TouchStateMachine.TouchState

/**
 * The transparent surface that hosts all radial gesture input.
 *
 * As of Module 5, touch events are delegated to [TouchStateMachine],
 * which integrates with [GeometryEngine] to resolve the finger's ring
 * and segment on every MOVE. The view renders a lightweight debug
 * overlay (ring circles + finger trace line) when [debugMode] is true.
 *
 * Lifecycle: created by [com.radialtype.RadialTypeIME.onCreateInputView],
 * which returns this view to the framework.
 *
 * @param context The InputMethodService context, passed through from
 *                RadialTypeIME.
 */
class RadialKeyboardView(
    context: Context
) : View(context) {

    companion object {
        private const val TAG = "RadialKeyboardView"
    }

    /**
     * When true, [onDraw] paints a faint overlay plus ring boundaries
     * and a finger-trace line for visual debugging.
     * Set to false for production (fully transparent).
     */
    var debugMode: Boolean = true

    /**
     * Touch state machine that processes all gesture events.
     * GeometryEngine is injected and density is supplied so that
     * px→dp conversion happens inside the state machine.
     */
    private val touchStateMachine = TouchStateMachine(
        geometryEngine = GeometryEngine(),
        density = context.resources.displayMetrics.density
    ).apply {
        onStateChanged = { newState ->
            Log.d(TAG, "State → $newState")
            invalidate()
        }
        onPositionChanged = {
            invalidate()
        }
        onCommit = {
            Log.d(TAG, "Commit (seg=$currentSegment ring=$currentRing)")
        }
        onRingChanged = { ring ->
            Log.d(TAG, "Ring changed → $ring")
        }
        onSegmentChanged = { segment ->
            Log.d(TAG, "Segment changed → $segment")
        }
    }

    // ── Exposed getters (for future modules / debugging) ─────────

    val state: TouchState get() = touchStateMachine.state
    val anchorX: Float get() = touchStateMachine.anchorX
    val anchorY: Float get() = touchStateMachine.anchorY
    val currentX: Float get() = touchStateMachine.currentX
    val currentY: Float get() = touchStateMachine.currentY

    /** Reusable paint for debug drawing. */
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        // Fully transparent — the host app shows through.
        setBackgroundColor(Color.TRANSPARENT)

        // Delegate every touch event to the state machine.
        setOnTouchListener { _, event ->
            touchStateMachine.onTouchEvent(event)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!debugMode) return

        // Faint background tint to confirm the view bounds.
        canvas.drawColor(Color.argb(10, 109, 74, 255))

        if (touchStateMachine.state == TouchState.IDLE) return

        // ── Ring boundaries (dp → px) ──────────────────────────────
        val d = resources.displayMetrics.density

        debugPaint.color = Color.argb(60, 255, 255, 255)
        debugPaint.strokeWidth = 1f

        // Inner ring boundary
        canvas.drawCircle(anchorX, anchorY, GeometryEngine.INNER_RADIUS_MAX * d, debugPaint)
        // Outer ring boundary
        canvas.drawCircle(anchorX, anchorY, GeometryEngine.OUTER_RADIUS_MAX * d, debugPaint)
        // Secondary ring boundary (dashed-style: thinner)
        debugPaint.color = Color.argb(40, 255, 200, 0)
        canvas.drawCircle(anchorX, anchorY, TouchStateMachine.SECONDARY_RING_MAX * d, debugPaint)

        // ── Finger trace line ─────────────────────────────────────
        debugPaint.color = Color.argb(180, 109, 74, 255) // Proton purple
        debugPaint.strokeWidth = 3f
        canvas.drawLine(anchorX, anchorY, currentX, currentY, debugPaint)

        // ── Anchor dot ────────────────────────────────────────────
        debugPaint.style = Paint.Style.FILL
        canvas.drawCircle(anchorX, anchorY, 5f, debugPaint)

        // ── Current finger dot ────────────────────────────────────
        debugPaint.color = Color.argb(220, 255, 255, 255)
        canvas.drawCircle(currentX, currentY, 5f, debugPaint)

        // Reset for next pass
        debugPaint.style = Paint.Style.STROKE
    }
}
