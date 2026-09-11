package com.radialtype.bench

import com.radialtype.engine.GeometryEngine.Ring

/**
 * Session-level diagnostics computed from completed TrialRecords at
 * summary time (Package 0.1 addendum). Everything here is derived
 * from discrete classifications the FSM already made — no
 * geometry-in-px assumptions — so the numbers are stable across
 * devices and independent of how the engine anchors its menus.
 *
 * Each function takes ALL completed records and filters internally,
 * so callers just hand over the record list.
 */
object TrialMetrics {

    // ── Miss-aspect tally ────────────────────────────────────────

    data class MissAspects(
        val menu: Int, val ring: Int, val segment: Int, val misses: Int
    )

    /** Counts of each miss aspect over all MISS records. */
    fun missAspects(records: List<TrialRecord>): MissAspects {
        var menu = 0; var ring = 0; var segment = 0; var total = 0
        for (rec in records) {
            if (rec.outcome != TrialOutcome.MISS) continue
            total++
            if (MissAspect.MENU in rec.missedBy) menu++
            if (MissAspect.RING in rec.missedBy) ring++
            if (MissAspect.SEGMENT in rec.missedBy) segment++
        }
        return MissAspects(menu, ring, segment, total)
    }

    // ── Under/overshoot (ring errors, per leg) ─────────────────────

    data class RingErrorSplit(
        val undershoot: Int,
        val overshoot: Int,
        /** Percent of ring errors that were undershoots; 50 when none. */
        val undershootPct: Int
    )

    /**
     * Ring-mismatch direction among menu-correct misses of the given
     * level: committed INNER while targeting OUTER = UNDERSHOOT (the
     * flick died short); OUTER while targeting INNER = OVERSHOOT.
     * Wrong-menu commits are excluded — their radius was judged
     * against the other menu's anchor.
     *
     * PRIMARY = the classic ballistic flick-length measure (Package
     * 0.1 brief). SECONDARY = flick #2 only, launch cell → target.
     * The launch flick of a dwell gesture has its own split in
     * [launchLegErrors].
     */
    fun ringErrors(records: List<TrialRecord>, level: TargetLevel): RingErrorSplit {
        var under = 0; var over = 0
        for (rec in records) {
            if (rec.outcome != TrialOutcome.MISS) continue
            if (rec.target.level != level) continue
            if (rec.committedOnWrongMenu) continue
            if (MissAspect.RING !in rec.missedBy) continue
            val got = rec.committed ?: continue
            if (got.first == Ring.INNER && rec.target.ring == Ring.OUTER) under++
            else if (got.first == Ring.OUTER && rec.target.ring == Ring.INNER) over++
        }
        val total = under + over
        val pct = if (total == 0) 50 else 100 * under / total
        return RingErrorSplit(under, over, pct)
    }
    
    data class LaunchLegSplit(
        val ringUnder: Int,
        val ringOver: Int,
        val segment: Int,
        /** Menu opened, but the finger dwelled in the deadzone. */
        val notReached: Int,
        /** Dwell cell matched the designated origin exactly. */
        val clean: Int,
        /** Trials with a measurable launch cell (denominator). */
        val measured: Int
    )

    fun launchLegErrors(records: List<TrialRecord>): LaunchLegSplit {
        var under = 0; var over = 0; var seg = 0; var nr = 0; var clean = 0; var n = 0
        for (rec in records) {
            if (rec.outcome == TrialOutcome.ABORTED) continue
            if (rec.target.level != TargetLevel.SECONDARY) continue
            val origin = rec.originCell ?: continue
            if (rec.committedOnWrongMenu) continue
            val dwell = rec.launchCell
            if (dwell == null) {
                if (rec.launchNotReached) nr++
                continue
            }
            n++
            val (dr, ds) = dwell
            if (dr == Ring.INNER && origin.ring == Ring.OUTER) under++
            else if (dr == Ring.OUTER && origin.ring == Ring.INNER) over++
            if (ds != origin.segment) seg++
            if (dr == origin.ring && ds == origin.segment) clean++
        }
        return LaunchLegSplit(under, over, seg, nr, clean, n)
    }

