package com.radialtype.engine

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import com.radialtype.bench.BenchObserver
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.settings.SettingsManager


/**
 * Gesture mode selected by the double-tap gateway. LETTERS is the
 * normal flow; NUMBERS/SYMBOLS commit from the primary ring directly
 * (no secondary menu).
 */
enum class LayoutMode { LETTERS, NUMBERS, SYMBOLS }

/**
 * Finite state machine for RadialType's single-gesture touch tracking.
 *
 * States: IDLE, PRIMARY, SECONDARY, AXIS_PENDING, DELETE, NUMBER, SYMBOL.
 *
 * ── Normal input (PRIMARY / SECONDARY) ──────────────────────────
 * Identical geometry in both states, centred on the touch-down point
 * (PRIMARY) or the dwell point (SECONDARY). Dwell is suppressed in the
 * deadzone. Overshoot beyond the outer edge clamps to OUTER.
 *
 * ── Double-tap gateway (AXIS_PENDING) ───────────────────────────
 * Any gesture ending with ring NONE arms the detector. A following DOWN
 * within the configured window enters AXIS_PENDING; the FIRST move past
 * the arm threshold decides the gesture's axis, which then locks:
 * - horizontal (|dx| >= |dy|) → DELETE (left) or CURSOR (right)
 * - upward  (dy < 0)          → NUMBER mode
 * - downward (dy > 0)         → SYMBOL mode
 *
 * ── DELETE ──────────────────────────────────────────────────────
 * Horizontal drag selects characters left/right of the cursor with
 * dual-threshold hysteresis; release commits via onDeleteCommit.
 *
 * ── NUMBER / SYMBOL modes ───────────────────────────────────────
 * Behave exactly like PRIMARY (same ring/segment geometry, anchored at
 * the second tap), except the dwell timer is never allowed to open a
 * secondary menu — the label comes straight from the mode's layout and
 * commits on release. Layout source: CharacterMap.ringsFor(activeMode).
 *
 * ── Cell-entry signalling ───────────────────────────────────────
 * [onCellChanged] fires for every distinct (ring, segment) cell the
 * finger enters, INCLUDING radial ring crossings where the segment
 * index is unchanged (inner → outer / outer → inner along the same
 * ray) and re-entry after a deadzone visit. This complements
 * [onSegmentChanged], which cannot see ring crossings.
 */
