package com.radialtype.engine

import android.util.Log
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.settings.SettingsManager

/**
 * Finite state machine for RadialType's single-gesture touch tracking,
 * integrated with [GeometryEngine] for ring/segment detection.
 *
 * States:
 * - IDLE      No active gesture.
 * - PRIMARY   Finger is down, performing the primary radial selection.
 * - SECONDARY Finger dwelled long enough to activate a secondary menu.
 *
 * Deadzone behavior:
 * Inside the center deadzone the ring resolves to [Ring.NONE]. While NONE:
 * - No segment changes are reported (and thus no segment haptics).
 * - Dwelling does NOT enter SECONDARY.
 * Overshoot beyond the outer edge clamps to [Ring.OUTER] instead of
 * deselecting.
 *
 * Segment suppression:
 * Immediately after a ring change, segment-change detection is
 * suppressed for [SEGMENT_SUPPRESSION_MS] milliseconds.
 *
 * Runtime settings (Module 12): dwell duration and ring radii are
 * refreshed from [com.radialtype.settings.SettingsManager] on every
 * ACTION_DOWN, so slider changes apply from the next gesture onward.
 *
 * @param geometryEngine Injected for testability; defaults to a new instance.
 * @param density Screen density for px→dp conversion.
 */
class TouchStateMachine(
    private val geometryEngine: GeometryEngine = GeometryEngine(),
    var density: Float = 1f,
    var dwellDurationMs: Long = DEFAULT_DWELL_MS,
    dwellTimerOverride: DwellTimer? = null
) {

    companion object {
        private const val TAG = "TouchStateMachine"

        const val SEGMENT_SUPPRESSION_MS = 50L

        /** Default dwell duration before PRIMARY → SECONDARY. */
        const val DEFAULT_DWELL_MS = 300L
    }

    // ── Dwell timer (Module 7) ──────────────────────────────────

    val dwellTimer: DwellTimer = dwellTimerOverride
        ?: DwellTimer(
            dwellDurationMs = dwellDurationMs,
            handler = Handler(Looper.getMainLooper()),
            callback = { enterSecondary() }
        )

    // ── Gesture state ────────────────────────────────────────────

    var state: TouchState = TouchState.IDLE
        private set

    var anchorX: Float = 0f
        private set

    var anchorY: Float = 0f
        private set

    var currentX: Float = 0f
        private set

    var currentY: Float = 0f
        private set

    // ── Geometry tracking ────────────────────────────────────────

    var currentRing: Ring = Ring.NONE
        private set

    var currentSegment: Int = -1
        private set

    var previousRing: Ring = Ring.NONE
        private set

    var previousSegment: Int = -1
        private set

    private var lastRingChangeTime: Long = 0L

    // ── Secondary state tracking ─────────────────────────────────

    var secondaryAnchorX: Float = 0f
        private set

    var secondaryAnchorY: Float = 0f
        private set

    // ── Callbacks ───────────────────────────────────────────────

    var onStateChanged: ((TouchState) -> Unit)? = null
    var onPositionChanged: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onRingChanged: ((Ring) -> Unit)? = null
    var onSegmentChanged: ((Int) -> Unit)? = null

    // ── Public API ───────────────────────────────────────────────

    /**
     * Syncs engine geometry + dwell timing with the current persisted
     * settings. Called on ACTION_DOWN so runtime slider changes take
     * effect from the very next gesture.
     */
    fun refreshFromSettings() {
        geometryEngine.refreshFromSettings()
        dwellDurationMs = SettingsManager.dwellDurationMs.toLong()
        dwellTimer.dwellDurationMs = dwellDurationMs
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN   -> handleDown(event)
            MotionEvent.ACTION_MOVE   -> handleMove(event)
            MotionEvent.ACTION_UP     -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel()
            else -> false
        }
    }

    /**
     * Transitions to SECONDARY state. Suppressed while the finger sits
     * in the deadzone (ring NONE).
     */
    fun enterSecondary() {
        if (state != TouchState.PRIMARY) {
            Log.w(TAG, "enterSecondary() called in $state — ignoring")
            return
        }
        if (currentRing == Ring.NONE) {
            Log.d(TAG, "enterSecondary() suppressed — finger in deadzone (ring=NONE)")
            return
        }
        secondaryAnchorX = currentX
        secondaryAnchorY = currentY
        lastRingChangeTime = 0L
        transitionTo(TouchState.SECONDARY)
    }

    // ── Handlers ─────────────────────────────────────────────────

    private fun handleDown(event: MotionEvent): Boolean {
        refreshFromSettings()

        anchorX = event.x
        anchorY = event.y
        currentX = event.x
        currentY = event.y

        currentRing = Ring.NONE
        currentSegment = -1
        previousRing = Ring.NONE
        previousSegment = -1
        lastRingChangeTime = 0L

        transitionTo(TouchState.PRIMARY)
        return true
    }

    private fun handleMove(event: MotionEvent): Boolean {
        currentX = event.x
        currentY = event.y

        when (state) {
            TouchState.PRIMARY   -> handlePrimaryMove(event)
            TouchState.SECONDARY -> handleSecondaryMove(event)
            TouchState.IDLE      -> { /* spurious MOVE with no DOWN — ignore */ }
        }

        onPositionChanged?.invoke()
        return true
    }

    private fun handleUp(event: MotionEvent): Boolean {
        currentX = event.x
        currentY = event.y
        onCommit?.invoke()
        transitionTo(TouchState.IDLE)
        return true
    }

    private fun handleCancel(): Boolean {
        currentX = anchorX
        currentY = anchorY
        transitionTo(TouchState.IDLE)
        return true
    }

    // ── Geometry (shared by PRIMARY and SECONDARY) ───────────────

    /**
     * Common geometry resolution for both states. In SECONDARY the
     * anchor is the dwell point; in PRIMARY it is the touch-down point.
     */
    private fun resolveGeometry(event: MotionEvent, anchorX: Float, anchorY: Float) {
        val distPx = geometryEngine.distance(anchorX, anchorY, currentX, currentY)
        val distDp = GeometryEngine.pxToDp(distPx, density)
        val angleDeg = geometryEngine.angle(anchorX, anchorY, currentX, currentY)

        val newRing = geometryEngine.computeRing(distDp, currentRing)

        if (newRing != currentRing) {
            previousRing = currentRing
            currentRing = newRing
            if (newRing == Ring.NONE) {
                previousSegment = currentSegment
                currentSegment = -1
            }
            lastRingChangeTime = event.eventTime
            onRingChanged?.invoke(newRing)
            dwellTimer.reset()
        }

        val newSegment = geometryEngine.computeSegment(angleDeg)

        val timeSinceRingChange = event.eventTime - lastRingChangeTime
        if (timeSinceRingChange >= SEGMENT_SUPPRESSION_MS) {
            if (newRing != Ring.NONE && newSegment != currentSegment) {
                previousSegment = currentSegment
                currentSegment = newSegment
                Log.d(TAG,
                    "Segment: $previousSegment → $newSegment  " +
                    "(angle=${String.format("%.1f", angleDeg)}°)")
                onSegmentChanged?.invoke(newSegment)
                dwellTimer.reset()
            }
        }
    }

    private fun handlePrimaryMove(event: MotionEvent) {
        resolveGeometry(event, anchorX, anchorY)
    }

    private fun handleSecondaryMove(event: MotionEvent) {
        resolveGeometry(event, secondaryAnchorX, secondaryAnchorY)
    }

    // ── Internals ────────────────────────────────────────────────

    private fun transitionTo(newState: TouchState) {
        if (state == newState) return
        Log.d(TAG, "$state → $newState")
        state = newState

        when (newState) {
            TouchState.PRIMARY   -> dwellTimer.start()
            TouchState.IDLE      -> dwellTimer.cancel()
            TouchState.SECONDARY -> { /* dwell callback already fired */ }
        }

        onStateChanged?.invoke(newState)
    }

    enum class TouchState {
        IDLE,
        PRIMARY,
        SECONDARY
    }
}
