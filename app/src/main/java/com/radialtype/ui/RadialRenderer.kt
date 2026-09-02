package com.radialtype.ui

import java.util.Locale
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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
    val labelX: Float = currentX,
    val labelY: Float = currentY,
    val deleteLeftCount: Int = 0,
    val deleteRightCount: Int = 0,
    val mode: com.radialtype.engine.LayoutMode = com.radialtype.engine.LayoutMode.LETTERS,
    val pushTimeMs: Long = android.os.SystemClock.uptimeMillis()
)


class RadialRenderer(
    context: Context,
    private val characterMap: CharacterMap,
    private val syllableProvider: SyllableProvider
) {

    companion object {
        const val LABEL_OFFSET_PX = 160f
        private const val FLOATING_TEXT_SP = 36f
        private const val MENU_LABEL_SP = 20f

        // ── Tokyo Night neon palette ─────────────────────────────
        // Sectors are nearly transparent; structure comes from thin
        // neon ring lines, emphasis comes from the active accent.
        private const val CYAN = 0xFF7DCFFFL             // primary accent
        private const val MAGENTA = 0xFFBB9AF7L          // secondary accent
        private const val FG_MUTED = 0xFF9AA5CEL
        private const val FG_BRIGHT = 0xFFEAF0FAL

        private const val SECTOR_IDLE = 0x0F161B26L      // whisper-faint glass
        private const val LINE_STRONG = 0x597AA2F7L      // inner boundary
        private const val LINE_FAINT = 0x2E565F89L       // outer edge
        private const val CELL_BACKDROP = 0xB31A1B26L    // translucent label chip
        private const val CELL_BORDER = 0x33565F89L

        private const val DEAD_FILL = 0xD9161616L
        private const val DEAD_RIM = 0x66F7768EL
        private const val ZONE_STROKE = 0x597AA2F7L

        private const val DELETE_RED = 0xFFF7768EL
        private const val NUMBER_GREEN = 0xFF9ECE6AL
        private const val SYMBOL_YELLOW = 0xFFE0AF68L
    }

    var debugMode: Boolean = true
    var perfHud: PerfHud? = null
    var showIdleZoneHint: Boolean = true

    var deadZoneRadius: Float = GeometryEngine.DEAD_ZONE_RADIUS
    var innerRadiusMax: Float = GeometryEngine.INNER_RADIUS_MAX
    var outerRadiusMax: Float = GeometryEngine.OUTER_RADIUS_MAX

    var floatingLabelOffsetPx: Float = LABEL_OFFSET_PX
        private set

    private var cachedMenuSp = 0f
    private var cachedFloatSp = 0f

    private var zoneCX = -1f
    private var zoneCY = -1f
    private var zoneRX = 0f
    private var zoneRY = 0f

    private val density = context.resources.displayMetrics.density
    private val appContext = context.applicationContext

    private val sectorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val ringLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val glowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
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

    private val cellBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CELL_BACKDROP.toInt()
    }

    private val cellBackdropStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = CELL_BORDER.toInt()
    }

    private val floatingPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE61A1B26.toInt()   // translucent: neon border floats over content
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
        idlePatternBuilt = false   // geometry changed → rebuild the lattice
    }

    fun render(canvas: Canvas, data: RadialRenderData) {
        refreshFeelFromSettings()
        if (SettingsManager.isInitialized) {
            deadZoneRadius = SettingsManager.deadzoneRadius
            innerRadiusMax = maxOf(SettingsManager.innerRingRadius, deadZoneRadius + 20f)
            outerRadiusMax = maxOf(SettingsManager.outerRingRadius, innerRadiusMax + 20f)
        }

        if (data.state == TouchState.IDLE || data.state == TouchState.AXIS_PENDING) {
            // Idle styling is owned by the pad view itself — the overlay
            // window doesn't exist in IDLE, so anything drawn here only
            // flashes during teardown frames (the "flicker").
            return
        }

        if (data.state == TouchState.DELETE) {
            drawDeleteFeedback(canvas, data)
            return
        }

        val accent: Int
        val cx: Float
        val cy: Float
        when (data.state) {
            TouchState.PRIMARY -> {
                accent = CYAN.toInt(); cx = data.anchorX; cy = data.anchorY
            }
            TouchState.NUMBER -> {
                accent = NUMBER_GREEN.toInt(); cx = data.anchorX; cy = data.anchorY
            }
            TouchState.SYMBOL -> {
                accent = SYMBOL_YELLOW.toInt(); cx = data.anchorX; cy = data.anchorY
            }
            else -> {
                accent = MAGENTA.toInt()
                cx = data.secondaryAnchorX; cy = data.secondaryAnchorY
            }
        }

        if (effectiveDebug) drawMenu(canvas, cx, cy, accent, data)
        drawFloatingLabel(canvas, data, accent)
    }

    private val effectiveDebug: Boolean
        get() = debugMode && (!SettingsManager.isInitialized || SettingsManager.debugMode)

    private fun drawDeleteFeedback(canvas: Canvas, data: RadialRenderData) {
        if (zoneRX > 0f && zoneRY > 0f && zoneCX >= 0f) {
            // Rounded-rect red wash + hairline rim, matching the pad's
            // perforated-glass styling instead of the old oval.
            val inset = 2f
            val r = 12f * density
            scratchRect.set(zoneCX - zoneRX + inset, zoneCY - zoneRY + inset,
                            zoneCX + zoneRX - inset, zoneCY + zoneRY - inset)
            deleteWashPaint.alpha = 0x2B
            canvas.drawRoundRect(scratchRect, r, r, deleteWashPaint)
            outlinePaint.color = DELETE_RED.toInt()
            canvas.drawRoundRect(scratchRect, r, r, outlinePaint)
        }

        drawCircleGlow(canvas, data.currentX, data.currentY, 22f * density, 0x88F7768E.toInt())

        val left = data.deleteLeftCount
        val right = data.deleteRightCount
        val sb = StringBuilder()
        if (left > 0) sb.append("← ").append(left)
        if (left > 0 && right > 0) sb.append("  ")
        if (right > 0) sb.append(right).append(" →")
        val text = if (sb.isEmpty()) "DEL" else sb.toString()

        val y = (data.labelY - floatingLabelOffsetPx).coerceAtLeast(floatingLabelPaint.textSize)
        drawFloatingTextWithBackdrop(canvas, text, data.labelX, y,
            floatingLabelPaint, DELETE_RED.toInt())
    }

    /** Pulls label/typography feel settings. Cheap string-free compare. */
    private fun refreshFeelFromSettings() {
        if (!SettingsManager.isInitialized) return
        floatingLabelOffsetPx = SettingsManager.floatingLabelOffsetPx.toFloat()
        val menuSp = SettingsManager.menuLabelSizeSp.toFloat()
        if (menuSp != cachedMenuSp) {
            cachedMenuSp = menuSp
            menuLabelPaint.textSize = spToPx(menuSp, appContext)
        }
        val floatSp = SettingsManager.floatingFontSizeSp.toFloat()
        if (floatSp != cachedFloatSp) {
            cachedFloatSp = floatSp
            floatingLabelPaint.textSize = spToPx(floatSp, appContext)
        }
    }

    private fun drawMenu(canvas: Canvas, cx: Float, cy: Float, accent: Int, data: RadialRenderData) {
        val deadPx = deadZoneRadius * density
        val boundaryPx = innerRadiusMax * density
        val outerPx = outerRadiusMax * density

        // Glass sectors: idle cells are near-invisible; the active cell
        // is the only one that fills with color.
        for (seg in 0 until 8) {
            drawAnnularSlice(canvas, cx, cy, deadPx, boundaryPx, seg,
                sectorColor(accent, data.ring == Ring.INNER && data.segment == seg))
            drawAnnularSlice(canvas, cx, cy, boundaryPx, outerPx, seg,
                sectorColor(accent, data.ring == Ring.OUTER && data.segment == seg))
        }

        // Structure: one hairline circle per ring boundary instead of
        // per-cell borders — reads as a neon instrument, not a grid.
        ringLinePaint.color = LINE_FAINT.toInt()
        canvas.drawCircle(cx, cy, outerPx, ringLinePaint)
        ringLinePaint.color = LINE_STRONG.toInt()
        canvas.drawCircle(cx, cy, boundaryPx, ringLinePaint)

        if (data.ring != Ring.NONE && data.segment in 0 until 8) {
            val lo = if (data.ring == Ring.INNER) deadPx else boundaryPx
            val hi = if (data.ring == Ring.INNER) boundaryPx else outerPx
            val path = annularSlicePath(cx, cy, lo, hi, data.segment)

            // Soft halo, then a crisp single-width accent line. Half the
            // old stroke width keeps the glow tight to the sector edge.
            for (layer in 1..3) {
                glowStrokePaint.color = accent
                glowStrokePaint.alpha = glowAlpha(layer)
                glowStrokePaint.strokeWidth = 3f * density * layer
                canvas.drawPath(path, glowStrokePaint)
            }
            ringLinePaint.color = accent
            ringLinePaint.strokeWidth = 1.5f * density
            canvas.drawPath(path, ringLinePaint)
        }

        drawDeadZone(canvas, cx, cy, deadPx, data.state == TouchState.SECONDARY, accent)

        if (data.state == TouchState.SECONDARY) {
            drawSecondaryLabels(canvas, cx, cy, data.primaryChar, accent, data)
        } else {
            drawPrimaryLabels(canvas, cx, cy, data.mode, accent, data)
        }
    }

    private fun drawPrimaryLabels(canvas: Canvas, cx: Float, cy: Float,
                                  mode: com.radialtype.engine.LayoutMode,
                                  accent: Int, data: RadialRenderData) {
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
                innerChars.getOrElse(seg) { "" },
                data.ring == Ring.INNER && data.segment == seg, accent)
            drawCellLabel(canvas, cx + dx * midOuter, cy + dy * midOuter,
                outerChars.getOrElse(seg) { "" },
                data.ring == Ring.OUTER && data.segment == seg, accent)
        }
    }

    private fun drawSecondaryLabels(canvas: Canvas, cx: Float, cy: Float,
                                    primaryChar: String, accent: Int, data: RadialRenderData) {
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
                syllableProvider.getSyllable(primaryChar, Ring.INNER, seg),
                data.ring == Ring.INNER && data.segment == seg, accent)
            drawCellLabel(canvas, cx + dx * midOuter, cy + dy * midOuter,
                syllableProvider.getSyllable(primaryChar, Ring.OUTER, seg),
                data.ring == Ring.OUTER && data.segment == seg, accent)
        }
    }

    private fun drawCellLabel(canvas: Canvas, x: Float, y: Float, text: String,
                              active: Boolean, accent: Int) {
        if (text.isEmpty()) return
        val halfW = menuLabelPaint.measureText(text) / 2f
        val halfH = (menuLabelPaint.descent() - menuLabelPaint.ascent()) / 2f
        val padX = 5f * density
        val padY = 2.5f * density
        val r = 6f * density
        scratchRect.set(x - halfW - padX, y - halfH - padY,
                        x + halfW + padX, y + halfH + padY)
        cellBackdropPaint.color =
            if (active) 0x59000000.toInt() or (accent and 0x00FFFFFF)
            else CELL_BACKDROP.toInt()
        canvas.drawRoundRect(scratchRect, r, r, cellBackdropPaint)
        cellBackdropStroke.color = if (active) accent else CELL_BORDER.toInt()
        canvas.drawRoundRect(scratchRect, r, r, cellBackdropStroke)
        menuLabelPaint.color = if (active) FG_BRIGHT.toInt() else FG_MUTED.toInt()
        val baseline = y - (menuLabelPaint.ascent() + menuLabelPaint.descent()) / 2f
        canvas.drawText(text, x, baseline, menuLabelPaint)
    }

    private fun drawDeadZone(canvas: Canvas, cx: Float, cy: Float, rPx: Float,
                             isSecondary: Boolean, accent: Int) {
        canvas.drawCircle(cx, cy, rPx, deadFillPaint)
        val rimColor = when {
            isSecondary -> 0x66F7B96EL.toInt()
            else        -> (0x66000000.toInt() or (accent and 0x00FFFFFF))
        }
        deadRimPaint.color = rimColor
        if (isSecondary) {
            drawCircleGlow(canvas, cx, cy, rPx, 0x88F7B96E.toInt())
        }
        canvas.drawCircle(cx, cy, rPx, deadRimPaint)
    }

    private fun drawIdleZone(canvas: Canvas) {
        if (zoneRX <= 0f || zoneRY <= 0f) return
        if (!idlePatternBuilt) buildIdleDotPattern()

        // Glass fill — faint cyan wash over the whole zone.
        glassIdlePaint.color = CYAN.toInt()
        glassIdlePaint.alpha = 0x12
        canvas.drawOval(zoneCX - zoneRX, zoneCY - zoneRY,
            zoneCX + zoneRX, zoneCY + zoneRY, glassIdlePaint)

        // Perforated-glass lattice: staggered dim cyan dots, clipped to
        // the zone oval. Pattern is centered on the zone origin and
        // rebuilt only when the zone geometry changes.
        val save = canvas.save()
        canvas.clipPath(idleZoneOvalPath)
        canvas.save()
        canvas.translate(zoneCX, zoneCY)
        canvas.drawPath(idleDotPath, idleDotPaint)
        canvas.restoreToCount(save)

        // Quiet neon rim.
        zoneStroke.alpha = 0x40
        canvas.drawOval(zoneCX - zoneRX, zoneCY - zoneRY,
            zoneCX + zoneRX, zoneCY + zoneRY, zoneStroke)
    }

    // ── Idle-zone dot lattice (built once, drawn per frame) ──────

    private var idlePatternBuilt = false
    private val idleDotPath = Path()
    private val idleZoneOvalPath = Path()

    private val idleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x307AA2F7.toInt()      // dim cyan dots
    }

    private fun buildIdleDotPattern() {
        idleDotPath.reset()
        idleZoneOvalPath.reset()
        idleZoneOvalPath.addOval(zoneCX - zoneRX, zoneCY - zoneRY,
            zoneCX + zoneRX, zoneCY + zoneRY, Path.Direction.CW)

        val pitch = 3.5f * density
        val r = 1.0f * density
        var row = 0
        var y = -zoneRY + pitch
        while (y < zoneRY) {
            val xOffset = if (row % 2 == 0) 0f else pitch / 2f
            var x = -zoneRX + pitch + xOffset
            while (x < zoneRX - pitch) {
                idleDotPath.addCircle(x, y, r, Path.Direction.CW)
                x += pitch
            }
            y += pitch
            row++
        }
        idlePatternBuilt = true
    }

    private fun drawFloatingLabel(canvas: Canvas, data: RadialRenderData, accent: Int) {
        val label = data.label
        if (label.isEmpty()) return
        val y = (data.labelY - floatingLabelOffsetPx).coerceAtLeast(floatingLabelPaint.textSize)
        drawFloatingTextWithBackdrop(canvas, label, data.labelX, y,
            floatingLabelPaint, accent)
    }

    /**
     * Floating text on an opaque rounded panel with a neon glow and
     * crisp accent border — always legible over any app content.
     */
    private fun drawFloatingTextWithBackdrop(
        canvas: Canvas, text: String, x: Float, y: Float,
        paint: TextPaint, accent: Int
    ) {
        val halfW = paint.measureText(text) / 2f
        val halfH = (paint.descent() - paint.ascent()) / 2f
        val padX = 14f * density
        val padY = 10f * density
        val r = 12f * density
        scratchRect.set(x - halfW - padX, y - halfH - padY,
                        x + halfW + padX, y + halfH + padY)
        canvas.drawRoundRect(scratchRect, r, r, floatingPanelPaint)
        drawRoundRectGlow(canvas, scratchRect, r, accent)
        outlinePaint.color = accent
        canvas.drawRoundRect(scratchRect, r, r, outlinePaint)
        paint.color = FG_BRIGHT.toInt()
        paint.clearShadowLayer()
        val baseline = y - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private val hudPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(12f, context)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
    }

    private val hudPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC1A1B26.toInt()
    }

    fun drawPerfHud(canvas: Canvas, viewHeight: Float) {
        if (!SettingsManager.isInitialized || !SettingsManager.perfHud) return
        val hud = perfHud ?: return
        val pad = 6f * density
        val lines = String.format(
            Locale.US,
            "%.0f fps  frame %.1f/%.1f ms  draw %.2f ms  stale %.1f ms  %.0f ev/s",
            hud.fps(), hud.avgIntervalMs(), hud.maxIntervalMs(),
            hud.lastDrawMs, hud.lastStalenessMs, hud.inputRatePct
        )
        val w = hudPaint.measureText(lines) + pad * 2
        val h = hudPaint.textSize + pad * 2
        scratchRect.set(0f, viewHeight - h, w, viewHeight)
        canvas.drawRoundRect(scratchRect, 8f * density, 8f * density, hudPanelPaint)
        hudPaint.color = FG_MUTED.toInt()
        canvas.drawText(lines, pad, viewHeight - pad, hudPaint)
    }

    private fun glowAlpha(layer: Int): Int = 0x28 - (layer - 1) * 0x0E   // 40, 24, 8

    private fun drawRoundRectGlow(canvas: Canvas, rect: RectF, r: Float, color: Int) {
        for (layer in 1..3) {
            glowStrokePaint.color = color
            glowStrokePaint.alpha = glowAlpha(layer)
            glowStrokePaint.strokeWidth = 6f * density * layer
            canvas.drawRoundRect(rect, r, r, glowStrokePaint)
        }
    }

    private fun drawCircleGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        for (layer in 1..3) {
            glowStrokePaint.color = color
            glowStrokePaint.alpha = glowAlpha(layer)
            glowStrokePaint.strokeWidth = 6f * density * layer
            canvas.drawCircle(cx, cy, radius, glowStrokePaint)
        }
    }

    private fun drawPathGlow(canvas: Canvas, path: Path, color: Int) {
        for (layer in 1..3) {
            glowStrokePaint.color = color
            glowStrokePaint.alpha = glowAlpha(layer)
            glowStrokePaint.strokeWidth = 6f * density * layer
            canvas.drawPath(path, glowStrokePaint)
        }
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
        if (active) 0x2E000000.toInt() or (accent and 0x00FFFFFF)
        else SECTOR_IDLE.toInt()

    private fun spToPx(sp: Float, context: Context): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ZONE_STROKE.toInt()
    }

    /** Idle-zone glass fill — cyan with per-draw alpha. */
    private val glassIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Unused right now; flip on for the soft center-glow variant. */
    private var idleGradientInstalled = false
}
