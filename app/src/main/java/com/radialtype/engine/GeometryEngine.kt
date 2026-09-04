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
 * **Thumb-reach profile:** the rings are an ECCENTRIC annulus. Segment
 * boundaries stay exactly at 22.5° + k·45° (the eight cardinal
 * directions keep their bearings), but each direction's radial extent
 * scales by a reach factor f(θ):
 *
 *     f(θ) = 1 − asymmetry · (1 + cos(θ − φ)) / 2
 *
 * where φ is the short direction's bearing. At the short direction
 * f = 1 − a (closest), at the opposite direction f = 1 (full reach),
 * halfway around f = 1 − a/2. The function is C∞-smooth in θ, so
 * classification is seamless across segment spokes, and
 * asymmetry = 0 reproduces the legacy perfect circle exactly.
 *
 * Classification normalizes distance: effective = r / f(θ), then the
 * existing fixed-radius logic (deadzone, boundaries, hysteresis) runs
 * on the normalized value. This means every existing hysteresis
 * behavior carries over unchanged, in consistent dp units.
 *
 * **Overshoot clamping:** positions beyond the outer boundary are NOT
 * rejected — they resolve to [Ring.OUTER]. [Ring.NONE] is produced only
 * by the center deadzone.
 *
 * All distance constants are in **dp**. Callers must convert pixel
 * coordinates (from MotionEvent) to dp using [pxToDp] before invoking
 * [computeRing]. This class has zero Android dependencies and is fully
 * unit-testable (constructor defaults reproduce the legacy constants
 * and the legacy circle).
 */
