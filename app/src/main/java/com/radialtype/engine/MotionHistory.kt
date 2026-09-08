package com.radialtype.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * One detected speed minimum ("bend"): the timestamp of the LATER
 * sample of the slowest consecutive pair, that pair's speed in
 * px/ms, and the fastest span speed ARRIVING at the minimum
 * (approach wall, 0 when the window holds no faster travel before
 * the valley — i.e. the buffer's leading stillness, not a real
 * deceleration). The FSM schedules actuation relative to [timeMs].
 */
data class Bend(
    val timeMs: Long,
    val speedPxPerMs: Float,
    val peakSpeedPxPerMs: Float,
    val approachSpeedPxPerMs: Float
)

/**
 * Fixed-capacity ring buffer of recent touch samples, feeding the
 * onset-weighted exit angle and the dwell bend detector.
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

    /**
     * Speed-minimum ("bend") detector.
     *
     * @param windowMs Search window for the valley and its peak.
     * @param virtualNowMs Optional wall-clock now. When supplied and
     *        the newest sample is at least [BEND_ARREST_MS] stale, the
     *        final sample is treated as PERSISTING to this instant:
     *        spans ending there decay toward zero, so a parked finger
     *        whose deceleration tail never dipped below the ceiling
     *        still surfaces as a valley. No effect while fresh samples
     *        stream (they are never BEND_ARREST_MS old), so live
     *        fingers are judged on real data only.
     */
    fun findBend(windowMs: Long = DEFAULT_WINDOW_MS, virtualNowMs: Long = -1L): Bend? {
        if (count < 2) return null
        val newest = (head - 1 + capacity) % capacity

        val arrestExtends = virtualNowMs > 0L &&
            virtualNowMs - ts[newest] >= BEND_ARREST_MS
        val tEnd = if (arrestExtends) virtualNowMs else ts[newest]

        // Chronological index list over the ENTIRE retained buffer —
        // the approach wall must not decay just because the finger
        // parked. Capacity 16 ≈ 130–260 ms; BEND_WINDOW_MS is 120, so
        // the buffer is marginally too tight — see FSM note below.
        val idx = ArrayList<Int>(count)
        for (k in count - 1 downTo 0) {
            idx.add((newest - k + capacity) % capacity)
        }
        val n = idx.size
        if (n < 2) return null

        // Per-sample span speeds (trailing BEND_SPAN_MS, min
        // MIN_SPAN_MS — same discipline as the benchmark's finder).
        // Slots with spanTime < 0 carry NO reading and must be excluded
        // from argmin, peak and wall. When arrest extends, the FINAL
        // span stretches to tEnd: fixed displacement over growing dt,
        // so its measured speed decays sample-free toward zero.
        val spanTime = LongArray(n) { -1L }
        val spanSpeed = FloatArray(n)
        for (i in 1 until n) {
            val now = idx[i]
            val endT = if (i == n - 1 && arrestExtends) tEnd else ts[now]
            var j = i
            while (j > 0 && endT - ts[idx[j - 1]] <= BEND_SPAN_MS) j--
            val dt = endT - ts[idx[j]]
            if (dt < MIN_SPAN_MS) continue
            val dx = xs[now] - xs[idx[j]]
            val dy = ys[now] - ys[idx[j]]
            spanTime[i] = endT
            spanSpeed[i] = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / dt
        }

        // Peak and valley restricted to the search window.
        var minIdx = -1
        var minSpeed = Float.MAX_VALUE
        var maxSpeed = 0f
        for (i in 1 until n) {
            if (spanTime[i] < 0) continue
            if (tEnd - spanTime[i] > windowMs) continue
            val s = spanSpeed[i]
            if (s > maxSpeed) maxSpeed = s
            if (s < minSpeed) {
                minSpeed = s
                minIdx = i
            }
        }
        if (minIdx < 0) return null

        // Approach wall = fastest span ANYWHERE in the buffer ending
        // strictly before the valley — deliberately NOT window-clamped,
        // so parking cannot erase a legitimate flick's approach speed.
        // (Peak stays window-clamped: the dip ratio compares the valley
        // against speeds of the same movement phase.)
        var approach = 0f
        for (i in 1 until minIdx) {
            if (spanTime[i] >= 0 && spanSpeed[i] > approach) approach = spanSpeed[i]
        }

        return Bend(
            timeMs = ts[idx[minIdx]],      // physical sample time, never virtual
            speedPxPerMs = minSpeed,
            peakSpeedPxPerMs = maxSpeed,
            approachSpeedPxPerMs = approach
        )
    }
    
    companion object {
        const val DEFAULT_CAPACITY = 16
        const val DEFAULT_WINDOW_MS = 40L
        const val BEND_SPAN_MS = 40L

        /** Minimum span (ms) for a trustworthy velocity estimate. */
        const val MIN_SPAN_MS = 8L
        /**
         * Staleness threshold (ms) for the virtual-arrest probe: when
         * the caller supplies a wall-clock now and the newest sample
         * is at least this old, delivery has stopped (parked finger)
         * and the final sample is treated as persisting to now.
         */
        const val BEND_ARREST_MS = 24L

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
