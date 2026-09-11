package com.radialtype.bench

import android.util.Log

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
 * raw-vs-perceived later, plus (Package 0.1) the designated origin cell
 * the gesture was supposed to launch from — the seed datum for
 * "does approach direction predict landing bias?" analysis. Mutable
 * during collection, handed over via [GestureLogger.onTrialComplete]
 * and treated as read-only afterwards.
 */
class TrialRecord(
    val target: BenchTarget,
    val displayTimeMs: Long,
    /** Designated launch cell (always PRIMARY-level); null = unconstrained. */
    val originCell: BenchTarget? = null,
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

    // ── Radial landing diagnostics ────────────────────────────────

    val launchCell: Pair<Ring, Int>? by lazy {
        val trans = _events.firstOrNull {
            it is BenchEvent.StateTransition && it.to == TouchState.SECONDARY
        } as? BenchEvent.StateTransition ?: return@lazy null
        var ring = Ring.NONE
        var seg = -1
        for (e in _events) {
            val et = when (e) {
                is BenchEvent.RingChanged -> e.t
                is BenchEvent.SegmentChanged -> e.t
                else -> continue
            }
            if (et > trans.t) break
            when (e) {
                is BenchEvent.RingChanged -> ring = e.to
                is BenchEvent.SegmentChanged -> seg = e.to
                else -> {}
            }
        }
        if (ring == Ring.NONE) null else Pair(ring, seg)
    }

    /**
     * True when the gesture opened SECONDARY but never rested on a
     * primary cell (dwell inside the deadzone) — the "leg 1 died at
     * the plant" signature. Meaningless when the menu never opened.
     */
    val launchNotReached: Boolean by lazy {
        launchCell == null && _events.any {
            it is BenchEvent.StateTransition && it.to == TouchState.SECONDARY
        }
    }

    /**
     * Sample nearest in time to a timestamp. Records are small
     * (hundreds of samples); linear scan is fine and unambiguous.
     */
    private fun nearestSampleTo(t: Long): BenchSample? {
        var best: BenchSample? = null
        var bestDt = Long.MAX_VALUE
        for (smp in _samples) {
            val dt = kotlin.math.abs(smp.t - t)
            if (dt < bestDt) { bestDt = dt; best = smp }
        }
        return best
    }

    /**
     * Radial distance from the gesture's plant point (first sample =
     * touch-down on the pad anchor) to the finger's position when
     * SECONDARY opened, in px. Null when the menu never opened.
     */
    val landingOpenRadiusPx: Float? by lazy {
        val c = _samples.firstOrNull() ?: return@lazy null
        val openT = (_events.firstOrNull {
            it is BenchEvent.StateTransition && it.to == TouchState.SECONDARY
        } as? BenchEvent.StateTransition)?.t ?: return@lazy null
        nearestSampleTo(openT)?.let { hypot(it.x - c.x, it.y - c.y) }
    }

    /**
     * Radial distance from the plant point to the final resting
     * position, in px. This is the hand's intended landing.
     */
    val landingRestRadiusPx: Float? by lazy {
        val c = _samples.firstOrNull() ?: return@lazy null
        val last = _samples.lastOrNull() ?: return@lazy null
        hypot(last.x - c.x, last.y - c.y)
    }

    /**
     * How much further outward the finger traveled AFTER the menu
     * opened. Strongly positive + a ring miss ⇒ the fire caught the
     * finger mid-flight (timing artifact, not an aim error).
     * Near zero ⇒ the finger had settled by open (true aim error).
     */
    val openToRestDriftPx: Float? by lazy {
        val o = landingOpenRadiusPx ?: return@lazy null
        val r = landingRestRadiusPx ?: return@lazy null
        r - o
    }

    /**
     * Compass angle of the resting position relative to the plant
     * point, 0–360°, screen coordinates. Join offline against the
     * target segment to group drift per spoke.
     */
    val landingAngleDeg: Float? by lazy {
        val c = _samples.firstOrNull() ?: return@lazy null
        val last = _samples.lastOrNull() ?: return@lazy null
        (((Math.toDegrees(
            Math.atan2((last.y - c.y).toDouble(), (last.x - c.x).toDouble())
        ))).let { if (it < 0) it + 360 else it }).toFloat()
    }

    /**
     * Finds the FIRST speed valley that has fast approach walls —
     * mirroring the gate's detector discipline (120 ms window, min
     * 8 ms span, approach must be fast so touch-down pauses and
     * end-rest plateaus can't masquerade as bends) — and, like the
     * gate's idempotent arm, the first qualifying valley owns the
     * result. Runs once per record at display time.
     */
    private fun findBend(): Pair<Long, Float>? {
        val s = _samples
        val n = s.size
        if (n < 3) return null

        // Per-sample speed over the trailing BEND_SPAN_MS — the SAME
        // measure the gate uses (engine BEND_SPAN_MS = 40), so logged
        // bend times align with gate arming. -1 = span too short.
        val speed = FloatArray(n) { -1f }
        for (i in 1 until n) {
            var j = i
            while (j > 0 && s[i].t - s[j - 1].t <= BEND_SPAN_MS) j--
            val dt = s[i].t - s[j].t
            if (dt >= BEND_MIN_SPAN_MS) {
                speed[i] = kotlin.math.hypot(
                    (s[i].x - s[j].x).toDouble(), (s[i].y - s[j].y).toDouble()
                ).toFloat() / dt
            }
        }

        // First local minimum with a fast approach. The FINAL sample is
        // eligible with a one-sided test (nothing after lift can refute
        // it) — fixes the "qualified 1 ms before finger-up" blind spot.
        for (i in 2 until n) {
            if (speed[i] < 0f || speed[i - 1] < 0f) continue
            val nextOk = (i == n - 1) || (speed[i + 1] >= 0f && speed[i] <= speed[i + 1])
            if (!nextOk || speed[i] > speed[i - 1]) continue
            var approach = 0f
            for (j in i - 1 downTo 0) {
                if (s[i].t - s[j].t > BEND_WINDOW_MS) break
                if (speed[j] > approach) approach = speed[j]
            }
            if (approach < BEND_APPROACH_WALL_PX_PER_MS) continue
            return s[i].t to speed[i]
        }
        return null
    }

    companion object {
        /** Search window for valley + wall (ms). */
        private const val BEND_WINDOW_MS = 120L
        /** Speed-measurement span — matches the engine's BEND_SPAN_MS. */
        private const val BEND_SPAN_MS = 40L
        private const val BEND_MIN_SPAN_MS = 8L
        private const val BEND_APPROACH_WALL_PX_PER_MS = 0.4f
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

    fun beginTrial(
        target: BenchTarget,
        displayTimeMs: Long,
        originCell: BenchTarget? = null
    ) {
        current = TrialRecord(target, displayTimeMs, originCell)
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

            val aspects = mutableSetOf<MissAspect>()
            if (onSecondaryMenu != (rec.target.level == TargetLevel.SECONDARY)) {
                aspects += MissAspect.MENU
            }
            if (r != rec.target.ring) aspects += MissAspect.RING
            if (s != rec.target.segment) aspects += MissAspect.SEGMENT
            rec.missedBy = aspects
            rec.outcome =
                if (aspects.isEmpty()) TrialOutcome.HIT else TrialOutcome.MISS
        }

        // Movement time: first sample → commit. Zero for aborted commits —
        // an abort carries no usable timing signal.
        val first = rec.samples.firstOrNull()
        rec.movementTimeMs =
            if (rec.outcome == TrialOutcome.ABORTED || first == null) 0L
            else (t - first.t).coerceAtLeast(0L)

        onTrialComplete?.invoke(rec)
    }
}