class GeometryEngine(
    deadZoneRadiusDp: Float = DEAD_ZONE_RADIUS,
    innerRadiusMaxDp: Float = INNER_RADIUS_MAX,
    outerRadiusMaxDp: Float = OUTER_RADIUS_MAX
) {

    /** Deadzone edge (dp) — inner ring begins here. Scales with f(θ). */
    var deadZoneRadius: Float = deadZoneRadiusDp

    /** Inner ring ends / outer ring begins (dp). Scales with f(θ). */
    var innerRadiusMax: Float = innerRadiusMaxDp

    /** Outer ring ends (dp); beyond this, positions clamp to OUTER. */
    var outerRadiusMax: Float = outerRadiusMaxDp

    /** Number of angular segments per ring (45° each). */
    val segmentCount: Int = SEGMENT_COUNT

    /** Ring hysteresis band (dp) — measured in NORMALIZED space. */
    var hysteresisRadiusDp: Float = HYSTERESIS

    /** Angular deadzone around segment boundaries (degrees). */
    var segmentHysteresisDeg: Float = SEGMENT_HYSTERESIS_DEG

    /**
     * Per-cardinal reach factors, each in 0.5..1.0, max normalized to
     * 1.0 by SettingsManager. All-1.0 reproduces the legacy circle.
     * Mutable reference — [refreshFromSettings] installs a copy.
     */
    var reachProfile: FloatArray = FloatArray(SEGMENT_COUNT) { 1f }

    /**
     * Pulls current geometry from [SettingsManager] so user-adjusted
     * sliders take effect live. Safe to call on every touch-down.
     *
     * Radial ordering is enforced: deadzone < inner boundary < outer
     * boundary, with a minimum 20 dp band so a ring can never collapse
     * to zero width even with contradictory slider values.
     */
    fun refreshFromSettings() {
        deadZoneRadius = SettingsManager.deadzoneRadius
        innerRadiusMax = maxOf(SettingsManager.innerRingRadius, deadZoneRadius + 20f)
        outerRadiusMax = maxOf(SettingsManager.outerRingRadius, innerRadiusMax + 20f)
        hysteresisRadiusDp = SettingsManager.ringHysteresisDp
        segmentHysteresisDeg = SettingsManager.segmentHysteresisDeg
        reachProfile = SettingsManager.reachProfile.copyOf()
    }

    // ── Reach profile ────────────────────────────────────────────

    /** Reach factor f(θ) for THIS engine's profile (classification path). */
    fun reachFactorAt(angleDegrees: Float): Float =
        reachFactorAt(angleDegrees, reachProfile)

    /**
     * Boundary radius (dp) for a ring edge whose circle-equivalent
     * radius is [baseRadiusDp] at an absolute bearing. The renderer
     * uses this to draw the profile-following shape.
     */
    fun boundaryRadius(baseRadiusDp: Float, angleDegrees: Float): Float =
        baseRadiusDp * reachFactorAt(angleDegrees)
        
    /**
     * Identifies which ring a finger position belongs to, honoring the
     * reach profile: the radial distance is NORMALIZED by f(θ) first,
     * then the fixed-radius classification (with hysteresis) runs.
     *
     * @param distanceFromCenter Radial distance from anchor, in dp.
     * @param angleDegrees       Absolute bearing from anchor.
     * @param previousRing       Ring from the previous frame.
     */
    fun computeRing(
        distanceFromCenter: Float,
        angleDegrees: Float,
        previousRing: Ring
    ): Ring {
        val effective = distanceFromCenter / reachFactorAt(angleDegrees)
        return computeRing(effective, previousRing)
    }

    /**
     * Fixed-radius ring classification, ignoring the reach profile.
     * Retained for tests and for callers that operate in normalized
     * space. Runtime gesture code must use the three-argument
     * [computeRing].
     */
    fun computeRing(
        distanceFromCenter: Float,
        previousRing: Ring
    ): Ring {
        // Overshoot beyond the outer edge: clamp to the outer ring.
        if (distanceFromCenter > outerRadiusMax) return Ring.OUTER

        // ── Deadzone boundary (0 .. deadZoneRadius) ───────────────
        val deadLower = deadZoneRadius - hysteresisRadiusDp
        val deadUpper = deadZoneRadius + hysteresisRadiusDp

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
        val lowerHysteresis = boundary - hysteresisRadiusDp
        val upperHysteresis = boundary + hysteresisRadiusDp

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
     * Maps an angle to a segment index with hysteresis. The reach
     * profile does NOT affect angular partitioning — cardinal
     * directions and 22.5° + k·45° boundaries are identical to legacy.
     */
    fun computeSegment(angleDegrees: Float, previousSegment: Int): Int {
        val raw = computeSegment(angleDegrees)
        if (previousSegment < 0 || previousSegment >= SEGMENT_COUNT) return raw
        if (raw == previousSegment) return raw

        // Adjacent only (with wraparound): jitter at a shared boundary.
        val adjacent = Math.abs(raw - previousSegment) == 1 ||
            Math.abs(raw - previousSegment) == SEGMENT_COUNT - 1
        if (!adjacent) return raw

        val distToBoundary = boundaryDistance(angleDegrees)
        return if (distToBoundary <= segmentHysteresisDeg) previousSegment else raw
    }

    fun computeSegment(angleDegrees: Float): Int {
        val segmentWidth = 360f / SEGMENT_COUNT
        val halfSegment = segmentWidth / 2f
        val index = ((angleDegrees + halfSegment) / segmentWidth).toInt()
        return index % SEGMENT_COUNT
    }

    /**
     * Angular distance (degrees) from [angleDegrees] to the nearest
     * segment boundary. Boundaries sit at 22.5° + k·45°.
     */
    private fun boundaryDistance(angleDegrees: Float): Float {
        val m = ((angleDegrees % 45f) + 45f) % 45f
        return Math.abs(m - 22.5f)
    }

    /**
     * Standard Euclidean distance between two points. Works in
     * whatever unit the inputs use (typically pixels).
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
     * Which ring a given distance falls in, ignoring hysteresis and the
     * reach profile. Useful for testing boundary behavior in isolation.
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

        /** Default angular deadzone around segment boundaries (deg). */
        const val SEGMENT_HYSTERESIS_DEG = 2f
        /** Default ring hysteresis band (dp). */
        const val HYSTERESIS = 8f

        /**
         * Floor for the reach factor: even at asymmetry = 1 the short
         * direction's band is squeezed to 55% — below that the inner
         * row's cells get physically too small to hit reliably.
         */
        const val MIN_REACH_FACTOR = 0.55f

        /**
         * Reach factor at an absolute bearing for an 8-entry profile.
         * COSINE-interpolated between adjacent cardinal anchors: value
         * [idx] sits exactly at bearing idx·45°, and θ between idx·45
         * and (idx+1)·45 blends the two with a smoothstep — C¹ across
         * every spoke, wraps 7→0 around 360°. All-equal profiles (the
         * legacy circle) short-circuit.
         *
         * Static so the renderer (no engine instance) resolves exactly
         * the profile the FSM classifies with.
         */
        fun reachFactorAt(angleDegrees: Float, profile: FloatArray): Float {
            if (profile.size < SEGMENT_COUNT) return 1f
            if ((0 until SEGMENT_COUNT).all { profile[it] == profile[0] }) {
                return profile[0]
            }
            val a = ((angleDegrees % 360f) + 360f) % 360f
            val idx = (a / 45f).toInt().coerceIn(0, SEGMENT_COUNT - 1)
            val t = (a - idx * 45f) / 45f
            if (t <= 0f) return profile[idx]
            if (t >= 1f) return profile[(idx + 1) % SEGMENT_COUNT]
            val smooth = (1.0 - Math.cos(Math.PI * t)).toFloat() / 2f
            return profile[idx] + (profile[(idx + 1) % SEGMENT_COUNT] - profile[idx]) * smooth
        }

        /** Converts a pixel distance to dp using the screen density. */
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
