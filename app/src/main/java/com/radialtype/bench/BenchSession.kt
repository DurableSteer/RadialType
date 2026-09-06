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
    }

    private val queue = ArrayDeque<BenchTarget>()
    private val perCell = LinkedHashMap<BenchTarget, PerCellStats>()
    private var abortedCount = 0
    private val retriesUsed = LinkedHashMap<BenchTarget, Int>()
    private val movementTimes = mutableListOf<Long>()
    private var current: BenchTarget? = null
    private var currentIsRetry = false

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
        targets.forEach { queue.add(it) }
        targets.forEach { perCell[it] = PerCellStats() }
    }

    /** Current target to display, or null when the session is done. */
    fun nextTarget(): BenchTarget? {
        if (current != null) return current
        current = queue.removeFirstOrNull()
        currentIsRetry = false
        return current
    }

    val plannedTrials: Int = trialsPerTarget *
        (if (includeSecondary) 32 else 16)

    /**
     * Consumes the current target. Call exactly once per completed trial,
     * with the record the GestureLogger produced (its outcome drives
     * the bookkeeping).
     */
    fun recordResult(record: TrialRecord) {
        val target = current ?: return
        current = null
        val stats = perCell.getOrPut(target) { PerCellStats() }
        when (record.outcome) {
            TrialOutcome.HIT -> {
                stats.attempts++; stats.hits++
                if (record.movementTimeMs > 0) movementTimes.add(record.movementTimeMs)
            }
            TrialOutcome.MISS -> {
                stats.attempts++
                // Re-queue for a later attempt, unless the cell already
                // burned its retry budget.
                val used = (retriesUsed[target] ?: 0) + 1
                retriesUsed[target] = used
                if (stats.attempts < maxAttemptsPerCell) {
                    queue.addLast(target)
                }
            }
            TrialOutcome.ABORTED -> {
                // Nothing entered — the trial didn't happen. Re-queue
                // without charging the cell, but count the abort so the
                // session can flag a struggling user.
                abortedCount++
                queue.addFirst(target)
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
