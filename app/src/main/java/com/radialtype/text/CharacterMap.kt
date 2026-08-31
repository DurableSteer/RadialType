package com.radialtype.text

import android.content.Context
import android.util.Log
import com.radialtype.engine.GeometryEngine.Ring
import org.json.JSONArray
import org.json.JSONObject

/**
 * Module 8 — Maps (ring, segment) to a primary character.
 *
 * The inner ring (8 segments) holds high-frequency consonants; the outer
 * ring holds vowels + remaining high-frequency letters. The layout is
 * loaded from a JSON resource file (assets/character_map.json) so layouts
 * can be swapped without recompiling. If no asset/context is available,
 * sensible English defaults are compiled in.
 *
 * Segment indices match [com.radialtype.engine.GeometryEngine.computeSegment]:
 * segment 0 is centered at 0° (east) proceeding clockwise.
 */
class CharacterMap(context: Context? = null) {

    companion object {
        // ── Default layout (English-oriented) ──────────────────────
        // Order corresponds to segment index 0..7 (0 = east, clockwise).
        val DEFAULT_INNER = listOf("T", "N", "S", "R", "H", "L", "D", "C")
        val DEFAULT_OUTER = listOf("A", "E", "I", "O", "U", "W", "F", "G")

        /** Embedded fallback, mirrors assets/character_map.json. */
        val DEFAULT_JSON: String = JSONObject().apply {
            put("inner", JSONArray(DEFAULT_INNER))
            put("outer", JSONArray(DEFAULT_OUTER))
        }.toString()

        const val RING_SIZE = 8
    }

    /** Primary characters for the inner ring, index = segment (0–7). */
    var innerRingChars: List<String> = DEFAULT_INNER
        private set

    /** Characters for the outer ring, index = segment (0–7). */
    var outerRingChars: List<String> = DEFAULT_OUTER
        private set

    init {
        loadFromJson(DEFAULT_JSON)
        // Asset overrides the compiled-in default when available.
        context?.let { ctx ->
            try {
                ctx.assets.open("character_map.json").bufferedReader().use { reader ->
                    loadFromJson(reader.readText())
                }
            } catch (e: Exception) {
                Log.w("CharacterMap", "character_map.json not found in assets — using defaults", e)
            }
        }
    }

    /**
     * Resolves the character for the given ring/segment.
     *
     * @return The mapped character, or "" when [ring] is [Ring.NONE] or
     *         [segment] is out of range (e.g. −1 before the first MOVE).
     */
    fun getPrimaryChar(ring: Ring, segment: Int): String {
        if (segment < 0 || segment >= RING_SIZE) return ""
        return when (ring) {
            Ring.INNER -> innerRingChars.getOrElse(segment) { "" }
            Ring.OUTER -> outerRingChars.getOrElse(segment) { "" }
            Ring.NONE  -> ""
        }
    }

    /**
     * Swaps in a new layout at runtime.
     *
     * Expected format:
     * ```json
     * { "inner": ["T","N","S","R","H","L","D","C"],
     *   "outer": ["A","E","I","O","U","W","F","G"] }
     * ```
     * Lists are padded with "" / truncated to exactly [RING_SIZE] entries,
     * so a malformed file can never crash the keyboard.
     */
    fun loadFromJson(json: String) {
        try {
            val root = JSONObject(json)
            innerRingChars = normalizeArray(root.optJSONArray("inner"))
            outerRingChars = normalizeArray(root.optJSONArray("outer"))
        } catch (e: Exception) {
            Log.e("CharacterMap", "Failed to parse character map JSON — keeping previous layout", e)
        }
    }

    private fun normalizeArray(array: org.json.JSONArray?): List<String> {
        if (array == null) return List(RING_SIZE) { "" }
        return (0 until RING_SIZE).map { i ->
            if (i < array.length()) array.optString(i, "") else ""
        }
    }
}
