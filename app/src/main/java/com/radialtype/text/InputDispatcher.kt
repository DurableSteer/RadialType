package com.radialtype.text

import android.view.inputmethod.InputConnection
import com.radialtype.settings.SettingsManager

/**
 * Module 11 — Handles text commitment to the active input field.
 *
 * The InputConnection is resolved lazily via [connectionProvider] because
 * it is only valid while an input session is active.
 *
 * Module 12: auto-space and auto-capitalization are read from
 * [SettingsManager] at commit time, so toggles take effect immediately.
 */
class InputDispatcher(
    private val connectionProvider: () -> InputConnection?
) {

    /** When true, the settings toggle decides whether spaces are added. */
    var autoSpaceEnabled: Boolean = true

    /**
     * Commits the given label with basic sentence capitalization
     * (when the auto-capitalization setting is enabled). Capitalizes
     * the first letter when the text before the cursor is empty, ends
     * with ". ", "? ", "! ", or a newline.
     */
    fun commit(label: String) {
        if (label.isEmpty()) return
        val ic = connectionProvider() ?: return

        val capitalized = if (SettingsManager.autoCapitalization && shouldCapitalize(
                ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
            )
        ) {
            label.replaceFirstChar { it.uppercase() }
        } else {
            label
        }

        ic.commitText(capitalized, 1)
        if (SettingsManager.autoSpaceEnabled) commitSpace()
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
