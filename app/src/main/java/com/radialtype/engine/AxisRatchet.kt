package com.radialtype.engine

/**
 * Directional ratchet for axis-mode drags (Package 0.9).
 *
 * Converts raw axis displacement (dp, signed, from the gesture anchor)
 * into a ratcheted step count that is ABSOLUTE — measured from the
 * gesture anchor exactly like the old signedCount, so delta-consuming
 * and base-relative consumers (cursor move deltas, delete left/right
 * from the preview base) stay consistent.
 *
 * - UNLOCKED: |disp| below [armDp] counts 0. Crossing it locks the
 *   sign and counts ±1.
 * - LOCKED (hold): the count only moves in the locked direction —
 *   it is the extreme of the raw anchor-relative count in that
 *   direction. Within-band retreat holds the count silently: jitter
 *   at a boundary or at a reversal point can never un-count, so
 *   consumers see one event per genuine net step and never a burst.
 * - RELEASE (flip): retreat past (extreme + [releaseDp]) flips the
 *   lock. The count SNAPS to the current raw position — releasing
 *   the held extreme so the caret lands exactly under the finger —
 *   and then counts freely in the new direction. No re-arm dead
 *   zone, no accumulated offset after repeated reversals.
 */
class AxisRatchet(
    /** Travel (dp) before the first count locks in. */
    private val armDp: Float,
    /** Retreat (dp) past the locked extreme before the lock flips. */
    private val releaseDp: Float,
    /** Distance per counted step (dp). */
    private val stepDp: Float
) {

    companion object {
        /** Release band as a multiple of the arm distance — tuned so
         *  boundary chatter never pays it back, but a deliberate
         *  reversal does. Not user-facing. */
        const val RELEASE_MULTIPLE = 2f
    }

    /** Locked direction: 0 unlocked, ±1 locked. */
    var sign: Int = 0
        private set

    /** Extreme raw displacement (dp) reached in the locked direction. */
    private var extremeDp = 0f

    /** Current ratcheted step count (signed, anchor-relative). */
    var count: Int = 0
        private set

    /** Old signedCount semantics, anchor-relative: 0 inside the arm
     *  threshold, otherwise steps measured past it. */
    private fun rawCount(dispDp: Float): Int {
        val mag = Math.abs(dispDp)
        if (mag <= armDp) return 0
        val c = 1 + ((mag - armDp) / stepDp).toInt()
        return if (dispDp < 0f) -c else c
    }

    /**
     * Feeds one raw displacement sample (dp, signed, from the gesture
     * anchor) and returns the NEW absolute step count. Monotonic
     * within a lock (hold), snaps to raw on a flip.
     */
    fun update(dispDp: Float): Int {
        if (sign == 0) {
            if (Math.abs(dispDp) < armDp) return count    // unarmed
            sign = if (dispDp < 0f) -1 else 1
            extremeDp = dispDp
            count = rawCount(dispDp)
            return count
        }

        // Track the extreme in the locked direction.
        val advanced = dispDp * sign > extremeDp * sign
        if (advanced) extremeDp = dispDp

        // Release check: retreat past the extreme by more than the band.
        val retreat = (extremeDp - dispDp) * sign
        if (retreat > releaseDp) {
            // Genuine reversal: flip and SNAP to the finger's position —
            // the held extreme is released, and the count is anchor-
            // relative again, so delta consumers land the caret exactly
            // where the finger is now.
            sign = -sign
            extremeDp = dispDp
            count = rawCount(dispDp)
            return count
        }

        // Hold: clamp toward the locked direction only. Forward motion
        // extends the count; within-band retreat changes nothing.
        val raw = rawCount(dispDp)
        count = if (sign > 0) Math.max(count, raw) else Math.min(count, raw)
        return count
    }

    /** Returns to the pristine unlocked state. */
    fun reset() {
        sign = 0
        extremeDp = 0f
        count = 0
    }
}
