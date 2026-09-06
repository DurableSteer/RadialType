package com.radialtype.bench

import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.TouchStateMachine.TouchState

/**
 * Passive tap into TouchStateMachine's gesture stream for the
 * benchmark. All methods are no-ops by default and the FSM guards
 * every call behind a null check, so the cost when no session is
 * active is one nullable-field read per event — nothing measurable.
 *
 * Events are delivered on the touch-delivery thread, in event order,
 * with the MotionEvent's own eventTime so timestamps correlate with
 * what the FSM itself decided on (never SystemClock at observation
 * time — under event batching those diverge).
 *
 * IMPORTANT: implementations must not mutate FSM state from these
 * callbacks; they are observers, not participants.
 */
interface BenchObserver {

    /** ACTION_DOWN — gesture begins. Includes the down-position sample. */
    fun onGestureStart(x: Float, y: Float, t: Long) {}

    /** Every ACTION_MOVE sample the FSM consumed, AFTER it consumed it. */
    fun onSample(x: Float, y: Float, t: Long) {}

    /** Ring classification changed (includes deadzone NONE ↔ populated). */
    fun onRingChanged(from: Ring, to: Ring, t: Long) {}

    /** Segment classification changed. from/to are -1 for "none yet". */
    fun onSegmentChanged(from: Int, to: Int, t: Long) {}

    /** Any FSM state transition. */
    fun onStateTransition(from: TouchState, to: TouchState, t: Long) {}
    
    /**
     * An anchor was (re)set. Primary anchor: on ACTION_DOWN, the down
     * position. Secondary anchor: the dwell point when the secondary
     * menu opens. Trace rendering needs both to place the menu(s) the
     * gesture was classified against.
     */
    fun onAnchorUpdate(x: Float, y: Float, isSecondary: Boolean, t: Long) {}

    /**
     * ACTION_UP resolving to a commit. ring may be NONE (lifted in the
     * deadzone) — the benchmark treats that as an aborted trial, not a
     * miss, since nothing was committed.
     */
    fun onCommit(ring: Ring, segment: Int, t: Long) {}

    /** ACTION_CANCEL (or session teardown) killed the gesture mid-flight. */
    fun onGestureAborted(t: Long) {}
}
