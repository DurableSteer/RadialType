package com.radialtype.engine

import android.util.Log
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import com.radialtype.engine.GeometryEngine.Ring

/**
 * Finite state machine for RadialType's single-gesture touch tracking,
 * integrated with [GeometryEngine] for ring/segment detection.
 *
 * States:
 * - IDLE      No active gesture.
 * - PRIMARY   Finger is down, performing the primary radial selection.
 * - SECONDARY Finger dwelled long enough to activate a secondary menu
 *             (triggered by Module 7's dwell timer — not yet wired).
 *
 * Geometry tracking (PRIMARY state):
 * Every ACTION_MOVE computes the distance and angle from the gesture
 * anchor to the current finger position, converts to dp, and feeds
 * the result to [GeometryEngine.computeRing] (with hysteresis) and
 * [GeometryEngine.computeSegment]. When the ring or segment changes,
 * the corresponding callback fires.
 *
 * Segment suppression:
 * Immediately after a ring change, segment-change detection is
 * suppressed for [SEGMENT_SUPPRESSION_MS] milliseconds. Moving the
 * finger radially often causes a spurious angular jitter at the same
 * instant; the suppression window prevents this from producing noise.
 *
 * Secondary escape (SECONDARY → PRIMARY):
 * While in SECONDARY, the distance from [secondaryAnchorX/Y] (the
 * position where dwell triggered) is monitored. If it exceeds
 * [SECONDARY_RING_MAX] dp, the machine transitions back to PRIMARY.
 *
 * Secondary segment tracking:
 * While in SECONDARY, the segment is re-resolved around
 * [secondaryAnchorX/Y] on every MOVE (angular only — the secondary
 * menu is a flat ring), so the selection label can follow the finger.
 *
 * @param geometryEngine Injected for testability; defaults to a new instance.
 * @param density Screen density for px→dp conversion. Must be set by the
 *                hosting view before the first ACTION_MOVE (typically
 *                `resources.displayMetrics.density`).
 */
