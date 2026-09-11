package com.radialtype.bench

import com.radialtype.engine.GeometryEngine.Ring
import kotlin.math.sqrt
import java.util.Random

/** Which menu a target lives on. */
enum class TargetLevel { PRIMARY, SECONDARY }

/**
 * A benchmark target: one cell of one menu. For SECONDARY targets the
 * committed classification is measured against the secondary anchor —
 * exactly what the FSM resolves in SECONDARY state, so the comparison
 * needs no translation.
 */
data class BenchTarget(val level: TargetLevel, val ring: Ring, val segment: Int)

data class PerCellStats(var attempts: Int = 0, var hits: Int = 0) {
    val hitRate: Float get() = if (attempts == 0) 0f else hits.toFloat() / attempts
}

data class SessionStats(
    val plannedTrials: Int,
    val attempts: Int,
    val hits: Int,
    val misses: Int,
    val aborted: Int,
    val meanMovementMs: Float,
    val perCell: Map<BenchTarget, PerCellStats>
) {
    val hitRate: Float get() = if (attempts == 0) 0f else hits.toFloat() / attempts

    /** Wilson 95% interval on the aggregate hit rate. */
    val wilsonLow: Double
        get() = BenchSession.wilsonInterval(hits, attempts).start
    val wilsonHigh: Double
        get() = BenchSession.wilsonInterval(hits, attempts).endInclusive
}

/**
 * One playlist entry: the cell to hit, plus (SECONDARY trials only) the
 * PRIMARY cell designated as the gesture's launch point. The origin is
 * where the finger must flick first and dwell — it fixes the approach
 * angle and travel distance, so secondary hit rate stops being a blend
 * of skill × arbitrary origin. Origin is null for PRIMARY trials and
 * always PRIMARY-level otherwise.
 */
data class BenchTrial(
    val target: BenchTarget,
    val origin: BenchTarget?
)

/**
 * Drives one benchmark run: an even-coverage, seeded-shuffle queue of
 * targets plus the bookkeeping that turns results into statistics.
 *
 * Coverage contract: the initial queue contains every
 * (level, ring, segment) cell exactly [trialsPerTarget] times, shuffled
 * with a seeded RNG. Misses are re-queued (appended at the back, after
 * an insertion delay so immediate retries don't train recovery patterns),
 * up to [maxAttemptsPerCell] attempts per cell — a hopeless cell can't
 * wedge the session, it just ends under-covered. Aborted trials
 * (deadzone lift, cancel) consume nothing and are re-queued for free:
 * the user entered nothing, so the attempt didn't happen.
 *
 * Origin contract (Package 0.1): every SECONDARY trial in the playlist
 * is paired with a PRIMARY origin cell, assigned by cycling a seeded
 * shuffle of all 16 primary cells (round-robin / balanced-Latin-square
 * style). With 16 secondary cells × trialsPerTarget trials and 16
 * origins, each origin gets exactly trialsPerTarget trials and each of
 * the 8 directions gets 2·trialsPerTarget (= N_secondary/8) — within ±1
 * always. The rotation derives from the seed via a dedicated RNG salt,
 * so the same seed replays IDENTICAL (origin, target) pairs — A/B runs
 * under bench_repeat_seed share both playlist order and origins.
 * Re-queued misses carry their original origin; the pair is never
 * re-rolled.
 */
