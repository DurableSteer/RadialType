package com.radialtype.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.TouchStateMachine.TouchState
import com.radialtype.settings.SettingsManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Static replay of one benchmark trial (Packet 3).
 *
 * Layers, bottom to top:
 * 1. Menu miniature — deadzone, ring outlines, drawn with the same
 *    reach-profile math the FSM classified with, centered on the
 *    anchor that was live at commit time (secondary if the trial
 *    dwelled, else primary).
 * 2. Perceived cell sequence — every (ring, segment) the classifier
 *    reported, tinted by visit order with an order badge; the
 *    committed cell gets an emphasized outline.
 * 3. Raw trace — polyline through every touch sample.
 * 4. Event marks — dots on the trace at samples nearest each
 *    classification event: magenta = ring change, cyan = segment
 *    change, red = abort.
 * 5. Deadzone-exit highlight, commit diamond, anchor dots.
 */
class TrialTraceView(
    context: Context,
    private val record: TrialRecord,
    private val density: Float
) : View(context) {

    companion object {
        private const val COL_TRACE = 0xFFC0CAF5.toInt()
        private const val COL_SAMPLE = 0x507AA2F7.toInt()
        private const val COL_EVENT_RING = 0xFFBB9AF7.toInt()
        private const val COL_EVENT_SEG = 0xFF7DCFFF.toInt()
        private const val COL_ABORT = 0xFFF7768E.toInt()
        private const val COL_DEADZONE = 0xE6161616.toInt()
        private const val COL_LINE = 0x66E3E6FD.toInt()
        private const val COL_CELL_BASE = 0xFF7DCFFF.toInt()
        private const val COL_COMMIT = 0xFF9ECE6A.toInt()
        private const val COL_ANCHOR = 0xFFE0AF68.toInt()
        private const val COL_TEXT = 0xFFEAF0FA.toInt()

        private const val MARK_RING = 0
        private const val MARK_SEG = 1
        private const val MARK_ABORT = 2
    }

    // ── Paints ───────────────────────────────────────────────────

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = COL_TRACE
    }
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = COL_SAMPLE
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val commitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = COL_COMMIT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = COL_LINE
    }
    private val deadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = COL_DEADZONE
    }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = COL_ANCHOR
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density; color = COL_TEXT; textAlign = Paint.Align.CENTER
    }
    private val scratch = Path()

    // ── Transform (set in onDraw, used by tx/ty) ─────────────────

    private var viewScale = 1f
    private var worldOffX = 0f
    private var worldOffY = 0f

    private fun tx(x: Float) = x * viewScale + worldOffX
    private fun ty(y: Float) = y * viewScale + worldOffY

    // ── Precomputed analysis ──────────────────────────────────────

    /** Did this trial dwell (secondary menu opened) before commit? */
    private val usedSecondary: Boolean = record.events.any {
        it is BenchEvent.StateTransition && it.to == TouchState.SECONDARY
    }

    data class CellVisit(val ring: Ring, val segment: Int, val orderT: Long)

    private val visits: List<CellVisit>
    private val marks: List<Pair<Long, Int>>
    private val firstExitT: Long
    private val committedCell: Pair<Ring, Int>? = record.committed
    private val anchor: Pair<Float, Float>? =
        (if (usedSecondary) record.secondaryAnchor ?: record.primaryAnchor
         else record.primaryAnchor ?: record.secondaryAnchor)

    init {
        var ring = Ring.NONE
        var segment = -1
        val seq = mutableListOf<CellVisit>()
        val mks = mutableListOf<Pair<Long, Int>>()
        var exit = 0L
        for (e in record.events) {
            when (e) {
                is BenchEvent.RingChanged -> {
                    ring = e.to
                    mks.add(e.t to MARK_RING)
                    if (e.to != Ring.NONE && exit == 0L) exit = e.t
                }
                is BenchEvent.SegmentChanged -> {
                    segment = e.to
                    mks.add(e.t to MARK_SEG)
                }
                is BenchEvent.Aborted -> mks.add(e.t to MARK_ABORT)
                is BenchEvent.StateTransition -> { /* used for usedSecondary */ }
            }
            if (ring != Ring.NONE && segment >= 0) {
                val last = seq.lastOrNull()
                if (last == null || last.ring != ring || last.segment != segment) {
                    seq.add(CellVisit(ring, segment, (seq.size + 1).toLong()))
                }
            }
        }
        visits = seq
        marks = mks
        firstExitT = exit
    }

    // ── Geometry (approximate miniature of the live menu) ────────

    // NOTE: these three lines are the ONLY places referencing engine
    // constants. If your GeometryEngine spells them differently, fix
    // the names here. The miniature tolerates a few dp of error.
    private val reachProfile: FloatArray =
        if (SettingsManager.isInitialized) SettingsManager.reachProfile.copyOf()
        else FloatArray(8) { 1f }
    private val paddingDp: Float =
        if (SettingsManager.isInitialized) SettingsManager.innerPaddingDp else 0f
    private val deadDp: Float = if (SettingsManager.isInitialized)
            SettingsManager.deadzoneRadius
        else GeometryEngine.DEAD_ZONE_RADIUS
    private val innerDp: Float =
        maxOf(
            if (SettingsManager.isInitialized) SettingsManager.innerRingRadius
            else GeometryEngine.INNER_RADIUS_MAX,
            deadDp + 20f
        ) + paddingDp
    private val outerDp: Float =
        maxOf(
            if (SettingsManager.isInitialized) SettingsManager.outerRingRadius
            else GeometryEngine.OUTER_RADIUS_MAX,
            innerDp + 20f
        )
    private val targetMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
}

    private fun reachAt(angleDeg: Float): Float =
        GeometryEngine.reachFactorAt(angleDeg, reachProfile)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    // ── Drawing ──────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchor = this.anchor ?: run {
            textPaint.textSize = 13f * density
            canvas.drawText("no anchor recorded", width / 2f, height / 2f, textPaint)
            return
        }

        val deadPx = deadDp * density
        val innerPx = innerDp * density
        val outerPx = outerDp * density

        // World bounds: union of menu extent and raw samples.
        var minX = anchor.first - outerPx; var maxX = anchor.first + outerPx
        var minY = anchor.second - outerPx; var maxY = anchor.second + outerPx
        for (s in record.samples) {
            if (s.x < minX) minX = s.x; if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y; if (s.y > maxY) maxY = s.y
        }
        record.primaryAnchor?.let { (x, y) ->
            if (x - outerPx < minX) minX = x - outerPx; if (x + outerPx > maxX) maxX = x + outerPx
            if (y - outerPx < minY) minY = y - outerPx; if (y + outerPx > maxY) maxY = y + outerPx
        }

        val pad = 8f * density
        viewScale = minOf(
            (width - 2 * pad) / (maxX - minX).coerceAtLeast(1f),
            (height - 2 * pad) / (maxY - minY).coerceAtLeast(1f)
        )
        worldOffX = width / 2f - ((minX + maxX) / 2f) * viewScale
        worldOffY = height / 2f - ((minY + maxY) / 2f) * viewScale

        // 1. Deadzone + ring outlines.
        drawProfileOutline(canvas, anchor, deadPx, deadPaint)
        drawProfileOutline(canvas, anchor, innerPx, linePaint)
        drawProfileOutline(canvas, anchor, outerPx, linePaint)
        
        // Bullseye: geometric center of this trial's TARGET cell — the ideal
        // landing spot. Distance from the commit diamond (green) to this mark
        // is the "how close was my hit" readout.
        run {
            val t = record.target
            val loT = if (t.ring == Ring.INNER) deadPx else innerPx
            val hiT = if (t.ring == Ring.INNER) innerPx else outerPx
            val midDeg = t.segment * 45f
            val rT = ((loT + hiT) / 2f) * reachAt(midDeg)
            val rad = Math.toRadians(midDeg.toDouble())
            val mx = tx(anchor.first + (cos(rad) * rT).toFloat())
            val my = ty(anchor.second + (sin(rad) * rT).toFloat())
            targetMarkerPaint.color =
                if (t.level == TargetLevel.PRIMARY) BenchTargetView.COL_PRIMARY
                else BenchTargetView.COL_SECONDARY
            canvas.drawCircle(mx, my, 4f * density, targetMarkerPaint)
            canvas.drawPoint(mx, my, targetMarkerPaint)
        }

        // 2. Perceived cell sequence + commit emphasis + order badges.
        for (v in visits) {
            val lo = if (v.ring == Ring.INNER) deadPx else innerPx
            val hi = if (v.ring == Ring.INNER) innerPx else outerPx
            cellPaint.color = tintForVisit(v.orderT)
            drawSector(canvas, anchor, lo, hi, v.segment, cellPaint)
        }
        committedCell?.let { (r, s) ->
            val lo = if (r == Ring.INNER) deadPx else innerPx
            val hi = if (r == Ring.INNER) innerPx else outerPx
            drawSector(canvas, anchor, lo, hi, s, commitPaint)
        }
        for (v in visits) {
            val mid = v.segment * 45f
            val rad = Math.toRadians(mid.toDouble())
            val rMid = (if (v.ring == Ring.INNER) (deadPx + innerPx) / 2f
                        else (innerPx + outerPx) / 2f) * reachAt(mid)
            canvas.drawText("${v.orderT}",
                tx(anchor.first + (cos(rad) * rMid).toFloat()),
                ty(anchor.second + (sin(rad) * rMid).toFloat()), textPaint)
        }

        // 3. Raw trace.
        if (record.samples.size > 1) {
            scratch.reset()
            record.samples.forEachIndexed { i, s ->
                if (i == 0) scratch.moveTo(tx(s.x), ty(s.y))
                else scratch.lineTo(tx(s.x), ty(s.y))
            }
            canvas.drawPath(scratch, tracePaint)
        }
        for (s in record.samples) {
            canvas.drawCircle(tx(s.x), ty(s.y), 1.5f * density, samplePaint)
        }

        // 4. Event marks at nearest sample by timestamp.
        for ((t, kind) in marks) {
            val s = nearestSample(t) ?: continue
            tracePaint.color = when (kind) {
                MARK_RING -> COL_EVENT_RING
                MARK_SEG -> COL_EVENT_SEG
                else -> COL_ABORT
            }
            canvas.drawCircle(tx(s.x), ty(s.y), 3.5f * density, tracePaint)
        }
        tracePaint.color = COL_TRACE

        // 5. Deadzone-exit highlight, commit diamond, anchor dots.
        if (firstExitT != 0L) {
            linePaint.color = COL_EVENT_SEG
            drawProfileOutline(canvas, anchor, deadPx, linePaint)
            linePaint.color = COL_LINE
        }
        record.samples.lastOrNull()?.let { s ->
            val x = tx(s.x); val y = ty(s.y); val r = 4f * density
            commitPaint.style = Paint.Style.FILL
            scratch.reset()
            scratch.moveTo(x, y - r); scratch.lineTo(x + r, y)
            scratch.lineTo(x, y + r); scratch.lineTo(x - r, y)
            scratch.close()
            canvas.drawPath(scratch, commitPaint)
            commitPaint.style = Paint.Style.STROKE
        }
        record.primaryAnchor?.let { (x, y) ->
            canvas.drawCircle(tx(x), ty(y), 2.5f * density, anchorPaint)
        }
        if (usedSecondary) record.secondaryAnchor?.let { (x, y) ->
            canvas.drawCircle(tx(x), ty(y), 2.5f * density, anchorPaint)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun nearestSample(t: Long): BenchSample? =
        record.samples.minByOrNull { abs(it.t - t) }

    private fun tintForVisit(order: Long): Int {
        val alpha = (0x26 + (order - 1).toInt() * 0x18).coerceAtMost(0x60)
        return (alpha shl 24) or (COL_CELL_BASE and 0x00FFFFFF)
    }

    /** Closed profile-following outline (radii in world px). */
    private fun drawProfileOutline(
        canvas: Canvas, anchor: Pair<Float, Float>, basePx: Float, paint: Paint
    ) {
        scratch.reset()
        val steps = 72
        for (i in 0..steps) {
            val a = i * 360f / steps
            val r = basePx * reachAt(a)
            val rad = Math.toRadians(a.toDouble())
            val x = tx(anchor.first + (cos(rad) * r).toFloat())
            val y = ty(anchor.second + (sin(rad) * r).toFloat())
            if (i == 0) scratch.moveTo(x, y) else scratch.lineTo(x, y)
        }
        canvas.drawPath(scratch, paint)
    }

    /** Annular sector for one perceived cell (radii in world px). Works
     *  filled with cellPaint and outlined with commitPaint alike. */
    private fun drawSector(
        canvas: Canvas, anchor: Pair<Float, Float>,
        rInnerPx: Float, rOuterPx: Float, seg: Int, paint: Paint
    ) {
        val start = seg * 45f - 22.5f
        val steps = 6
        scratch.reset()
        for (i in 0..steps) {
            val a = start + 45f * i / steps
            val r = rOuterPx * reachAt(a)
            val rad = Math.toRadians(a.toDouble())
            val x = tx(anchor.first + (cos(rad) * r).toFloat())
            val y = ty(anchor.second + (sin(rad) * r).toFloat())
            if (i == 0) scratch.moveTo(x, y) else scratch.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val a = start + 45f * i / steps
            val r = rInnerPx * reachAt(a)
            val rad = Math.toRadians(a.toDouble())
            scratch.lineTo(
                tx(anchor.first + (cos(rad) * r).toFloat()),
                ty(anchor.second + (sin(rad) * r).toFloat())
            )
        }
        scratch.close()
        canvas.drawPath(scratch, paint)
    }
}
