package com.radialtype.engine

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.settings.SettingsManager

/**
 * Finite state machine for RadialType's single-gesture touch tracking.
 *
 * States: IDLE, PRIMARY, SECONDARY, DELETE.
 *
 * ── Normal input (PRIMARY / SECONDARY) ──────────────────────────
 * Identical geometry in both states: [GeometryEngine.computeRing] and
 * [computeSegment] centred on the touch-down point (PRIMARY) or the
 * dwell point (SECONDARY). Dwell (PRIMARY → SECONDARY) is suppressed
 * in the deadzone. Overshoot beyond the outer edge clamps to OUTER.
 *
 * ── DELETE state ────────────────────────────────────────────────
 * Entry paths:
 * 1. Double-tap on the deadzone (arming rule A): ANY gesture that ends
 *    while the ring is NONE arms the detector. If the next DOWN arrives
 *    within SettingsManager.doubleTapDeadzoneMs, DELETE starts on that
 *    DOWN and the DOWN's position becomes the delete anchor.
 * 2. [enterDeleteFromKey]: called by the view layer when the finger is
 *    resting on a DEL key. The current position becomes the anchor.
 *
 * While in DELETE: horizontal displacement from the delete anchor
 * selects characters. Leftward drag → chars before the cursor
 * ([deleteLeftCount]); rightward drag → chars after the cursor
 * ([deleteRightCount]). One character per DELETE_CHAR_STEP_DP.
 * ACTION_UP fires [onDeleteCommit] with the final counts; ACTION_CANCEL
 * aborts via [onDeleteCancelled] with nothing deleted.
 *
 * In DELETE, ring/segment geometry and the dwell timer are suspended.
 */
