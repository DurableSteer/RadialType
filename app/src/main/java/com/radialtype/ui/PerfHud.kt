package com.radialtype.ui

import java.util.Locale

/**
 * Rolling per-frame performance statistics for the overlay renderer.
 * All mutation happens on the UI thread (setData / onDraw), so no
 * synchronization is needed.
 */
class PerfHud {

    companion object {
        private const val WINDOW = 64          // rolling frame-interval samples
        private const val RATE_PERIOD_MS = 1000L
    }

    private val intervals = FloatArray(WINDOW)
    private var head = 0
    private var filled = 0
    private var lastFrameNs = 0L

    /** Duration of the last renderer.render call, ms. */
    var lastDrawMs = 0f
        private set

    /** Push → raster staleness of the last drawn frame, ms. */
    var lastStalenessMs = 0f
        private set

    private var inputRate = 0f
    private var inputCount = 0
    private var inputWindowStart = 0L

    /** Called once per onDraw with monotonic nanos. */
    fun recordFrameInterval(nowNs: Long) {
        if (lastFrameNs != 0L) {
            val intervalMs = (nowNs - lastFrameNs) / 1_000_000f
            if (intervalMs > 100f) {
                // Quiescent gap — not a frame. Restart the window rather
                // than recording it as a 200ms "frame".
                filled = 0
                head = 0
            } else if (intervalMs in 1f..100f) {
                intervals[head] = intervalMs
                head = (head + 1) % WINDOW
                if (filled < WINDOW) filled++
            }
        }
        lastFrameNs = nowNs
    }

    fun recordDraw(durationMs: Float) { lastDrawMs = durationMs }

    /** Push→raster staleness, in ms, of the frame just drawn. */
    fun recordStaleness(pushTimeMs: Long, drawStartMs: Long) {
        lastStalenessMs = (drawStartMs - pushTimeMs).coerceAtLeast(0L).toFloat()
    }

    /** Called from [RadialOverlayView.setData] — counts render-producing updates. */
    fun recordPush(nowMs: Long) {
        if (inputWindowStart == 0L) inputWindowStart = nowMs
        inputCount++
        val elapsed = nowMs - inputWindowStart
        if (elapsed >= RATE_PERIOD_MS) {
            inputRate = inputCount * 1000f / elapsed
            inputCount = 0
            inputWindowStart = nowMs
        }
    }

    fun fps(): Float {
        if (filled == 0) return 0f
        var sum = 0f
        for (i in 0 until filled) sum += intervals[i]
        return if (sum > 0f) filled * 1000f / sum else 0f
    }

    fun avgIntervalMs(): Float {
        if (filled == 0) return 0f
        var s = 0f
        for (i in 0 until filled) s += intervals[i]
        return s / filled
    }

    fun maxIntervalMs(): Float {
        var m = 0f
        for (i in 0 until filled) if (intervals[i] > m) m = intervals[i]
        return m
    }

    val inputRatePct: Float get() = inputRate  // named accessor, kept simple
}