class BenchSession(
    seed: Long = System.currentTimeMillis(),
    val trialsPerTarget: Int = 6,
    val includeSecondary: Boolean = true,
    val maxAttemptsPerCell: Int = trialsPerTarget * 3
) {

    companion object {
        /**
         * Wilson score interval for a binomial proportion. n = 0 returns
         * 0.0..0.0 rather than NaN — an empty session is a perfectly
         * known amount of nothing.
         */
        fun wilsonInterval(hits: Int, n: Int, z: Double = 1.96): ClosedFloatingPointRange<Double> {
            if (n <= 0) return 0.0..0.0
            val p = hits.toDouble() / n
            val denom = 1.0 + z * z / n
            val center = (p + z * z / (2 * n)) / denom
            val spread = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
            return (center - spread).coerceAtLeast(0.0)..(center + spread).coerceAtMost(1.0)
        }

        /** Salt separating the origin-rotation RNG stream from the shuffle. */
        private const val ORIGIN_STREAM_SALT = 0x5EEDC0DEL
    }

    private val queue = ArrayDeque<BenchTrial>()
    private val perCell = LinkedHashMap<BenchTarget, PerCellStats>()
    private var abortedCount = 0
    private val retriesUsed = LinkedHashMap<BenchTarget, Int>()
    private val movementTimes = mutableListOf<Long>()
    private var current: BenchTrial? = null
    private var currentIsRetry = false

    /**
     * Seeded rotation of all 16 PRIMARY cells, used round-robin as the
     * origin assignment for SECONDARY trials. Derived from the seed
     * through a dedicated RNG stream so the rotation is independent of
     * playlist-length changes (e.g. primary-only vs. full sessions).
     */
    private val originRotation: List<BenchTarget> = run {
        val origins = mutableListOf<BenchTarget>()
        for (ring in listOf(Ring.INNER, Ring.OUTER)) {
            for (seg in 0 until 8) {
                origins.add(BenchTarget(TargetLevel.PRIMARY, ring, seg))
            }
        }
        val rnd = Random(seed xor ORIGIN_STREAM_SALT)
        for (i in origins.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = origins[i]; origins[i] = origins[j]; origins[j] = tmp
        }
        origins
    }

    val remaining: Int get() = queue.size
    val isFinished: Boolean get() = queue.isEmpty() && current == null

    init {
        val levels = if (includeSecondary) TargetLevel.entries.toList()
                     else listOf(TargetLevel.PRIMARY)
        val targets = mutableListOf<BenchTarget>()
        for (level in levels) {
            for (ring in listOf(Ring.INNER, Ring.OUTER)) {
                for (seg in 0 until 8) {
                    repeat(trialsPerTarget) { targets.add(BenchTarget(level, ring, seg)) }
                }
            }
        }
        // Seeded Fisher–Yates: same seed → same playlist, so two configs
        // can be compared on an identical sequence (order variance
        // eliminated from the A/B).
        val rnd = Random(seed)
        for (i in targets.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = targets[i]; targets[i] = targets[j]; targets[j] = tmp
        }

        // Pair each SECONDARY trial with the next origin in the seeded
        // rotation. Cycling a shuffled 16-cell rotation over 16t
        // secondary trials yields each origin exactly t times.
        var originIdx = 0
        for (t in targets) {
            val origin = if (t.level == TargetLevel.SECONDARY)
                originRotation[originIdx++ % originRotation.size]
            else null
            queue.add(BenchTrial(t, origin))
            perCell[t] = PerCellStats()
        }
    }

    /** Current target to display, or null when the session is done. */
    fun nextTarget(): BenchTarget? = nextTrial()?.target

    /**
     * Current playlist entry (target + origin), or null when the session
     * is done. Calling repeatedly without [recordResult] keeps returning
     * the same trial.
     */
    fun nextTrial(): BenchTrial? {
        if (current != null) return current
        current = queue.removeFirstOrNull()
        currentIsRetry = false
        return current
    }

    /** Designated origin of the current trial; null for PRIMARY trials. */
    fun currentOrigin(): BenchTarget? = current?.origin

    val plannedTrials: Int = trialsPerTarget *
        (if (includeSecondary) 32 else 16)

    /**
     * Consumes the current target. Call exactly once per completed trial,
     * with the record the GestureLogger produced (its outcome drives
     * the bookkeeping). The origin pairing travels with the trial on
     * every re-queue, so retries keep the original approach direction.
     */
    fun recordResult(record: TrialRecord) {
        val trial = current ?: return
        current = null
        val target = trial.target
        val stats = perCell.getOrPut(target) { PerCellStats() }
        when (record.outcome) {
            TrialOutcome.HIT -> {
                stats.attempts++; stats.hits++
                if (record.movementTimeMs > 0) movementTimes.add(record.movementTimeMs)
            }
            TrialOutcome.MISS -> {
                stats.attempts++
                // Re-queue for a later attempt, unless the cell already
                // burned its retry budget. The original origin is kept.
                val used = (retriesUsed[target] ?: 0) + 1
                retriesUsed[target] = used
                if (stats.attempts < maxAttemptsPerCell) {
                    queue.addLast(trial)
                }
            }
            TrialOutcome.ABORTED -> {
                // Nothing entered — the trial didn't happen. Re-queue
                // without charging the cell, but count the abort so the
                // session can flag a struggling user.
                abortedCount++
                queue.addFirst(trial)
            }
        }
    }

    /**
     * Abandons the current target without recording anything (e.g. the
     * session is being closed mid-trial).
     */
    fun discardCurrent() {
        current?.let { queue.addFirst(it) }
        current = null
    }

    fun stats(): SessionStats = SessionStats(
        plannedTrials = plannedTrials,
        attempts = perCell.values.sumOf { it.attempts },
        hits = perCell.values.sumOf { it.hits },
        misses = perCell.values.sumOf { it.attempts - it.hits },
        aborted = abortedCount,
        meanMovementMs = if (movementTimes.isEmpty()) 0f
                         else movementTimes.average().toFloat(),
        perCell = perCell
    )

    /** True when the NEXT target is a retried miss (UI hint material). */
    fun currentIsRetryTrial(): Boolean = currentIsRetry
}
