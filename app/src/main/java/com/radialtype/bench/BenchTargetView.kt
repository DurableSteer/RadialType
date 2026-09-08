package com.radialtype.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.settings.SettingsManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual target indicator for the benchmark banner. Draws a miniature
 * of the live menu geometry — deadzone, rings, spokes — with the
 * target cell filled and stroked. Ring is communicated spatially;
 * menu level by color: blue = primary (single flick), orange =
 * secondary (dwell, then flick).
 *
 * Geometry mirrors TrialTraceView, so the indicator literally shows
 * the region the finger must land in under the current reach profile.
 */
class BenchTargetView(context: Context) : View(context) {

    companion object {
        const val COL_PRIMARY = 0xFF7AA2F7.toInt()
        const val COL_SECONDARY = 0xFFFF9E64.toInt()
        private const val COL_DEADZONE = 0xD9161616.toInt()
        private const val COL_LINE = 0x40E3E6FD.toInt()
    }

    var currentTarget: BenchTarget? = null
    set(value) {
        if (field != value) {
            field = value
            invalidate()
        }
    }

    private val density = context.resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = COL_LINE
    }
    private val deadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = COL_DEADZONE
    }
    private val scratch = Path()

    // Geometry sources identical to TrialTraceView.
    private val reachProfile: FloatArray =
        if (SettingsManager.isInitialized) SettingsManager.reachProfile.copyOf()
        else FloatArray(8) { 1f }
    private val deadDp: Float =
        if (SettingsManager.isInitialized) SettingsManager.deadzoneRadius
        else GeometryEngine.DEAD_ZONE_RADIUS
    private val innerDp: Float =
        maxOf(
            if (SettingsManager.isInitialized) SettingsManager.innerRingRadius
            else GeometryEngine.INNER_RADIUS_MAX,
            deadDp + 20f
        )
    private val outerDp: Float =
        maxOf(
            if (SettingsManager.isInitialized) SettingsManager.outerRingRadius
            else GeometryEngine.OUTER_RADIUS_MAX,
            innerDp + 20f
        )

    private fun reachAt(angleDeg: Float): Float =
        GeometryEngine.reachFactorAt(angleDeg, reachProfile)

    // Transform state, set in onDraw, used by tx/ty and helpers.
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f

    private fun tx(x: Float) = cx + x * scale
    private fun ty(y: Float) = cy + y * scale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = currentTarget ?: return

        val deadPx = deadDp * density
        val innerPx = innerDp * density
        val outerPx = outerDp * density
        val pad = 4f * density
        cx = width / 2f
        cy = height / 2f
        scale = minOf(
            (width - 2 * pad) / (2 * outerPx),
            (height - 2 * pad) / (2 * outerPx)
        )

        // Deadzone + ring outlines (profile-following).
        drawOutline(canvas, deadPx, deadPaint)
        drawOutline(canvas, innerPx, linePaint)
        drawOutline(canvas, outerPx, linePaint)

        // Spokes: boundaries at 22.5° + k·45°.
        for (k in 0 until 8) {
            val aDeg = k * 45f - 22.5f
            val a = Math.toRadians(aDeg.toDouble())
            val rIn = deadPx * reachAt(aDeg) * scale
            val rOut = outerPx * reachAt(aDeg) * scale
            scratch.reset()
            scratch.moveTo(tx((cos(a) * rIn).toFloat()), ty((sin(a) * rIn).toFloat()))
            scratch.lineTo(tx((cos(a) * rOut).toFloat()), ty((sin(a) * rOut).toFloat()))
            canvas.drawPath(scratch, linePaint)
        }

        // Target cell highlight.
        val color = if (target.level == TargetLevel.PRIMARY) COL_PRIMARY else COL_SECONDARY
        val lo = if (target.ring == Ring.INNER) deadPx else innerPx
        val hi = if (target.ring == Ring.INNER) innerPx else outerPx
        fillPaint.color = (color and 0x00FFFFFF) or 0x59000000   // 35% alpha fill
        strokePaint.color = color
        drawSector(canvas, lo, hi, target.segment, fillPaint)
        drawSector(canvas, lo, hi, target.segment, strokePaint)
    }

    private fun drawOutline(canvas: Canvas, basePx: Float, paint: Paint) {
        scratch.reset()
        val steps = 72
        for (i in 0..steps) {
            val aDeg = i * 360f / steps
            val a = Math.toRadians(aDeg.toDouble())
            val r = basePx * reachAt(aDeg) * scale
            val x = tx((cos(a) * r).toFloat())
            val y = ty((sin(a) * r).toFloat())
            if (i == 0) scratch.moveTo(x, y) else scratch.lineTo(x, y)
        }
        canvas.drawPath(scratch, paint)
    }

    private fun drawSector(
        canvas: Canvas, rInnerPx: Float, rOuterPx: Float, seg: Int, paint: Paint
    ) {
        val start = seg * 45f - 22.5f
        val steps = 6
        scratch.reset()
        for (i in 0..steps) {
            val aDeg = start + 45f * i / steps
            val a = Math.toRadians(aDeg.toDouble())
            val r = rOuterPx * reachAt(aDeg) * scale
            val x = tx((cos(a) * r).toFloat())
            val y = ty((sin(a) * r).toFloat())
            if (i == 0) scratch.moveTo(x, y) else scratch.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val aDeg = start + 45f * i / steps
            val a = Math.toRadians(aDeg.toDouble())
            val r = rInnerPx * reachAt(aDeg) * scale
            scratch.lineTo(tx((cos(a) * r).toFloat()), ty((sin(a) * r).toFloat()))
        }
        scratch.close()
        canvas.drawPath(scratch, paint)
    }
}
