package com.radialtype.bench

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.TouchStateMachine
import com.radialtype.engine.TouchStateMachine.TouchState
import com.radialtype.settings.SettingsManager
import com.radialtype.text.CharacterMap
import com.radialtype.text.SelectionTracker
import com.radialtype.text.SyllableProvider
import com.radialtype.ui.RadialRenderData
import com.radialtype.ui.RadialRenderer
import java.util.Locale
import kotlin.math.abs

/**
 * Flick-accuracy benchmark. Full-screen surface driving a private
 * TouchStateMachine identical to the production pad (same geometry,
 * settings, onset blend, angle lock); every gesture is scored against
 * the queued target via the GestureLogger. Nothing is committed to a
 * text field.
 *
 * Target presentation is visual (BenchTargetView): ring encoded
 * spatially, menu level by color (blue = primary flick, orange =
 * secondary dwell-then-flick). Secondary trials ALSO show the
 * designated origin cell (Package 0.1): hollow blue marker "1" with a
 * dashed arrow to the target "2" — the cue for "start here first,
 * then flick to the marked cell". Completed sessions persist via
 * BenchStore for history and A/B comparison.
 */
class BenchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SEED = "seed"
        const val EXTRA_TRIALS_PER_TARGET = "trials_per_target"
        const val EXTRA_INCLUDE_SECONDARY = "include_secondary"
        const val DEFAULT_TRIALS_PER_TARGET = 6

        /** Segment index → compass shorthand (0 = east, clockwise). */
        val DIRS = arrayOf("E", "SE", "S", "SW", "W", "NW", "N", "NE")

        fun dirName(segment: Int): String = DIRS[segment.coerceIn(0, 7)]

        private val COL_GOOD = 0xFF9ECE6A.toInt()
        private val COL_BAD = 0xFFF7768E.toInt()
        private val COL_TEXT = 0xFFEAF0FA.toInt()
        private val COL_MUTED = 0xFF9AA5CE.toInt()
        private const val FLASH_MS = 450L
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var session: BenchSession
    private val logger = GestureLogger()
    private val records = mutableListOf<TrialRecord>()
    private var sessionPersisted = false

    /** Live playlist entry (target + origin); null between sessions. */
    private var currentTrial: BenchTrial? = null

    private var characterMap: CharacterMap? = null
    private var syllableProvider: SyllableProvider? = null
    private var surface: BenchSurfaceView? = null

    private var rootLayout: FrameLayout? = null
    private var statusText: TextView? = null
    private var targetText: TextView? = null
    private var progressText: TextView? = null
    private var targetView: BenchTargetView? = null
    private var feedbackRunnable: Runnable? = null

    private var seed: Long = 0L
    private var trialsPerTarget: Int = DEFAULT_TRIALS_PER_TARGET
    private var includeSecondary: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)

        seed = if (intent.hasExtra(EXTRA_SEED))
            intent.getLongExtra(EXTRA_SEED, System.currentTimeMillis())
        else if (SettingsManager.benchRepeatSeed && SettingsManager.benchLastSeed != 0L)
            SettingsManager.benchLastSeed
        else
            System.currentTimeMillis()
        SettingsManager.benchLastSeed = seed
        trialsPerTarget = intent.getIntExtra(
            EXTRA_TRIALS_PER_TARGET, SettingsManager.benchTrialsPerTarget)
        includeSecondary = intent.getBooleanExtra(EXTRA_INCLUDE_SECONDARY, true)

        logger.onTrialComplete = { record -> onTrialComplete(record) }

        setContentView(buildRunView())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!sessionPersisted && this@BenchActivity::session.isInitialized &&
                    session.stats().attempts > 0
                ) {
                    persistSession(System.currentTimeMillis(), partial = true)
                }
                finish()
            }
        })

        startSession()
    }

    // ── Session driving ──────────────────────────────────────────

    private fun startSession() {
        records.clear()
        sessionPersisted = false
        session = BenchSession(
            seed = seed,
            trialsPerTarget = trialsPerTarget,
            includeSecondary = includeSecondary
        )
        val trial = session.nextTrial()
        if (trial == null) {
            showSummary()
            return
        }
        installTrial(trial)
        updateBanner(null)
    }

    /**
     * Installs a playlist entry on the surface and banner widgets.
     * The ORIGIN is as much a part of the trial as the target: the
     * surface keeps scoring target-only, but the banner cues both.
     */
    private fun installTrial(trial: BenchTrial) {
        currentTrial = trial
        surface?.currentTarget = trial.target
    }

    private fun onTrialComplete(record: TrialRecord) {
        // Stray gesture after the session finished (between last commit and
        // the posted showSummary): drop it — it has no target to score against.
        if (session.isFinished) return

        records.add(record)
        session.recordResult(record)

        // Pick the next trial NOW, synchronously inside ACTION_UP handling.
        // Deferring this to a handler post let a fast follow-up gesture beat
        // the banner update and get scored against a stale target. Retried
        // misses come back carrying their ORIGINAL origin, so the approach
        // direction never gets re-rolled mid-session.
        val next = session.nextTrial()
        if (next != null) installTrial(next)

        uiHandler.post {
            if (next == null) showSummary()
            else onTrialScored(record, next.target)
        }
    }

    private fun onTrialScored(record: TrialRecord, next: BenchTarget) {
        val feedback: String
        val color: Int
        when (record.outcome) {
            TrialOutcome.HIT -> { feedback = "✓ hit"; color = COL_GOOD }
            TrialOutcome.MISS -> {
                val got = record.committed
                feedback = if (got != null)
                    "✗ got ${got.first.name.lowercase()} · ${dirName(got.second)}"
                else "✗ miss"
                color = COL_BAD
            }
            TrialOutcome.ABORTED -> {
                feedback = "– lifted in deadzone, retrying"; color = COL_MUTED
            }
        }
        // next is already installed on the surface; the banner just reflects it.
        updateBanner(feedback to color)
    }

    private fun updateBanner(flash: Pair<String, Int>?) {
        val target = currentTrial?.target ?: return

        feedbackRunnable?.let { uiHandler.removeCallbacks(it) }
        if (flash != null) {
            statusText?.text = flash.first
            statusText?.setTextColor(flash.second)
            val r = Runnable {
                statusText?.text = ""
                feedbackRunnable = null
            }
            feedbackRunnable = r
            uiHandler.postDelayed(r, FLASH_MS)
        } else {
            statusText?.text = ""
        }

        // Visual target: ring spatial, level by color, origin as the
        // hollow "1" marker with a dashed arrow to the target "2".
        targetView?.showTrial(currentTrial)

        val origin = currentTrial?.origin
        targetText?.text = when {
            target.level == TargetLevel.PRIMARY ->
                "primary · flick to ${dirName(target.segment)} ${target.ring.name.lowercase()}"
            origin != null ->
                "1 · dwell ${dirName(origin.segment)} ${origin.ring.name.lowercase()}" +
                    "   →   2 · flick ${dirName(target.segment)} ${target.ring.name.lowercase()}"
            else -> "secondary · dwell, then flick"
        }
        targetText?.setTextColor(
            if (target.level == TargetLevel.PRIMARY) BenchTargetView.COL_PRIMARY
            else BenchTargetView.COL_SECONDARY
        )

        val stats = session.stats()
        // Remaining WORK, not attempts-done: converges to 0 so the session's
        // end is visible. The old attempts+aborted counter could only hit
        // plannedTrials on a perfect run.
        val outstanding = session.remaining + 1   // + live target
        progressText?.text = String.format(Locale.US,
            "%d✓ · %d✗ · %d left", stats.hits, stats.misses, outstanding)
    }

    // ── Run view ─────────────────────────────────────────────────

    private fun buildRunView(): View {
        val cm = CharacterMap(this).also { characterMap = it }
        val sp = SyllableProvider(this).also { syllableProvider = it }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF10121C.toInt()) }
        rootLayout = root

        val sv = BenchSurfaceView(this, cm, sp).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onGestureStarting = { beginCurrentTrial() }
            fsm.benchObserver = logger
        }
        surface = sv
        root.addView(sv)

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(24) }
        }
        fun line(spSize: Float): TextView = TextView(this).apply {
            textSize = spSize
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER
        }
        targetView = BenchTargetView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(208), dp(208)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        statusText = line(18f)
        targetText = line(13f)
        progressText = line(13f).apply { setTextColor(COL_MUTED) }
        banner.addView(targetView)
        banner.addView(statusText)
        banner.addView(targetText)
        banner.addView(progressText)
        root.addView(banner)

        return root
    }

    private fun beginCurrentTrial() {
        val trial = currentTrial ?: return
        logger.beginTrial(trial.target, SystemClock.uptimeMillis(), trial.origin)
    }

    // ── Summary ──────────────────────────────────────────────────

    private fun showSummary() {
        if (sessionPersisted) return
        sessionPersisted = true
        surface?.release()
        rootLayout?.removeAllViews()
        val timestamp = System.currentTimeMillis()
        persistSession(timestamp, partial = false)
        setContentView(buildSummaryView(session.stats(), timestamp))
    }

    private fun persistSession(timestamp: Long, partial: Boolean) {
        runCatching {
            BenchStore.save(this, BenchStore.sessionToJson(
                timestamp, seed, trialsPerTarget, includeSecondary,
                session.stats(),
                BenchStore.autoLabel() + if (partial) " · partial" else "",
                BenchStore.originTally(records)
            ))
        }
    }

    private fun buildSummaryView(stats: SessionStats, timestamp: Long): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF10121C.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        scroll.addView(col)

        fun header(text: String) {
            col.addView(TextView(this).apply {
                this.text = text
                textSize = 18f
                setTextColor(COL_TEXT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(6))
            })
        }
        fun statLine(format: String) {
            col.addView(TextView(this).apply {
                text = format
                textSize = 15f
                setTextColor(COL_MUTED)
            })
        }

        header("Results")
        val rate = stats.hitRate * 100
        statLine(String.format(Locale.US,
            "Hit rate: %.1f%%  (95%% CI %.1f–%.1f)", rate,
            stats.wilsonLow * 100, stats.wilsonHigh * 100))
        statLine("Hits ${stats.hits} · Misses ${stats.misses} · Aborted ${stats.aborted}")
        statLine(String.format(Locale.US,
            "Mean movement time: %.0f ms (hits only)", stats.meanMovementMs))
        statLine("Seed: $seed  ·  planned ${stats.plannedTrials} trials")

        val prev = BenchStore.list(this)
            .firstOrNull { it.optLong("timestampMs") != timestamp }
        if (prev != null) {
            val pRate = BenchStore.aggregateHitRate(prev) * 100
            statLine(String.format(Locale.US,
                "Previous: %.1f%%  (Δ %+.1f pts)", pRate, rate - pRate))
        }

        // ── Diagnostics: error taxonomy for settings tuning ──────
        // Every number here maps to a knob in the settings menu;
        // the "→" lines fire only when a threshold is crossed and
        // sample size backs it, so a clean run prints nothing but
        // the raw tallies.
        val attemptedRecs = records.filter { it.outcome != TrialOutcome.ABORTED }
        val tally = TrialMetrics.missAspects(attemptedRecs)
        if (tally.misses > 0) {
            header("Diagnostics")
            val aspPct = { n: Int -> 100f * n / tally.misses }
            statLine(String.format(Locale.US,
                "Miss aspects: %d× segment (%.0f%%) · %d× ring (%.0f%%) · %d× menu (%.0f%%)",
                tally.segment, aspPct(tally.segment),
                tally.ring, aspPct(tally.ring),
                tally.menu, aspPct(tally.menu)))

            // Under/overshoot — settings-aware. The SAME miss data
            // feeds either stage of the tuning ladder: projection off
            // → hints target the geometric ring edges; projection on
            // → hints target the projection parameters. Run order
            // (per the tuning brief): geometry first (projection off),
            // then projection on top of a settled geometry.
            val projOn = SettingsManager.radprojEnabled
            val rsP = TrialMetrics.ringErrors(attemptedRecs, TargetLevel.PRIMARY)
            if (rsP.undershoot + rsP.overshoot > 0) {
                statLine(String.format(Locale.US,
                    "Primary ring errors: %d under · %d over  (%d%% under)%s",
                    rsP.undershoot, rsP.overshoot, rsP.undershootPct,
                    if (projOn) "  · proj ON" else ""))
                val n = rsP.undershoot + rsP.overshoot
                if (projOn) {
                    if (rsP.undershootPct >= 67 && n >= 3) {
                        statLine("→ still dying short WITH projection: raise " +
                            "projection strength/max credit; if exhausted, the " +
                            "geometry itself is short — raise the boundary radius")
                    } else if (rsP.undershootPct <= 33 && n >= 3) {
                        statLine("→ projection overshoots: lower strength or " +
                            "max projected distance")
                    }
                } else {
                    if (rsP.undershootPct >= 67 && n >= 3) {
                        statLine("→ flicks die short (no projection): move the " +
                            "inner→outer boundary INWARD (reduce the outer " +
                            "ring's start radius) so short flicks land OUTER")
                    } else if (rsP.undershootPct <= 33 && n >= 3) {
                        statLine("→ flicks sail past inner into OUTER: move the " +
                            "inner→outer boundary OUTWARD so landing INNER " +
                            "takes less precision")
                    }
                }
            }

            // Leg 2 of secondary trials — same split, launch cell →
            // target. Gated on having secondary attempts at all.
            val secAttempted = attemptedRecs.any {
                it.target.level == TargetLevel.SECONDARY
            }
            if (secAttempted) {
                val rsS = TrialMetrics.ringErrors(attemptedRecs, TargetLevel.SECONDARY)
                if (rsS.undershoot + rsS.overshoot > 0) {
                    statLine(String.format(Locale.US,
                        "Secondary ring errors (flick 2): %d under · %d over  (%d%% under)",
                        rsS.undershoot, rsS.overshoot, rsS.undershootPct))
                    if (rsS.undershootPct >= 67 && rsS.undershoot + rsS.overshoot >= 3) {
                        statLine("→ second flick dies short from the launch " +
                            "cell: the dwell-anchored menu may sit closer " +
                            "than the flick length tuned for primary — check " +
                            "secondary geometry relative to primary")
                    }
                }

                // Leg 1 — plant → origin. THE launch-discipline number.
                val ll = TrialMetrics.launchLegErrors(attemptedRecs)
                statLine(String.format(Locale.US,
                    "Launch leg (plant → origin): %d clean · %d ring · %d seg " +
                        "· %d deadzone-dwell  (n=%d)",
                    ll.clean, ll.ringUnder + ll.ringOver, ll.segment,
                    ll.notReached, ll.measured))
                val lUnderOver = ll.ringUnder + ll.ringOver
                if (ll.notReached > 0 && ll.notReached >= ll.measured / 4 &&
                    ll.measured >= 4
                ) {
                    statLine("→ deadzone dwells: launches stall near the plant — " +
                        "raise deadzone abort-ease or shrink the deadzone")
                }
                if (lUnderOver >= 3 && ll.ringUnder * 2 > lUnderOver) {
                    statLine("→ launch flicks die short: move the boundary " +
                        "INWARD — flick 1 length drives the same geometry " +
                        "as primary")
                } else if (lUnderOver >= 3 && ll.ringOver * 2 > lUnderOver) {
                    statLine("→ launch flicks sail past the origin ring: move " +
                        "the inner→outer boundary OUTWARD")
                }
            }

            // Rotational (launch-angle) bias.
            val ab = TrialMetrics.angularBias(attemptedRecs)
            if (ab.samples > 0) {
                statLine(String.format(Locale.US,
                    "Rotational error: mean %+.1f seg  ·  %d CW · %d CCW  (n=%d)",
                    ab.meanSignedSegments, ab.cwCount, ab.ccwCount, ab.samples))
                if (abs(ab.meanSignedSegments) >= 0.25f && ab.samples >= 4) {
                    statLine("→ systematic launch-angle bias: tune onset exit-angle " +
                        "blend (position weight) or per-direction reach")
                }
            }

            // Steering churn vs boundary chatter.
            val meanCorr = TrialMetrics.meanCorrections(attemptedRecs)
            statLine(String.format(Locale.US,
                "Corrections per gesture: %.1f", meanCorr))
            if (meanCorr >= 2f) {
                statLine("→ boundary chatter: raise segment hysteresis")
            }

            // Dwell handoff: is the gate + dwell longer than the pause?
            val lag = TrialMetrics.dwellLagSpread(attemptedRecs)
            if (lag.median != null) {
                statLine(String.format(Locale.US,
                    "Dwell handoff lag: median %d ms · p90 %d ms  (n=%d)",
                    lag.median, lag.p90 ?: lag.median, lag.n))
                if (lag.median >= 40) {
                    statLine("→ menu opens well after you stopped: " +
                        "try lowering dwell duration")
                } else if (lag.median < 0) {
                    statLine("→ menu opened mid-motion (misfire): lengthen dwell " +
                        "duration or lower the gate speed ceiling")
                }
            }

            // Bend speed vs the stillness ceiling (only meaningful
            // when the stillness leg is armed at all).
            if (SettingsManager.dwellGateEnabled &&
                SettingsManager.dwellGateMode != SettingsManager.GATE_MODE_BEND
            ) {
                val bs = TrialMetrics.bendSpeedSpread(attemptedRecs)
                if (bs.medianPxPerMs != null) {
                    val density = resources.displayMetrics.density
                    val p90dp = (bs.p90PxPerMs ?: bs.medianPxPerMs) / density
                    val ceiling = SettingsManager.dwellGateMaxSpeedDpPerMs
                    statLine(String.format(Locale.US,
                        "Bend speed: p90 %.2f dp/ms vs gate ceiling %.2f dp/ms  (n=%d)",
                        p90dp, ceiling, bs.n))
                    if (p90dp >= 0.9f * ceiling && bs.n >= 3) {
                        statLine("→ decelerations barely pass the ceiling: " +
                            "raise dwell gate speed")
                    }
                }
            }
        }

        // Per-level hit movement time — the secondary−primary gap is
        // the dwell tax in milliseconds.
        val mtP = TrialMetrics.movementTimeSpread(records, TargetLevel.PRIMARY)
        val mtS = TrialMetrics.movementTimeSpread(records, TargetLevel.SECONDARY)
        if (mtP.n > 0 && mtS.n > 0) {
            statLine(String.format(Locale.US,
                "Hit time: primary median %d ms (n=%d) · secondary median %d ms (n=%d)",
                mtP.median ?: 0, mtP.n, mtS.median ?: 0, mtS.n))
        }

        // Per-cell grids.
        for (level in TargetLevel.entries) {
            val cells = stats.perCell.entries.filter { it.key.level == level }
            if (cells.isEmpty()) continue
            header(if (level == TargetLevel.PRIMARY)
                "Primary menu (hits/attempts)" else "Secondary menu")
            val grid = GridLayout(this).apply { columnCount = 9 }
            fun cell(text: String, muted: Boolean = true) {
                grid.addView(TextView(this).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(if (muted) COL_MUTED else COL_TEXT)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                })
            }
            cell("")
            DIRS.forEach { cell(it, muted = false) }
            for (ring in listOf(GeometryEngine.Ring.INNER, GeometryEngine.Ring.OUTER)) {
                cell("${ring.name.lowercase()}", muted = false)
                for (seg in 0 until 8) {
                    val cs = stats.perCell[BenchTarget(level, ring, seg)]
                    cell(if (cs != null && cs.attempts > 0) "${cs.hits}/${cs.attempts}" else "–")
                }
            }
            col.addView(grid)
        }

        // ── Trial traces, grouped by exact target cell ─────────────
        // Every completed attempt (hit AND miss) of one
        // (level, ring, segment) under one header, in commit order,
        // misses first — they're the diagnostic payload. Hits are
        // sampled so the summary stays scrollable; the bullseye in
        // TrialTraceView is what to eyeball on them. Aborts are
        // excluded from groups: they were free retries, not attempts
        // charged to the cell. Headers now carry the designated
        // origin (Package 0.1) so landing bias can be read per
        // approach direction right in the summary.
        val maxMissCards = 6
        val maxHitCards = 3

        val attempted = records.filter { it.outcome != TrialOutcome.ABORTED }
        val groups = attempted.groupBy { it.target }
            .entries.sortedWith(compareBy(
                { it.key.level }, { it.key.ring }, { it.key.segment }
            ))

        header("Traces — ${groups.size} cells · " +
            "${attempted.size} attempts, ${attempted.size - stats.hits} missed")

        if (groups.isEmpty()) {
            statLine("No completed attempts to review.")
        }

        for ((target, trials) in groups) {
            val (misses, hits) = trials.partition { it.outcome == TrialOutcome.MISS }
            val levelName = if (target.level == TargetLevel.PRIMARY) "primary" else "secondary"
            col.addView(TextView(this).apply {
                text = "$levelName · ${dirName(target.segment)} · " +
                    "${target.ring.name.lowercase()}  —  ${hits.size}/${trials.size} hit"
                textSize = 14f
                setTextColor(COL_TEXT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(16), 0, dp(4))
            })

            // Miss-aspect tally for this cell's misses: "2×menu, 1×ring"
            // under the header — the first thing to read per weak cell.
            if (misses.isNotEmpty()) {
                val tallyLine = misses.flatMap { it.missedBy }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.value}×${it.key.label}" }
                col.addView(TextView(this).apply {
                    text = if (tallyLine.isEmpty()) "misses: unattributed" else "misses: $tallyLine"
                    textSize = 12f
                    setTextColor(COL_MUTED)
                    setPadding(dp(8), 0, 0, dp(2))
                })
            }

            fun card(r: TrialRecord, hit: Boolean) {
                val got = r.committed
                val wrong = if (r.outcome == TrialOutcome.MISS && r.missedBy.isNotEmpty())
                    "  ✗ wrong: ${MissAspect.describe(r.missedBy)}" else ""
                val gotTxt = if (got != null)
                    "got ${got.first.name.lowercase()}/${dirName(got.second)}" else "no commit"
                val prefix = if (hit) "✓ hit" else "✗ $gotTxt"
                // Launch cell the gesture was supposed to use — the
                // approach-direction variable for landing-bias analysis.
                val fromTxt = r.originCell?.let { oc ->
                    "from ${dirName(oc.segment)} ${oc.ring.name.lowercase()} · "
                } ?: ""
                // Dwell lag, only when the menu actually opened: time from
                // the velocity-minimum sample (the bend) to the SECONDARY
                // transition. Positive = menu opened after you stopped;
                // negative = misfire, opened mid-motion.
                val lagTxt = r.dwellLagMs?.let { " · lag ${it} ms" } ?: ""
                col.addView(buildTraceCard(r,
                    "$fromTxt① ${dirName(target.segment)} ${target.ring.name.lowercase()} — " +
                        "$prefix$wrong · ${r.movementTimeMs} ms$lagTxt · ${correctionsIn(r)} corr."
                ))
            }

            for (r in misses.take(maxMissCards)) card(r, hit = false)
            for (r in hits.take(maxHitCards)) card(r, hit = true)
            if (misses.size > maxMissCards) {
                col.addView(TextView(this).apply {
                    text = "+${misses.size - maxMissCards} more misses hidden"
                    textSize = 12f
                    setTextColor(COL_MUTED)
                    setPadding(dp(8), 0, 0, dp(4))
                })
            } else if (hits.size > maxHitCards) {
                col.addView(TextView(this).apply {
                    text = "+${hits.size - maxHitCards} more hits hidden"
                    textSize = 12f
                    setTextColor(COL_MUTED)
                    setPadding(dp(8), 0, 0, dp(4))
                })
            }
        }

        // Aborted gestures: sample only — they consumed nothing.
        val aborted = records.filter { it.outcome == TrialOutcome.ABORTED }
        if (aborted.isNotEmpty()) {
            header("Aborted (${aborted.size}) — free retries, not scored")
            for (r in aborted.take(5)) {
                col.addView(buildTraceCard(r,
                    "① ${dirName(r.target.segment)} ${r.target.ring.name.lowercase()} — " +
                        "lifted in deadzone · ${r.movementTimeMs} ms"
                ))
            }
        }

        // ── Buttons ──────────────────────────────────────────────
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        fun button(label: String, onClick: (View) -> Unit) {
            buttonBar.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick(it) }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }
        button("Again — same playlist") {
            setContentView(buildRunView())
            startSession()
        }
        button("Again — new") {
            seed = System.currentTimeMillis()
            setContentView(buildRunView())
            startSession()
        }
        button("History") {
            startActivity(android.content.Intent(this, BenchHistoryActivity::class.java))
        }
        col.addView(buttonBar)

        return scroll
    }

    /** Segment changes after the first populated cell. */
    private fun correctionsIn(record: TrialRecord): Int {
        val segChanges = record.events.count { it is BenchEvent.SegmentChanged }
        return if (segChanges > 0) segChanges - 1 else 0
    }

    private fun buildTraceCard(record: TrialRecord, header: String): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(TextView(this@BenchActivity).apply {
                text = header
                textSize = 12f
                setTextColor(COL_MUTED)
            })
            addView(TrialTraceView(this@BenchActivity, record, density).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
                )
            })
        }
    }

    override fun onDestroy() {
        feedbackRunnable?.let { uiHandler.removeCallbacks(it) }
        feedbackRunnable = null
        surface?.release()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

/**
 * Full-screen touch surface for the benchmark: a private FSM +
 * renderer, no IME plumbing. [onGestureStarting] fires on
 * ACTION_DOWN before the FSM consumes the event.
 */
internal class BenchSurfaceView(
    context: Context,
    characterMap: CharacterMap,
    syllableProvider: SyllableProvider
) : View(context) {

    /** Target the NEXT gesture is scored against; owned by the activity. */
    var currentTarget: BenchTarget? = null

    var onGestureStarting: (() -> Unit)? = null

    private val selectionTracker = SelectionTracker(characterMap, syllableProvider)
    private val renderer = RadialRenderer(context, characterMap, syllableProvider).apply {
        debugMode = true
    }

    val fsm: TouchStateMachine = TouchStateMachine(
        geometryEngine = GeometryEngine(),
        density = context.resources.displayMetrics.density
    ).apply {
        onStateChanged = { newState ->
            selectionTracker.mode = activeMode
            selectionTracker.update(currentRing, currentSegment)
            selectionTracker.updateState(newState)
            pushFrame()
        }
        onPositionChanged = {
            selectionTracker.update(currentRing, currentSegment)
            pushFrame()
        }
    }

    private var lastData: RadialRenderData? = null

    init { setBackgroundColor(android.graphics.Color.TRANSPARENT) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onGestureStarting?.invoke()
        return fsm.onTouchEvent(event)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val d = lastData ?: return
        renderer.render(canvas, d)
    }

    private fun pushFrame() {
        lastData = RadialRenderData(
            state = fsm.state,
            anchorX = fsm.anchorX,
            anchorY = fsm.anchorY,
            secondaryAnchorX = fsm.secondaryAnchorX,
            secondaryAnchorY = fsm.secondaryAnchorY,
            currentX = fsm.currentX,
            currentY = fsm.currentY,
            ring = selectionTracker.currentRing,
            segment = selectionTracker.currentSegment,
            primaryChar = selectionTracker.currentPrimaryChar,
            label = selectionTracker.currentLabel(),
            labelX = fsm.currentX,
            labelY = fsm.currentY,
            mode = fsm.activeMode
        )
        invalidate()
    }

    fun release() {
        fsm.reset()
        fsm.benchObserver = null
    }
}
