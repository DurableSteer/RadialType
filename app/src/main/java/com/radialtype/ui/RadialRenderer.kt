package com.radialtype.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.TouchStateMachine
import com.radialtype.engine.TouchStateMachine.TouchState
import com.radialtype.text.CharacterMap
import com.radialtype.text.SyllableProvider
import kotlin.math.cos
import kotlin.math.sin

/** Per-frame snapshot of everything the renderer needs. */
class RadialRenderData(
    val state: TouchState,
    val anchorX: Float,
    val anchorY: Float,
    val secondaryAnchorX: Float,
    val secondaryAnchorY: Float,
    val currentX: Float,
    val currentY: Float,
    val ring: Ring,
    val segment: Int,
    val primaryChar: String,
    val label: String
)

/**
 * Module 10 — Tokyo Night neon renderer.
 *
 * PRIMARY = cyan accent, SECONDARY = magenta, identical ring geometry.
 * The deadzone is a dark void with a faint red rim. Also draws the idle
 * first-touch ovoid so users know where the keyboard "lives".
 */
class RadialRenderer(
    context: Context,
    private val characterMap: CharacterMap,
    private val syllableProvider: SyllableProvider
) {

    companion object {
        const val LABEL_OFFSET_PX = 160f

        private const val FLOATING_TEXT_SP = 48f
        private const val MENU_LABEL_SP = 20f

        // ── Tokyo Night palette (alpha-heavy literals are Long) ──
        private const val CYAN = 0xFF7AA2F7L
        private const val MAGENTA = 0xFFBB9AF7L
        private const val FG_MUTED = 0xE6A9B1D6L
        private const val FG_BRIGHT = 0xFFF5EBFAL
        private const val PANEL_BASE = 0xE01A1B26L
        private const val PANEL_ACTIVE_ALPHA = 0x66000000   // Int — fits
        private const val DEAD_FILL = 0xF2161616L
        private const val DEAD_RIM = 0x66F7768EL
        private const val ZONE_STROKE = 0x597AA2F7L
    }

    var debugMode: Boolean = false
    var showIdleZoneHint: Boolean = true

    var deadZoneRadius: Float = GeometryEngine.DEAD_ZONE_RADIUS
    var innerRadiusMax: Float = GeometryEngine.INNER_RADIUS_MAX
    var outerRadiusMax: Float = GeometryEngine.OUTER_RADIUS_MAX

    // Idle first-touch zone (px), set by the keyboard view.
    private var zoneCX = -1f
    private var zoneCY = -1f
    private var zoneRX = 0f
    private var zoneRY = 0f

    private val density = context.resources.displayMetrics.density

    private val sectorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val deadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DEAD_FILL.toInt()
    }

    private val deadRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = DEAD_RIM.toInt()
    }

    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ZONE_STROKE.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 8f * density), 0f)
    }

    private val menuLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(MENU_LABEL_SP, context)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val floatingLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(FLOATING_TEXT_SP, context)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val scratchPath = Path()
    private val scratchRect = android.graphics.RectF()

    // ── Public API ───────────────────────────────────────────────

    /** Called by the keyboard view once the view size is known. */
    fun setTouchZone(cx: Float, cy: Float, rx: Float, ry: Float) {
        zoneCX = cx; zoneCY = cy; zoneRX = rx; zoneRY = ry
    }

    fun render(canvas: Canvas, data: RadialRenderData) {
        if (data.state == TouchState.IDLE) {
            if (debugMode && showIdleZoneHint) drawIdleZone(canvas)
            return
        }

        val accent: Int
        val cx: Float
        val cy: Float
        when (data.state) {
            TouchState.PRIMARY -> {
                accent = CYAN.toInt()
                cx = data.anchorX; cy = data.anchorY
            }
            else -> {
                accent = MAGENTA.toInt()
                cx = data.secondaryAnchorX
                cy = data.secondaryAnchorY
            }
        }

        if (debugMode) drawMenu(canvas, cx, cy, accent, data)
        drawFloatingLabel(canvas, data, accent)
    }

    // ── Menu drawing ─────────────────────────────────────────────

    private fun drawMenu(canvas: Canvas, cx: Float, cy: Float, accent: Int, data: RadialRenderData) {
        val deadPx = deadZoneRadius * density
        val boundaryPx = innerRadiusMax * density
        val outerPx = outerRadiusMax * density

        // 1. Sector fills, both rings.
        for (seg in 0 until 8) {
            drawAnnularSlice(canvas, cx, cy, deadPx, boundaryPx, seg,
                sectorColor(accent, data.ring == Ring.INNER && data.segment == seg))
            drawAnnularSlice(canvas, cx, cy, boundaryPx, outerPx, seg,
                sectorColor(accent, data.ring == Ring.OUTER && data.segment == seg))
        }

        // 2. Neon glow + outline on the active sector.
        if (data.ring != Ring.NONE && data.segment in 0 until 8) {
            val lo = if (data.ring == Ring.INNER) deadPx else boundaryPx
            val hi = if (data.ring == Ring.INNER) boundaryPx else outerPx
            val path = annularSlicePath(cx, cy, lo, hi, data.segment)
            glowPaint.color = accent
            outlinePaint.color = accent
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, outlinePaint)
        }

        drawDeadZone(canvas, cx, cy, deadPx, data.state == TouchState.SECONDARY)

        // 3. Labels: characters in PRIMARY, ranked syllables in SECONDARY.
        if (data.state == TouchState.PRIMARY) {
            drawPrimaryLabels(canvas, cx, cy)
        } else {
            drawSecondaryLabels(canvas, cx, cy, data.primaryChar)
        }
    }

    private fun drawPrimaryLabels(canvas: Canvas, cx: Float, cy: Float) {
        val deadPx = deadZoneRadius * density
        val boundaryPx = innerRadiusMax * density
        val outerPx = outerRadiusMax * density
        val midInner = (deadPx + boundaryPx) / 2f
        val midOuter = (boundaryPx + outerPx) / 2f

        for (seg in 0 until 8) {
            val rad = Math.toRadians((seg * 45f).toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            drawCellLabel(canvas, cx + dx * midInner, cy + dy * midInner,
                characterMap.innerRingChars.getOrElse(seg) { "" })
            drawCellLabel(canvas, cx + dx * midOuter, cy + dy * midOuter,
                characterMap.outerRingChars.getOrElse(seg) { "" })
        }
    }

    private fun drawSecondaryLabels(canvas: Canvas, cx: Float, cy: Float, primaryChar: String) {
        val deadPx = deadZoneRadius * density
        val boundaryPx = innerRadiusMax * density
        val outerPx = outerRadiusMax * density
        val midInner = (deadPx + boundaryPx) / 2f
        val midOuter = (boundaryPx + outerPx) / 2f

        for (seg in 0 until 8) {
            val isInner = SyllableProvider.SECONDARY_INNER_SEGMENTS.contains(seg)
            val ring = if (isInner) Ring.INNER else Ring.OUTER
            val rMid = if (isInner) midInner else midOuter
            val rad = Math.toRadians((seg * 45f).toDouble())
            val label = syllableProvider.getSyllable(primaryChar, ring, seg)
            drawCellLabel(canvas, cx + cos(rad).toFloat() * rMid,
                cy + sin(rad).toFloat() * rMid, label)
        }
    }

    private fun drawCellLabel(canvas: Canvas, x: Float, y: Float, text: String) {
        if (text.isEmpty()) return
        menuLabelPaint.color = FG_MUTED.toInt()
        canvas.drawText(text, x, y + menuLabelPaint.textSize / 3f, menuLabelPaint)
    }

    private fun drawDeadZone(canvas: Canvas, cx: Float, cy: Float, rPx: Float, isSecondary: Boolean) {
        canvas.drawCircle(cx, cy, rPx, deadFillPaint)
        // Primary = faint red rim; Secondary = amber rim + amber glow ring,
        // so the two modes are distinguishable at a glance.
        val rimColor = if (isSecondary) 0x66F7B96EL.toInt() else DEAD_RIM.toInt()
        deadRimPaint.color = rimColor
        if (isSecondary) {
            glowPaint.color = 0x88F7B96E.toInt()
            canvas.drawCircle(cx, cy, rPx, glowPaint)
        }
        canvas.drawCircle(cx, cy, rPx, deadRimPaint)
    }

    private fun drawIdleZone(canvas: Canvas) {
        if (zoneRX <= 0f || zoneRY <= 0f) return
        canvas.drawOval(zoneCX - zoneRX, zoneCY - zoneRY,
            zoneCX + zoneRX, zoneCY + zoneRY, zoneOutlinePaint())
    }

    private fun zoneOutlinePaint(): Paint = zoneStroke

    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ZONE_STROKE.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 8f * density), 0f)
    }

    private fun drawFloatingLabel(canvas: Canvas, data: RadialRenderData, accent: Int) {
        val label = data.label
        if (label.isEmpty()) return
        floatingLabelPaint.color = FG_BRIGHT.toInt()
        floatingLabelPaint.setShadowLayer(12f * density, 0f, 0f, accent)
        val y = (data.currentY - LABEL_OFFSET_PX).coerceAtLeast(floatingLabelPaint.textSize)
        canvas.drawText(label, data.currentX, y, floatingLabelPaint)
    }

    // ── Geometry helpers ─────────────────────────────────────────

    private fun annularSlicePath(cx: Float, cy: Float, rInner: Float, rOuter: Float, seg: Int): Path {
        val start = seg * 45f - 22.5f
        scratchPath.reset()
        scratchRect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        scratchPath.arcTo(scratchRect, start, 45f, false)
        scratchRect.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner)
        scratchPath.arcTo(scratchRect, start + 45f, -45f, true)
        scratchPath.close()
        return scratchPath
    }

    private fun drawAnnularSlice(canvas: Canvas, cx: Float, cy: Float,
                                 rInner: Float, rOuter: Float, seg: Int, fillColor: Int) {
        val path = annularSlicePath(cx, cy, rInner, rOuter, seg)
        sectorFillPaint.color = fill(sectorFillPaint, fillColor)
        canvas.drawPath(path, sectorFillPaint)
    }

    private fun fill(paint: Paint, color: Int): Int { paint.color = color; return color }

    private fun sectorColor(accent: Int, active: Boolean): Int =
        if (active) PANEL_ACTIVE_ALPHA or (accent and 0x00FFFFFF)
        else PANEL_BASE.toInt()

    private fun spToPx(sp: Float, context: Context): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
}
