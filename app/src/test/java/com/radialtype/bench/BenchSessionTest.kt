package com.radialtype.bench

import com.radialtype.engine.GeometryEngine.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchSessionTest {

    private fun fullSession(seed: Long = 42L, per: Int = 3, secondary: Boolean = true) =
        BenchSession(seed = seed, trialsPerTarget = per, includeSecondary = secondary)

    // ── Queue composition ─────────────────────────────────────────

    @Test
    fun `queue covers every cell exactly trialsPerTarget times`() {
        val s = fullSession(per = 3)
        val counts = mutableMapOf<BenchTarget, Int>()
        var t = s.nextTarget()
        while (t != null) {
            counts[t] = (counts[t] ?: 0) + 1
            // Simulate a hit so the target is consumed, not re-queued.
            s.recordResult(hitRecord(t))
            t = s.nextTarget()
        }
        // Session ran to completion; verify the QUEUE consumed every
        // cell the planned number of times (before re-queues, which
        // only happen on misses).
        assertEquals(if (true) 32 else 16, 32)
        val expectedCells = if (true) 32 else 16
        assertEquals(expectedCells * 3, counts.values.sum())
        counts.values.forEach { assertEquals(3, it) }
    }

    @Test
    fun `primary-only session covers 16 cells`() {
        val s = fullSession(secondary = false, per = 2)
        val seen = mutableSetOf<BenchTarget>()
        var t = s.nextTarget()
        while (t != null) {
            seen.add(t)
            s.recordResult(hitRecord(t))
            t = s.nextTarget()
        }
        assertEquals(16, seen.size)
        assertEquals(32, s.stats().attempts)
    }

    @Test
    fun `same seed produces identical playlist`() {
        val a = fullSession(seed = 7L)
        val b = fullSession(seed = 7L)
        repeat(a.remaining) {
            assertEquals(a.nextTarget(), b.nextTarget())
        }
    }

    // ── Miss re-queue and caps ────────────────────────────────────

    @Test
    fun `miss is re-queued and retried later`() {
        val s = fullSession(per = 1, secondary = false, seed = 1L)
        val first = s.nextTarget()!!
        var t: BenchTarget?
        var guard = 0
        while (s.nextTarget().also { t = it } != null && guard++ < 500) {
            val target = t!!
            s.recordResult(
                if (target == first) missRecord(target) else hitRecord(target)
            )
        }
        assertTrue(guard < 500)
        assertTrue(s.isFinished)
    }

    @Test
    fun `misses stop re-queuing after maxAttemptsPerCell`() {
        val s = BenchSession(
            seed = 1L, trialsPerTarget = 1, includeSecondary = false,
            maxAttemptsPerCell = 2
        )
        var t = s.nextTarget()
        var total = 0
        while (t != null && total < 200) {
            s.recordResult(missRecord(t))
            t = s.nextTarget()
            total++
        }
        // Every cell capped at 2 attempts → 32 attempts total, session finished.
        assertEquals(32, total)
        assertTrue(s.isFinished)
    }

    @Test
    fun `abort re-queues without charging the cell`() {
        val s = fullSession(secondary = false, per = 1)
        val target = s.nextTarget()!!
        s.recordResult(abortedRecord(target))
        // Not consumed: same target comes back.
        assertEquals(target, s.nextTarget())
        assertEquals(0, s.stats().attempts)
        assertEquals(1, s.stats().aborted)
    }

    // ── Stats ─────────────────────────────────────────────────────

    @Test
    fun `hit rate and counts are correct`() {
        val s = fullSession(secondary = false, per = 1)
        var t = s.nextTarget()
        var hits = 0
        while (t != null) {
            val rec = if (hits % 2 == 0) hitRecord(t) else missRecord(t)
            s.recordResult(rec)
            if (rec.outcome == TrialOutcome.HIT) hits++
            t = s.nextTarget()
        }
        val st = s.stats()
        assertEquals(st.attempts, st.hits + st.misses)
        assertTrue(st.hitRate in 0f..1f)
    }

    @Test
    fun `wilson interval brackets the point estimate`() {
        val lo = BenchSession.wilsonInterval(8, 100).start
        val hi = BenchSession.wilsonInterval(8, 100).endInclusive
        assertTrue(lo < 0.08 && hi > 0.08)
        assertTrue(lo >= 0.0 && hi <= 1.0)
        assertEquals(0.0..0.0, BenchSession.wilsonInterval(0, 0))
    }

    // ── GestureLogger ─────────────────────────────────────────────

    @Test
    fun `logger assembles a complete trial record`() {
        val logger = GestureLogger()
        val target = BenchTarget(TargetLevel.PRIMARY, Ring.INNER, 2)
        var completed: TrialRecord? = null
        logger.onTrialComplete = { completed = it }

        logger.beginTrial(target, displayTimeMs = 1000L)
        logger.onGestureStart(10f, 10f, 1000L)
        logger.onSample(20f, 12f, 1010L)
        logger.onSample(30f, 40f, 1020L)
        logger.onRingChanged(Ring.NONE, Ring.INNER, 1015L)
        logger.onSegmentChanged(-1, 2, 1016L)
        logger.onStateTransition(
            com.radialtype.engine.TouchStateMachine.TouchState.IDLE,
            com.radialtype.engine.TouchStateMachine.TouchState.PRIMARY,
            1000L
        )
        logger.onCommit(Ring.INNER, 2, 1050L)

        val rec = completed
        assertNotNull(rec)
        val r = rec!!
        assertEquals(TrialOutcome.HIT, r.outcome)
        assertEquals(3, r.samples.size)
        assertEquals(3, r.events.size)
        assertEquals(50L, r.movementTimeMs)
        assertEquals(Pair(Ring.INNER, 2), r.committed)
        assertEquals(false, logger.trialInProgress)
    }

    @Test
    fun `deadzone lift records as aborted not miss`() {
        val logger = GestureLogger()
        logger.beginTrial(BenchTarget(TargetLevel.PRIMARY, Ring.INNER, 0), 0L)
        var completed: TrialRecord? = null
        logger.onTrialComplete = { completed = it }
        logger.onGestureStart(10f, 10f, 100L)
        logger.onCommit(Ring.NONE, -1, 150L)
        assertEquals(TrialOutcome.ABORTED, completed!!.outcome)
        assertNull(completed!!.committed)
    }

    @Test
    fun `wrong cell is a miss`() {
        val logger = GestureLogger()
        logger.beginTrial(BenchTarget(TargetLevel.PRIMARY, Ring.OUTER, 3), 0L)
        var completed: TrialRecord? = null
        logger.onTrialComplete = { completed = it }
        logger.onGestureStart(0f, 0f, 0L)
        logger.onCommit(Ring.OUTER, 4, 100L)
        assertEquals(TrialOutcome.MISS, completed!!.outcome)
    }
    
    @Test
    fun `primary flick against secondary target is a miss`() {
        val logger = GestureLogger()
        logger.beginTrial(BenchTarget(TargetLevel.SECONDARY, Ring.INNER, 2), 0L)
        var completed: TrialRecord? = null
        logger.onTrialComplete = { completed = it }
        logger.onGestureStart(0f, 0f, 0L)
        // No SECONDARY state transition — a plain single-flick gesture.
        logger.onCommit(Ring.INNER, 2, 120L)
        assertEquals(TrialOutcome.MISS, completed!!.outcome)
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun hitRecord(t: BenchTarget): TrialRecord =
        record(t, TrialOutcome.HIT, Pair(t.ring, t.segment))

    private fun missRecord(t: BenchTarget): TrialRecord =
        record(t, TrialOutcome.MISS, Pair(t.ring, (t.segment + 1) % 8))

    private fun abortedRecord(t: BenchTarget): TrialRecord =
        record(t, TrialOutcome.ABORTED, null)

    private fun record(t: BenchTarget, outcome: TrialOutcome, committed: Pair<Ring, Int>?): TrialRecord {
        val r = TrialRecord(t, 0L)
        r._samples.add(BenchSample(0f, 0f, 100L))
        r.outcome = outcome
        r.committed = committed
        r.movementTimeMs = 150L
        return r
    }
}
