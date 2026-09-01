package com.radialtype.text

import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.LayoutMode
import com.radialtype.engine.TouchStateMachine.TouchState

/**
 * Tracks the user's current selection as a human-readable label.
 *
 * [mode] mirrors TouchStateMachine.activeMode and selects which layout
 * table primary labels resolve from. NUMBER and SYMBOL states resolve
 * directly from the primary tables — no syllable lookup.
 */
class SelectionTracker(
    val characterMap: CharacterMap = CharacterMap(),
    var syllableProvider: SyllableProvider? = null
) {

    var currentRing: Ring = Ring.NONE
        private set

    var currentSegment: Int = -1
        private set

    var currentState: TouchState = TouchState.IDLE
        private set

    /** Layout mode the labels are resolved against. Synced by the view. */
    var mode: LayoutMode = LayoutMode.LETTERS

    var currentPrimaryChar: String = ""
        private set

    var onLabelChanged: ((String) -> Unit)? = null

    fun update(ring: Ring, segment: Int) {
        val changed = ring != currentRing || segment != currentSegment
        currentRing = ring
        currentSegment = segment
        if (currentState == TouchState.PRIMARY ||
            currentState == TouchState.NUMBER ||
            currentState == TouchState.SYMBOL
        ) {
            currentPrimaryChar = characterMap.getPrimaryChar(ring, segment, mode)
        }
        if (changed) notifyChanged()
    }

    fun updateState(state: TouchState) {
        if (state == currentState) return
        val previous = currentState
        currentState = state
        when (state) {
            TouchState.PRIMARY ->
                currentPrimaryChar = characterMap.getPrimaryChar(currentRing, currentSegment, mode)

            TouchState.SECONDARY ->
                currentPrimaryChar = characterMap.getPrimaryChar(currentRing, currentSegment, LayoutMode.LETTERS)

            TouchState.NUMBER,
            TouchState.SYMBOL ->
                currentPrimaryChar = characterMap.getPrimaryChar(currentRing, currentSegment, mode)

            TouchState.DELETE ->
                currentPrimaryChar = ""

            TouchState.AXIS_PENDING,
            TouchState.IDLE -> { /* no selection yet */ }
        }
        if (previous != state) notifyChanged()
    }

    fun currentLabel(): String {
        return when (currentState) {
            TouchState.IDLE -> ""
            TouchState.PRIMARY -> currentPrimaryChar
            TouchState.SECONDARY -> {
                if (currentRing == Ring.NONE) return ""
                val syllable = syllableProvider?.getSyllable(currentPrimaryChar, currentRing, currentSegment)
                if (syllable.isNullOrEmpty()) currentPrimaryChar else syllable
            }
            TouchState.NUMBER, TouchState.SYMBOL ->
                if (currentRing == Ring.NONE) "" else currentPrimaryChar
            TouchState.DELETE, TouchState.AXIS_PENDING -> ""
        }
    }

    private fun notifyChanged() {
        onLabelChanged?.invoke(currentLabel())
    }
}
