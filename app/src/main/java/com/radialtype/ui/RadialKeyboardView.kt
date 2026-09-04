package com.radialtype.ui

import android.content.Context
import android.graphics.Path
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.radialtype.engine.GeometryEngine
import com.radialtype.engine.TouchStateMachine
import com.radialtype.engine.TouchStateMachine.TouchState
import com.radialtype.haptics.HapticController
import com.radialtype.settings.SettingsManager
import com.radialtype.text.CharacterMap
import com.radialtype.text.InputDispatcher
import com.radialtype.text.SelectionTracker
import com.radialtype.text.SyllableProvider

/**
 * The small, touchable pad that starts radial gestures.
 *
 * Function keys (Module 13):
 * - A layout slot containing "DEL" enters DELETE mode as soon as the
 *   finger selects it; horizontal swiping selects characters, release
 *   deletes them.
 * - "SPACE" commits a space on release.
 * - "SHIFT" toggles auto-capitalization on release.
 *
 * Haptics: PRIMARY → SECONDARY and secondary INNER → OUTER only.
 *
 * Module 15: accepts an optional shared [SyllableProvider] so the pad view
 * and the renderer always resolve generated syllable rankings identically.
 * When null, this view creates its own (backwards-compatible with tests).
 */
class RadialKeyboardView(
    context: Context,
    characterMapOverride: CharacterMap? = null,
    syllableProviderOverride: SyllableProvider? = null
) : View(context) {

    companion object {
        private const val TAG = "RadialKeyboardView"
        private const val VERBOSE_LOG = false
        private const val LABEL_LEAD_MS = 32f
        private const val MAX_LEAD_PX = 100f   // safety cap for flings
    }

    var onRenderFrame: ((RadialRenderData) -> Unit)? = null
    var inputDispatcher: InputDispatcher? = null
    // ── Cursor-mode delta bridging ────────────────────────────────
    // Shadow copies of the FSM's absolute cursor counts; each callback
    // translates "current count" into "delta since last applied" for
    // the InputDispatcher's relative DPAD events.
    private var lastCursorCols = 0
    private var lastCursorLines = 0

    val haptics = HapticController(context)

    private var lastHapticState: TouchState = TouchState.IDLE

    private val characterMap: CharacterMap = characterMapOverride ?: CharacterMap(context)
    private val syllableProvider: SyllableProvider =
        syllableProviderOverride ?: SyllableProvider(context)
    val selectionTracker = SelectionTracker(characterMap, syllableProvider)

    private var zoneRX = 0f
    private var zoneRY = 0f
    private var zoneReady = false

    var screenOffsetX: Float = 0f
    var screenOffsetY: Float = 0f
    
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x127AA2F7.toInt()          // faint glass wash
    }

    private val zoneRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x407AA2F7.toInt()          // quiet neon rim
    }

    private val zoneDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x307AA2F7.toInt()          // dim lattice dots
    }

    private var zonePatternBuilt = false
    private val zoneDotPath = Path()
    private val zoneClipPath = Path()

    val state: TouchState get() = touchStateMachine.state
    val anchorX: Float get() = touchStateMachine.anchorX
    val anchorY: Float get() = touchStateMachine.anchorY
    val currentX: Float get() = touchStateMachine.currentX
    val currentY: Float get() = touchStateMachine.currentY

    /** True while a DELETE gesture is active — the renderer uses this. */
    val isDeleting: Boolean get() = touchStateMachine.state == TouchState.DELETE


    fun configureZone(rxPx: Float, ryPx: Float) {
        zoneRX = rxPx
        zoneRY = ryPx
        zoneReady = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!zoneReady) return

        // Static perforated-glass presentation, drawn by the pad itself.
        // The pad window exists whenever the keyboard does, so this is
        // stable — no dependency on overlay frames that only appear
        // intermittently while idle.
        val inset = 2f
        val r = 12f * resources.displayMetrics.density

        if (zoneDotPath.isEmpty) {
            zoneClipPath.reset()
            zoneClipPath.addRoundRect(inset, inset, width - inset, height - inset,
                r, r, Path.Direction.CW)

            val pitch = 3.5f * resources.displayMetrics.density
            val dotR = 1.0f * resources.displayMetrics.density
            var row = 0
            var y = inset + pitch
            while (y < height - inset) {
                val xOff = if (row % 2 == 0) 0f else pitch / 2f
                var x = inset + pitch + xOff
                while (x < width - inset - pitch) {
                    zoneDotPath.addCircle(x, y, dotR, Path.Direction.CW)
                    x += pitch
                }
                y += pitch
                row++
            }
        }

        canvas.drawRoundRect(inset, inset, width - inset, height - inset,
            r, r, zoneFillPaint)
        val save = canvas.save()
        canvas.clipPath(zoneClipPath)
        canvas.drawPath(zoneDotPath, zoneDotPaint)
        canvas.restoreToCount(save)
        canvas.drawRoundRect(inset, inset, width - inset, height - inset,
            r, r, zoneRimPaint)
    }

    private val touchStateMachine: TouchStateMachine = TouchStateMachine(
        geometryEngine = GeometryEngine(),
        density = context.resources.displayMetrics.density
    ).apply {
        onStateChanged = { newState ->
            if (VERBOSE_LOG) Log.d(TAG, "State -> $newState")

            if (lastHapticState == TouchState.PRIMARY && newState == TouchState.SECONDARY) {
                haptics.pulseSecondaryEnter()
            }
            if (newState == TouchState.CURSOR) {
                lastCursorCols = 0
                lastCursorLines = 0
            }
            lastHapticState = newState

            selectionTracker.mode = activeMode   // unqualified: apply-block receiver
            selectionTracker.update(currentRing, currentSegment)
            selectionTracker.updateState(newState)
            pushFrame()
        }
        onPositionChanged = {
            selectionTracker.update(currentRing, currentSegment)
            pushFrame()
        }
        onCommit = {
            val label = selectionTracker.currentLabel()
            if (VERBOSE_LOG) Log.d(TAG, "Commit (seg=$currentSegment ring=$currentRing label=$label)")
            when (label) {
                CharacterMap.TOKEN_SPACE -> inputDispatcher?.commitSpace()
                CharacterMap.TOKEN_ENTER -> inputDispatcher?.commitEnter()
                CharacterMap.TOKEN_SHIFT -> inputDispatcher?.shiftNext()
                CharacterMap.TOKEN_TAB   -> inputDispatcher?.sendKey(KeyEvent.KEYCODE_TAB)
                CharacterMap.TOKEN_ESC   -> inputDispatcher?.sendKey(KeyEvent.KEYCODE_ESCAPE)
                CharacterMap.TOKEN_LEFT  -> inputDispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
                CharacterMap.TOKEN_RIGHT -> inputDispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                CharacterMap.TOKEN_UP    -> inputDispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_UP)
                CharacterMap.TOKEN_DOWN   -> inputDispatcher?.sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            if (label.isNotEmpty() && !characterMap.isFunctionKey(label)) {
                inputDispatcher?.commit(label)
            }
        }
        onRingChanged = { newRing ->
            // Inside this .apply{} the FSM under construction is the receiver:
            // `previousRing` and `state` are its live fields, already updated
            // (previousRing = old ring) BEFORE this callback runs. Never qualify
            // them with `touchStateMachine.` here — the property is still being
            // initialized.
            if (VERBOSE_LOG) Log.d(TAG, "Ring changed -> $newRing (prev=$previousRing)")

            val oldRing = previousRing

            // Deadzone exit: NONE → any ring
            if (oldRing == GeometryEngine.Ring.NONE && newRing != GeometryEngine.Ring.NONE) {
                haptics.pulseDeadzoneExit()
            }

            // Secondary inner → outer ring transition
            if (state == TouchState.SECONDARY &&
                oldRing == GeometryEngine.Ring.INNER &&
                newRing == GeometryEngine.Ring.OUTER
            ) {
                haptics.pulseSecondaryRingOut()
            }

            // Ring-to-ring crossing (PRIMARY, NUMBER, SYMBOL): fires both
            // directions — inner→outer and outer→inner. Excluded in SECONDARY
            // where pulseSecondaryRingOut owns the radial transition.
            if (state != TouchState.SECONDARY &&
                oldRing != GeometryEngine.Ring.NONE &&
                newRing != GeometryEngine.Ring.NONE
            ) {
                haptics.pulseRingCross()
            }
        }
        onSegmentChanged = { segment ->
            if (VERBOSE_LOG) Log.d(TAG, "Segment changed -> $segment")
            selectionTracker.update(currentRing, currentSegment)
            // NOTE: the "tick on cell entry" haptic used to live here, driven
            // off characterMap.getPrimaryChar(...). That was doubly wrong:
            // (a) it never fired for radial inner↔outer crossings where the
            // segment index is unchanged, and (b) on the SECONDARY menu it
            // consulted the primary layout table instead of the syllable
            // provider. Both cases are now handled by onCellChanged below.
        }
        onCellChanged = { ring, segment ->
            // Fires for EVERY newly-entered (ring, segment) cell, including
            // deadzone→inner, inner→outer and outer→inner crossings at the
            // same segment index, and re-entry after a deadzone visit.
            val label = if (state == TouchState.SECONDARY) {
                // Secondary menus are labelled by the syllable provider,
                // keyed on the dwelled primary character — NOT by the
                // primary ring layout. selectionTracker.currentPrimaryChar
                // is stable for the whole SECONDARY gesture (set at dwell
                // time), so reading it here is safe.
                syllableProvider.getSyllable(
                    selectionTracker.currentPrimaryChar, ring, segment
                )
            } else {
                characterMap.getPrimaryChar(ring, segment, activeMode)
            }
            if (VERBOSE_LOG) {
                Log.d(TAG, "Cell entry -> ring=$ring seg=$segment label='$label'")
            }
            if (label.isNotEmpty()) {
                haptics.pulseLabelTouch()
            }
        }

        onDeleteProgress = { left, right ->
            if (VERBOSE_LOG) Log.d(TAG, "Delete progress: -$left +$right")
            if (left > 0 || right > 0) {
                haptics.pulseDeleteTick()
            } else {
                haptics.resetDeleteTicks()   // selection collapsed → crescendo restarts
            }
            inputDispatcher?.previewDeleteRange(left, right)
            pushFrame()
        }
        onDeleteCommit = { left, right ->
            if (VERBOSE_LOG) Log.d(TAG, "Delete commit: -$left +$right")
            inputDispatcher?.deleteRange(left, right)
            pushFrame()
        }
        onDeleteCancelled = {
            if (VERBOSE_LOG) Log.d(TAG, "Delete cancelled")
            inputDispatcher?.cancelDeletePreview()
            pushFrame()
        }
        onCursorMoveH = { cols ->
            val delta = cols - lastCursorCols
            lastCursorCols = cols
            if (delta != 0) inputDispatcher?.moveCursorHorizontally(delta)
            pushFrame()
        }
        onCursorMoveV = { lines ->
            val delta = lines - lastCursorLines
            lastCursorLines = lines
            if (delta != 0) inputDispatcher?.moveCursorVertically(delta)
            pushFrame()
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetVelocity()
                updateVelocity(event, event.getPointerId(event.actionIndex))
                reloadLayoutsIfChanged()
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(touchStateMachine.activePointerId)
                if (idx >= 0) updateVelocity(event, idx)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> resetVelocity()
        }
        return touchStateMachine.onTouchEvent(event)
    }
    
    /** Cancels any in-progress gesture and returns the pad to IDLE. */
    fun resetGesture() = touchStateMachine.reset()

    fun pushFrame() {
        // Predicted position for the floating label only. The ring/segment
        // geometry deliberately uses the raw touch position.
        var leadX = velX * LABEL_LEAD_MS
        var leadY = velY * LABEL_LEAD_MS
        val leadMag = Math.hypot(leadX.toDouble(), leadY.toDouble()).toFloat()
        if (leadMag > MAX_LEAD_PX) {
            leadX *= MAX_LEAD_PX / leadMag
            leadY *= MAX_LEAD_PX / leadMag
        }
        
        onRenderFrame?.invoke(
            RadialRenderData(
                state = touchStateMachine.state,
                anchorX = touchStateMachine.anchorX + screenOffsetX,
                anchorY = touchStateMachine.anchorY + screenOffsetY,
                secondaryAnchorX = touchStateMachine.secondaryAnchorX + screenOffsetX,
                secondaryAnchorY = touchStateMachine.secondaryAnchorY + screenOffsetY,
                currentX = touchStateMachine.currentX + screenOffsetX,
                currentY = touchStateMachine.currentY + screenOffsetY,
                ring = selectionTracker.currentRing,
                segment = selectionTracker.currentSegment,
                primaryChar = selectionTracker.currentPrimaryChar,
                label = selectionTracker.currentLabel(),
                labelX = touchStateMachine.currentX + screenOffsetX + leadX,
                labelY = touchStateMachine.currentY + screenOffsetY + leadY,
                deleteLeftCount = touchStateMachine.deleteLeftCount,
                deleteRightCount = touchStateMachine.deleteRightCount,
                cursorDx = touchStateMachine.cursorColumns,
                cursorDy = touchStateMachine.cursorLines,
                lockedSegment = touchStateMachine.lockedSegment,
                mode = touchStateMachine.activeMode
            )
        )
    }
    
    // ── Velocity tracking for label prediction ───────────────────
    private var lastEvtX = 0f
    private var lastEvtY = 0f
    private var lastEvtT = 0L
    private var velX = 0f
    private var velY = 0f

    private fun resetVelocity() {
        velX = 0f
        velY = 0f
        lastEvtT = 0L
    }

    private fun updateVelocity(event: MotionEvent, pointerIndex: Int) {
        val dt = (event.eventTime - lastEvtT).toFloat()
        if (dt > 0f && lastEvtT != 0L) {
            val ix = (event.getX(pointerIndex) - lastEvtX) / dt
            val iy = (event.getY(pointerIndex) - lastEvtY) / dt
            velX = velX * 0.6f + ix * 0.4f
            velY = velY * 0.6f + iy * 0.4f
        }
        lastEvtX = event.getX(pointerIndex)
        lastEvtY = event.getY(pointerIndex)
        lastEvtT = event.eventTime
    }
    
     /**
     * Picks up layout regeneration without recreating the provider.
     * Both maybe-reload methods short-circuit on a string compare of
     * the stored JSON, so calling this on every ACTION_DOWN is
     * effectively free.
     */
    private fun reloadLayoutsIfChanged() {
        characterMap.maybeReload()
        syllableProvider.maybeReloadFromLayout(SettingsManager.customLayoutJson)
    }
}
