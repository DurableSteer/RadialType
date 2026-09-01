package com.radialtype.text

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.radialtype.settings.SettingsManager

/**
 * Module 11 (+13) — Handles text commitment and deletion.
 *
 * Delete gesture: [previewDeleteRange] selects the doomed range live
 * during the swipe. The cursor base position is cached on the first
 * preview call so successive previews don't drift as the selection
 * moves the cursor. [deleteRange] removes the selection on release.
 */
class InputDispatcher(
    private val connectionProvider: () -> InputConnection?
) {

    /** When true, the settings toggle decides whether spaces are added. */
    var autoSpaceEnabled: Boolean = true

    /** Cursor position captured before any preview selection, or null. */
    private var previewBaseCursor: Int? = null

    /**
     * Commits the given label with basic sentence capitalization
     * (when the auto-capitalization setting is enabled).
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

    /**
     * Live preview for the DELETE gesture: selects up to [left] characters
     * before the original cursor and [right] after it. The base cursor is
     * captured on the first call and reused for the whole gesture, so the
     * selection growing does not shift the reference point.
     */
    fun previewDeleteRange(left: Int, right: Int) {
        val ic = connectionProvider() ?: return

        // Zero selection: collapse back to the original cursor position.
        if (left <= 0 && right <= 0) {
            val base = previewBaseCursor
            if (base != null) ic.setSelection(base, base)
            return
        }

        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        if (et.text == null) return

        val base = previewBaseCursor ?: run {
            val pos = et.selectionEnd
            previewBaseCursor = pos
            pos
        }

        val start = (base - left).coerceAtLeast(0)
        val end = (base + right).coerceAtMost(et.text.length)
        if (end >= start) ic.setSelection(start, end)
    }

    /** Clears the preview selection state without deleting. */
    fun cancelDeletePreview() {
        previewBaseCursor = null
    }

    /**
     * Performs the deletion selected by the DELETE gesture: commits an
     * empty string over the previewed selection (most robust across
     * editors), falling back to deleteSurroundingText when no selection
     * data is available.
     */
    fun deleteRange(left: Int, right: Int) {
        if (left <= 0 && right <= 0) return
        val ic = connectionProvider() ?: return
        val base = previewBaseCursor

        if (base != null) {
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            if (et?.text != null) {
                val start = (base - left).coerceAtLeast(0)
                val end = (base + right).coerceAtMost(et.text!!.length)
                if (end > start) {
                    ic.setSelection(start, end)
                    ic.commitText("", 1)   // replaces selection with nothing
                }
                previewBaseCursor = null
                return
            }
        }

        // Fallback for editors without extracted-text support.
        ic.deleteSurroundingText(left, right)
        previewBaseCursor = null
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
