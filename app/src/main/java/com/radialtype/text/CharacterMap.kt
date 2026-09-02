package com.radialtype.text

import android.content.Context
import android.util.Log
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.engine.LayoutMode
import com.radialtype.settings.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Module 8 (+13) — Maps (mode, ring, segment) to a label.
 *
 * Layout resolution order for letters:
 * 1. SettingsManager.customLayoutJson (user-edited, persists)
 * 2. assets/character_map.json
 * 3. Compiled-in DEFAULT_JSON
 *
 * Mode layouts: "digits" and "symbols" objects in the layout JSON are
 * OPTIONAL {inner:[...], outer:[...]} tables used by the NUMBER and
 * SYMBOL gateway modes (double-tap, then flick up / down). Defaults:
 * digits inner = 1..8, outer = 9,0 on segments 0/1; symbols ranked by
 * rough English prose frequency.
 *
 * Function tokens (letters mode only): DEL, SHIFT, SPACE.
 */
class CharacterMap(context: Context? = null) {

  companion object {
    // ── Function tokens (must precede any list that references them) ──
    const val TOKEN_ENTER = "ENTER"
    const val TOKEN_SHIFT = "SHIFT"
    const val TOKEN_SPACE = "SPACE"
    const val TOKEN_TAB = "TAB"
    const val TOKEN_ESC = "ESC"
    const val TOKEN_LEFT = "LEFT"
    const val TOKEN_RIGHT = "RIGHT"
    const val TOKEN_UP = "UP"
    const val TOKEN_DOWN = "DOWN"

    const val RING_SIZE = 8

    val DEFAULT_INNER = listOf("t", "n", "s", "r", "h", "l", "d", "c")
    val DEFAULT_OUTER = listOf("a", "e", "i", "o", "u", "w", "f", "g")

    /**
     * Digits: inner 1–8; outer keeps 9/0 on segments 0/1 and fills the
     * remaining slots (ergonomic order: 6, 7, 5, 4, 3, 2) with math
     * symbols: * + - / ( ).
     */
    val DEFAULT_DIGITS_INNER = listOf("1", "2", "3", "4", "5", "6", "7", "8")
    val DEFAULT_DIGITS_OUTER = listOf("9", "0", ")", "(", "/", "-", "*", "+")

    /**
     * Symbol menu: ENTER at 0 (east), SPACE at 2 (south), SHIFT at 6
     * (north) are fixed. The five free inner slots (7, 5, 1, 4, 3, in
     * ergonomic order) and all eight outer slots carry the curated
     * special characters, in priority order: . , " ? @ : $ ! ; { } & =
     */
    val DEFAULT_SYMBOLS_INNER = listOf(
        TOKEN_ENTER, "\"", TOKEN_SPACE, "@", "?", ",", TOKEN_SHIFT, "."
    )
    val DEFAULT_SYMBOLS_OUTER = listOf(
        "$", "{", "&", "=", "}", ";", ":", "!"
    )

    val DEFAULT_JSON: String = buildLayoutJson(
        DEFAULT_INNER, DEFAULT_OUTER, null, null
    )

    private fun buildLayoutJson(
        inner: List<String>, outer: List<String>,
        digitsInner: List<String>?, symbolsInner: List<String>?
    ): String {
        val root = JSONObject()
        root.put("inner", JSONArray(inner))
        root.put("outer", JSONArray(outer))
        if (digitsInner != null) {
            root.put("digits", JSONObject().apply {
                put("inner", JSONArray(digitsInner))
                put("outer", JSONArray(DEFAULT_DIGITS_OUTER))
            })
        }
        if (symbolsInner != null) {
            root.put("symbols", JSONObject().apply {
                put("inner", JSONArray(symbolsInner))
                put("outer", JSONArray(DEFAULT_SYMBOLS_OUTER))
            })
        }
        return root.toString()
    }
    }

    var innerRingChars: List<String> = DEFAULT_INNER
        private set

    var outerRingChars: List<String> = DEFAULT_OUTER
        private set

    var digitInnerChars: List<String> = DEFAULT_DIGITS_INNER
        private set

    var digitOuterChars: List<String> = DEFAULT_DIGITS_OUTER
        private set

    var symbolInnerChars: List<String> = DEFAULT_SYMBOLS_INNER
        private set

    var symbolOuterChars: List<String> = DEFAULT_SYMBOLS_OUTER
        private set

