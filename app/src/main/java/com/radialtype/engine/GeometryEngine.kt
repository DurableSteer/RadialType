package com.radialtype.engine

import kotlin.math.atan2
import kotlin.math.hypot
import com.radialtype.settings.SettingsManager

/**
 * Pure-math engine for radial keyboard geometry.
 *
 * Defines a deadzone and two concentric rings around the touch anchor.
 * The same geometry is used for both PRIMARY and SECONDARY menus —
 * the secondary menu is simply re-centred on the dwell point.
 *
 * **Runtime configurability (Module 12):** ring radii are mutable
 * instance fields. [refreshFromSettings] pulls current values from
 * [SettingsManager]; callers invoke it when the user adjusts the
 * advanced sliders so changes take effect without recreating the engine.
 *
 * **Overshoot clamping:** positions beyond [outerRadiusMax] are NOT
 * rejected — they resolve to [Ring.OUTER]. [Ring.NONE] is produced only
 * by the center deadzone.
 *
 * All distance constants are in **dp**. Callers must convert pixel
 * coordinates (from MotionEvent) to dp using [pxToDp] before invoking
 * [computeRing]. This class has zero Android dependencies and is fully
 * unit-testable (defaults reproduce the legacy constants).
 */
class GeometryEngine(
    deadZoneRadiusDp: Float = DEAD_ZONE_RADIUS,
    innerRadiusMaxDp: Float = INNER_RADIUS_MAX,
    outerRadiusMaxDp: Float = OUTER_RADIUS_MAX
) {

    /** Deadzone edge — inner ring begins here (dp). */
    var deadZoneRadius: Float = deadZoneRadiusDp

    /** Inner ring ends / outer ring begins (boundary has hysteresis, dp). */
    var innerRadiusMax: Float = innerRadiusMaxDp

    /** Outer ring ends; beyond this, positions clamp to OUTER (dp). */
    var outerRadiusMax: Float = outerRadiusMaxDp

    /** Number of angular segments per ring (45° each). */
    val segmentCount: Int = SEGMENT_COUNT

    /**
     * Pulls current ring radii from [SettingsManager] so user-adjusted
     * sliders take effect live. Safe to call on every touch-down.
     */
    fun refreshFromSettings() {
        deadZoneRadius = SettingsManager.deadzoneRadius
        innerRadiusMax = SettingsManager.outerRingRadius
        outerRadiusMax = SettingsManager.outerRingMaxRadius
    }

    /**
     * Identifies which ring a finger position belongs to, applying
     * hysteresis near both boundaries: the center deadzone edge and
     * [innerRadiusMax] (inner/outer boundary).
     *
     * Hysteresis logic:
     * - Inside the deadzone band (within [HYSTERESIS] of the deadzone
     *   edge): a finger already in INNER/OUTER lingers there; with no
     *   prior state the gesture stays [Ring.NONE] until it clearly exits.
     * - Near [innerRadiusMax], INNER/OUTER assignments retain the
     *   previous ring within a ±[HYSTERESIS] deadband.
     * - Beyond [outerRadiusMax]: clamped to [Ring.OUTER] (overshoot
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
        if (distanceFromCenter > outerRadiusMax) return Ring.OUTER

        // ── Deadzone boundary (0 .. deadZoneRadius) ───────────────
        val deadLower = deadZoneRadius - HYSTERESIS
        val deadUpper = deadZoneRadius + HYSTERESIS

        if (distanceFromCenter < deadLower) return Ring.NONE

        if (distanceFromCenter <= deadUpper) {
            return when (previousRing) {
                Ring.INNER -> Ring.INNER
                Ring.OUTER -> Ring.OUTER
                Ring.NONE  -> Ring.NONE
            }
        }

        // ── Inner/outer boundary (deadzone .. outerRadiusMax) ─────
        val boundary = innerRadiusMax
        val lowerHysteresis = boundary - HYSTERESIS
        val upperHysteresis = boundary + HYSTERESIS

        return when {
            distanceFromCenter < lowerHysteresis -> Ring.INNER
            distanceFromCenter > upperHysteresis -> Ring.OUTER
            else -> when (previousRing) {
                Ring.OUTER -> Ring.OUTER
                Ring.INNER -> Ring.INNER
                Ring.NONE  -> {
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
     * −22.5° to +22.5°. Segments proceed clockwise.
     *
     * @param angleDegrees Angle in degrees, normalized to 0–360°.
     * @return Segment index 0–7.
     */
    fun computeSegment(angleDegrees: Float): Int {
        val segmentWidth = 360f / SEGMENT_COUNT
        val halfSegment = segmentWidth / 2f
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
     * in degrees, normalized to 0–360°. 0° = east; angles increase
     * clockwise (screen Y is inverted).
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
            distanceFromCenter < deadZoneRadius -> Ring.NONE
            distanceFromCenter > innerRadiusMax -> Ring.OUTER  // includes overshoot clamp
            else -> Ring.INNER
        }
    }

    companion object {
        // ── Ring radii (dp) — compiled-in defaults ───────────────
        const val DEAD_ZONE_RADIUS = 60f
        const val INNER_RADIUS_MIN = DEAD_ZONE_RADIUS
        const val INNER_RADIUS_MAX = 120f
        const val OUTER_RADIUS_MAX = 180f

        const val SEGMENT_COUNT = 8

        const val HYSTERESIS = 8f

        /**
         * Converts a pixel distance to dp using the screen density.
         */
        fun pxToDp(px: Float, density: Float): Float = px / density
    }

    /**
     * Represents which concentric ring the finger currently occupies.
     * [NONE] means the finger is inside the center deadzone.
     */
    enum class Ring {
        INNER,
        OUTER,
        NONE
    }
}
