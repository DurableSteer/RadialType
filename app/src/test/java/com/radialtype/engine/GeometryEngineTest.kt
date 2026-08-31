package com.radialtype.engine
import com.radialtype.engine.GeometryEngine.Ring

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for [GeometryEngine].
 *
 * Covers: distance, angle normalization, ring classification with
 * hysteresis, segment mapping at boundaries, and 360°→0° wraparound.
 */
class GeometryEngineTest {

    private lateinit var engine: GeometryEngine

    @Before
    fun setUp() {
        engine = GeometryEngine()
    }

    // ════════════════════════════════════════════════════════════
    //  DISTANCE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `distance returns 0 for identical points`() {
        assertEquals(0f, engine.distance(5f, 5f, 5f, 5f), TOLERANCE)
    }

    @Test
    fun `distance computes horizontal displacement`() {
        assertEquals(10f, engine.distance(0f, 0f, 10f, 0f), TOLERANCE)
    }

    @Test
    fun `distance computes vertical displacement`() {
        assertEquals(10f, engine.distance(0f, 0f, 0f, 10f), TOLERANCE)
    }

    @Test
    fun `distance computes diagonal via Pythagorean theorem`() {
        // 3-4-5 triangle
        assertEquals(5f, engine.distance(0f, 0f, 3f, 4f), TOLERANCE)
    }

    @Test
    fun `distance is symmetric`() {
        val d1 = engine.distance(10f, 20f, 30f, 40f)
        val d2 = engine.distance(30f, 40f, 10f, 20f)
        assertEquals(d1, d2, TOLERANCE)
    }

    @Test
    fun `distance handles negative coordinates`() {
        assertEquals(5f, engine.distance(-3f, -4f, 0f, 0f), TOLERANCE)
    }

    // ════════════════════════════════════════════════════════════
    //  ANGLE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `angle returns 0 for due east`() {
        // Anchor at origin, finger to the right (+X)
        assertEquals(0f, engine.angle(0f, 0f, 100f, 0f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 90 for due south`() {
        // On screen, +Y is downward → south = 90°
        assertEquals(90f, engine.angle(0f, 0f, 0f, 100f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 180 for due west`() {
        assertEquals(180f, engine.angle(0f, 0f, -100f, 0f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 270 for due north`() {
        // -Y is upward on screen → north = 270°
        assertEquals(270f, engine.angle(0f, 0f, 0f, -100f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle normalizes negative atan2 result to 0-360`() {
        // atan2(-1, 1) ≈ -45°, should normalize to 315°
        assertEquals(315f, engine.angle(0f, 0f, 100f, -100f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 0 when anchor equals current point`() {
        // atan2(0, 0) = 0 in Java/Kotlin Math
        assertEquals(0f, engine.angle(5f, 5f, 5f, 5f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle computes 45 degrees for southeast`() {
        assertEquals(45f, engine.angle(0f, 0f, 100f, 100f), ANGLE_TOLERANCE)
    }

    // ════════════════════════════════════════════════════════════
    //  COMPUTE RING — clear zones (no hysteresis involvement)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `ring returns INNER at center`() {
        assertEquals(Ring.INNER, engine.computeRing(0f, Ring.NONE))
    }

    @Test
    fun `ring returns INNER well within inner zone`() {
        assertEquals(Ring.INNER, engine.computeRing(30f, Ring.NONE))
    }

    @Test
    fun `ring returns INNER just below lower hysteresis boundary`() {
        // Lower hysteresis = 60 - 8 = 52
        assertEquals(Ring.INNER, engine.computeRing(51.9f, Ring.NONE))
    }

    @Test
    fun `ring returns OUTER well within outer zone`() {
        assertEquals(Ring.OUTER, engine.computeRing(90f, Ring.NONE))
    }

    @Test
    fun `ring returns OUTER just above upper hysteresis boundary`() {
        // Upper hysteresis = 60 + 8 = 68
        assertEquals(Ring.OUTER, engine.computeRing(68.1f, Ring.NONE))
    }

    @Test
    fun `ring returns NONE beyond outer max`() {
        assertEquals(Ring.NONE, engine.computeRing(120.1f, Ring.NONE))
    }

    @Test
    fun `ring returns NONE far beyond outer max`() {
        assertEquals(Ring.NONE, engine.computeRing(500f, Ring.NONE))
    }

    @Test
    fun `ring returns OUTER at exactly outer max boundary`() {
        // 120 dp is inclusive — still in the outer ring
        assertEquals(Ring.OUTER, engine.computeRing(120f, Ring.OUTER))
    }

    // ════════════════════════════════════════════════════════════
    //  COMPUTE RING — hysteresis behavior
    // ════════════════════════════════════════════════════════════

    @Test
    fun `hysteresis keeps finger in INNER when slightly past boundary`() {
        // Distance = 65 (past 60, within hysteresis band 52–68)
        // Previously INNER → should stay INNER
        assertEquals(Ring.INNER, engine.computeRing(65f, Ring.INNER))
    }

    @Test
    fun `hysteresis keeps finger in OUTER when slightly before boundary`() {
        // Distance = 55 (before 60, within hysteresis band 52–68)
        // Previously OUTER → should stay OUTER
        assertEquals(Ring.OUTER, engine.computeRing(55f, Ring.OUTER))
    }

    @Test
    fun `hysteresis allows transition from INNER to OUTER when past upper hysteresis`() {
        // Distance = 68.1 (just above upper hysteresis)
        // Previously INNER → should now switch to OUTER
        assertEquals(Ring.OUTER, engine.computeRing(68.1f, Ring.INNER))
    }

    @Test
    fun `hysteresis allows transition from OUTER to INNER when below lower hysteresis`() {
        // Distance = 51.9 (just below lower hysteresis)
        // Previously OUTER → should now switch to INNER
        assertEquals(Ring.INNER, engine.computeRing(51.9f, Ring.OUTER))
    }

    @Test
    fun `hysteresis at exact boundary retains previous ring when previously INNER`() {
        // Distance = 60 exactly (on the boundary)
        assertEquals(Ring.INNER, engine.computeRing(60f, Ring.INNER))
    }

    @Test
    fun `hysteresis at exact boundary retains previous ring when previously OUTER`() {
        // Distance = 60 exactly (on the boundary)
        assertEquals(Ring.OUTER, engine.computeRing(60f, Ring.OUTER))
    }

    @Test
    fun `hysteresis with no prior state at boundary favors INNER`() {
        // Distance = 60 exactly, no prior ring → defaults to INNER
        assertEquals(Ring.INNER, engine.computeRing(60f, Ring.NONE))
    }

    @Test
    fun `hysteresis with no prior state below boundary favors INNER`() {
        // Distance = 55, within hysteresis, no prior → INNER (≤ boundary)
        assertEquals(Ring.INNER, engine.computeRing(55f, Ring.NONE))
    }

    @Test
    fun `hysteresis with no prior state above boundary favors OUTER`() {
        // Distance = 65, within hysteresis, no prior → OUTER (> boundary)
        assertEquals(Ring.OUTER, engine.computeRing(65f, Ring.NONE))
    }

    @Test
    fun `hysteresis at lower edge of band with previous INNER`() {
        // Distance = 52 (lower hysteresis edge), prev INNER → INNER
        assertEquals(Ring.INNER, engine.computeRing(52f, Ring.INNER))
    }

    @Test
    fun `hysteresis at lower edge of band with previous OUTER`() {
        // Distance = 52 (lower hysteresis edge), prev OUTER → OUTER
        assertEquals(Ring.OUTER, engine.computeRing(52f, Ring.OUTER))
    }

    @Test
    fun `hysteresis at upper edge of band with previous INNER`() {
        // Distance = 68 (upper hysteresis edge), prev INNER → INNER
        assertEquals(Ring.INNER, engine.computeRing(68f, Ring.INNER))
    }

    @Test
    fun `hysteresis at upper edge of band with previous OUTER`() {
        // Distance = 68 (upper hysteresis edge), prev OUTER → OUTER
        assertEquals(Ring.OUTER, engine.computeRing(68f, Ring.OUTER))
    }

    // ════════════════════════════════════════════════════════════
    //  COMPUTE SEGMENT — center angles
    // ════════════════════════════════════════════════════════════

    @Test
    fun `segment returns 0 at angle 0 degrees`() {
        assertEquals(0, engine.computeSegment(0f))
    }

    @Test
    fun `segment returns 1 at angle 45 degrees`() {
        assertEquals(1, engine.computeSegment(45f))
    }

    @Test
    fun `segment returns 2 at angle 90 degrees`() {
        assertEquals(2, engine.computeSegment(90f))
    }

    @Test
    fun `segment returns 3 at angle 135 degrees`() {
        assertEquals(3, engine.computeSegment(135f))
    }

    @Test
    fun `segment returns 4 at angle 180 degrees`() {
        assertEquals(4, engine.computeSegment(180f))
    }

    @Test
    fun `segment returns 5 at angle 225 degrees`() {
        assertEquals(5, engine.computeSegment(225f))
    }

    @Test
    fun `segment returns 6 at angle 270 degrees`() {
        assertEquals(6, engine.computeSegment(270f))
    }

    @Test
    fun `segment returns 7 at angle 315 degrees`() {
        assertEquals(7, engine.computeSegment(315f))
    }

    // ════════════════════════════════════════════════════════════
    //  COMPUTE SEGMENT — boundaries and wraparound
    // ════════════════════════════════════════════════════════════

    @Test
    fun `segment boundary at 22_5 degrees belongs to segment 1`() {
        // Exactly on the boundary → rounds up to segment 1
        assertEquals(1, engine.computeSegment(22.5f))
    }

    @Test
    fun `segment just below boundary at 22_4 returns 0`() {
        assertEquals(0, engine.computeSegment(22.49f))
    }

    @Test
    fun `segment boundary at 67_5 degrees belongs to segment 2`() {
        assertEquals(2, engine.computeSegment(67.5f))
    }

    @Test
    fun `segment just below boundary at 67_4 returns 1`() {
        assertEquals(1, engine.computeSegment(67.49f))
    }

    @Test
    fun `segment wraps at 360 degrees back to 0`() {
        // 360° is equivalent to 0°
        assertEquals(0, engine.computeSegment(360f))
    }

    @Test
    fun `segment wraps at 337_5 to segment 0`() {
        // Segment 0 spans 337.5° → 360° → 22.5°
        assertEquals(0, engine.computeSegment(337.5f))
    }

    @Test
    fun `segment at 359_9 returns 0`() {
        assertEquals(0, engine.computeSegment(359.99f))
    }

    @Test
    fun `segment at 337_4 returns 7`() {
        // Just below 337.5° → still in segment 7
        assertEquals(7, engine.computeSegment(337.49f))
    }

    // ════════════════════════════════════════════════════════════
    //  RING WITHOUT HYSTERESIS (sanity check)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `ringWithoutHysteresis returns INNER below boundary`() {
        assertEquals(Ring.INNER, engine.ringWithoutHysteresis(59f))
    }

    @Test
    fun `ringWithoutHysteresis returns OUTER above boundary`() {
        assertEquals(Ring.OUTER, engine.ringWithoutHysteresis(61f))
    }

    @Test
    fun `ringWithoutHysteresis returns NONE above outer max`() {
        assertEquals(Ring.NONE, engine.ringWithoutHysteresis(121f))
    }

    @Test
    fun `ringWithoutHysteresis at boundary returns INNER`() {
        // At exactly 60 → INNER (uses ≤ comparison)
        assertEquals(Ring.INNER, engine.ringWithoutHysteresis(60f))
    }

    // ════════════════════════════════════════════════════════════
    //  INTEGRATION: angle + distance + ring + segment
    // ════════════════════════════════════════════════════════════

    @Test
    fun `integration - southeast in inner ring segment 1`() {
        val ax = 500f
        val ay = 500f
        val cx = 540f   // +40px right
        val cy = 540f   // +40px down → southeast, 45°

        val dist = engine.distance(ax, ay, cx, cy)   // ~56.57
        val ang = engine.angle(ax, ay, cx, cy)        // ~45°
        val ring = engine.computeRing(dist, Ring.NONE) // INNER (< 52)
        val seg = engine.computeSegment(ang)           // segment 1

        assertEquals(45f, ang, ANGLE_TOLERANCE)
        assertEquals(Ring.INNER, ring)
        assertEquals(1, seg)
    }

    @Test
    fun `integration - full ring transition sequence simulates finger drag outward`() {
        var ring = Ring.NONE
        val transitions = mutableListOf<Pair<Float, Ring>>()

        // Start at 30dp (clearly inner)
        ring = engine.computeRing(30f, ring)
        transitions.add(30f to ring)

        // Move to 55dp (hysteresis band, prev INNER → stays INNER)
        ring = engine.computeRing(55f, ring)
        transitions.add(55f to ring)

        // Move to 60dp (boundary, prev INNER → stays INNER)
        ring = engine.computeRing(60f, ring)
        transitions.add(60f to ring)

        // Move to 65dp (still in hysteresis, prev INNER → stays INNER)
        ring = engine.computeRing(65f, ring)
        transitions.add(65f to ring)

        // Move to 70dp (past upper hysteresis → switches to OUTER)
        ring = engine.computeRing(70f, ring)
        transitions.add(70f to ring)

        // Now drag back inward...
        // 65dp (hysteresis, prev OUTER → stays OUTER)
        ring = engine.computeRing(65f, ring)
        transitions.add(65f to ring)

        // 50dp (below lower hysteresis → switches back to INNER)
        ring = engine.computeRing(50f, ring)
        transitions.add(50f to ring)

        assertEquals(Ring.INNER, transitions[0].second)
        assertEquals(Ring.INNER, transitions[1].second)
        assertEquals(Ring.INNER, transitions[2].second)
        assertEquals(Ring.INNER, transitions[3].second)
        assertEquals(Ring.OUTER, transitions[4].second)
        assertEquals(Ring.OUTER, transitions[5].second)
        assertEquals(Ring.INNER, transitions[6].second)
    }

    companion object {
        private const val TOLERANCE = 0.001f
        private const val ANGLE_TOLERANCE = 0.1f
    }
}
