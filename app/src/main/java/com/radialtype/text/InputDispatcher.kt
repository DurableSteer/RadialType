package com.radialtype.text

import android.view.inputmethod.InputConnection

/**
 * Module 11 — Handles text commitment to the active input field.
 *
 * The InputConnection is resolved lazily via [connectionProvider] because
 * it is only valid while an input session is active.
 */
class InputDispatcher(
    private val connectionProvider: () -> InputConnection?
) {

    /** Auto-commit a space after every commit (configurable setting). */
    var autoSpaceEnabled: Boolean = false

    /**
     * Commits the given label with basic sentence capitalization.
     * Capitalizes the first letter when the text before the cursor is
     * empty, ends with ". ", "? ", "! ", or a newline.
     */
    fun commit(label: String) {
        if (label.isEmpty()) return
        val ic = connectionProvider() ?: return

        val before = ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
        val capitalized = if (shouldCapitalize(before)) {
            label.replaceFirstChar { it.uppercase() }
        } else {
            label
        }

        ic.commitText(capitalized, 1)
        if (autoSpaceEnabled) commitSpace()
    }

    /** Commits a single space character. */
    fun commitSpace() {
        connectionProvider()?.commitText(" ", 1)
    }

    /** Deletes one character before the cursor. */
    fun backspace() {
        connectionProvider()?.deleteSurroundingText(1, 0)
    }

    private fun shouldCapitalize(before: String): Boolean {
        if (before.isEmpty()) return true
        return before.endsWith(". ") ||
            before.endsWith("? ") ||
            before.endsWith("! ") ||
            before.endsWith("\n")
    }
}