class TouchStateMachine(
    private val geometryEngine: GeometryEngine = GeometryEngine(),
    var density: Float = 1f,
    var dwellDurationMs: Long = DEFAULT_DWELL_MS,
    dwellTimerOverride: DwellTimer? = null
) {

    companion object {
        private const val TAG = "TouchStateMachine"
        
        /** A valley also counts as intent when it dips below this
         *  fraction of the window's PEAK speed — catches rounded,
         *  high-speed 90° corners whose absolute apex stays above
         *  the stillness ceiling. */
        const val BEND_REL_DIP_RATIO = 0.35f

        /** Default dwell duration before PRIMARY → SECONDARY. */
        const val DEFAULT_DWELL_MS = 125L
        
        /** Position share in the onset-weighted exit-angle blend (rest = velocity). */
        const val ONSET_POSITION_WEIGHT = 0.6f

        /** Look-back window for the exit-velocity estimate (ms). */
        const val ONSET_VELOCITY_WINDOW_MS = 40L
        
        /** Look-back window (ms) for the dwell stillness velocity check. */
        const val DWELL_GATE_WINDOW_MS = 40L

        /** Fallback stillness ceiling (dp/ms) when SettingsManager is uninitialized. */
        const val DWELL_GATE_MAX_SPEED = 0.03f
        
        /** Fire is blocked only if an above-ceiling move happened within
         *  this wall-clock window of the timer maturing. Covers batching
         *  overlap; a parked finger goes stale instantly and is allowed. */
        const val DWELL_GATE_FIRE_GRACE_MS = 50L
        
        /** Look-back window (ms) for the bend (speed-minimum) detector.
         *  Must fit inside MotionHistory's retained span (~130–260 ms
         *  at default capacity). */
        const val BEND_WINDOW_MS = 120L
        
        /** BEND-mode fire-time retry: how long to wait for a qualifying valley
         *  before re-checking, after a fire arrived without an armed bend. Three
         *  probe cycles — the log shows qualification typically crosses within
         *  one probe of a cancelled fire. */
        private const val BEND_ARM_RETRY_MS = 48L
        
        /** Failed fire-time probes tolerated before the dip ratio
         *  relaxes (tier 2). Move-side probes ALWAYS stay tier 1. */
        private const val BEND_RELAX_AFTER_RETRIES = 2

        /** Tier-2 (relaxed) relative dip ratio. Tier 2 additionally
         *  requires the finger to have a resolved ring — fires from
         *  the deadzone never qualify relaxed. */
        const val BEND_REL_DIP_RATIO_RELAXED = 0.50f
        
        /** Fallback projection horizon (ms) when settings unavailable. */
        const val RADPROJ_HORIZON_DEFAULT = 40

        /** Fallback projection cap (dp) when settings unavailable. */
        const val RADPROJ_SHIFT_DEFAULT = 12f

        /** Exit speeds below this (dp/ms) skip the velocity term — parked-finger exits use pure position. */
        const val MIN_EXIT_SPEED_DP_PER_MS = 0.05f

        /** Fallback double-tap window when SettingsManager is uninitialized. */
        const val DOUBLE_TAP_FALLBACK_MS = 300L

        /** dp per millimetre on Android (160dp per inch / 25.4 mm). */
        const val DP_PER_MM = 160f / 25.4f

        /** Default characters deleted per mm of swipe (fallback). */
        const val DELETE_CHARS_PER_MM_DEFAULT = 2f

        /** |dx| (dp) required to FIRST enter a delete selection. */
        const val DELETE_ARM_THRESHOLD_DP = 6f

        /** |dx| below which an ACTIVE selection is released back to zero. */
        const val DELETE_DISARM_THRESHOLD_DP = 2f

        /**
         * Travel (dp, dominant axis) required after the double-tap DOWN
         * before the gesture axis (delete/cursor/number/symbol) locks in.
         */
        const val AXIS_ARM_THRESHOLD_DP = 6f

        /** Default post-lock menu grace window (ms) — matches settings default. */
        const val MODE_GRACE_DEFAULT = 80

        /** Fallback cursor-mode deadzone radius (dp). */
        const val CURSOR_DEADZONE_DEFAULT = 12f     // dp — note the f

        /** Fallback cursor horizontal speed (tenths: 2.0 columns/mm). */
        const val CURSOR_COLS_PER_MM_DEFAULT = 20

        /** Fallback cursor vertical speed (tenths: 1.0 line/cm). */
        const val CURSOR_LINES_PER_CM_DEFAULT = 10
        
        /** Fallback abort-ease offset (dp) when SettingsManager is uninitialized. */
        const val SECONDARY_ABORT_EASE_DEFAULT = 10f
        
        const val ABORT_EASE_RADIAL_FLOOR = 0.05f
        
        const val RATCHET_RELEASE_MULTIPLE = 2f
        
        /** Fallback characters-per-mm when SettingsManager is uninitialized. */
        private const val DELETE_CHARS_PER_MM_FALLBACK = DELETE_CHARS_PER_MM_DEFAULT
    }

    val dwellTimer: DwellTimer = dwellTimerOverride
        ?: DwellTimer(
            dwellDurationMs = dwellDurationMs,
            handler = Handler(Looper.getMainLooper()),
            callback = { enterSecondary() }
        )
    
    /**
     * Optional benchmark tap. Null in production; every call site is
     * guarded, so idle cost is one field read per event. Implementations
     * must never mutate FSM state from callbacks.
     */
    var benchObserver: BenchObserver? = null

    // ── Gesture state ────────────────────────────────────────────

    var state: TouchState = TouchState.IDLE
        private set

    /** Active layout mode (LETTERS unless the gateway opened a mode). */
    var activeMode: LayoutMode = LayoutMode.LETTERS
        private set

    var anchorX: Float = 0f
        private set

    var anchorY: Float = 0f
        private set

    var currentX: Float = 0f
        private set

    var currentY: Float = 0f
        private set

    var currentRing: Ring = Ring.NONE
        private set

    var currentSegment: Int = -1
        private set

    var previousRing: Ring = Ring.NONE
        private set

    var previousSegment: Int = -1
        private set
    
    /**
     * Wall-clock (uptime) stamp of the last MOVE sample whose speed was
     * at or above the stillness ceiling. 0 = none this gesture. Used by
     * the fire-time check instead of re-measuring velocity — the buffer
     * reading FREEZES when a parked finger stops generating MOVE events,
     * and the frozen tail of a deceleration can sit above the ceiling
     * indefinitely, re-blocking every dwell fire (the 400–500 ms lag).
     */
    private var lastFastMoveWallMs = 0L
    
    /**
     * Timestamp of the speed-minimum the dwell timer is currently
     * armed against (bend/hybrid gate modes). 0 = no bend this
     * gesture. Event-clock based, like the samples it comes from.
     */
    private var lastArmedBendTimeMs = 0L
    
    /** Consecutive tier-1 fire probes that failed to find a qualifying
     *  bend. ≥ BEND_RELAX_AFTER_RETRIES switches fire-time probes to
     *  the relaxed dip ratio. Reset on arm, DOWN, and reset(). */
    private var bendFireRetryCount = 0
    

    /** Pointer that owns the gesture, captured on ACTION_DOWN. */
    var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
        private set
    
    /**
     * Recent touch samples for the onset-weighted exit angle. Reset on
     * ACTION_DOWN, appended on every handled move. Zero cost when idle.
     */
    private val motionHistory = MotionHistory(capacity = 24)
    
    // ── Directional ratchets (Package 0.9) ───────────────────────
    // One ratchet for delete's horizontal axis; one per cursor axis
    // (per-axis release: an L-shaped drag locks H and V independently,
    // so horizontal reversal never fights an ongoing vertical move).
    // Instances are REBUILT at each gesture start via the factories,
    // so slider changes apply per gesture without mutable-threshold
    // plumbing; the declaration-time values are placeholders.
    private var deleteRatchet: AxisRatchet = newDeleteRatchet()
    private var cursorRatchetH: AxisRatchet = newCursorRatchetH()
    private var cursorRatchetV: AxisRatchet = newCursorRatchetV()

    private fun newDeleteRatchet(): AxisRatchet {
        val arm = if (SettingsManager.isInitialized) SettingsManager.deleteDeadzoneDp
                  else DELETE_ARM_THRESHOLD_DP.toFloat()
        val charsPerMm = if (SettingsManager.isInitialized) SettingsManager.deleteCharsPerMm
                         else DELETE_CHARS_PER_MM_DEFAULT
        return AxisRatchet(arm, arm * RATCHET_RELEASE_MULTIPLE,
                           DP_PER_MM / charsPerMm.coerceAtLeast(0.01f))
    }

    private fun newCursorRatchetH(): AxisRatchet {
        val arm = if (SettingsManager.isInitialized) SettingsManager.cursorDeadzoneDp
                  else CURSOR_DEADZONE_DEFAULT
        val stepDp = DP_PER_MM / cursorColumnsPerMm()
        // Cursor release band = ONE STEP: suppresses intra-column
        // boundary chatter (wobble < a step can't pay it back) while
        // bounding the flip's snap debt to ~1 column, so a genuine
        // reversal costs one position, not release-multiple × arm's
        // worth of columns. Delete keeps the 2× arm band — its
        // granularity is a character, not a position under the finger.
        return AxisRatchet(arm, stepDp, stepDp)
    }

    private fun newCursorRatchetV(): AxisRatchet {
        val arm = if (SettingsManager.isInitialized) SettingsManager.cursorDeadzoneDp
                  else CURSOR_DEADZONE_DEFAULT
        val stepDp = (DP_PER_MM * 10f) / cursorLinesPerCm()
        // See newCursorRatchetH: one-step release, same rationale.
        return AxisRatchet(arm, stepDp, stepDp)
    }

    var secondaryAnchorX: Float = 0f
        private set

    var secondaryAnchorY: Float = 0f
        private set

    // ── Delete gesture state ─────────────────────────────────────
    
    /**
     * Grace period after the gateway locks NUMBER/SYMBOL mode: while active,
     * the menu continuously re-anchors to the finger (selection impossible —
     * the finger is always at its own anchor) and freezes when the window
     * expires. Set in enterModeLocked, read in handleModeMove.
     */
    private var modeGraceActive = false
    private var modeGraceDeadline: Long = 0L

    private fun modeGraceMs(): Int =
        if (SettingsManager.isInitialized) SettingsManager.modeLockGraceMs
        else MODE_GRACE_DEFAULT
    
    /**
     * Deadzone radius active for the finger's current motion, dp.
     *
     * Secondary-menu abort ease (0.3, retreat-gated in 0.3b, togglable
     * in 0.3c): while holding a cell in SECONDARY, an INWARD-moving
     * finger meets the cancel boundary earlier — deadzone + ease —
     * so pull-to-cancel stays forgiving. Outward/tangential/parked
     * fingers are classified by the true boundary (see resolveGeometry's
     * velocity gates; this value is the eased radius those gates use).
     *
     * When the master toggle is off, the eased radius collapses to the
     * plain deadzone: the [resolveGeometry] band empties and everything
     * degenerates to pure geometric classification.
     */
    private fun effectiveDeadzoneDp(): Float {
        val base = if (SettingsManager.isInitialized) SettingsManager.deadzoneRadius
           else 18f
        if (state != TouchState.SECONDARY) return base
        if (!SettingsManager.isInitialized ||
            !SettingsManager.secondaryAbortEaseEnabled) return base
        return base + SettingsManager.secondaryAbortEaseDp
    }
        
    private fun cursorColumnsPerMm(): Float =
        if (SettingsManager.isInitialized) SettingsManager.cursorColumnsPerMm
        else CURSOR_COLS_PER_MM_DEFAULT / 10f

    private fun cursorLinesPerCm(): Float =
        if (SettingsManager.isInitialized) SettingsManager.cursorLinesPerCm
        else CURSOR_LINES_PER_CM_DEFAULT / 10f

    /** Characters currently selected LEFT of the cursor (pending delete). */
    var deleteLeftCount: Int = 0
        private set

    /** Characters currently selected RIGHT of the cursor (pending delete). */
    var deleteRightCount: Int = 0
        private set

    /** Timestamp of the last ACTION_UP (double-tap detection). */
    private var lastUpTimestamp: Long = 0L

    /** True when the previous gesture ended with the ring in NONE (rule A). */
    private var lastUpInDeadzone: Boolean = false

    // ── Callbacks ───────────────────────────────────────────────

    var onStateChanged: ((TouchState) -> Unit)? = null
    var onPositionChanged: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onRingChanged: ((Ring) -> Unit)? = null
    var onPulseRingChanged: ((Ring, Ring) -> Unit)? = null
    var onSegmentChanged: ((Int) -> Unit)? = null

    /**
     * Fired whenever the finger enters a DIFFERENT (ring, segment) cell
     * than the last one signaled — including:
     * - deadzone → populated cell,
     * - inner cell → outer cell at the SAME segment index (radial
     *   crossing, invisible to [onSegmentChanged]),
     * - outer cell → inner cell at the same segment index,
     * - re-entry of a previously visited cell after a deadzone visit.
     *
     * Never fires while the finger is in the deadzone (ring NONE).
     * Consumers resolve their own label for (ring, segment) and decide
     * whether the cell is populated (e.g. the "tick on cell entry"
     * haptic).
     */
    var onCellChanged: ((Ring, Int) -> Unit)? = null

    /** Last cell passed to [onCellChanged]; NONE/-1 = nothing signaled. */
    private var signaledRing: Ring = Ring.NONE
    private var signaledSegment: Int = -1
    /** Tracking ring that drives [onPulseRingChanged]. See its docs. */
    private var pulseRing: Ring = Ring.NONE

    /**
     * Radial ordering of rings. NOTE: [Ring] enum's declaration order is
     * INNER, OUTER, NONE — its .ordinal is NOT radial distance. Use this.
     */
    private fun rank(r: Ring): Int = when (r) {
        Ring.NONE -> 0
        Ring.INNER -> 1
        Ring.OUTER -> 2
    }

    private fun ringAt(rank: Int): Ring = when (rank) {
        2 -> Ring.OUTER
        1 -> Ring.INNER
        else -> Ring.NONE
    }

    /** Fired during CURSOR drags: signed column displacement from anchor. */
    var onCursorMoveH: ((Int) -> Unit)? = null
    /** Fired during CURSOR drags: signed line displacement (negative = up). */
    var onCursorMoveV: ((Int) -> Unit)? = null

    /** Fired on every drag in DELETE: (charsLeft, charsRight). */
    var onDeleteProgress: ((left: Int, right: Int) -> Unit)? = null

    /** Fired on ACTION_UP in DELETE: remove the selected range. */
    var onDeleteCommit: ((left: Int, right: Int) -> Unit)? = null

    /** Fired when a DELETE gesture is aborted (ACTION_CANCEL). */
    var onDeleteCancelled: (() -> Unit)? = null

    // ── Public API ───────────────────────────────────────────────
    
    /**
     * Clears cell-signalling bookkeeping; the next populated cell
     * re-signals. Called at every context boundary: state entries,
     * ACTION_DOWN, and deadzone entry.
     */
    private fun resetSignaledCell() {
        signaledRing = Ring.NONE
        signaledSegment = -1
    }
    
    /**
     * Clears pulse-ring bookkeeping (Package 0.4a). Called wherever
     * [resetSignaledCell] fires: context boundaries and deadzone entry.
     * Any outstanding projection debt is forgiven and the next genuine
     * outward crossing pulses from scratch.
     */
    private fun resetPulseTracking() {
        pulseRing = Ring.NONE
        pulseLedByProjection = false
    }

    fun refreshFromSettings() {
        geometryEngine.refreshFromSettings()
        dwellDurationMs = SettingsManager.dwellDurationMs.toLong()
        dwellTimer.dwellDurationMs = dwellDurationMs
        // Dwell gate ceiling rides the same live-pull path; nothing to
        // cache — fingerSpeed / dwellGateMaxSpeed read settings on demand.
    }
    
     /**
     * Aborts any in-flight gesture and returns to IDLE. Used when the
     * input session dies mid-gesture (input view finished, window hidden,
     * overlay torn down): the pending dwell callback is cancelled so it
     * can never re-open the overlay after teardown, and a DELETE gesture
     * in progress releases its text-selection preview.
     */
    fun reset() {
        benchObserver?.onGestureAborted(currentEventTime)
        if (state == TouchState.DELETE) {
            deleteLeftCount = 0
            deleteRightCount = 0
            onDeleteCancelled?.invoke()   // also clears InputDispatcher preview state
        }
        dwellTimer.cancel()
        activeMode = LayoutMode.LETTERS
        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        secondaryAnchorX = 0f
        secondaryAnchorY = 0f
        lastUpTimestamp = 0L
        lastUpInDeadzone = false
        modeGraceActive = false
        modeGraceDeadline = 0L
        bendFireRetryCount = 0
        cursorColumns = 0
        cursorLines = 0
        deleteRatchet = newDeleteRatchet()
        cursorRatchetH = newCursorRatchetH()
        cursorRatchetV = newCursorRatchetV()
        resetSignaledCell()
        resetPulseTracking()
        transitionTo(TouchState.IDLE)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(event.actionIndex)
                handleDown(event)
            }
            // Extra fingers never affect the gesture — but they must not
            // fall through to the `else -> false` path, or the system may
            // stop delivering MOVE events for the tracked pointer.
            MotionEvent.ACTION_POINTER_DOWN -> true
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) handleMove(event, pointerIndex) else true
            }
            // First finger lifting while a second is still down: end the
            // gesture here; subsequent events until full release are ignored.
            MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    handleUp(event)
                } else true
            MotionEvent.ACTION_UP -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                handleUp(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                handleCancel()
            }
            else -> false
        }
    }

    fun enterSecondary() {
        if (state != TouchState.PRIMARY) {
            Log.w(TAG, "enterSecondary() called in $state — ignoring")
            return
        }
        if (currentRing == Ring.NONE || currentSegment < 0) {
            val armed = dwellGateEnabled() &&
                (gateMode() == SettingsManager.GATE_MODE_BEND ||
                 gateMode() == SettingsManager.GATE_MODE_HYBRID) &&
                lastArmedBendTimeMs != 0L
            if (armed) {
                // An armed bend's fire matured while the finger was in the
                // deadzone or before a segment resolved. Dropping it strands
                // the arm: cell entry respects the arm and never restarts
                // the clock, so the menu would never open this gesture.
                // Retry briefly — by the next probe the finger is almost
                // always out of the deadzone with a segment.
                Log.d(TAG, "Armed fire held in deadzone — retrying in " +
                      "${BEND_ARM_RETRY_MS}ms")
                dwellTimer.cancel()
                dwellTimer.start(BEND_ARM_RETRY_MS)
            } else {
                Log.d(TAG, "enterSecondary() suppressed — " +
                    if (currentRing == Ring.NONE) "finger in deadzone (ring=NONE)"
                    else "no segment resolved yet")
            }
            return
        }
        // ── Dwell gate, fire-time check (mode-aware) ─────────────────
        if (dwellGateEnabled()) {
            when (gateMode()) {
                SettingsManager.GATE_MODE_BEND -> {
                    if (lastArmedBendTimeMs == 0L && !tryBackstopArm("Bend")) {
                        bendFireRetryCount++
                        Log.d(TAG, "Bend gate: fire without armed bend — retrying in " +
                              "${BEND_ARM_RETRY_MS}ms (attempt ${bendFireRetryCount}," +
                              if (bendFireRetryCount >= BEND_RELAX_AFTER_RETRIES)
                                  " tier=relaxed)" else " tier=strict)")
                        dwellTimer.cancel()
                        dwellTimer.start(BEND_ARM_RETRY_MS)
                        return
                    }
                    // Armed — but an arm is an ANCHOR, not a verdict. A
                    // straight glide's deceleration tail arms exactly like
                    // a 90° corner; the discriminator is what the finger
                    // did AFTER the valley. A recent above-ceiling move
                    // means the valley was mid-glide: defer and re-probe.
                    // A genuine park goes stale within the grace window
                    // and fires on the next maturity.
                    val sinceFastMs = SystemClock.uptimeMillis() - lastFastMoveWallMs
                    if (lastFastMoveWallMs != 0L &&
                        sinceFastMs < DWELL_GATE_FIRE_GRACE_MS
                    ) {
                        Log.d(TAG, "Bend gate: armed fire deferred — fast move " +
                              "$sinceFastMs ms ago")
                        dwellTimer.cancel()
                        dwellTimer.start(BEND_ARM_RETRY_MS)
                        return
                    }
                    // Armed bend and finger quiescent → fire.
                }
                SettingsManager.GATE_MODE_HYBRID -> {
                    if (lastArmedBendTimeMs == 0L) tryBackstopArm("Hybrid")
                    if (lastArmedBendTimeMs == 0L) {
                        val sinceFastMs = SystemClock.uptimeMillis() - lastFastMoveWallMs
                        if (lastFastMoveWallMs != 0L && sinceFastMs < DWELL_GATE_FIRE_GRACE_MS) {
                            Log.d(TAG, "Hybrid gate blocked fire — fast move $sinceFastMs ms ago, no bend")
                            dwellTimer.start(dwellDurationMs)
                            return
                        }
                    }
                    // Armed bend (or quiescent stillness) → fire.
                }
                else -> {
                    val sinceFastMs = SystemClock.uptimeMillis() - lastFastMoveWallMs
                    if (lastFastMoveWallMs != 0L && sinceFastMs < DWELL_GATE_FIRE_GRACE_MS) {
                        Log.d(TAG, "Dwell gate blocked fire — fast move $sinceFastMs ms ago")
                        dwellTimer.start(dwellDurationMs)
                        return
                    }
                }
            }
        }
        secondaryAnchorX = currentX
        secondaryAnchorY = currentY
        benchObserver?.onAnchorUpdate(currentX, currentY, true, currentEventTime)
        resetSignaledCell()
        resetPulseTracking()
        dwellTimer.cancel()
        transitionTo(TouchState.SECONDARY)
    }
    
    private fun startDelete() {
        dwellTimer.cancel()
        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        deleteLeftCount = 0
        deleteRightCount = 0
        deleteRatchet = newDeleteRatchet()
        activeMode = LayoutMode.LETTERS
        resetSignaledCell()
        resetPulseTracking()
        transitionTo(TouchState.DELETE)
    }

    /** Locks the gateway to NUMBER or SYMBOL mode and shows the menu. */
    private fun enterModeLocked(mode: LayoutMode) {
        dwellTimer.cancel()
        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        deleteLeftCount = 0
        deleteRightCount = 0
        activeMode = mode
        resetSignaledCell()
        resetPulseTracking()

        val grace = modeGraceMs()
        modeGraceActive = grace > 0
        modeGraceDeadline = currentEventTime + grace

        transitionTo(
            if (mode == LayoutMode.NUMBERS) TouchState.NUMBER else TouchState.SYMBOL
        )
    }

    // ── Handlers ─────────────────────────────────────────────────

    private fun handleDown(event: MotionEvent): Boolean {
        refreshFromSettings()

        // Gesture start: stamp event time and seed the motion buffer so
        // the very first exit from the deadzone already has velocity
        // history (short flicks reach the edge within a few samples).
        currentEventTime = event.eventTime
        motionHistory.reset()
        motionHistory.add(event.x, event.y, event.eventTime)
        lastArmedBendTimeMs = 0L
        bendFireRetryCount = 0
        benchObserver?.onGestureStart(event.x, event.y, event.eventTime)

        // Arming rule A: previous gesture ended in the deadzone and this
        // DOWN arrives within the configured window → gateway gesture.
        val windowMs = if (SettingsManager.isInitialized) {
            SettingsManager.doubleTapDeadzoneMs.toLong()
        } else {
            DOUBLE_TAP_FALLBACK_MS
        }
        val elapsed = SystemClock.uptimeMillis() - lastUpTimestamp
        if (lastUpInDeadzone && elapsed in 0..windowMs) {
            Log.d(TAG, "Double-tap deadzone detected → AXIS_PENDING")
            anchorX = event.x
            anchorY = event.y
            currentX = event.x
            currentY = event.y
            lastUpInDeadzone = false
            benchObserver?.onAnchorUpdate(anchorX, anchorY, false, event.eventTime)
            dwellTimer.cancel()
            currentRing = Ring.NONE
            currentSegment = -1
            previousRing = Ring.NONE
            previousSegment = -1
            deleteLeftCount = 0
            deleteRightCount = 0
            resetSignaledCell()
            resetPulseTracking()
            transitionTo(TouchState.AXIS_PENDING)
            return true
        }

        anchorX = event.x
        anchorY = event.y
        currentX = event.x
        currentY = event.y
        benchObserver?.onAnchorUpdate(anchorX, anchorY, false, event.eventTime)

        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        activeMode = LayoutMode.LETTERS
        resetSignaledCell()
        resetPulseTracking()

        transitionTo(TouchState.PRIMARY)
        return true
    }

    private fun handleMove(event: MotionEvent, pointerIndex: Int): Boolean {
        // Drain batched/coalesced sub-samples FIRST — the deceleration
        // tail of a flick lives here, not in the merged "current"
        // position. Order matters: historical samples precede the
        // current one chronologically.
        //
        // ARG ORDER TRAP: getHistoricalX/Y(int pos, int pointerIndex) —
        // history position FIRST, pointer index SECOND (unlike getX,
        // which takes only the pointer index). Swapping them reads
        // "history sample #h" as "finger #h" and crashes on the first
        // batched event, since a lone finger has only index 0.
        val historySize = event.historySize
        for (h in 0 until historySize) {
            val hx = event.getHistoricalX(pointerIndex, h)
            val hy = event.getHistoricalY(pointerIndex, h)
            val ht = event.getHistoricalEventTime(h)
            motionHistory.add(hx, hy, ht)
            benchObserver?.onSample(hx, hy, ht)
        }

        currentX = event.getX(pointerIndex)
        currentY = event.getY(pointerIndex)
        currentEventTime = event.eventTime
        motionHistory.add(currentX, currentY, event.eventTime)
        benchObserver?.onSample(currentX, currentY, event.eventTime)

        when (state) {
            TouchState.PRIMARY      -> handlePrimaryMove()
            TouchState.SECONDARY    -> handleSecondaryMove()
            TouchState.DELETE       -> handleDeleteMove(event, pointerIndex)
            TouchState.CURSOR       -> handleCursorMove(event, pointerIndex)
            TouchState.AXIS_PENDING -> handleAxisPendingMove(event, pointerIndex)
            TouchState.NUMBER,
            TouchState.SYMBOL       -> handleModeMove()
            TouchState.IDLE         -> { /* spurious MOVE with no DOWN — ignore */ }
        }

        if (state == TouchState.PRIMARY || state == TouchState.SECONDARY) {
            applyDwellStillnessGate()
        }

        onPositionChanged?.invoke()
        return true
    }
    
    /**
     * NUMBER/SYMBOL move handling with the post-lock grace window.
     * While grace is active the anchor tracks the finger — the menu follows
     * the flick and nothing can be selected (finger ≡ anchor → deadzone).
     * On the first MOVE after the deadline the anchor freezes and normal
     * geometry resolution resumes. Grace = 0 skips the window entirely.
     */
    private fun handleModeMove() {
        if (!modeGraceActive) {
            resolveGeometry(anchorX, anchorY)
            return
        }
        if (currentEventTime >= modeGraceDeadline) {
            modeGraceActive = false
            resolveGeometry(anchorX, anchorY)   // freeze happened earlier; resolve now
            return
        }
        anchorX = currentX
        anchorY = currentY
    }

    private fun handleUp(event: MotionEvent): Boolean {
        currentX = event.x
        currentY = event.y
        currentEventTime = event.eventTime

        if (state == TouchState.DELETE) {
            val left = deleteLeftCount
            val right = deleteRightCount
            lastUpTimestamp = SystemClock.uptimeMillis()
            lastUpInDeadzone = false
            transitionTo(TouchState.IDLE)
            onDeleteCommit?.invoke(left, right)
            return true
        }
        
        // in handleUp, alongside the AXIS_PENDING branch:
        if (state == TouchState.CURSOR) {
            lastUpTimestamp = SystemClock.uptimeMillis()
            lastUpInDeadzone = false   // deliberately do NOT re-arm the gateway
            transitionTo(TouchState.IDLE)
            return true
        }

        // Gateway released before any axis lock: a deadzone-end that
        // keeps the detector armed for another tap, but commits nothing.
        if (state == TouchState.AXIS_PENDING) {
            lastUpTimestamp = SystemClock.uptimeMillis()
            lastUpInDeadzone = true
            transitionTo(TouchState.IDLE)
            return true
        }
        
        // Lifted inside the post-lock grace window: nothing was ever selected,
        // so nothing commits. Counts as a deadzone-end for the gateway.
        if ((state == TouchState.NUMBER || state == TouchState.SYMBOL) && modeGraceActive) {
            modeGraceActive = false
            lastUpTimestamp = SystemClock.uptimeMillis()
            lastUpInDeadzone = true
            transitionTo(TouchState.IDLE)
            return true
        }

        // Force-final resolution before commit: the suppression window
        // must never mask the gesture-ending position, or a micro-flick
        // that crossed the deadzone edge in its last frames commits
        // nothing (currentSegment still −1 at UP).
        when (state) {
            TouchState.PRIMARY,
            TouchState.NUMBER,
            TouchState.SYMBOL    -> resolveGeometry(anchorX, anchorY, final = true)
            TouchState.SECONDARY -> resolveGeometry(secondaryAnchorX, secondaryAnchorY, final = true)
            else                 -> { /* IDLE: nothing to resolve */ }
        }

        lastUpTimestamp = SystemClock.uptimeMillis()
        lastUpInDeadzone = currentRing == Ring.NONE
        benchObserver?.onCommit(currentRing, currentSegment, event.eventTime)
        onCommit?.invoke()
        transitionTo(TouchState.IDLE)
        return true
    }

    private fun handleCancel(): Boolean {
        benchObserver?.onGestureAborted(currentEventTime)
        if (state == TouchState.DELETE) {
            deleteLeftCount = 0
            deleteRightCount = 0
            transitionTo(TouchState.IDLE)
            onDeleteCancelled?.invoke()
            return true
        }
        if (state == TouchState.CURSOR) {
            cursorColumns = 0
            cursorLines = 0
            transitionTo(TouchState.IDLE)
            return true
        }
        currentX = anchorX
        currentY = anchorY
        transitionTo(TouchState.IDLE)
        return true
    }

    // ── Axis-resolution for the gateway gesture ──────────────────

    /**
     * First significant move after the gateway DOWN locks the gesture:
     * left-horizontal → DELETE, right-horizontal → CURSOR, upward →
     * NUMBER, downward → SYMBOL. Once locked, this same move is processed
     * immediately so the gesture loses no stroke.
     */
    private fun handleAxisPendingMove(event: MotionEvent, pointerIndex: Int) {
        val dxDp = GeometryEngine.pxToDp(event.getX(pointerIndex) - anchorX, density)
        val dyDp = GeometryEngine.pxToDp(event.getY(pointerIndex) - anchorY, density)
        val axDp = Math.abs(dxDp)
        val ayDp = Math.abs(dyDp)
        if (axDp < AXIS_ARM_THRESHOLD_DP && ayDp < AXIS_ARM_THRESHOLD_DP) return

        when {
            dxDp < 0f && axDp >= ayDp -> {
                Log.d(TAG, "Gateway lock: left → DELETE")
                startDelete()
                reanchorToCurrentPosition()
                handleDeleteMove(event, pointerIndex)
            }
            dxDp > 0f && axDp >= ayDp -> {
                Log.d(TAG, "Gateway lock: right → CURSOR mode")
                startCursor()
                reanchorToCurrentPosition()
                handleCursorMove(event, pointerIndex)
            }
            dyDp < 0f -> {
                Log.d(TAG, "Gateway lock: upward → NUMBER mode")
                enterModeLocked(LayoutMode.NUMBERS)
                reanchorToCurrentPosition()
                resolveGeometry(anchorX, anchorY)
            }
            else -> {
                Log.d(TAG, "Gateway lock: downward → SYMBOL mode")
                enterModeLocked(LayoutMode.SYMBOLS)
                reanchorToCurrentPosition()
                resolveGeometry(anchorX, anchorY)
            }
        }
    }

    // ── DELETE-mode drag handling ────────────────────────────────

    /**
     * Package 0.9: delete drag counts ride [deleteRatchet]. The old
     * arm/disarm dual-threshold hysteresis is SUBSUMED by the ratchet
     * (arm = deleteDeadzoneDp, release = 2× arm): within-band retreats
     * produce no count change, oscillation at a reversal point can
     * never re-tick, and a deliberate reversal past the release band
     * FLIPS the lock so sweeping deletes continue without a re-arm
     * dead zone. Direction (left vs right selection) follows the
     * ratchet's locked sign; one sign stays 0 as before.
     */
    private fun handleDeleteMove(event: MotionEvent, pointerIndex: Int) {
        val dxDp = GeometryEngine.pxToDp(event.getX(pointerIndex) - anchorX, density)

        val newCount = deleteRatchet.update(dxDp)

        if (newCount == 0) {
            if (deleteLeftCount != 0 || deleteRightCount != 0) {
                deleteLeftCount = 0
                deleteRightCount = 0
                onDeleteProgress?.invoke(0, 0)
            }
            return
        }

        val newLeft: Int
        val newRight: Int
        if (newCount < 0) {
            newLeft = -newCount
            newRight = 0
        } else {
            newLeft = 0
            newRight = newCount
        }

        if (newLeft != deleteLeftCount || newRight != deleteRightCount) {
            deleteLeftCount = newLeft
            deleteRightCount = newRight
            onDeleteProgress?.invoke(deleteLeftCount, deleteRightCount)
        }
    }
    
    // Cursor feature.
    
    /** Signed column displacement applied so far in CURSOR mode. */
    var cursorColumns: Int = 0
        private set

    /** Signed line displacement applied so far (negative = up). */
    var cursorLines: Int = 0
        private set
    
    private fun startCursor() {
        cursorColumns = 0
        cursorLines = 0
        cursorRatchetH = newCursorRatchetH()
        cursorRatchetV = newCursorRatchetV()
        dwellTimer.cancel()
        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        activeMode = LayoutMode.LETTERS
        resetSignaledCell()
        resetPulseTracking()
        transitionTo(TouchState.CURSOR)
    }

    /**
     * Common geometry resolution for PRIMARY, SECONDARY, NUMBER and
     * SYMBOL. In SECONDARY the anchor is the dwell point; otherwise the
     * gesture anchor. Deadzone (ring NONE) suppresses segment updates;
     * overshoot beyond the outer edge clamps to OUTER.
     *
     * Package 0.3: in SECONDARY the deadzone boundary is the EFFECTIVE
     * (widened) one — see [effectiveDeadzoneDp]. Entering it clears the
     * signaled cell and latches nothing selected while the menu stays
     * visible; lifting inside it commits nothing (ring NONE ⇒ the
     * gesture records as ABORTED, same as a true-deadzone lift). Leaving
     * it resumes classification with immediate cell signaling, so
     * flick-out-again reselects on arrival with no re-dwell.
     *
     * @param final true when resolving the gesture-ending position on
     *              ACTION_UP — enables the radial exit projection so
     *              the last classification before the lift always
     *              yields a committed segment, even for a micro-flick
     *              whose final sample hadn't crossed yet.
     */
    private fun resolveGeometry(anchorX: Float, anchorY: Float, final: Boolean = false) {
        val distPx = geometryEngine.distance(anchorX, anchorY, currentX, currentY)
        val distDp = GeometryEngine.pxToDp(distPx, density)
        val posAngleDeg = geometryEngine.angle(anchorX, anchorY, currentX, currentY)

        // ── Radial exit projection (commit only) ────────────────────
        // At lift the finger is still moving: the last sample reflects
        // where the flick WAS, not where it was GOING. Project the
        // radius along the radial velocity component so a long flick
        // is credited where it was headed (outer) even though the
        // final sample hadn't crossed yet — and an inward mover must
        // genuinely retreat. Slow/parked fingers project nothing.
        var classDistDp = distDp
        if (final && radprojEnabled()) {
            classDistDp = projectedCommitDistanceDp(anchorX, anchorY, distDp)
        }

        // Exit detection BEFORE currentRing is mutated below.
        val wasInDeadzone = currentRing == Ring.NONE

        // ── Package 0.3b: retreat-gated abort ease ───────────────────
        // The abort ease is MOTION-gated, not position-gated: the band
        // [deadzone, deadzone+ease] is only special during INWARD
        // motion. This restores small flicks that land short of the
        // inner ring (outward/tangential/parked fingers classify by the
        // TRUE boundary) while keeping "pull back to cancel" forgiving
        // (inward velocity arms the eased boundary). The same gate on
        // the NONE side prevents boundary flap: leaving NONE requires
        // either clearing the eased radius outright or active outward
        // velocity across the TRUE boundary — hovering in the band
        // can't oscillate cell↔none without reversing radial velocity,
        // which hands don't do.
        val newRing = if (state == TouchState.SECONDARY) {
            val eased = effectiveDeadzoneDp()
            val base = if (SettingsManager.isInitialized) SettingsManager.deadzoneRadius
                       else 18f
            val radialV = radialVelocityDpPerMs(anchorX, anchorY)
            when {
                // Inside the true deadzone: abort unconditionally.
                classDistDp < base -> Ring.NONE

                // Currently in NONE: re-select only on outward intent —
                // either fully past the eased radius, or crossing the
                // TRUE boundary while flicking outward.
                wasInDeadzone ->
                    if (classDistDp > eased || radialV >= ABORT_EASE_RADIAL_FLOOR)
                        geometryEngine.computeRing(classDistDp, posAngleDeg)
                    else Ring.NONE

                // Holding a cell: abort only on INWARD intent inside the
                // band. Outward, tangential, and parked fingers keep the
                // cell — a small flick that landed short of the inner
                // ring still selects.
                else ->
                    if (classDistDp < eased && radialV <= -ABORT_EASE_RADIAL_FLOOR)
                        Ring.NONE
                    else geometryEngine.computeRing(classDistDp, posAngleDeg)
            }
        } else {
            geometryEngine.computeRing(classDistDp, posAngleDeg)
        }

        // ── Onset-weighted exit angle ─────────────────────────────────
        // (unchanged from 0.3 — see its block comment)
        var angleDeg = posAngleDeg
        if (onsetEnabled() && newRing != Ring.NONE && wasInDeadzone) {
            motionHistory.velocity(onsetVelocityWindowMs())?.let { v ->
                val speedDpPerMs = GeometryEngine.pxToDp(
                    Math.hypot(v.first.toDouble(), v.second.toDouble()).toFloat(),
                    density
                )
                if (speedDpPerMs >= onsetMinSpeedDpPerMs()) {
                    val velAngleDeg = geometryEngine.angle(0f, 0f, v.first, v.second)
                    angleDeg = MotionHistory.blendAngles(
                        posAngleDeg, velAngleDeg, onsetPositionWeight()
                    )
                }
            }
        }

        if (newRing != currentRing) {
            previousRing = currentRing
            currentRing = newRing
            if (newRing == Ring.NONE) {
                // Entering the deadzone deselects everything — and any
                // cell the finger later re-enters must signal again.
                previousSegment = currentSegment
                currentSegment = -1
                resetSignaledCell()
                resetPulseTracking()
            }
            onRingChanged?.invoke(newRing)
            benchObserver?.onRingChanged(previousRing, newRing, currentEventTime)
            // Geometry motion resets the dwell clock ONLY when no bend
            // owns the scheduled fire (see 0.4-era note — armed bend
            // owns the scheduled fire; classic stillness keeps the
            // unconditional reset).
            if (state == TouchState.PRIMARY) maybeRestartDwellOnMotion()
        }

        val newSegment = geometryEngine.computeSegment(angleDeg, currentSegment)

        if (newRing != Ring.NONE && newSegment != currentSegment) {
            previousSegment = currentSegment
            currentSegment = newSegment
            onSegmentChanged?.invoke(newSegment)
            if (state == TouchState.PRIMARY) maybeRestartDwellOnMotion()
        }

        updatePulseTracking(anchorX, anchorY, distDp, posAngleDeg)

        if (currentRing != Ring.NONE && currentSegment != -1 &&
            (currentRing != signaledRing || currentSegment != signaledSegment)
        ) {
            signaledRing = currentRing
            signaledSegment = currentSegment
            onCellChanged?.invoke(currentRing, currentSegment)
        }
    }
    
    /**
     * Ring/segment crossings in PRIMARY traditionally restart the dwell
     * clock (moving finger ⇒ not dwelling). In bend-driven gate modes
     * that rule applies ONLY while unarmed: once a valley has armed the
     * fire ([lastArmedBendTimeMs] != 0), the scheduled fire belongs to
     * the bend, and crossing a ring boundary after the valley must not
     * postpone it. Unarmed resets are kept for HYBRID's stillness leg
     * and for the classic stillness mode, where they are the whole
     * mechanism. Finger-up still tears everything down (handleUp).
     */
    private fun maybeRestartDwellOnMotion() {
        if (dwellGateEnabled() &&
            (gateMode() == SettingsManager.GATE_MODE_BEND ||
             gateMode() == SettingsManager.GATE_MODE_HYBRID) &&
            lastArmedBendTimeMs != 0L
        ) {
            return   // armed bend owns the fire — no reset
        }
        dwellTimer.reset()
    }
    
    /**
     * Pulse-ring update (Package 0.4a). Runs after the raw ring
     * resolution in [resolveGeometry].
     *
     * Rules:
     * 1. ADVANCE outward (any rank increase) fires — whether earned by
     *    raw geometry or led by the projection. Anticipatory by at
     *    most the projection horizon / max shift.
     * 2. REGRESS inward fires ONLY when the current advance was NOT
     *    projection-led: the raw ring walked itself down through the
     *    boundary, a genuine crossing. A projection-led advance that
     *    withdraws (finger braked before crossing) collapses SILENTLY
     *    back to the raw ring — no compensating pulse, no callback.
     * 3. While the raw ring catches up to a projection-led pulseRing,
     *    the ledger silently forgets the projection debt, so a later
     *    genuine regress fires normally.
     *
     * Callers see onPulseRingChanged(old, new) with the same pairing
     * semantics the old onRingChanged haptics used — deadzone exits,
     * ring↔ring crossings, both directions.
     */
    private fun updatePulseTracking(
        anchorX: Float, anchorY: Float, distDp: Float, angleDeg: Float
    ) {
        // Projection applies to the PULSE path whenever the feature is
        // on — not just at commit. Parked/slow fingers project nothing.
        val projDistDp = if (radprojEnabled())
            projectedCommitDistanceDp(anchorX, anchorY, distDp) else distDp
        val projRing =
            if (state == TouchState.SECONDARY && projDistDp < effectiveDeadzoneDp())
                Ring.NONE
            else geometryEngine.computeRing(projDistDp, angleDeg)

        // Rule 3: raw catch-up retires the projection debt.
        if (rank(currentRing) >= rank(pulseRing) && pulseLedByProjection) {
            pulseLedByProjection = false
        }

        val oldRank = rank(pulseRing)
        val newRank = rank(projRing)
        when {
            newRank > oldRank -> {
                pulseLedByProjection = newRank > rank(currentRing)
                pulseRing = projRing
                onPulseRingChanged?.invoke(ringAt(oldRank), projRing)
            }
            newRank < oldRank -> {
                if (pulseLedByProjection) {
                    pulseRing = currentRing          // silent collapse (rule 2)
                } else {
                    pulseRing = projRing
                    onPulseRingChanged?.invoke(ringAt(oldRank), projRing)
                }
                pulseLedByProjection = false
            }
            // equal ranks: steady — nothing to signal
        }
    }

    /** True when [pulseRing] outranks the raw ring (projection debt). */
    private var pulseLedByProjection: Boolean = false
     
    private fun onsetEnabled(): Boolean =
        if (SettingsManager.isInitialized) SettingsManager.onsetExitAngleEnabled
        else true

    private fun onsetPositionWeight(): Float =
        if (SettingsManager.isInitialized) SettingsManager.onsetPositionWeight
        else ONSET_POSITION_WEIGHT

    private fun onsetVelocityWindowMs(): Long =
        if (SettingsManager.isInitialized) SettingsManager.onsetVelocityWindowMs.toLong()
        else ONSET_VELOCITY_WINDOW_MS

    private fun onsetMinSpeedDpPerMs(): Float =
        if (SettingsManager.isInitialized) SettingsManager.onsetMinSpeedDpPerMs
        else MIN_EXIT_SPEED_DP_PER_MS
    
        // ── Radial exit projection ───────────────────────────────────

    private fun radprojEnabled(): Boolean =
        if (SettingsManager.isInitialized) SettingsManager.radprojEnabled
        else false

    private fun radprojHorizonMs(): Int =
        if (SettingsManager.isInitialized) SettingsManager.radprojHorizonMs
        else RADPROJ_HORIZON_DEFAULT

    private fun radprojMaxShiftDp(): Float =
        if (SettingsManager.isInitialized) SettingsManager.radprojMaxShiftDp
        else RADPROJ_SHIFT_DEFAULT

    /**
     * PROJECTED anchored radius (dp) for the commit classification.
     *
     * Radial velocity = velocity vector (px/ms, trailing 40 ms window
     * from MotionHistory — same estimator the gate and onset blend
     * use) dotted with the unit vector anchor→finger. Sign follows
     * the direction of travel, so inward motion projects inward.
     * Unmeasurable velocity (parked, <2 samples, batched burst) or a
     * finger sitting exactly on the anchor project nothing.
     */
    private fun projectedCommitDistanceDp(
        anchorX: Float, anchorY: Float, distDp: Float
    ): Float {
        val v = motionHistory.velocity() ?: return distDp
        val dxPx = currentX - anchorX
        val dyPx = currentY - anchorY
        val lenPx = Math.hypot(dxPx.toDouble(), dyPx.toDouble()).toFloat()
        if (lenPx < 1f) return distDp      // at anchor: no direction defined
        val urx = dxPx / lenPx
        val ury = dyPx / lenPx
        val radialVPxPerMs = v.first * urx + v.second * ury
        val radialVDpPerMs = GeometryEngine.pxToDp(radialVPxPerMs, density)
        return GeometryEngine.projectedRadiusDp(
            distDp, radialVDpPerMs, radprojHorizonMs(), radprojMaxShiftDp()
        )
    }
    
    /**
     * Radial velocity (dp/ms) of the finger along the anchor→finger
     * ray: positive = moving OUTWARD, negative = retreating INWARD.
     * Same trailing-window velocity estimator the exit projection and
     * onset blend use. Returns 0f when unmeasurable (parked, <2
     * samples) — a parked finger gets pure geometry on both edges of
     * the 0.3b rule set.
     */
    private fun radialVelocityDpPerMs(anchorX: Float, anchorY: Float): Float {
        val v = motionHistory.velocity() ?: return 0f
        val dxPx = currentX - anchorX
        val dyPx = currentY - anchorY
        val lenPx = Math.hypot(dxPx.toDouble(), dyPx.toDouble()).toFloat()
        if (lenPx < 1f) return 0f      // at anchor: no direction defined
        val radialVPxPerMs = v.first * (dxPx / lenPx) + v.second * (dyPx / lenPx)
        return GeometryEngine.pxToDp(radialVPxPerMs, density)
    }
    
    /**
     * Dwell stillness gate, move-side. While the finger is moving faster
     * than the stillness ceiling, the dwell clock is restarted so a fast
     * flick can never accrue dwell time. Paired with the DOWN/UP cancels
     * and reset-on-cell-change, the effective contract is:
     *
     *   dwell timer restarts on     finger speed ≥ ceiling  (still moving)
     *   dwell timer proceeds        finger held still for dwellDurationMs
     *
     * In SECONDARY the menu is already open and dwell cannot fire again,
     * so the gate is a no-op there.
     */
    private fun applyDwellStillnessGate() {
        if (state == TouchState.PRIMARY) {
            val speed = currentFingerSpeedDpPerMs()
            val fast = speed >= dwellGateMaxSpeedDpPerMs()
            if (fast) {
                lastDwellFireSpeedDpPerMs = speed
                lastFastMoveWallMs = SystemClock.uptimeMillis()
            }
            when (gateMode()) {
                SettingsManager.GATE_MODE_BEND,
                SettingsManager.GATE_MODE_HYBRID -> {
                    // Idempotent arm: the FIRST qualifying valley owns the
                    // scheduled fire. After the first arm, nothing here
                    // touches the timer again.
                    if (lastArmedBendTimeMs == 0L) {
                        val bend = motionHistory.findBend(BEND_WINDOW_MS, SystemClock.uptimeMillis())
                        if (bend != null) {
                            val dipRatio = if (bend.peakSpeedPxPerMs > 0f)
                                bend.speedPxPerMs / bend.peakSpeedPxPerMs else 1f
                            val qualifies = bendQualifies(bend, BEND_REL_DIP_RATIO)  // move side: tier 1 always
                            val ageMs = currentEventTime - bend.timeMs
                            Log.d(TAG, "bend probe t=${bend.timeMs} ageMs=$ageMs" +
                                  " minPx=${bend.speedPxPerMs} peakPx=${bend.peakSpeedPxPerMs}" +
                                  " wallPx=${bend.approachSpeedPxPerMs}" +
                                  " ratio=$dipRatio qualifies=$qualifies")
                            if (qualifies) {
                                lastArmedBendTimeMs = bend.timeMs
                                bendFireRetryCount = 0
                                dwellTimer.cancel()
                                dwellTimer.start((dwellDurationMs - ageMs).coerceAtLeast(1L))
                            }
                        }
                    }
                    // Stillness leg (hybrid only) — UNARMED only. A flick
                    // re-accelerates THROUGH its armed fire moment by
                    // design; resetting here would wipe the bend-earned
                    // schedule on the very next fast MOVE and re-impose the
                    // "opens dwellMs after you stop" latency on every
                    // flick gesture. The arm owns the clock from its
                    // moment of qualification.
                    if (gateMode() == SettingsManager.GATE_MODE_HYBRID &&
                        fast && lastArmedBendTimeMs == 0L
                    ) {
                        dwellTimer.reset()
                    }
                }
                else -> {
                    // Classic stillness: fast motion restarts the clock.
                    if (fast) dwellTimer.reset()
                }
            }
        }
    }
    
    /**
     * Finger speed (dp/ms) measured at the most recent dwell-fire attempt,
     * stamped for benchmark logging. −1 = no dwell has fired yet.
     */
    var lastDwellFireSpeedDpPerMs: Float = -1f
        private set

    private fun dwellGateEnabled(): Boolean =
        if (SettingsManager.isInitialized) SettingsManager.dwellGateEnabled else true
    
    private fun gateMode(): String =
        if (SettingsManager.isInitialized) SettingsManager.dwellGateMode
        else SettingsManager.GATE_MODE_STILLNESS

    private fun dwellGateMaxSpeedDpPerMs(): Float =
        if (SettingsManager.isInitialized) SettingsManager.dwellGateMaxSpeedDpPerMs
        else DWELL_GATE_MAX_SPEED
    
        /**
     * Shared qualification test for a candidate bend. The approach
     * wall is mandatory at every tier — never relaxed.
     */
    private fun bendQualifies(bend: Bend, relDipRatio: Float): Boolean {
        val bendDpPerMs = GeometryEngine.pxToDp(bend.speedPxPerMs, density)
        val peakDpPerMs = GeometryEngine.pxToDp(bend.peakSpeedPxPerMs, density)
        val approachDpPerMs = GeometryEngine.pxToDp(bend.approachSpeedPxPerMs, density)
        val dipRatio = if (peakDpPerMs > 0f) bendDpPerMs / peakDpPerMs else 1f
        return approachDpPerMs >= dwellGateMaxSpeedDpPerMs() && (
            bendDpPerMs < dwellGateMaxSpeedDpPerMs() ||
            dipRatio <= relDipRatio
        )
    }

    /**
     * Fire-time backstop shared by BEND and HYBRID: probe for a late
     * bend with virtual-arrest extension (parked fingers surface their
     * deceleration tail as a valley), qualify at the current tier, and
     * arm if it passes. Tier 2 (relaxed ratio) is refused while the
     * finger sits in the deadzone. Returns true when armed.
     */
    private fun tryBackstopArm(tag: String): Boolean {
        val nowMs = SystemClock.uptimeMillis()
        val relaxed = bendFireRetryCount >= BEND_RELAX_AFTER_RETRIES
        val tier = if (relaxed) BEND_REL_DIP_RATIO_RELAXED else BEND_REL_DIP_RATIO
        val bend = motionHistory.findBend(BEND_WINDOW_MS, nowMs) ?: return false
        if (relaxed && currentRing == Ring.NONE) {
            Log.d(TAG, "$tag backstop: tier-2 candidate rejected — finger in deadzone")
            return false
        }
        if (!bendQualifies(bend, tier)) return false
        lastArmedBendTimeMs = bend.timeMs
        bendFireRetryCount = 0
        val ageMs = nowMs - bend.timeMs
        Log.d(TAG, "$tag gate: backstop armed bend t=${bend.timeMs} ageMs=$ageMs" +
              " tier=${if (relaxed) "relaxed" else "strict"}" +
              " minPx=${bend.speedPxPerMs} wallPx=${bend.approachSpeedPxPerMs}")
        return true
    }

    /**
     * Displacement speed (dp/ms) of the finger over the trailing
     * [DWELL_GATE_WINDOW_MS], from the same MotionHistory the onset blend
     * uses. Returns 0f when no velocity is measurable — an unmeasurable
     * finger counts as still, so gate mode never opens the menu on a
     * guess; it errs toward opening.
     */
    private fun currentFingerSpeedDpPerMs(): Float {
        val v = motionHistory.velocity(DWELL_GATE_WINDOW_MS) ?: return 0f
        return GeometryEngine.pxToDp(
            Math.hypot(v.first.toDouble(), v.second.toDouble()).toFloat(),
            density
        )
    }

    private var currentEventTime: Long = 0L

    private fun handlePrimaryMove() {
        resolveGeometry(anchorX, anchorY)
    }

    private fun handleSecondaryMove() {
        resolveGeometry(secondaryAnchorX, secondaryAnchorY)
    }
    
    /**
     * Moves the gesture anchor to the finger's current position. Called when
     * the gateway locks NUMBER/SYMBOL mode: the locking flick has already
     * displaced the finger from the second tap's down position, so keeping
     * that anchor opens the menu offset from the finger. Re-anchoring here
     * centres the menu under the finger and drops it into the new menu's
     * deadzone — a neutral start, identical to a fresh gesture's down — and
     * mirrors the secondary menu's behaviour of centring on the finger.
     */
    private fun reanchorToCurrentPosition() {
        anchorX = currentX
        anchorY = currentY
    }
    
    /**
     * Package 0.9: cursor drags ride per-axis ratchets (H and V lock
     * independently — the pinned sub-decision, so an L-shaped drag
     * works and one axis's reversal never freezes the other). The old
     * raw signedCount path is subsumed: the ratchet's arm threshold
     * IS the cursor deadzone, its release band kills boundary
     * ping-pong, and a genuine reversal flips and counts on the same
     * event. Counts remain signed net displacement, so the 0.4
     * cursor-tick semantics (tick per count change) are inherited.
     */
    private fun handleCursorMove(event: MotionEvent, pointerIndex: Int) {
        val dxDp = GeometryEngine.pxToDp(event.getX(pointerIndex) - anchorX, density)
        val dyDp = GeometryEngine.pxToDp(event.getY(pointerIndex) - anchorY, density)

        val newCols = cursorRatchetH.update(dxDp)
        if (newCols != cursorColumns) {
            cursorColumns = newCols
            onCursorMoveH?.invoke(newCols)
        }

        val newLines = cursorRatchetV.update(dyDp)
        if (newLines != cursorLines) {
            cursorLines = newLines
            onCursorMoveV?.invoke(newLines)
        }
    }

    // ── Transition machinery ─────────────────────────────────────

    private fun transitionTo(newState: TouchState) {
        if (state == newState) return
        Log.d(TAG, "$state → $newState")
        val oldState = state
        state = newState

        when (newState) {
            TouchState.PRIMARY      -> dwellTimer.start()
            TouchState.IDLE         -> dwellTimer.cancel()
            TouchState.AXIS_PENDING -> dwellTimer.cancel()
            TouchState.SECONDARY    -> { /* dwell callback already fired */ }
            TouchState.DELETE       -> { /* timer cancelled in startDelete */ }
            TouchState.CURSOR       -> { /* timer cancelled in startCursor */ }
            TouchState.NUMBER,
            TouchState.SYMBOL       -> { /* dwell never fires in locked modes */ }
        }

        benchObserver?.onStateTransition(oldState, newState, currentEventTime)
        onStateChanged?.invoke(newState)
    }

    enum class TouchState {
        IDLE,
        PRIMARY,
        SECONDARY,
        AXIS_PENDING,
        DELETE,
        CURSOR,
        NUMBER,
        SYMBOL
    }
}