class TouchStateMachine(
    private val geometryEngine: GeometryEngine = GeometryEngine(),
    var density: Float = 1f,
    /** Dwell threshold before PRIMARY → SECONDARY. */
    var dwellDurationMs: Long = DEFAULT_DWELL_MS,
    /** Injectable override for unit testing (real timer used when null). */
    dwellTimerOverride: DwellTimer? = null
) {

    companion object {
        private const val TAG = "TouchStateMachine"

        const val SECONDARY_RING_MAX = 100f
        const val SEGMENT_SUPPRESSION_MS = 50L

        /** Default dwell duration before PRIMARY → SECONDARY. */
        const val DEFAULT_DWELL_MS = 300L
    }
    
    // ── Dwell timer (Module 7) ──────────────────────────────────

    /**
     * Fires [enterSecondary] after [dwellDurationMs] of no segment/ring
     * change in PRIMARY. An override can be injected for tests; otherwise
     * a real timer on the main looper is created.
     */
    val dwellTimer: DwellTimer = dwellTimerOverride
        ?: DwellTimer(
            dwellDurationMs = dwellDurationMs,
            handler = Handler(Looper.getMainLooper()),
            callback = { enterSecondary() }
        )
        
    // ── Gesture state ────────────────────────────────────────────

    /** Current state of the gesture. */
    var state: TouchState = TouchState.IDLE
        private set

    /** Touch-down anchor — where the primary gesture started. */
    var anchorX: Float = 0f
        private set

    var anchorY: Float = 0f
        private set

    /** Current finger position, updated on every MOVE. */
    var currentX: Float = 0f
        private set

    var currentY: Float = 0f
        private set

    // ── Geometry tracking ────────────────────────────────────────

    /** Ring resolved by [GeometryEngine.computeRing] on the last MOVE. */
    var currentRing: Ring = Ring.NONE
        private set

    /** Segment resolved by [GeometryEngine.computeSegment] on the last MOVE. */
    var currentSegment: Int = -1
        private set

    /** Previous ring value (for transition logging / diffing). */
    var previousRing: Ring = Ring.NONE
        private set

    /** Previous segment value. */
    var previousSegment: Int = -1
        private set

    /**
     * Timestamp (from [MotionEvent.getEventTime]) of the last ring
     * change. Used for the [SEGMENT_SUPPRESSION_MS] suppression window.
     * A value of 0 means no ring change has occurred yet in this gesture.
     */
    private var lastRingChangeTime: Long = 0L

    // ── Secondary state tracking ─────────────────────────────────

    /**
     * Position captured when the machine enters SECONDARY state.
     * Used as the centre for the secondary-ring escape check.
     */
    var secondaryAnchorX: Float = 0f
        private set

    var secondaryAnchorY: Float = 0f
        private set

    // ── Callbacks ───────────────────────────────────────────────

    /** Fired whenever the state changes. Receives the new state. */
    var onStateChanged: ((TouchState) -> Unit)? = null

    /** Fired on every ACTION_MOVE after currentX/Y are updated. */
    var onPositionChanged: (() -> Unit)? = null

    /** Fired on ACTION_UP — the view/controller should commit selection. */
    var onCommit: (() -> Unit)? = null

    /** Fired when the resolved ring changes (INNER/OUTER/NONE). */
    var onRingChanged: ((Ring) -> Unit)? = null

    /** Fired when the resolved segment changes (0–7). */
    var onSegmentChanged: ((Int) -> Unit)? = null

    // ── Public API ───────────────────────────────────────────────

    /**
     * Main entry point. Called by the hosting view's touch listener
     * for every MotionEvent in the gesture.
     *
     * @return true if the event was handled.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN   -> handleDown(event)
            MotionEvent.ACTION_MOVE   -> handleMove(event)
            MotionEvent.ACTION_UP     -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel()
            else -> false
        }
    }

    /**
     * Transitions to SECONDARY state, capturing the current finger
     * position as the secondary ring centre.
     *
     * Intended to be called by the dwell timer (Module 7). Safe to
     * call only while in PRIMARY state; calling from other states
     * is a no-op.
     */
    fun enterSecondary() {
        if (state != TouchState.PRIMARY) {
            Log.w(TAG, "enterSecondary() called in $state — ignoring")
            return
        }
        secondaryAnchorX = currentX
        secondaryAnchorY = currentY
        // Reset ring-change timestamp so suppression does not carry over
        lastRingChangeTime = 0L
        transitionTo(TouchState.SECONDARY)
    }

    // ── Handlers ─────────────────────────────────────────────────

    private fun handleDown(event: MotionEvent): Boolean {
        anchorX = event.x
        anchorY = event.y
        currentX = event.x
        currentY = event.y

        // Reset geometry tracking for a fresh gesture
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

        when (state) {
            TouchState.PRIMARY   -> handlePrimaryMove(event)
            TouchState.SECONDARY -> handleSecondaryMove(event)
            TouchState.IDLE      -> { /* spurious MOVE with no DOWN — ignore */ }
        }

        onPositionChanged?.invoke()
        return true
    }

    private fun handleUp(event: MotionEvent): Boolean {
        currentX = event.x
        currentY = event.y
        onCommit?.invoke()
        transitionTo(TouchState.IDLE)
        return true
    }

    private fun handleCancel(): Boolean {
        currentX = anchorX
        currentY = anchorY
        transitionTo(TouchState.IDLE)
        return true
    }

    // ── PRIMARY-state geometry ───────────────────────────────────

    /**
     * Processes a MOVE while in PRIMARY state: computes distance,
     * angle, ring, and segment from the gesture anchor, applying
     * hysteresis (via GeometryEngine) and segment suppression.
     */
    private fun handlePrimaryMove(event: MotionEvent) {
        val distPx = geometryEngine.distance(anchorX, anchorY, currentX, currentY)
        val distDp = GeometryEngine.pxToDp(distPx, density)
        val angleDeg = geometryEngine.angle(anchorX, anchorY, currentX, currentY)

        val newRing = geometryEngine.computeRing(distDp, currentRing)

        if (newRing != currentRing) {
            previousRing = currentRing
            currentRing = newRing
            lastRingChangeTime = event.eventTime
            Log.d(TAG,
                "Ring: $previousRing → $newRing  " +
                "(dist=${String.format("%.1f", distDp)}dp, " +
                "angle=${String.format("%.1f", angleDeg)}°)")
            onRingChanged?.invoke(newRing)
            dwellTimer.reset()   // ring change → dwell clock restarts
        }

        val newSegment = geometryEngine.computeSegment(angleDeg)

        val timeSinceRingChange = event.eventTime - lastRingChangeTime
        if (timeSinceRingChange >= SEGMENT_SUPPRESSION_MS) {
            if (newSegment != currentSegment) {
                previousSegment = currentSegment
                currentSegment = newSegment
                Log.d(TAG,
                    "Segment: $previousSegment → $newSegment  " +
                    "(angle=${String.format("%.1f", angleDeg)}°)")
                onSegmentChanged?.invoke(newSegment)
                dwellTimer.reset()   // segment change → dwell clock restarts
            }
        } else {
            Log.v(TAG,
                "Segment suppressed: newSeg=$newSegment curSeg=$currentSegment " +
                "(${timeSinceRingChange}ms < ${SEGMENT_SUPPRESSION_MS}ms since ring change)")
        }
    }

    // ── SECONDARY-state escape & segment tracking ────────────────

    /**
     * Processes a MOVE while in SECONDARY state:
     * 1. Checks whether the finger has escaped the secondary ring
     *    (distance from [secondaryAnchorX/Y] > [SECONDARY_RING_MAX] dp)
     *    and, if so, transitions back to PRIMARY.
     * 2. Otherwise, re-resolves the segment relative to the secondary
     *    anchor so the SECONDARY selection (syllables, Module 9) can
     *    follow the finger. Angular-only — the secondary menu is not
     *    split into concentric rings.
     */
    private fun handleSecondaryMove(event: MotionEvent) {
        val distPx = geometryEngine.distance(
            secondaryAnchorX, secondaryAnchorY,
            currentX, currentY
        )
        val distDp = GeometryEngine.pxToDp(distPx, density)

        if (distDp > SECONDARY_RING_MAX) {
            Log.d(TAG,
                "Secondary escape: ${String.format("%.1f", distDp)}dp " +
                "> ${SECONDARY_RING_MAX}dp — returning to PRIMARY")
            // transitionTo(PRIMARY) below restarts the dwell clock for
            // whichever segment the finger lands in.
            transitionTo(TouchState.PRIMARY)
            return
        }

        // Secondary segment tracking: angle measured from the point
        // where dwell triggered, so segment 0 is "east" of the tap.
        val angleDeg = geometryEngine.angle(
            secondaryAnchorX, secondaryAnchorY,
            currentX, currentY
        )
        val newSegment = geometryEngine.computeSegment(angleDeg)
        if (newSegment != currentSegment) {
            previousSegment = currentSegment
            currentSegment = newSegment
            Log.d(TAG,
                "Secondary segment: $previousSegment → $newSegment  " +
                "(angle=${String.format("%.1f", angleDeg)}°)")
            onSegmentChanged?.invoke(newSegment)
        }
    }

    // ── Internals ────────────────────────────────────────────────

    /**
     * Transitions to [newState], logging and firing the callback
     * only if the state actually changes.
     */
    private fun transitionTo(newState: TouchState) {
        if (state == newState) return
        Log.d(TAG, "$state → $newState")
        state = newState

        when (newState) {
            // Entering PRIMARY (from DOWN, or escaping SECONDARY):
            // (re)start the dwell clock for the current segment.
            TouchState.PRIMARY   -> dwellTimer.start()

            // Finger lifted or gesture cancelled: kill the pending callback.
            TouchState.IDLE      -> dwellTimer.cancel()

            // Entering SECONDARY: the dwell callback already consumed
            // the timer; nothing to do here.
            TouchState.SECONDARY -> { /* dwell callback already fired */ }
        }

        onStateChanged?.invoke(newState)
    }
    /**
     * Three-state lifecycle for the radial gesture.
     *
     * [IDLE]      — no finger on screen.
     * [PRIMARY]   — finger down, primary radial menu active.
     * [SECONDARY] — finger dwelled, secondary radial menu active.
     */
    enum class TouchState {
        IDLE,
        PRIMARY,
        SECONDARY
    }
}
