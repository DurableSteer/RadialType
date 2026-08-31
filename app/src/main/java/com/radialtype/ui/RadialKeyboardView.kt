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
import com.radialtype.haptics.HapticController
import com.radialtype.text.CharacterMap
import com.radialtype.text.SelectionTracker
import com.radialtype.text.SyllableProvider

/**
 * The transparent surface that hosts all radial gesture input.
 *
 * Touch events are delegated to [TouchStateMachine], which integrates
 * with [GeometryEngine] to resolve the finger's ring and segment on
 * every MOVE (Module 5) and drives dwell-triggered secondary menus
 * (Module 7). The resolved selection is tracked by [selectionTracker]
 * (Modules 8–9) and exposed via [currentLabel] for the renderer
 * (Module 10). The view renders a lightweight debug overlay (ring
 * circles + finger trace + current character label) when [debugMode]
 * is true.
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

    var debugMode: Boolean = true

    /**
     * Haptics provider (Module 6). Toggle via [haptics.setEnabled].
     */
    val haptics = HapticController(context)

    /** Tracks the previous state so we only buzz on PRIMARY ↔ SECONDARY. */
    private var lastHapticState: TouchState = TouchState.IDLE

    /**
     * Character layout + selection state (Modules 8 & 9). The view keeps
     * these in sync with the state machine and queries the label on
     * every invalidate for the renderer.
     */
    private val characterMap = CharacterMap(context)
    private val syllableProvider = SyllableProvider(context)
    val selectionTracker = SelectionTracker(characterMap, syllableProvider)

    private val touchStateMachine: TouchStateMachine = TouchStateMachine(
        geometryEngine = GeometryEngine(),
        density = context.resources.displayMetrics.density
    ).apply {
        onStateChanged = { newState ->
            Log.d(TAG, "State → $newState")
            // Mode-change haptic: only for PRIMARY ↔ SECONDARY transitions
            // (either direction). PRIMARY→IDLE and IDLE→PRIMARY are silent —
            // the segment/ring tick already covers finger-down feedback.
            val relevant = (lastHapticState == TouchState.PRIMARY && newState == TouchState.SECONDARY) ||
                           (lastHapticState == TouchState.SECONDARY && newState == TouchState.PRIMARY)
            if (relevant) haptics.pulseModeChange()
            lastHapticState = newState

            // Keep the selection tracker in sync with the state machine.
            // The order matters: geometry first (so a fresh DOWN resets
            // ring/segment to NONE/−1), then the state transition.
            selectionTracker.update(currentRing, currentSegment)
            selectionTracker.updateState(newState)
            invalidate()
        }
        onPositionChanged = {
            // Mirror the machine's resolved ring/segment into the tracker
            // on every MOVE so the label is always current.
            selectionTracker.update(currentRing, currentSegment)
            invalidate()
        }
        onCommit = {
            Log.d(TAG, "Commit (seg=$currentSegment ring=$currentRing label=${selectionTracker.currentLabel()})")
        }
        onRingChanged = { ring ->
            Log.d(TAG, "Ring changed → $ring")
            // Only pulse on real INNER ↔ OUTER crossings, not on entering
            // or leaving the keyboard area (NONE).
            val prev = previousRing
            if (prev != GeometryEngine.Ring.NONE && ring != GeometryEngine.Ring.NONE) {
                haptics.pulseRingChange()
            }
        }
        onSegmentChanged = { segment ->
            Log.d(TAG, "Segment changed → $segment")
            haptics.pulseSegmentChange(this@RadialKeyboardView)
            // Segment changes are already reflected on the next
            // onPositionChanged, but sync eagerly so the label never
            // lags a frame behind the haptic tick.
            selectionTracker.update(currentRing, currentSegment)
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

    /** Label paint for the debug character readout (Module 10 replaces this). */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 48f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
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
        // ── Debug overlay ─────────────────────────────────────────
        if (debugMode) {
            // Faint background tint to confirm the view bounds.
            canvas.drawColor(Color.argb(10, 109, 74, 255))

            if (touchStateMachine.state != TouchState.IDLE) {
                drawDebugOverlay(canvas)
            }
        }

        // ── Character label (Module 10 renderer consumes this) ────
        val label = selectionTracker.currentLabel()
        if (label.isNotEmpty()) {
            labelPaint.color = Color.argb(230, 255, 255, 255)
            canvas.drawText(label, currentX, currentY - 160f, labelPaint)
        }
    }

    private fun drawDebugOverlay(canvas: Canvas) {
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
