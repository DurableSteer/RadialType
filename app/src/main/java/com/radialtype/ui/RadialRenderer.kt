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
import com.radialtype.settings.SettingsManager
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
    val label: String,
    val deleteLeftCount: Int = 0,
    val deleteRightCount: Int = 0,
    val mode: com.radialtype.engine.LayoutMode = com.radialtype.engine.LayoutMode.LETTERS
)

/**
 * Module 10 — Tokyo Night neon renderer.
 *
 * PRIMARY = cyan accent, SECONDARY = magenta, identical ring geometry.
 * DELETE state: no radial menu. The touch zone glows red and a live
 * counter shows the pending deletion (← left / → right).
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

        // ── Tokyo Night palette ──────────────────────────────────
        private const val CYAN = 0xFF7AA2F7L
        private const val MAGENTA = 0xFFBB9AF7L
        private const val FG_MUTED = 0xE6A9B1D6L
        private const val FG_BRIGHT = 0xFFF5EBFAL
        private const val PANEL_BASE = 0xE01A1B26L
        private const val PANEL_ACTIVE_ALPHA = 0x66000000
        private const val DEAD_FILL = 0xF2161616L
        private const val DEAD_RIM = 0x66F7768EL
        private const val ZONE_STROKE = 0x597AA2F7L

        /** Tokyo Night red for delete-mode feedback. */
        private const val DELETE_RED = 0xFFF7768EL
        /** Tokyo Night green for number mode. */
        private const val NUMBER_GREEN = 0xFF9ECE6AL
        /** Tokyo Night yellow for symbol mode. */
        private const val SYMBOL_YELLOW = 0xFFE0AF68L
    }

    var debugMode: Boolean = true
    var showIdleZoneHint: Boolean = true

    var deadZoneRadius: Float = GeometryEngine.DEAD_ZONE_RADIUS
    var innerRadiusMax: Float = GeometryEngine.INNER_RADIUS_MAX
    var outerRadiusMax: Float = GeometryEngine.OUTER_RADIUS_MAX

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

    private val deleteWashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2BF7768E.toInt()
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
    private val scratchRect = RectF()

    fun setTouchZone(cx: Float, cy: Float, rx: Float, ry: Float) {
        zoneCX = cx; zoneCY = cy; zoneRX = rx; zoneRY = ry
    }

    fun render(canvas: Canvas, data: RadialRenderData) {
        // Live-sync configurable geometry each frame.
        if (SettingsManager.isInitialized) {
            deadZoneRadius = SettingsManager.deadzoneRadius
            innerRadiusMax = SettingsManager.outerRingRadius
            outerRadiusMax = SettingsManager.outerRingMaxRadius
        }

        if (data.state == TouchState.IDLE) {
            if (effectiveDebug && showIdleZoneHint) drawIdleZone(canvas)
            return
        }

        // DELETE: zone-glow feedback only — never a radial menu.
        if (data.state == TouchState.DELETE) {
            drawDeleteFeedback(canvas, data)
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
            TouchState.NUMBER -> {
                accent = NUMBER_GREEN.toInt()
                cx = data.anchorX; cy = data.anchorY
            }
            TouchState.SYMBOL -> {
                accent = SYMBOL_YELLOW.toInt()
                cx = data.anchorX; cy = data.anchorY
            }
            else -> {
                accent = MAGENTA.toInt()
                cx = data.secondaryAnchorX
                cy = data.secondaryAnchorY
            }
        }

        if (effectiveDebug) drawMenu(canvas, cx, cy, accent, data)
        drawFloatingLabel(canvas, data, accent)
    }

    private val effectiveDebug: Boolean
        get() = debugMode && (!SettingsManager.isInitialized || SettingsManager.debugMode)

    /**
     * Delete-mode feedback: the touch-zone ellipse washes red with a
     * red rim, plus a finger halo and the live selection counter.
     */
    private fun drawDeleteFeedback(canvas: Canvas, data: RadialRenderData) {
        if (zoneRX > 0f && zoneRY > 0f && zoneCX >= 0f) {
            // Wash inside the idle touch zone only.
            canvas.drawOval(
                zoneCX - zoneRX, zoneCY - zoneRY,
                zoneCX + zoneRX, zoneCY + zoneRY,
                deleteWashPaint
            )
            // Red neon rim around the zone.
            outlinePaint.color = DELETE_RED.toInt()
            canvas.drawOval(
                zoneCX - zoneRX, zoneCY - zoneRY,
                zoneCX + zoneRX, zoneCY + zoneRY,
                outlinePaint
            )
        }

        // Halo around the finger.
        glowPaint.color = 0x88F7768EL.toInt()
        canvas.drawCircle(data.currentX, data.currentY, 22f * density, glowPaint)

        val left = data.deleteLeftCount
        val right = data.deleteRightCount
        val sb = StringBuilder()
        if (left > 0) sb.append("← ").append(left)
        if (left > 0 && right > 0) sb.append("  ")
        if (right > 0) sb.append(right).append(" →")
        val text = if (sb.isEmpty()) "DEL" else sb.toString()

        floatingLabelPaint.color = FG_BRIGHT.toInt()
        floatingLabelPaint.setShadowLayer(12f * density, 0f, 0f, DELETE_RED.toInt())
        val y = (data.currentY - LABEL_OFFSET_PX).coerceAtLeast(floatingLabelPaint.textSize)
        canvas.drawText(text, data.currentX, y, floatingLabelPaint)
    }

    private fun drawMenu(canvas: Canvas, cx: Float, cy: Float, accent: Int, data: RadialRenderData) {
        val deadPx = deadZoneRadius * density
        val boundaryPx = innerRadiusMax * density
        val outerPx = outerRadiusMax * density

        for (seg in 0 until 8) {
            drawAnnularSlice(canvas, cx, cy, deadPx, boundaryPx, seg,
                sectorColor(accent, data.ring == Ring.INNER && data.segment == seg))
            drawAnnularSlice(canvas, cx, cy, boundaryPx, outerPx, seg,
                sectorColor(accent, data.ring == Ring.OUTER && data.segment == seg))
        }

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

        if (data.state == TouchState.SECONDARY) {
            drawSecondaryLabels(canvas, cx, cy, data.primaryChar)
        } else {
            drawPrimaryLabels(canvas, cx, cy, data.mode)
        }
    }

    private fun drawPrimaryLabels(canvas: Canvas, cx: Float, cy: Float, mode: com.radialtype.engine.LayoutMode) {
        val deadPx = deadZoneRadius * density
        val boundaryPx = innerRadiusMax * density
        val outerPx = outerRadiusMax * density
        val midInner = (deadPx + boundaryPx) / 2f
        val midOuter = (boundaryPx + outerPx) / 2f
        val (innerChars, outerChars) = characterMap.ringsFor(mode)

        for (seg in 0 until 8) {
            val rad = Math.toRadians((seg * 45f).toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            drawCellLabel(canvas, cx + dx * midInner, cy + dy * midInner,
                innerChars.getOrElse(seg) { "" })
            drawCellLabel(canvas, cx + dx * midOuter, cy + dy * midOuter,
                outerChars.getOrElse(seg) { "" })
        }
    }

    private fun drawSecondaryLabels(canvas: Canvas, cx: Float, cy: Float, primaryChar: String) {
      val deadPx = deadZoneRadius * density
      val boundaryPx = innerRadiusMax * density
      val outerPx = outerRadiusMax * density
      val midInner = (deadPx + boundaryPx) / 2f
      val midOuter = (boundaryPx + outerPx) / 2f

      // Both rings are fully populated on the secondary menu: every
      // segment resolves via the full ERGONOMIC_ORDER (inner ranks 0–7,
      // outer ranks 8–15). Empty results simply draw nothing.
      for (seg in 0 until 8) {
          val rad = Math.toRadians((seg * 45f).toDouble())
          val dx = cos(rad).toFloat()
          val dy = sin(rad).toFloat()
          drawCellLabel(canvas, cx + dx * midInner, cy + dy * midInner,
              syllableProvider.getSyllable(primaryChar, Ring.INNER, seg))
          drawCellLabel(canvas, cx + dx * midOuter, cy + dy * midOuter,
              syllableProvider.getSyllable(primaryChar, Ring.OUTER, seg))
      }
    }

    private fun drawCellLabel(canvas: Canvas, x: Float, y: Float, text: String) {
        if (text.isEmpty()) return
        menuLabelPaint.color = FG_MUTED.toInt()
        canvas.drawText(text, x, y + menuLabelPaint.textSize / 3f, menuLabelPaint)
    }

    private fun drawDeadZone(canvas: Canvas, cx: Float, cy: Float, rPx: Float, isSecondary: Boolean) {
        canvas.drawCircle(cx, cy, rPx, deadFillPaint)
        val rimColor = if (isSecondary) 0x66F7B96EL.toInt() else DEAD_RIM.toInt()
        deadRimPaint.color = rimColor
        if (isSecondary) {
            glowPaint.color = 0x88F7B96EL.toInt()
            canvas.drawCircle(cx, cy, rPx, glowPaint)
        }
        canvas.drawCircle(cx, cy, rPx, deadRimPaint)
    }

    private fun drawIdleZone(canvas: Canvas) {
        if (zoneRX <= 0f || zoneRY <= 0f) return
        canvas.drawOval(zoneCX - zoneRX, zoneCY - zoneRY,
            zoneCX + zoneRX, zoneCY + zoneRY, zoneStroke)
    }

    private fun drawFloatingLabel(canvas: Canvas, data: RadialRenderData, accent: Int) {
        val label = data.label
        if (label.isEmpty()) return
        floatingLabelPaint.color = FG_BRIGHT.toInt()
        floatingLabelPaint.setShadowLayer(12f * density, 0f, 0f, accent)
        val y = (data.currentY - LABEL_OFFSET_PX).coerceAtLeast(floatingLabelPaint.textSize)
        canvas.drawText(label, data.currentX, y, floatingLabelPaint)
    }

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
        sectorFillPaint.color = fillColor
        canvas.drawPath(path, sectorFillPaint)
    }

    private fun sectorColor(accent: Int, active: Boolean): Int =
        if (active) PANEL_ACTIVE_ALPHA or (accent and 0x00FFFFFF)
        else PANEL_BASE.toInt()

    private fun spToPx(sp: Float, context: Context): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ZONE_STROKE.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 8f * density), 0f)
    }
}
