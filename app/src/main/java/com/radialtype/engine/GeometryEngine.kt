package com.radialtype.engine

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure-math engine for radial keyboard geometry.
 *
 * Defines a deadzone and two concentric rings around the touch anchor.
 * The same geometry is used for both PRIMARY and SECONDARY menus —
 * the secondary menu is simply re-centred on the dwell point.
 *
 * ```
 *         ┌─────────────────────────┐
 *         │    OUTER RING (8 seg)   │  ← INNER_RADIUS_MAX .. OUTER_RADIUS_MAX
 *         │  ┌───────────────────┐  │
 *         │  │   INNER RING       │  │  ← DEAD_ZONE_RADIUS .. INNER_RADIUS_MAX
 *         │  │   (8 segments)     │  │
 *         │  │    ░ deadzone ░    │  │  ← 0 .. DEAD_ZONE_RADIUS (unrendered,
 *         │  │       • anchor    │  │     no selection, no haptics)
 *         │  └───────────────────┘  │
 *         └─────────────────────────┘
 * ```
 *
 * **Overshoot clamping:** positions beyond [OUTER_RADIUS_MAX] are NOT
 * rejected — they resolve to [Ring.OUTER]. The selected key is the
 * outer-ring segment on the ray from the anchor through the touch
 * point, so overshooting the keyboard can never deselect the finger.
 * [Ring.NONE] is produced only by the center deadzone.
 *
 * All distance constants are in **dp**. Callers must convert pixel
 * coordinates (from MotionEvent) to dp using [pxToDp] before invoking
 * [computeRing]. The [angle] and [distance] helpers work in whatever
 * unit is passed — they are pure arithmetic.
 *
 * This class has zero Android dependencies and is fully unit-testable.
 */
class GeometryEngine {

    /** Deadzone edge — inner ring begins here. */
    val deadZoneRadius: Float = DEAD_ZONE_RADIUS

    /** Inner ring ends / outer ring begins (boundary has hysteresis). */
    val innerRadiusMax: Float = INNER_RADIUS_MAX

    /** Outer ring ends; beyond this, positions clamp to OUTER. */
    val outerRadiusMax: Float = OUTER_RADIUS_MAX

    /** Number of angular segments per ring (45° each). */
    val segmentCount: Int = SEGMENT_COUNT

    /**
     * Identifies which ring a finger position belongs to, applying
     * hysteresis near both boundaries: [DEAD_ZONE_RADIUS] (center
     * deadzone) and [INNER_RADIUS_MAX] (inner/outer boundary).
     *
     * Used for BOTH primary and secondary ring tracking — the secondary
     * menu shares the same geometry, just centred on a different anchor.
     *
     * Hysteresis logic:
     * - Inside the deadzone band (within [HYSTERESIS] of [DEAD_ZONE_RADIUS]):
     *   a finger already in INNER/OUTER lingers there; with no prior
     *   state the gesture stays [Ring.NONE] until it clearly exits.
     * - Near [INNER_RADIUS_MAX], INNER/OUTER assignments retain the
     *   previous ring within a ±[HYSTERESIS] deadband.
     * - Beyond [OUTER_RADIUS_MAX]: clamped to [Ring.OUTER] (overshoot
     *   keeps the outer-ring selection along the current ray).
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
        // Overshoot beyond the outer edge: clamp to the outer ring.
        // The segment along the anchor→finger ray remains selected.
        if (distanceFromCenter > OUTER_RADIUS_MAX) return Ring.OUTER

        // ── Deadzone boundary (0 .. DEAD_ZONE_RADIUS) ─────────────
        val deadLower = DEAD_ZONE_RADIUS - HYSTERESIS   // e.g. 52 dp
        val deadUpper = DEAD_ZONE_RADIUS + HYSTERESIS   // e.g. 68 dp

        if (distanceFromCenter < deadLower) return Ring.NONE

        if (distanceFromCenter <= deadUpper) {
            // Inside the deadzone hysteresis band — retain previous
            // state. With no prior state, remain unselected until the
            // finger clearly exits the deadzone.
            return when (previousRing) {
                Ring.INNER -> Ring.INNER
                Ring.OUTER -> Ring.OUTER
                Ring.NONE  -> Ring.NONE
            }
        }

        // ── Inner/outer boundary (DEAD_ZONE_RADIUS .. OUTER_RADIUS_MAX) ──
        val boundary = INNER_RADIUS_MAX
        val lowerHysteresis = boundary - HYSTERESIS   // e.g. 112 dp
        val upperHysteresis = boundary + HYSTERESIS   // e.g. 128 dp

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
            distanceFromCenter < DEAD_ZONE_RADIUS -> Ring.NONE
            distanceFromCenter > INNER_RADIUS_MAX -> Ring.OUTER  // includes overshoot clamp
            else -> Ring.INNER
        }
    }

    companion object {
        // ── Ring radii (dp) ──────────────────────────────────────
        /** Center deadzone — gestures here select nothing and stay silent. */
        const val DEAD_ZONE_RADIUS = 60f

        /** Inner ring starts at the deadzone edge. */
        const val INNER_RADIUS_MIN = DEAD_ZONE_RADIUS

        /** Inner ring ends / outer ring begins (boundary has hysteresis). */
        const val INNER_RADIUS_MAX = 120f

        /** Outer ring ends; beyond this, positions clamp to OUTER. */
        const val OUTER_RADIUS_MAX = 180f

        // ── Segment configuration ────────────────────────────────
        const val SEGMENT_COUNT = 8

        // ── Hysteresis band width (dp) ──────────────────────────
        const val HYSTERESIS = 8f

        /**
         * Converts a pixel distance to dp using the screen density.
         * Call this before passing MotionEvent-derived distances to
         * [computeRing], since all ring constants are in dp.
         */
        fun pxToDp(px: Float, density: Float): Float = px / density
    }

    /**
     * Represents which concentric ring the finger currently occupies.
     *
     * [NONE] means the finger is inside the center deadzone —
     * the gesture should be treated as cancelled or ignored.
     * Overshoot beyond the outer edge no longer produces [NONE];
     * it clamps to [Ring.OUTER].
     */
    enum class Ring {
        INNER,
        OUTER,
        NONE
    }
}
