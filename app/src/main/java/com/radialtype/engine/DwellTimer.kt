package com.radialtype.engine

import android.os.Handler

/**
 * Single-shot dwell timer for RadialType's PRIMARY → SECONDARY transition.
 *
 * Lifecycle contract:
 * - [start]   begins (or restarts) the countdown; any pending callback is
 *             cancelled first, so [start] is idempotent. The optional
 *             [delayMs] overrides the configured duration — bend-gate mode
 *             uses it to schedule the fire relative to a PAST event (the
 *             bend timestamp), so the actuation point is anchored to the
 *             motion, not to when the arming code happened to run.
 * - [reset]   cancels and immediately restarts — called whenever the segment
 *             or ring changes so the dwell clock restarts for the new cell.
 * - [cancel]  removes any pending callback — called on ACTION_UP and when
 *             leaving PRIMARY for any reason other than dwelling.
 *
 * The callback runs on the thread backing [handler] — for touch-driven
 * usage that is the main looper, matching where MotionEvent handlers run,
 * so no additional synchronization is needed.
 */
class DwellTimer(
    var dwellDurationMs: Long = 300L,
    private val handler: Handler,
    private val callback: () -> Unit
) {

    private val dwellRunnable = Runnable {
        isRunning = false
        callback()
    }

    /** True while a countdown is pending. Exposed for debugging/tests. */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Starts the countdown, cancelling any pending callback first.
     * A zero or negative [delayMs] fires on the next loop pass.
     */
    fun start(delayMs: Long = dwellDurationMs) {
        cancel()
        isRunning = true
        handler.postDelayed(dwellRunnable, delayMs.coerceAtLeast(0L))
    }

    /** Removes any pending callback without firing it. */
    fun cancel() {
        handler.removeCallbacks(dwellRunnable)
        isRunning = false
    }

    /** Cancels and immediately restarts the countdown. */
    fun reset() = start()
}
