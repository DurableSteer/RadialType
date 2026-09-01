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
        val DEFAULT_INNER = listOf("T", "N", "S", "R", "H", "L", "D", "C")
        val DEFAULT_OUTER = listOf("A", "E", "I", "O", "U", "W", "F", "G")

        /** Digits: inner 1–8, outer segments 0/1 hold 9 and 0. */
        val DEFAULT_DIGITS_INNER = listOf("1", "2", "3", "4", "5", "6", "7", "8")
        val DEFAULT_DIGITS_OUTER = listOf("9", "0", "", "", "", "", "", "")

        /** Symbols, roughly frequency-ordered for prose. */
        val DEFAULT_SYMBOLS_INNER = listOf(".", ",", "'", "-", "?", "!", "\"", ":")
        val DEFAULT_SYMBOLS_OUTER = listOf(";", "(", ")", "@", "#", "&", "/", "+")

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

        const val TOKEN_DEL = "DEL"
        const val TOKEN_SHIFT = "SHIFT"
        const val TOKEN_SPACE = "SPACE"

        const val RING_SIZE = 8
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
        label == TOKEN_DEL || label == TOKEN_SHIFT || label == TOKEN_SPACE

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
