package com.radialtype.bench

import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.TouchStateMachine.TouchState
import kotlin.math.hypot

/** One raw touch sample. */
data class BenchSample(val x: Float, val y: Float, val t: Long)

/**
 * A classification event as the FSM saw it, with its timestamp —
 * the "perceived" half of the visualization (Packet 3).
 */
sealed class BenchEvent {
    data class RingChanged(val from: Ring, val to: Ring, val t: Long) : BenchEvent()
    data class SegmentChanged(val from: Int, val to: Int, val t: Long) : BenchEvent()
    data class StateTransition(val from: TouchState, val to: TouchState, val t: Long) : BenchEvent()
    data class Aborted(val t: Long) : BenchEvent()
}

enum class TrialOutcome { HIT, MISS, ABORTED }

/**
 * Which dimension(s) of the target a committed classification got
 * wrong. A MISS can fail on several at once (e.g. committed
 * primary/INNER/SE when the target was secondary/OUTER/SE fails on
 * all three).
 */
enum class MissAspect(val label: String) {
    MENU("menu"),
    RING("ring"),
    SEGMENT("segment");

    companion object {
        /** Human-readable diagnosis, e.g. "menu · ring". Empty set → "". */
        fun describe(aspects: Set<MissAspect>): String =
            aspects.joinToString(" · ") { it.label }
    }
}

/**
 * Complete record of one benchmark trial: everything needed to draw
 * raw-vs-perceived later. Mutable during collection, handed over via
 * [GestureLogger.onTrialComplete] and treated as read-only afterwards.
 */
class TrialRecord(
    val target: BenchTarget,
    val displayTimeMs: Long,
    internal val _samples: MutableList<BenchSample> = mutableListOf(),
    internal val _events: MutableList<BenchEvent> = mutableListOf()
) {
    val samples: List<BenchSample> get() = _samples
    val events: List<BenchEvent> get() = _events

    var outcome: TrialOutcome = TrialOutcome.ABORTED
        internal set

    /** First gesture sample → commit (ms). 0 when no samples arrived. */
    var movementTimeMs: Long = 0L
        internal set

    /** Anchors observed during the trial; null = never emitted. */
    var primaryAnchor: Pair<Float, Float>? = null
        internal set
    var secondaryAnchor: Pair<Float, Float>? = null
        internal set

    /** Final committed classification, or null if aborted. */
    var committed: Pair<Ring, Int>? = null
        internal set

    /**
     * Dimensions the commit got wrong (MISS only). Empty for HIT and
     * ABORTED — an abort entered nothing, so there's nothing to diff.
     */
    var missedBy: Set<MissAspect> = emptySet()
        internal set

    /** True when the gesture committed on the opposite menu from the target. */
    val committedOnWrongMenu: Boolean
        get() = MissAspect.MENU in missedBy

    // ── Dwell-lag instrumentation ────────────────────────────────

    /**
     * Timestamp of the velocity-minimum sample — the "bend" where the
     * finger slowed before the second flick. Null when no trustworthy
     * minimum exists (too few samples, or every candidate window too
     * short to judge speed on).
     */
    val bendTimeMs: Long? by lazy {
        findBend()?.first
    }

    /**
     * Speed at the bend (px/ms, screen units — comparable within a
     * device, not across devices). Roughly the stillness the finger
     * actually reached; compare against the gate ceiling scaled to px.
     */
    val bendSpeedPxPerMs: Float? by lazy {
        findBend()?.second
    }

    /**
     * Dwell lag: time (ms) from the velocity-minimum sample (the
     * bend) to the SECONDARY state transition — i.e. how long after
     * you actually stopped did the menu open. Null when the menu
     * never opened or no bend was detectable.
     *
     *   ≈ 0, low variance  → gate hands off to dwell right at arrival
     *   large constant      → dwell duration is the bottleneck
     *   scales with how hard you decelerated → ceiling is eating tails
     *   negative            → menu opened mid-motion (misfire)
     */
    val dwellLagMs: Long? by lazy {
        val bt = bendTimeMs ?: return@lazy null
        val trans = _events.firstOrNull {
            it is BenchEvent.StateTransition && it.to == TouchState.SECONDARY
        } as? BenchEvent.StateTransition ?: return@lazy null
        trans.t - bt
    }

    /**
     * Finds the sample with the lowest trailing-window speed, using
     * the same window discipline as MotionHistory: 40 ms look-back,
     * minimum 8 ms span for a trustworthy reading. Returns
     * (sampleTimeMs, speedPxPerMs) or null. Plain argmin over the
     * raw samples — this runs once per record at display time, so
     * clarity beats cleverness.
     */
    private fun findBend(): Pair<Long, Float>? {
        val s = _samples
        if (s.size < 3) return null
        var bestT: Long? = null
        var bestSpeed = Float.MAX_VALUE
        for (i in 1 until s.size) {
            // Oldest sample still within the 40 ms window.
            var j = i
            val tNow = s[i].t
            while (j > 0 && tNow - s[j - 1].t <= BEND_WINDOW_MS) j--
            val dt = tNow - s[j].t
            if (dt < BEND_MIN_SPAN_MS) continue      // window too short to judge
            val dx = s[i].x - s[j].x
            val dy = s[i].y - s[j].y
            val speed = hypot(dx, dy) / dt
            if (speed < bestSpeed) {
                bestSpeed = speed
                bestT = s[i].t
            }
        }
        return bestT?.let { it to bestSpeed }
    }

    companion object {
        private const val BEND_WINDOW_MS = 40L
        private const val BEND_MIN_SPAN_MS = 8L
    }
}

