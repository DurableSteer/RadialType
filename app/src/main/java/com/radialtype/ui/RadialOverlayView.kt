package com.radialtype.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View

/**
 * Fullscreen, non-touchable canvas for the radial menus. The pad view
 * pushes [RadialRenderData] snapshots here; this view only draws them.
 */
class RadialOverlayView(
    context: Context,
    private val renderer: RadialRenderer
) : View(context) {

    private var dataRef: RadialRenderData? = null
    private var lastData: RadialRenderData? = null

    val perfHud = PerfHud()

    init {
        renderer.perfHud = perfHud
    }

    fun setData(data: RadialRenderData) {
        val last = lastData
        // Skip invalidate when nothing visible changed: still frame,
        // same selection, sub-pixel label movement. Comparing every
        // rendered field prevents stale frames on text/count changes
        // within the same cell (delete mode, custom layouts).
        if (last != null &&
            last.state == data.state &&
            last.ring == data.ring &&
            last.segment == data.segment &&
            last.mode == data.mode &&
            last.label == data.label &&
            last.deleteLeftCount == data.deleteLeftCount &&
            last.deleteRightCount == data.deleteRightCount &&
            Math.abs(last.labelX - data.labelX) < 0.5f &&
            Math.abs(last.labelY - data.labelY) < 0.5f
        ) {
            lastData = data
            return
        }
        lastData = data
        dataRef = data
        perfHud.recordPush(SystemClock.uptimeMillis())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = dataRef ?: return
        val drawStart = SystemClock.uptimeMillis()
        renderer.render(canvas, d)
        perfHud.recordDraw((SystemClock.uptimeMillis() - drawStart).toFloat())
        perfHud.recordFrameInterval(System.nanoTime())
        perfHud.recordStaleness(d.pushTimeMs, drawStart)
        renderer.drawPerfHud(canvas, height.toFloat())
    }
}
