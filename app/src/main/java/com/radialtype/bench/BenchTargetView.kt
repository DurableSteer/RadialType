package com.radialtype.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.radialtype.engine.GeometryEngine.Ring
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual target indicator for the benchmark banner (Package 0.12).
 *
 * The old miniature menu decoded well but cost cognitive effort to
 * translate into a motor plan — misinterpreted goals leaked into the
 * miss tallies. This view renders the motor plan DIRECTLY:
 *
 *   - a small dot marks the plant point;
 *   - a BLUE arrow is flick 1: angle = segment direction (screen-
 *     absolute), length = ring;
 *   - for secondary trials with a designated origin, an ORANGE arrow
 *     is flick 2, chained at the blue arrow's tip, with its own
 *     absolute angle and length encoding;
 *   - primary trials draw just the blue arrow; unconstrained
 *     secondary trials (no origin) draw the orange arrow from the
 *     plant as a fallback.
 *
 * Sizing: CONSTANT physical lengths — LONG (OUTER) = 13 mm, SHORT
 * (INNER) = LONG / 2 = 6.5 mm — so ring is readable from length
 * alone in every trial. The 208 dp view fits the worst-case chained
 * path (LONG+LONG = 26 mm ≈ 98 dp against a ~100 dp half-span).
 *
 * Anti-concealment (0.12d): the general case relies on draw order
 * only — the LONGER arrow is drawn first, so the SHORTER is always
 * in the foreground where paths overlap at an angle. The special
 * case of EXACTLY OPPOSITE segments with the SAME ring collapses
 * into a single DOUBLE-HEADED arrow centered on the plant: one
 * shaft in a blend color, one head per direction, the head colored
 * by its leg (blue = flick 1, orange = flick 2) — start at the
 * plant, flick toward blue, return toward orange. No digit badges.
 */
class BenchTargetView(context: Context) : View(context) {

    companion object {
        const val COL_PRIMARY = 0xFF7AA2F7.toInt()
        const val COL_SECONDARY = 0xFFFF9E64.toInt()
        private const val COL_PLANT = 0xFFC0CAF5.toInt()

        /** Shaft color of the double-headed opposite/same-length pair. */
        private const val COL_BACKTRACK = 0xFFF2A0BE.toInt()

        /** px per mm at mdpi; multiply by density. */
        private const val PX_PER_MM_MDPI = 3.78f

        /** LONG (OUTER) flick arrow length, mm. SHORT = LONG / 2. */
        private const val LONG_MM = 13f
    }

    var currentTarget: BenchTarget? = null
        set(value) {
            if (field != value || originCell != null) {
                field = value
                originCell = null
                invalidate()
            }
        }

    /** Designated launch cell; only ever set via [showTrial]. */
    private var originCell: BenchTarget? = null

    /**
     * Installs a full playlist entry. A null trial clears the view;
     * a null trial.origin renders an unconstrained target.
     */
    fun showTrial(trial: BenchTrial?) {
        val changed = trial?.target != currentTarget || trial?.origin != originCell
        currentTarget = trial?.target
        originCell = trial?.origin
        if (changed) invalidate()
    }

