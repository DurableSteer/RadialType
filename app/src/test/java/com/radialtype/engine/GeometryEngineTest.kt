package com.radialtype.engine
import com.radialtype.engine.GeometryEngine.Ring

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for [GeometryEngine].
 *
 * Constants under test (all dp):
 *   DEAD_ZONE_RADIUS  = 60   inner ring: 60..120   outer ring: 120..180
 *   HYSTERESIS        = 8    (bands: 52–68 and 112–128)
 *
 * Overshoot beyond 180 clamps to OUTER — NONE comes only from the
 * center deadzone.
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
        assertEquals(0f, engine.angle(0f, 0f, 100f, 0f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 90 for due south`() {
        assertEquals(90f, engine.angle(0f, 0f, 0f, 100f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 180 for due west`() {
        assertEquals(180f, engine.angle(0f, 0f, -100f, 0f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 270 for due north`() {
        assertEquals(270f, engine.angle(0f, 0f, 0f, -100f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle normalizes negative atan2 result to 0-360`() {
        assertEquals(315f, engine.angle(0f, 0f, 100f, -100f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle returns 0 when anchor equals current point`() {
        assertEquals(0f, engine.angle(5f, 5f, 5f, 5f), ANGLE_TOLERANCE)
    }

    @Test
    fun `angle computes 45 degrees for southeast`() {
        assertEquals(45f, engine.angle(0f, 0f, 100f, 100f), ANGLE_TOLERANCE)
    }

    // ════════════════════════════════════════════════════════════
    //  COMPUTE RING — clear zones
    // ════════════════════════════════════════════════════════════

    @Test
    fun `ring returns NONE at center`() {
        assertEquals(Ring.NONE, engine.computeRing(0f, Ring.NONE))
    }

    @Test
    fun `ring returns NONE well within deadzone`() {
        assertEquals(Ring.NONE, engine.computeRing(30f, Ring.NONE))
    }

    @Test
    fun `ring returns NONE just below deadzone lower hysteresis edge`() {
        // deadLower = 60 - 8 = 52
        assertEquals(Ring.NONE, engine.computeRing(51.9f, Ring.NONE))
    }

    @Test
    fun `ring returns INNER just above deadzone upper hysteresis edge`() {
        // deadUpper = 60 + 8 = 68; 68.1 is clearly inner territory
        assertEquals(Ring.INNER, engine.computeRing(68.1f, Ring.NONE))
    }

    @Test
    fun `ring returns INNER well within inner zone`() {
        assertEquals(Ring.INNER, engine.computeRing(90f, Ring.NONE))
    }

    @Test
    fun `ring returns INNER just below inner-outer lower hysteresis boundary`() {
        // lower hysteresis = 120 - 8 = 112
        assertEquals(Ring.INNER, engine.computeRing(111.9f, Ring.NONE))
    }

    @Test
    fun `ring returns OUTER well within outer zone`() {
        assertEquals(Ring.OUTER, engine.computeRing(140f, Ring.NONE))
    }

    @Test
    fun `ring returns OUTER just above upper hysteresis boundary`() {
        // upper hysteresis = 120 + 8 = 128
        assertEquals(Ring.OUTER, engine.computeRing(128.1f, Ring.NONE))
    }

    @Test
    fun `ring clamps overshoot beyond outer max to OUTER`() {
        assertEquals(Ring.OUTER, engine.computeRing(180.1f, Ring.NONE))
    }

    @Test
    fun `ring clamps far beyond outer max to OUTER`() {
        assertEquals(Ring.OUTER, engine.computeRing(500f, Ring.NONE))
    }

    @Test
    fun `ring returns OUTER at exactly outer max boundary`() {
        // 180 is not strictly greater than OUTER_RADIUS_MAX → hysteresis path
        assertEquals(Ring.OUTER, engine.computeRing(180f, Ring.OUTER))
    }

    // ════════════════════════════════════════════════════════════
    //  COMPUTE RING — hysteresis behavior
    // ════════════════════════════════════════════════════════════

    // ── Deadzone boundary band (52..68) ──

    @Test
    fun `deadzone hysteresis keeps INNER when slightly below deadzone edge`() {
        // 55 is in the band; previously INNER → stays INNER
        assertEquals(Ring.INNER, engine.computeRing(55f, Ring.INNER))
    }

    @Test
    fun `deadzone hysteresis keeps OUTER when slightly above deadzone edge`() {
        // 65 is in the band; prev OUTER → stays OUTER
        assertEquals(Ring.OUTER, engine.computeRing(65f, Ring.OUTER))
    }

    @Test
    fun `deadzone with no prior state stays NONE at deadzone edge`() {
        // 60 exactly: within band, no prior → NONE
        assertEquals(Ring.NONE, engine.computeRing(60f, Ring.NONE))
    }

    @Test
    fun `deadzone retains INNER at band edges`() {
        assertEquals(Ring.INNER, engine.computeRing(52f, Ring.INNER))
        assertEquals(Ring.INNER, engine.computeRing(68f, Ring.INNER))
    }

    @Test
    fun `deadzone retains OUTER at band edges`() {
        assertEquals(Ring.OUTER, engine.computeRing(52f, Ring.OUTER))
        assertEquals(Ring.OUTER, engine.computeRing(68f, Ring.OUTER))
    }

    // ── Inner/outer boundary band (112..128) ──

    @Test
    fun `hysteresis keeps finger in INNER when slightly past boundary`() {
        // 115 is inside the 112..128 band; prev INNER → INNER
        assertEquals(Ring.INNER, engine.computeRing(115f, Ring.INNER))
    }

    @Test
    fun `hysteresis keeps finger in OUTER when slightly before boundary`() {
        // 125 is in the band; prev OUTER → OUTER
        assertEquals(Ring.OUTER, engine.computeRing(125f, Ring.OUTER))
    }

    @Test
    fun `hysteresis allows transition INNER to OUTER past upper hysteresis`() {
        assertEquals(Ring.OUTER, engine.computeRing(128.1f, Ring.INNER))
    }

    @Test
    fun `hysteresis allows transition OUTER to INNER when below lower hysteresis`() {
        assertEquals(Ring.INNER, engine.computeRing(111.9f, Ring.OUTER))
    }

    @Test
    fun `hysteresis at exact boundary retains previous ring when previously INNER`() {
        assertEquals(Ring.INNER, engine.computeRing(120f, Ring.INNER))
    }

    @Test
    fun `hysteresis at exact boundary retains previous ring when previously OUTER`() {
        assertEquals(Ring.OUTER, engine.computeRing(120f, Ring.OUTER))
    }

    @Test
    fun `hysteresis with no prior state at boundary favors INNER`() {
        // Exactly at INNER_RADIUS_MAX → INNER (closer to center)
        assertEquals(Ring.INNER, engine.computeRing(120f, Ring.NONE))
    }

    @Test
    fun `hysteresis with no prior state below boundary favors INNER`() {
        assertEquals(Ring.INNER, engine.computeRing(115f, Ring.NONE))
    }

    @Test
    fun `hysteresis with no prior state above boundary favors OUTER`() {
        assertEquals(Ring.OUTER, engine.computeRing(125f, Ring.NONE))
    }

    @Test
    fun `hysteresis at lower edge of boundary band with previous INNER`() {
        assertEquals(Ring.INNER, engine.computeRing(112f, Ring.INNER))
    }

    @Test
    fun `hysteresis at lower edge of boundary band with previous OUTER`() {
        assertEquals(Ring.OUTER, engine.computeRing(112f, Ring.OUTER))
    }

    @Test
    fun `hysteresis at upper edge of boundary band with previous INNER`() {
        assertEquals(Ring.INNER, engine.computeRing(128f, Ring.INNER))
    }

    @Test
    fun `hysteresis at upper edge of boundary band with previous OUTER`() {
        assertEquals(Ring.OUTER, engine.computeRing(128f, Ring.OUTER))
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
        assertEquals(0, engine.computeSegment(360f))
    }

    @Test
    fun `segment wraps at 337_5 to segment 0`() {
        assertEquals(0, engine.computeSegment(337.5f))
    }

    @Test
    fun `segment at 359_9 returns 0`() {
        assertEquals(0, engine.computeSegment(359.99f))
    }

    @Test
    fun `segment at 337_4 returns 7`() {
        assertEquals(7, engine.computeSegment(337.49f))
    }

    // ════════════════════════════════════════════════════════════
    //  RING WITHOUT HYSTERESIS (sanity check)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `ringWithoutHysteresis returns INNER below boundary`() {
        assertEquals(Ring.INNER, engine.ringWithoutHysteresis(100f))
    }

    @Test
    fun `ringWithoutHysteresis returns OUTER above boundary`() {
        assertEquals(Ring.OUTER, engine.ringWithoutHysteresis(121f))
    }

    @Test
    fun `ringWithoutHysteresis returns NONE inside deadzone`() {
        assertEquals(Ring.NONE, engine.ringWithoutHysteresis(50f))
    }

    @Test
    fun `ringWithoutHysteresis at deadzone boundary returns INNER`() {
        // At exactly 60 → INNER (uses < comparison against deadzone)
        assertEquals(Ring.INNER, engine.ringWithoutHysteresis(60f))
    }

    // ════════════════════════════════════════════════════════════
    //  INTEGRATION: angle + distance + ring + segment
    // ════════════════════════════════════════════════════════════

    @Test
    fun `integration - southeast in inner ring segment 1`() {
        val ax = 500f
        val ay = 500f
        val cx = 550f   // +50px right
        val cy = 550f   // +50px down → southeast, 45°

        val distPx = engine.distance(ax, ay, cx, cy)          // ~70.7 px
        val distDp = GeometryEngine.pxToDp(distPx, 1f)        // density 1 → same
        val angle = engine.angle(ax, ay, cx, cy)              // 45°
        val ring = engine.computeRing(distDp, Ring.NONE)      // INNER
        val seg = engine.computeSegment(angle)                // 1

        assertEquals(45f, angle, ANGLE_TOLERANCE)
        assertEquals(Ring.INNER, ring)
        assertEquals(1, seg)
    }

    private fun ang(ax: Float, ay: Float, cx: Float, cy: Float): Float =
        engine.angle(ax, ay, cx, cy)

    private fun seg(ax: Float, ay: Float, cx: Float, cy: Float): Int =
        engine.computeSegment(engine.angle(ax, ay, cx, cy))

    @Test
    fun `integration - deadzone rejects selection at anchor`() {
        // Finger directly on the anchor → NONE, no segment
        val dist = engine.distance(500f, 500f, 500f, 500f)
        assertEquals(Ring.NONE, engine.computeRing(dist, Ring.NONE))
    }

    @Test
    fun `integration - full ring transition sequence simulates finger drag outward`() {
        var ring = Ring.NONE
        val transitions = mutableListOf<Pair<Float, Ring>>()

        // Start at 70dp (clearly inner)
        ring = engine.computeRing(70f, ring)
        transitions.add(70f to ring)

        // 110dp (below lower boundary hysteresis 112 → stays INNER)
        ring = engine.computeRing(110f, ring)
        transitions.add(110f to ring)

        // 115dp (boundary hysteresis band, prev INNER → stays INNER)
        ring = engine.computeRing(115f, ring)
        transitions.add(115f to ring)

        // 125dp (still in band, prev INNER → stays INNER)
        ring = engine.computeRing(125f, ring)
        transitions.add(125f to ring)

        // 130dp (past upper hysteresis → OUTER)
        ring = engine.computeRing(130f, ring)
        transitions.add(130f to ring)

        // Drag back inward: 125 (band, prev OUTER → OUTER)
        ring = engine.computeRing(125f, ring)
        transitions.add(125f to ring)

        // 100dp (below lower boundary hysteresis → INNER)
        ring = engine.computeRing(100f, ring)
        transitions.add(100f to ring)

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
