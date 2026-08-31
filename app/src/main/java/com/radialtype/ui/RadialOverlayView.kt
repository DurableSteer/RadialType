package com.radialtype.ui

import android.content.Context
import android.graphics.Canvas
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

    fun setData(data: RadialRenderData) {
        dataRef = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dataRef?.let { renderer.render(canvas, it) }
    }
}