    /** Raw JSON string the current custom layout was loaded from. */
    private var loadedRawJson: String = ""

    init {
        loadFromJson(DEFAULT_JSON)
        val custom = SettingsManager.customLayoutJson
        if (!custom.isNullOrEmpty()) {
            loadFromJson(custom)
            loadedRawJson = custom
        } else {
            context?.let { ctx ->
                try {
                    ctx.assets.open("character_map.json").bufferedReader().use { reader ->
                        loadFromJson(reader.readText())
                    }
                } catch (e: Exception) {
                    Log.w("CharacterMap", "character_map.json not found — using defaults", e)
                }
            }
        }
    }

    /**
     * Re-loads the layout if the stored custom JSON changed since the
     * last load. String comparison makes repeat calls nearly free.
     */
    fun maybeReload() {
        val raw = SettingsManager.customLayoutJson
        if (raw == loadedRawJson) return
        loadedRawJson = raw
        if (raw.isNotBlank()) {
            loadFromJson(raw)
        } else {
            loadFromJson(DEFAULT_JSON)
        }
    }

    fun getPrimaryChar(ring: Ring, segment: Int): String =
        getPrimaryChar(ring, segment, LayoutMode.LETTERS)

    /**
     * Label for the given gesture mode, ring and segment. Returns ""
     * for empty slots and for ring NONE.
     */
    fun getPrimaryChar(ring: Ring, segment: Int, mode: LayoutMode): String {
        if (segment < 0 || segment >= RING_SIZE) return ""
        return when (mode) {
            LayoutMode.LETTERS -> when (ring) {
                Ring.INNER -> innerRingChars.getOrElse(segment) { "" }
                Ring.OUTER -> outerRingChars.getOrElse(segment) { "" }
                Ring.NONE  -> ""
            }
            LayoutMode.NUMBERS -> when (ring) {
                Ring.INNER -> digitInnerChars.getOrElse(segment) { "" }
                Ring.OUTER -> digitOuterChars.getOrElse(segment) { "" }
                Ring.NONE  -> ""
            }
            LayoutMode.SYMBOLS -> when (ring) {
                Ring.INNER -> symbolInnerChars.getOrElse(segment) { "" }
                Ring.OUTER -> symbolOuterChars.getOrElse(segment) { "" }
                Ring.NONE  -> ""
            }
        }
    }

    /** Both ring arrays for the given mode — used by the renderer. */
    fun ringsFor(mode: LayoutMode): Pair<List<String>, List<String>> = when (mode) {
        LayoutMode.LETTERS -> innerRingChars to outerRingChars
        LayoutMode.NUMBERS -> digitInnerChars to digitOuterChars
        LayoutMode.SYMBOLS -> symbolInnerChars to symbolOuterChars
    }

    /** True when the given label is a function key token. */
    fun isFunctionKey(label: String): Boolean =
        label == TOKEN_ENTER || label == TOKEN_SHIFT || label == TOKEN_SPACE ||
            label == TOKEN_TAB || label == TOKEN_ESC ||
            label == TOKEN_LEFT || label == TOKEN_RIGHT ||
            label == TOKEN_UP || label == TOKEN_DOWN

    fun loadFromJson(json: String) {
        try {
            val root = JSONObject(json)
            innerRingChars = normalizeArray(root.optJSONArray("inner"), DEFAULT_INNER)
            outerRingChars = normalizeArray(root.optJSONArray("outer"), DEFAULT_OUTER)

            val digits = root.optJSONObject("digits")
            digitInnerChars = normalizeArray(digits?.optJSONArray("inner"), DEFAULT_DIGITS_INNER)
            digitOuterChars = normalizeArray(digits?.optJSONArray("outer"), DEFAULT_DIGITS_OUTER)

            val symbols = root.optJSONObject("symbols")
            symbolInnerChars = normalizeArray(symbols?.optJSONArray("inner"), DEFAULT_SYMBOLS_INNER)
            symbolOuterChars = normalizeArray(symbols?.optJSONArray("outer"), DEFAULT_SYMBOLS_OUTER)
        } catch (e: Exception) {
            Log.e("CharacterMap", "Failed to parse layout JSON — keeping previous", e)
        }
    }

    private fun normalizeArray(array: org.json.JSONArray?, fallback: List<String>): List<String> {
        if (array == null) return fallback
        return (0 until RING_SIZE).map { i ->
            if (i < array.length()) array.optString(i, "") else ""
        }
    }
}
