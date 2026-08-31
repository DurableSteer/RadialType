package com.radialtype.engine

import android.os.Handler

/**
 * Single-shot dwell timer for RadialType's PRIMARY → SECONDARY transition.
 *
 * Lifecycle contract:
 * - [start]   begins (or restarts) the countdown; any pending callback is
 *             cancelled first, so [start] is idempotent.
 * - [reset]   cancels and immediately restarts — called whenever the segment
 *             or ring changes so the dwell clock restarts for the new cell.
 * - [cancel]  removes any pending callback — called on ACTION_UP and when
 *             leaving PRIMARY for any reason other than dwelling.
 *
 * The callback runs on the thread backing [handler] — for touch-driven
 * usage that is the main looper, matching where MotionEvent handlers run,
 * so no additional synchronization is needed.
 *
 * @param dwellDurationMs How long the finger must stay still before the
 *                        callback fires (default 300 ms).
 * @param handler         Handler used to schedule the delayed callback.
 * @param callback        Invoked when the dwell threshold elapses without
 *                        any segment/ring change.
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

    /** Starts the countdown, cancelling any pending callback first. */
    fun start() {
        cancel()
        isRunning = true
        handler.postDelayed(dwellRunnable, dwellDurationMs)
    }

    /** Removes any pending callback without firing it. */
    fun cancel() {
        handler.removeCallbacks(dwellRunnable)
        isRunning = false
    }

    /** Cancels and immediately restarts the countdown. */
    fun reset() = start()
}