class TouchStateMachine(
    private val geometryEngine: GeometryEngine = GeometryEngine(),
    var density: Float = 1f,
    var dwellDurationMs: Long = DEFAULT_DWELL_MS,
    dwellTimerOverride: DwellTimer? = null
) {

    companion object {
        private const val TAG = "TouchStateMachine"

        const val SEGMENT_SUPPRESSION_MS = 50L

        /** Default dwell duration before PRIMARY → SECONDARY. */
        const val DEFAULT_DWELL_MS = 125L

        /** dp of horizontal travel per selected character in DELETE mode. */
        const val DELETE_CHAR_STEP_DP = 12f

        /** Fallback double-tap window when SettingsManager is uninitialized. */
        const val DOUBLE_TAP_FALLBACK_MS = 300L
        
        /** dp per millimetre on Android (160dp per inch / 25.4 mm). */
        const val DP_PER_MM = 160f / 25.4f

        /** Default characters deleted per mm of swipe (fallback). */
        const val DELETE_CHARS_PER_MM_DEFAULT = 2f
        
                /** |dx| (dp) required to FIRST enter a delete selection. */
        const val DELETE_ARM_THRESHOLD_DP = 6f

        /** |dx| below which an ACTIVE selection is released back to zero. */
        const val DELETE_DISARM_THRESHOLD_DP = 2f
    }

    val dwellTimer: DwellTimer = dwellTimerOverride
        ?: DwellTimer(
            dwellDurationMs = dwellDurationMs,
            handler = Handler(Looper.getMainLooper()),
            callback = { enterSecondary() }
        )

    // ── Gesture state ────────────────────────────────────────────

    var state: TouchState = TouchState.IDLE
        private set

    var anchorX: Float = 0f
        private set

    var anchorY: Float = 0f
        private set

    var currentX: Float = 0f
        private set

    var currentY: Float = 0f
        private set

    var currentRing: Ring = Ring.NONE
        private set

    var currentSegment: Int = -1
        private set

    var previousRing: Ring = Ring.NONE
        private set

    var previousSegment: Int = -1
        private set

    private var lastRingChangeTime: Long = 0L

    var secondaryAnchorX: Float = 0f
        private set

    var secondaryAnchorY: Float = 0f
        private set

    // ── Delete gesture state ─────────────────────────────────────

    /** Characters currently selected LEFT of the cursor (pending delete). */
    var deleteLeftCount: Int = 0
        private set

    /** Characters currently selected RIGHT of the cursor (pending delete). */
    var deleteRightCount: Int = 0
        private set

    /** Timestamp of the last ACTION_UP (double-tap detection). */
    private var lastUpTimestamp: Long = 0L

    /** True when the previous gesture ended with the ring in NONE (rule A). */
    private var lastUpInDeadzone: Boolean = false

    // ── Callbacks ───────────────────────────────────────────────

    var onStateChanged: ((TouchState) -> Unit)? = null
    var onPositionChanged: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onRingChanged: ((Ring) -> Unit)? = null
    var onSegmentChanged: ((Int) -> Unit)? = null

    /** Fired on every drag in DELETE: (charsLeft, charsRight). */
    var onDeleteProgress: ((left: Int, right: Int) -> Unit)? = null

    /** Fired on ACTION_UP in DELETE: remove the selected range. */
    var onDeleteCommit: ((left: Int, right: Int) -> Unit)? = null

    /** Fired when a DELETE gesture is aborted (ACTION_CANCEL). */
    var onDeleteCancelled: (() -> Unit)? = null

    // ── Public API ───────────────────────────────────────────────

    fun refreshFromSettings() {
        geometryEngine.refreshFromSettings()
        dwellDurationMs = SettingsManager.dwellDurationMs.toLong()
        dwellTimer.dwellDurationMs = dwellDurationMs
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN   -> handleDown(event)
            MotionEvent.ACTION_MOVE   -> handleMove(event)
            MotionEvent.ACTION_UP     -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel()
            else -> false
        }
    }

    fun enterSecondary() {
        if (state != TouchState.PRIMARY) {
            Log.w(TAG, "enterSecondary() called in $state — ignoring")
            return
        }
        if (currentRing == Ring.NONE) {
            Log.d(TAG, "enterSecondary() suppressed — finger in deadzone (ring=NONE)")
            return
        }
        secondaryAnchorX = currentX
        secondaryAnchorY = currentY
        lastRingChangeTime = 0L
        transitionTo(TouchState.SECONDARY)
    }

    /**
     * Enters DELETE mode while the finger is still down (DEL key path).
     * The current position becomes the delete anchor; subsequent
     * horizontal dragging selects characters.
     */
    fun enterDeleteFromKey() {
        if (state != TouchState.PRIMARY) return
        anchorX = currentX
        anchorY = currentY
        startDelete()
    }

    private fun startDelete() {
        dwellTimer.cancel()
        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        deleteLeftCount = 0
        deleteRightCount = 0
        lastRingChangeTime = 0L
        transitionTo(TouchState.DELETE)
    }

    // ── Handlers ─────────────────────────────────────────────────

    private fun handleDown(event: MotionEvent): Boolean {
        refreshFromSettings()

        // Arming rule A: previous gesture ended in the deadzone and this
        // DOWN arrives within the configured window → DELETE immediately.
        val windowMs = if (SettingsManager.isInitialized) {
            SettingsManager.doubleTapDeadzoneMs.toLong()
        } else {
            DOUBLE_TAP_FALLBACK_MS
        }
        val elapsed = SystemClock.uptimeMillis() - lastUpTimestamp
        if (lastUpInDeadzone && elapsed in 0..windowMs) {
            Log.d(TAG, "Double-tap deadzone detected → DELETE mode")
            anchorX = event.x
            anchorY = event.y
            currentX = event.x
            currentY = event.y
            lastUpInDeadzone = false
            startDelete()
            return true
        }

        anchorX = event.x
        anchorY = event.y
        currentX = event.x
        currentY = event.y

        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        lastRingChangeTime = 0L

        transitionTo(TouchState.PRIMARY)
        return true
    }

    private fun handleMove(event: MotionEvent): Boolean {
        currentX = event.x
        currentY = event.y
        currentEventTime = event.eventTime

        when (state) {
            TouchState.PRIMARY   -> handlePrimaryMove()
            TouchState.SECONDARY -> handleSecondaryMove()
            TouchState.DELETE    -> handleDeleteMove(event)
            TouchState.IDLE      -> { /* spurious MOVE with no DOWN — ignore */ }
        }

        onPositionChanged?.invoke()
        return true
    }

    private fun handleUp(event: MotionEvent): Boolean {
        currentX = event.x
        currentY = event.y

        if (state == TouchState.DELETE) {
            val left = deleteLeftCount
            val right = deleteRightCount
            lastUpTimestamp = SystemClock.uptimeMillis()
            lastUpInDeadzone = false
            transitionTo(TouchState.IDLE)
            onDeleteCommit?.invoke(left, right)
            return true
        }

        lastUpTimestamp = SystemClock.uptimeMillis()
        lastUpInDeadzone = currentRing == Ring.NONE
        onCommit?.invoke()
        transitionTo(TouchState.IDLE)
        return true
    }

    private fun handleCancel(): Boolean {
        if (state == TouchState.DELETE) {
            deleteLeftCount = 0
            deleteRightCount = 0
            transitionTo(TouchState.IDLE)
            onDeleteCancelled?.invoke()
            return true
        }
        currentX = anchorX
        currentY = anchorY
        transitionTo(TouchState.IDLE)
        return true
    }

    // ── DELETE-mode drag handling ────────────────────────────────

        private fun handleDeleteMove(event: MotionEvent) {
        val dxDp = GeometryEngine.pxToDp(event.x - anchorX, density)
        val axDp = Math.abs(dxDp)

        val selectionActive = deleteLeftCount > 0 || deleteRightCount > 0

        // Dual-threshold hysteresis: a fresh selection needs 6 dp of
        // travel, but an existing selection survives down to 2 dp —
        // so lift-off jitter around the neutral zone can't flip a
        // deliberate 1-character delete into a no-op (or vice versa).
        if (!selectionActive) {
            if (axDp < DELETE_ARM_THRESHOLD_DP) return
        } else if (axDp < DELETE_DISARM_THRESHOLD_DP) {
            deleteLeftCount = 0
            deleteRightCount = 0
            onDeleteProgress?.invoke(0, 0)
            return
        }

        val charsPerMm = if (SettingsManager.isInitialized) {
            SettingsManager.deleteCharsPerMm
        } else {
            DELETE_CHARS_PER_MM_DEFAULT
        }
        val stepDp = DP_PER_MM / charsPerMm.coerceAtLeast(0.01f)

        val count = (axDp / stepDp).toInt().coerceAtLeast(1)

        val newLeft: Int
        val newRight: Int
        if (dxDp < 0f) {
            newLeft = count
            newRight = 0
        } else {
            newLeft = 0
            newRight = count
        }

        if (newLeft != deleteLeftCount || newRight != deleteRightCount) {
            deleteLeftCount = newLeft
            deleteRightCount = newRight
            onDeleteProgress?.invoke(deleteLeftCount, deleteRightCount)
        }
    }

    // ── Normal geometry resolution (PRIMARY / SECONDARY) ─────────

    /**
     * Common geometry resolution for both states. In SECONDARY the
     * anchor is the dwell point; in PRIMARY it is the touch-down point.
     * Deadzone (ring NONE) suppresses segment updates; overshoot
     * beyond the outer edge clamps to OUTER.
     */
    private fun resolveGeometry(anchorX: Float, anchorY: Float) {
        val distPx = geometryEngine.distance(anchorX, anchorY, currentX, currentY)
        val distDp = GeometryEngine.pxToDp(distPx, density)
        val angleDeg = geometryEngine.angle(anchorX, anchorY, currentX, currentY)

        val newRing = geometryEngine.computeRing(distDp, currentRing)

        if (newRing != currentRing) {
            previousRing = currentRing
            currentRing = newRing
            if (newRing == Ring.NONE) {
                // Entering the deadzone deselects everything.
                previousSegment = currentSegment
                currentSegment = -1
            }
            lastRingChangeTime = currentEventTime
            onRingChanged?.invoke(newRing)
            dwellTimer.reset()
        }

        val newSegment = geometryEngine.computeSegment(angleDeg)

        val timeSinceRingChange = currentEventTime - lastRingChangeTime
        if (timeSinceRingChange >= SEGMENT_SUPPRESSION_MS) {
            if (newRing != Ring.NONE && newSegment != currentSegment) {
                previousSegment = currentSegment
                currentSegment = newSegment
                onSegmentChanged?.invoke(newSegment)
                dwellTimer.reset()
            }
        }
    }

    private var currentEventTime: Long = 0L

    private fun handlePrimaryMove() {
        resolveGeometry(anchorX, anchorY)
    }

    private fun handleSecondaryMove() {
        resolveGeometry(secondaryAnchorX, secondaryAnchorY)
    }

    // ── Transition machinery ─────────────────────────────────────

    private fun transitionTo(newState: TouchState) {
        if (state == newState) return
        Log.d(TAG, "$state → $newState")
        state = newState

        when (newState) {
            TouchState.PRIMARY   -> dwellTimer.start()
            TouchState.IDLE      -> dwellTimer.cancel()
            TouchState.SECONDARY -> { /* dwell callback already fired */ }
            TouchState.DELETE    -> { /* timer cancelled in startDelete */ }
        }

        onStateChanged?.invoke(newState)
    }

    enum class TouchState {
        IDLE,
        PRIMARY,
        SECONDARY,
        DELETE
    }
}