    private val density = context.resources.displayMetrics.density

    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.BUTT
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val plantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COL_PLANT
    }
    private val scratch = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    /** One arrow's drawing spec. */
    private data class ArrowSpec(
        val segment: Int, val ring: Ring, val color: Int
    )

    /** Segment difference wrapped to [-4, 4]. */
    private fun segDiff(a: Int, b: Int): Int {
        var d = (a - b) % 8
        if (d > 4) d -= 8
        if (d < -4) d += 8
        return d
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = currentTarget ?: return

        val cx = width / 2f
        val cy = height / 2f

        val origin = originCell?.takeIf { target.level == TargetLevel.SECONDARY }

        // Arrows in PATH order. Chained pair = designated-origin
        // secondary; single blue = primary; lone orange = unconstrained
        // secondary.
        val arrows: List<ArrowSpec> = when {
            origin != null -> listOf(
                ArrowSpec(origin.segment, origin.ring, COL_PRIMARY),
                ArrowSpec(target.segment, target.ring, COL_SECONDARY)
            )
            target.level == TargetLevel.PRIMARY -> listOf(
                ArrowSpec(target.segment, target.ring, COL_PRIMARY)
            )
            else -> listOf(
                ArrowSpec(target.segment, target.ring, COL_SECONDARY)
            )
        }

        // Plant point.
        canvas.drawCircle(cx, cy, 3.5f * density, plantPaint)

        // Special case (0.12d): exactly opposite segments with the
        // same ring — the two legs are congruent backtracks, so one
        // double-headed arrow centered on the plant encodes the whole
        // path: blend shaft, blue head = flick 1, orange head = flick 2.
        if (arrows.size == 2 &&
            segDiff(arrows[1].segment, arrows[0].segment) == 4 &&
            arrows[0].ring == arrows[1].ring
        ) {
            drawDoubleArrow(canvas, cx, cy, arrows[0], arrows[1])
            return
        }
        
        // Special case #2: exactly-opposite flicks with DIFFERENT
        // rings. The chained path plant → far tip → landing point
        // retraces the same shaft; a naive chain buries the outbound
        // head under the return and truncates both legs to the same
        // visible size. Render the return as a wider orange band
        // capping the shared leg: the blue arrow reads full-length
        // with its far head sharp, and the band's head marks the
        // landing ring.
        if (arrows.size == 2 &&
            segDiff(arrows[1].segment, arrows[0].segment) == 4 &&
            arrows[0].ring != arrows[1].ring
        ) {
            drawBacktrackBand(canvas, cx, cy, arrows[0], arrows[1])
            return
        }

        // General case: chain anchors in undisplaced path order —
        // each arrow starts at the previous arrow's tip.
        val starts = ArrayList<Pair<Float, Float>>(arrows.size)
        var ax = cx
        var ay = cy
        for (a in arrows) {
            starts.add(Pair(ax, ay))
            val rad = Math.toRadians((a.segment * 45f).toDouble())
            val len = arrowLen(a.ring)
            ax += (cos(rad) * len).toFloat()
            ay += (sin(rad) * len).toFloat()
        }

        // Overlay draw order: LONGER arrow first, so the SHORTER is
        // always in the foreground where paths overlap. Stable sort
        // keeps equal rings in path order.
        val order = arrows.indices.sortedByDescending {
            arrows[it].ring == Ring.OUTER
        }
        for (idx in order) {
            val a = arrows[idx]
            val (sx, sy) = starts[idx]
            drawArrow(canvas, sx, sy, a)
        }
    }

    /** Constant physical arrow length (px) for a ring. */
    private fun arrowLen(ring: Ring): Float {
        val pxPerMm = PX_PER_MM_MDPI * density
        return (if (ring == Ring.OUTER) LONG_MM else LONG_MM / 2f) * pxPerMm
    }

    /**
     * Special-case render: exactly-opposite segments with the SAME
     * ring. The chained path plant → far tip → plant exactly retraces
     * itself: flick 2 starts at flick 1's tip and its own tip lands
     * back ON the plant dot (same length, opposite direction). One
     * pink shaft spans the leg; flick 1's head caps the far end,
     * flick 2's head caps the plant end — a true chain, total span
     * one leg. Order is unambiguous: flick 1 cannot end where it
     * started, so the head touching the plant is flick 2's arrival.
     * The shaft stops one head-length short of the far tip so the
     * round cap hides under the head base (no blunt tips).
     */
    private fun drawDoubleArrow(
        canvas: Canvas, cx: Float, cy: Float, first: ArrowSpec, second: ArrowSpec
    ) {
        val rad = Math.toRadians((first.segment * 45f).toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        val len = arrowLen(first.ring)
        val headLen = 9f * density
        val shaftLen = (len - headLen).coerceAtLeast(4f * density)

        // Shared shaft (pink), spanning the single leg.
        shaftPaint.color = COL_BACKTRACK
        scratch.reset()
        scratch.moveTo(cx + dirX * 2f * density, cy + dirY * 2f * density)
        scratch.lineTo(cx + dirX * shaftLen, cy + dirY * shaftLen)
        canvas.drawPath(scratch, shaftPaint)

        // Flick 1's head at the far tip (pointing outward).
        drawHead(canvas, cx + dirX * len, cy + dirY * len, dirX, dirY, COL_BACKTRACK)
        // Flick 2's head with its tip ON the plant (pointing back
        // toward it) — the chain closes where the gesture began.
        drawHead(canvas, cx, cy, -dirX, -dirY, COL_BACKTRACK)

        // Redraw the plant dot on top so the start/end point stays
        // marked under flick 2's head tip.
        canvas.drawCircle(cx, cy, 3.5f * density, plantPaint)
    }
    
        /**
     * Special-case #2 render: exactly-opposite flicks with DIFFERENT
     * rings. The chained path plant → far tip → landing point all lies
     * on ONE shaft (flick 2 retraces flick 1's direction in reverse),
     * so two full arrows would bury each other. Instead: flick 1 draws
     * as a normal blue arrow; flick 2 renders as a wide orange BUTT-cap
     * band spanning the return leg (flick 1's far tip → landing point,
     * which sits len2 back along the ray, possibly past the plant when
     * flick 2 is the LONGER leg), capped by an orange head at the
     * landing point pointing along flick 2's travel. The blue head is
     * redrawn LAST so it stays sharp atop the band. Ring order reads
     * from the two head positions: blue at flick 1's tip, orange at
     * flick 2's landing.
     */
    private fun drawBacktrackBand(
        canvas: Canvas, cx: Float, cy: Float, first: ArrowSpec, second: ArrowSpec
    ) {
        val rad = Math.toRadians((first.segment * 45f).toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        val len1 = arrowLen(first.ring)
        val len2 = arrowLen(second.ring)

        // Flick 1: full blue arrow from the plant.
        drawArrow(canvas, cx, cy, first)

        // Flick 2: orange band retracing the shaft from flick 1's far
        // tip to the landing point len2 back along the ray. When
        // len2 > len1 the landing crosses the plant to the opposite
        // side — the band legitimately spans past it.
        val tipX = cx + dirX * len1
        val tipY = cy + dirY * len1
        val landX = tipX - dirX * len2
        val landY = tipY - dirY * len2
        scratch.reset()
        scratch.moveTo(tipX, tipY)
        scratch.lineTo(landX, landY)
        bandPaint.color = COL_SECONDARY
        canvas.drawPath(scratch, bandPaint)

        // Orange head at the landing point, pointing along flick 2's
        // travel (back toward the plant side).
        drawHead(canvas, landX, landY, -dirX, -dirY, COL_SECONDARY)

        // Blue head redrawn last — stays sharp atop the band.
        drawHead(canvas, tipX, tipY, dirX, dirY, COL_PRIMARY)

        // Plant dot on top so the chain origin stays marked, even when
        // flick 2's band crosses it.
        canvas.drawCircle(cx, cy, 3.5f * density, plantPaint)
    }

    /**
     * General-case render: one flick arrow from (x, y) in the
     * segment's absolute screen direction, at its CONSTANT length
     * (LONG = 13 mm, SHORT = LONG/2 mm). No per-trial scaling, no
     * lateral offset — overlap is resolved by draw order.
     */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, a: ArrowSpec) {
        val rad = Math.toRadians((a.segment * 45f).toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()

        val len = arrowLen(a.ring)
        val headLen = 9f * density
        val shaftLen = (len - headLen).coerceAtLeast(4f * density)
        val tipX = x + dirX * len
        val tipY = y + dirY * len

        shaftPaint.color = a.color
        headPaint.color = a.color
        scratch.reset()
        scratch.moveTo(x + dirX * 2f * density, y + dirY * 2f * density)
        scratch.lineTo(x + dirX * shaftLen, y + dirY * shaftLen)
        canvas.drawPath(scratch, shaftPaint)

        drawHead(canvas, tipX, tipY, dirX, dirY, a.color)
    }
    
    /** Solid triangular head at (tipX, tipY) pointing along (dirX, dirY). */
    private fun drawHead(
        canvas: Canvas, tipX: Float, tipY: Float,
        dirX: Float, dirY: Float, color: Int
    ) {
        headPaint.color = color
        val headLen = 9f * density
        val perpX = -dirY
        val perpY = dirX
        val bx = tipX - dirX * headLen
        val by = tipY - dirY * headLen
        scratch.reset()
        scratch.moveTo(bx + perpX * headLen * 0.65f, by + perpY * headLen * 0.65f)
        scratch.lineTo(tipX, tipY)
        scratch.lineTo(bx - perpX * headLen * 0.65f, by - perpY * headLen * 0.65f)
        scratch.close()
        canvas.drawPath(scratch, headPaint)
    }
}