    // ── Rotational bias ──────────────────────────────────────────

    data class AngularBias(
        /** Signed segment offset → trial count; -4..-1 and 1..4. */
        val histogram: Map<Int, Int>,
        /** Mean signed offset; positive = clockwise of target. */
        val meanSignedSegments: Float,
        val cwCount: Int,
        val ccwCount: Int,
        val samples: Int
    )

    /**
     * Signed rotational error of every menu-correct miss, wrapped to
     * [-4, 4] segments. Zero-offset misses (pure ring errors) are
     * excluded — they carry no angular information. A persistent
     * sign with decent sample size means systematic launch-angle
     * bias; symmetric spread means aim noise.
     */
    fun angularBias(records: List<TrialRecord>): AngularBias {
        val hist = HashMap<Int, Int>()
        var sum = 0; var cw = 0; var ccw = 0; var n = 0
        for (rec in records) {
            if (rec.outcome != TrialOutcome.MISS) continue
            if (rec.committedOnWrongMenu) continue
            val got = rec.committed ?: continue
            var d = (got.second - rec.target.segment) % 8
            if (d > 4) d -= 8
            if (d < -4) d += 8
            if (d == 0) continue
            hist[d] = (hist[d] ?: 0) + 1
            sum += d; n++
            if (d > 0) cw++ else ccw++
        }
        return AngularBias(
            hist, if (n == 0) 0f else sum.toFloat() / n, cw, ccw, n
        )
    }

    // ── Spreads ──────────────────────────────────────────────────

    data class Spread(val median: Long?, val p90: Long?, val n: Int)

    /**
     * Distribution of dwell-handoff lag (bend → SECONDARY open) over
     * trials where both are measurable. Median isolates the typical
     * gate+dwell cost; p90 catches tail misfires.
     */
    fun dwellLagSpread(records: List<TrialRecord>): Spread =
        longSpread(records.mapNotNull { it.dwellLagMs })

    /** Movement time over HITS, filtered by menu level. */
    fun movementTimeSpread(
        records: List<TrialRecord>, level: TargetLevel
    ): Spread = longSpread(
        records.filter {
            it.outcome == TrialOutcome.HIT && it.target.level == level &&
                it.movementTimeMs > 0
        }.map { it.movementTimeMs }
    )

    data class SpeedSpread(
        val medianPxPerMs: Float?, val p90PxPerMs: Float?, val n: Int
    )

    /**
     * Distribution of speed AT THE BEND over all measurable trials
     * (hits and misses alike — every dwell gesture decelerates
     * somewhere). Measured over the same trailing-40 ms span the
     * gate uses, so dividing by density makes it directly comparable
     * to the dwell-gate ceiling in dp/ms.
     */
    fun bendSpeedSpread(records: List<TrialRecord>): SpeedSpread {
        val v = records.mapNotNull { it.bendSpeedPxPerMs }.sorted()
        if (v.isEmpty()) return SpeedSpread(null, null, 0)
        val n = v.size
        return SpeedSpread(
            v[n / 2],
            v[((n - 1) * 0.9).toInt().coerceIn(0, n - 1)],
            n
        )
    }

    // ── Steering churn ────────────────────────────────────────────

    /**
     * Mean segment corrections per completed gesture, using the same
     * convention as the trace headers (a single populated cell counts
     * as zero corrections). High values = the finger bounces across
     * spoke boundaries while steering — hysteresis territory.
     */
    fun meanCorrections(records: List<TrialRecord>): Float {
        if (records.isEmpty()) return 0f
        var total = 0
        for (rec in records) {
            val segChanges = rec.events.count { it is BenchEvent.SegmentChanged }
            if (segChanges > 0) total += segChanges - 1
        }
        return total.toFloat() / records.size
    }

    // ── Helper ────────────────────────────────────────────────────

    private fun longSpread(values: List<Long>): Spread {
        if (values.isEmpty()) return Spread(null, null, 0)
        val s = values.sorted()
        val n = s.size
        return Spread(
            s[n / 2],
            s[((n - 1) * 0.9).toInt().coerceIn(0, n - 1)],
            n
        )
    }
}
