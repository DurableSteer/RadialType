package com.radialtype.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fixed-capacity ring buffer of recent touch samples, feeding the
 * onset-weighted exit angle: when the finger leaves the deadzone, the
 * direction of TRAVEL over the last few samples is a better predictor
 * of the intended spoke than the instantaneous bearing from the anchor
 * (curved flicks and deadzone-edge jitter corrupt the positional angle
 * on exactly that first decisive frame).
 *
 * Thread contract: touched only from the touch-delivery thread, same
 * as TouchStateMachine — no synchronization.
 *
 * @param capacity Number of retained samples (default 16 ≈ 130–260 ms
 *                 of history at typical move-event rates).
 */
class MotionHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val ts = LongArray(capacity)

    /** Index the NEXT write lands on. */
    private var head = 0

    /** Samples currently retained (≤ capacity). */
    private var count = 0

    val size: Int get() = count

    /** Records one sample. Oldest sample is evicted when full. */
    fun add(x: Float, y: Float, t: Long) {
        xs[head] = x
        ys[head] = y
        ts[head] = t
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /** Drops all samples. */
    fun reset() {
        head = 0
        count = 0
    }

    /**
     * Displacement-based velocity over the trailing [windowMs],
     * measured from the NEWEST sample backwards. Returns (vx, vy) in
     * px/ms, or null when the window holds fewer than two samples or
     * spans less than [MIN_SPAN_MS] (too little time for a stable
     * estimate — a slowly parked finger or a batched event burst).
     */
    fun velocity(windowMs: Long = DEFAULT_WINDOW_MS): Pair<Float, Float>? {
        if (count < 2) return null
        val newest = (head - 1 + capacity) % capacity
        val tNew = ts[newest]

        // Walk backwards to the OLDEST sample still inside the window;
        // stop one step too far if we blow past it.
        var older = newest
        for (k in 1 until count) {
            val cand = (newest - k + capacity) % capacity
            if (tNew - ts[cand] > windowMs) break
            older = cand
        }

        val dt = tNew - ts[older]
        if (dt < MIN_SPAN_MS) return null
        return Pair(
            (xs[newest] - xs[older]) / dt.toFloat(),
            (ys[newest] - ys[older]) / dt.toFloat()
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 16
        const val DEFAULT_WINDOW_MS = 40L

        /** Minimum span (ms) for a trustworthy velocity estimate. */
        const val MIN_SPAN_MS = 8L

        /**
         * Circular blend of two bearings: converts both to unit vectors,
         * weights them, and returns the direction of the weighted sum.
         * Unlike arithmetic averaging this handles the 0°/360° wrap and
         * never produces a "through the pole" result. If the two vectors
         * cancel (opposite directions, equal weight), the first angle
         * wins — a safe fallback, never NaN.
         *
         * @param weightA Share of [angleADeg] in the blend, 0..1.
         */
        fun blendAngles(angleADeg: Float, angleBDeg: Float, weightA: Float): Float {
            val w = weightA.coerceIn(0f, 1f)
            val ra = Math.toRadians(angleADeg.toDouble())
            val rb = Math.toRadians(angleBDeg.toDouble())
            val vx = w * cos(ra) + (1f - w) * cos(rb)
            val vy = w * sin(ra) + (1f - w) * sin(rb)
            if (Math.hypot(vx, vy) < 1e-9) return angleADeg
            var deg = Math.toDegrees(atan2(vy, vx))
            if (deg < 0) deg += 360.0
            return deg.toFloat()
        }
    }
}
