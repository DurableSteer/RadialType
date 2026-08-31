package com.radialtype.engine

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure-math engine for radial keyboard geometry.
 *
 * Defines two concentric rings around the touch anchor:
 *
 * ```
 *         ┌─────────────────────────┐
 *         │    OUTER RING (8 seg)   │  ← R1_MAX .. R2_MAX
 *         │  ┌───────────────────┐  │
 *         │  │   INNER RING       │  │  ← R1_MIN .. R1_MAX
 *         │  │   (8 segments)     │  │
 *         │  │       • anchor    │  │
 *         │  └───────────────────┘  │
 *         └─────────────────────────┘
 * ```
 *
 * All distance constants are in **dp**. Callers must convert pixel
 * coordinates (from MotionEvent) to dp using [pxToDp] before invoking
 * [computeRing]. The [angle] and [distance] helpers work in whatever
 * unit is passed — they are pure arithmetic.
 *
 * This class has zero Android dependencies and is fully unit-testable.
 */
class GeometryEngine {

    /** Inner ring starts at the anchor point. */
    val innerRadiusMin: Float = INNER_RADIUS_MIN

    /** Inner ring ends / outer ring begins (boundary has hysteresis). */
    val innerRadiusMax: Float = INNER_RADIUS_MAX

    /** Outer ring ends; beyond this is Ring.NONE (finger exited). */
    val outerRadiusMax: Float = OUTER_RADIUS_MAX

    /** Number of angular segments per ring (45° each). */
    val segmentCount: Int = SEGMENT_COUNT

    /**
     * Identifies which ring a finger position belongs to, applying
     * hysteresis to prevent rapid oscillation when the finger hovers
     * near [INNER_RADIUS_MAX].
     *
     * Hysteresis logic:
     * - If the finger was in [Ring.INNER] and drifts slightly past
     *   [INNER_RADIUS_MAX] (up to +[HYSTERESIS]), it stays INNER.
     * - If the finger was in [Ring.OUTER] and drifts slightly below
     *   [INNER_RADIUS_MAX] (down to −[HYSTERESIS]), it stays OUTER.
     * - This deadband prevents flickering at the ring boundary.
     *
     * @param distanceFromCenter Radial distance from anchor, in dp.
     * @param previousRing The ring classification from the previous
     *                     frame, or [Ring.NONE] if there is no prior state.
     * @return The resolved [Ring].
     */
    fun computeRing(
        distanceFromCenter: Float,
        previousRing: Ring
    ): Ring {
        // Finger is outside the entire keyboard area
        if (distanceFromCenter > OUTER_RADIUS_MAX) return Ring.NONE

        // Hysteresis band around the inner/outer boundary
        val boundary = INNER_RADIUS_MAX
        val lowerHysteresis = boundary - HYSTERESIS   // e.g. 52 dp
        val upperHysteresis = boundary + HYSTERESIS    // e.g. 68 dp

        return when {
            // Clearly in the inner ring
            distanceFromCenter < lowerHysteresis -> Ring.INNER

            // Clearly in the outer ring
            distanceFromCenter > upperHysteresis -> Ring.OUTER

            // Inside the hysteresis band — retain previous ring state
            else -> when (previousRing) {
                Ring.OUTER -> Ring.OUTER
                Ring.INNER -> Ring.INNER
                Ring.NONE  -> {
                    // No prior state: use the physical boundary to decide.
                    // At exactly INNER_RADIUS_MAX, favor INNER (closer to center).
                    if (distanceFromCenter <= boundary) Ring.INNER
                    else Ring.OUTER
                }
            }
        }
    }

    /**
     * Maps an angle to a segment index (0 .. SEGMENT_COUNT−1).
     *
     * Segment 0 is centered at 0° (east / "3 o'clock"), so it spans
     * −22.5° to +22.5°. Segments proceed clockwise:
     *
     * ```
     *      3   4   5
     *    2           6
     *      1   0   7
     *               ↑ 0° (east)
     * ```
     *
     * @param angleDegrees Angle in degrees, normalized to 0–360°.
     *                     Use [angle] to compute this from coordinates.
     * @return Segment index 0–7.
     */
    fun computeSegment(angleDegrees: Float): Int {
        // Shift by half a segment so segment 0 is centered on 0°,
        // then floor-divide by segment width.
        val segmentWidth = 360f / SEGMENT_COUNT   // 45°
        val halfSegment = segmentWidth / 2f       // 22.5°
        val index = ((angleDegrees + halfSegment) / segmentWidth).toInt()
        return index % SEGMENT_COUNT
    }

    /**
     * Standard Euclidean distance between two points.
     * Works in whatever unit the inputs use (typically pixels
     * from MotionEvent). Convert to dp if needed before
     * passing to [computeRing].
     */
    fun distance(
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    /**
     * Computes the angle from (anchorX, anchorY) to (currentX, currentY),
     * in degrees, normalized to 0–360°.
     *
     * 0° = east (positive X direction, "3 o'clock").
     * Angles increase clockwise (because screen Y is inverted: positive Y
     * is downward, so atan2(dy, dx) naturally increases clockwise on
     * a screen where +Y is down).
     *
     * @return Degrees in [0, 360).
     */
    fun angle(
        anchorX: Float, anchorY: Float,
        currentX: Float, currentY: Float
    ): Float {
        val dx = (currentX - anchorX).toDouble()
        val dy = (currentY - anchorY).toDouble()
        var degrees = Math.toDegrees(atan2(dy, dx))
        if (degrees < 0) degrees += 360.0
        return degrees.toFloat()
    }

    /**
     * Which ring a given distance falls in, ignoring hysteresis.
     * Useful for testing boundary behavior in isolation.
     */
    fun ringWithoutHysteresis(distanceFromCenter: Float): Ring {
        return when {
            distanceFromCenter > OUTER_RADIUS_MAX -> Ring.NONE
            distanceFromCenter > INNER_RADIUS_MAX -> Ring.OUTER
            else -> Ring.INNER
        }
    }

    companion object {
        // ── Ring radii (dp) ──────────────────────────────────────
        const val INNER_RADIUS_MIN = 0f
        const val INNER_RADIUS_MAX = 60f
        const val OUTER_RADIUS_MAX = 120f

        // ── Segment configuration ────────────────────────────────
        const val SEGMENT_COUNT = 8

        // ── Hysteresis band width (dp) ──────────────────────────
        const val HYSTERESIS = 8f

        /**
         * Converts a pixel distance to dp using the screen density.
         * Call this before passing MotionEvent-derived distances to
         * [computeRing], since all ring constants are in dp.
         *
         * Typical usage from a View:
         * ```
         * val density = resources.displayMetrics.density
         * val distDp = GeometryEngine.pxToDp(distPx, density)
         * ```
         */
        fun pxToDp(px: Float, density: Float): Float = px / density
    }

    /**
     * Represents which concentric ring the finger currently occupies.
     *
     * [NONE] means the finger is outside the keyboard area entirely
     * (beyond [OUTER_RADIUS_MAX]) — the gesture should be treated as
     * cancelled or ignored.
     */
    enum class Ring {
        INNER,
        OUTER,
        NONE
    }
}
