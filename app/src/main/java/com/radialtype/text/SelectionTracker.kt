package com.radialtype.text

import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.TouchStateMachine.TouchState

/**
 * Module 8 — Tracks the user's current selection as a human-readable label.
 *
 * Bridges the engine side (ring/segment from [com.radialtype.engine.TouchStateMachine],
 * state transitions from Module 7) with presentation (Module 10 renderer).
 *
 * Lifecycle:
 * - [update] is called on every ring/segment geometry change.
 * - [updateState] is called on every state transition.
 * - When entering SECONDARY, the primary character at the dwell position
 *   is snapshotted; the label then comes from the syllable provider
 *   (Module 9) if available, otherwise it falls back to the primary char.
 *
 * Thread-safety: all methods are expected to be invoked on the main
 * thread (touch events + dwell timer callbacks), matching the IME setup.
 */
class SelectionTracker(
    val characterMap: CharacterMap = CharacterMap(),
    /** Null until Module 9 is wired — label falls back to the primary char. */
    var syllableProvider: SyllableProvider? = null
) {

    /** Currently resolved ring (synced from the state machine). */
    var currentRing: Ring = Ring.NONE
        private set

    /** Currently resolved segment (0–7, or −1 before the first MOVE). */
    var currentSegment: Int = -1
        private set

    /** Current gesture state, synced from the state machine. */
    var currentState: TouchState = TouchState.IDLE
        private set

    /**
     * Character resolved from the last geometry update while in PRIMARY.
     * Snapshotted again when entering SECONDARY so that secondary-segment
     * movements do not clobber the primary selection.
     */
    var currentPrimaryChar: String = ""
        private set

    /** Fired whenever the label may have changed. */
    var onLabelChanged: ((String) -> Unit)? = null

    /**
     * Called on geometry changes (ring and/or segment).
     * While in PRIMARY, refreshes the resolved primary character.
     */
    fun update(ring: Ring, segment: Int) {
        val changed = ring != currentRing || segment != currentSegment
        currentRing = ring
        currentSegment = segment
        if (currentState == TouchState.PRIMARY) {
            currentPrimaryChar = characterMap.getPrimaryChar(ring, segment)
        }
        if (changed) notifyChanged()
    }

    /**
     * Called on every state transition. On PRIMARY → SECONDARY the primary
     * character at the dwell position is frozen; on SECONDARY → PRIMARY
     * (escape), it is re-resolved for wherever the finger now sits.
     */
    fun updateState(state: TouchState) {
        if (state == currentState) return
        val previous = currentState
        currentState = state
        when (state) {
            TouchState.PRIMARY ->
                // Fresh DOWN resets ring/segment to NONE/−1 → label "";
                // escape from SECONDARY re-resolves from the current cell.
                currentPrimaryChar = characterMap.getPrimaryChar(currentRing, currentSegment)

            TouchState.SECONDARY ->
                // Freeze the primary char exactly as dwelled.
                currentPrimaryChar = characterMap.getPrimaryChar(currentRing, currentSegment)

            TouchState.IDLE -> { /* label resolves to "" anyway */ }
        }
        if (previous != state) notifyChanged()
    }

    /**
     * The label the renderer (Module 10) should display.
     *
     * - IDLE: ""
     * - PRIMARY: the primary character for (ring, segment)
     * - SECONDARY: the ranked syllable at the current segment, falling
     *   back to the primary char when no provider/syllable exists.
     */
    fun currentLabel(): String = when (currentState) {
        TouchState.IDLE -> ""
        TouchState.PRIMARY -> currentPrimaryChar
        TouchState.SECONDARY -> {
            val syllable = syllableProvider?.getSyllable(currentPrimaryChar, currentSegment)
            if (syllable.isNullOrEmpty()) currentPrimaryChar else syllable
        }
    }

    private fun notifyChanged() {
        onLabelChanged?.invoke(currentLabel())
    }
}
