package com.radialtype.text

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.radialtype.settings.SettingsManager

/**
 * Module 11 (+13) — Handles text commitment, deletion, editor actions
 * and synthetic special-key events.
 */
class InputDispatcher(
    private val connectionProvider: () -> InputConnection?
) {

    /** When true, the settings toggle decides whether spaces are added. */
    var autoSpaceEnabled: Boolean = true

    /** Cursor position captured before any preview selection, or null. */
    private var previewBaseCursor: Int? = null

    /** One-shot shift: the next committed letter gets an uppercase first letter. */
    private var shiftArmed: Boolean = false

    /** EditorInfo of the currently focused field; synced by the IME service. */
    var currentEditorInfo: EditorInfo? = null

    /** Arms the one-shot shift (SHIFT function key). */
    fun shiftNext() {
        shiftArmed = true
    }

    /**
     * Commits the given label. The first letter is uppercased when the
     * one-shot shift is armed, or when sentence auto-capitalization
     * applies (and its setting is enabled). The shift is consumed by
     * the first actual letter commit; SPACE/ENTER pass-throughs in the
     * view bypass this method and leave the shift armed.
     */
    fun commit(label: String) {
        if (label.isEmpty()) return
        val ic = connectionProvider() ?: return

        val capitalize = shiftArmed || (
            SettingsManager.autoCapitalization && shouldCapitalize(
                ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
            )
            )
        val capitalized = if (capitalize) {
            label.replaceFirstChar { it.uppercase() }
        } else {
            label
        }

        ic.commitText(capitalized, 1)
        shiftArmed = false
        if (SettingsManager.autoSpaceEnabled) commitSpace()
    }

    /**
     * Triggers the field's editor action (ENTER function key). For search /
     * go / send fields this submits the query via performEditorAction;
     * for plain text fields it falls back to synthetic KEYCODE_ENTER key
     * events, which multiline editors treat as a newline and single-line
     * editors translate into their default action.
     */
    fun commitEnter() {
        val ic = connectionProvider() ?: return
        val action = currentEditorInfo?.actionId ?: EditorInfo.IME_ACTION_UNSPECIFIED
        if (action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            action != EditorInfo.IME_ACTION_NONE
        ) {
            ic.performEditorAction(action)
            return
        }
        // Fallback: hardware-ENTER semantics.
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    /**
     * Live preview for the DELETE gesture: selects up to [left] characters
     * before the original cursor and [right] after it.
     */
    fun previewDeleteRange(left: Int, right: Int) {
        val ic = connectionProvider() ?: return

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

    /** Restores the cursor to the pre-gesture position and clears preview state. */
    fun cancelDeletePreview() {
        val base = previewBaseCursor
        if (base != null) {
            connectionProvider()?.setSelection(base, base)
        }
        previewBaseCursor = null
    }
    
    /**
     * Moves the cursor by [columns] characters (negative = left) via
     * synthetic DPAD events. Editors translate these natively, including
     * across line boundaries in multiline fields.
     */
    fun moveCursorHorizontally(columns: Int) {
        if (columns == 0) return
        val ic = connectionProvider() ?: return

        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text
        if (et == null || text == null || et.selectionStart < 0) {
            // Editor without extracted-text support: fall back to DPAD,
            // accepting the focus-traversal edge case on those targets.
            val code = if (columns < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(Math.abs(columns)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            return
        }

        val pos = et.selectionStart
        val target = (pos + columns).coerceIn(0, text.length)
        if (target != pos) {
            ic.setSelection(target, target)
        }
    }
    
    /** Moves the cursor by [lines] lines up (negative) or down. */
    fun moveCursorVertically(lines: Int) {
        val ic = connectionProvider() ?: return
        val code = if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(Math.abs(lines)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    /**
     * Performs the deletion selected by the DELETE gesture.
     */
    fun deleteRange(left: Int, right: Int) {
        if (left <= 0 && right <= 0) {
            // Zero-count release still ends the gesture: collapse any
            // preview selection back to the cached base and clear the
            // cache, or the NEXT delete gesture inherits this gesture's
            // cursor position and its selections slip left.
            val base = previewBaseCursor
            if (base != null) {
                connectionProvider()?.setSelection(base, base)
            }
            previewBaseCursor = null
            return
        }
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
    
    /** Sends a synthetic hardware key event (down + up). */
    fun sendKey(keyCode: Int) {
        val ic = connectionProvider() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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