/**
 * Concrete [BenchObserver] that assembles one [TrialRecord] per
 * gesture. Only collects between [beginTrial] and the terminating
 * event (commit or abort); stray gestures outside a trial are
 * ignored — the benchmark UI decides when a target is live.
 */
class GestureLogger : BenchObserver {

    /** Fired exactly once per begun trial, with its completed record. */
    var onTrialComplete: ((TrialRecord) -> Unit)? = null

    private var current: TrialRecord? = null

    val trialInProgress: Boolean get() = current != null

    fun beginTrial(target: BenchTarget, displayTimeMs: Long) {
        current = TrialRecord(target, displayTimeMs)
    }

    fun reset() {
        current = null
    }

    override fun onAnchorUpdate(x: Float, y: Float, isSecondary: Boolean, t: Long) {
        val rec = current ?: return
        if (isSecondary) rec.secondaryAnchor = Pair(x, y)
        else rec.primaryAnchor = Pair(x, y)
    }

    override fun onGestureStart(x: Float, y: Float, t: Long) {
        current?.let { rec -> rec._samples.add(BenchSample(x, y, t)) }
    }

    override fun onSample(x: Float, y: Float, t: Long) {
        current?.let { rec -> rec._samples.add(BenchSample(x, y, t)) }
    }

    override fun onRingChanged(from: Ring, to: Ring, t: Long) {
        current?._events?.add(BenchEvent.RingChanged(from, to, t))
    }

    override fun onSegmentChanged(from: Int, to: Int, t: Long) {
        current?._events?.add(BenchEvent.SegmentChanged(from, to, t))
    }

    override fun onStateTransition(from: TouchState, to: TouchState, t: Long) {
        current?._events?.add(BenchEvent.StateTransition(from, to, t))
    }

    override fun onGestureAborted(t: Long) {
        val rec = current ?: return
        current = null
        rec._events.add(BenchEvent.Aborted(t))
        rec.outcome = TrialOutcome.ABORTED
        onTrialComplete?.invoke(rec)
    }

    override fun onCommit(ring: Ring, segment: Int, t: Long) {
        val rec = current ?: return
        current = null
        rec.committed = if (ring == Ring.NONE) null else Pair(ring, segment)
        if (rec.committed == null) {
            rec.outcome = TrialOutcome.ABORTED
        } else {
            val (r, s) = rec.committed!!
            // LEVEL check: which menu was this commit measured against? The
            // FSM only ever transitions INTO SECONDARY (never back), so one
            // dwell event ⇒ the whole rest of the gesture was secondary.
            val onSecondaryMenu = rec.events.any {
                it is BenchEvent.StateTransition && it.to == TouchState.SECONDARY
            }
            val correctLevel =
                (rec.target.level == TargetLevel.SECONDARY) == onSecondaryMenu
            rec.outcome =
                if (correctLevel && r == rec.target.ring && s == rec.target.segment)
                    TrialOutcome.HIT
                else TrialOutcome.MISS
            // Diagnose WHICH dimensions failed so miss cards can say
            // "menu · ring" instead of a bare compass direction that
            // makes a miss look like a scoring bug.
            if (rec.outcome == TrialOutcome.MISS) {
                val aspects = mutableSetOf<MissAspect>()
                if (!correctLevel) aspects.add(MissAspect.MENU)
                if (r != rec.target.ring) aspects.add(MissAspect.RING)
                if (s != rec.target.segment) aspects.add(MissAspect.SEGMENT)
                rec.missedBy = aspects
            }
        }
        rec.movementTimeMs = rec._samples.firstOrNull()?.let { t - it.t } ?: 0L
        onTrialComplete?.invoke(rec)
    }
}
