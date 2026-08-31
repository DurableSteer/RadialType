package com.radialtype.text

import android.content.Context
import android.util.Log
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.settings.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Module 8 (+13) — Maps (ring, segment) to a primary character or
 * function token.
 *
 * Layout resolution order:
 * 1. [SettingsManager.customLayoutJson] (user-edited, persists)
 * 2. assets/character_map.json
 * 3. Compiled-in DEFAULT_JSON
 *
 * Function tokens (placed directly in the inner/outer arrays):
 * - "DEL"   — delete key: press-hold, swipe left/right to select, release deletes
 * - "SHIFT" — toggles auto-capitalization for the next commit
 * - "SPACE" — commits a space
 */
class CharacterMap(context: Context? = null) {

    companion object {
        val DEFAULT_INNER = listOf("T", "N", "S", "R", "H", "L", "D", "C")
        val DEFAULT_OUTER = listOf("A", "E", "I", "O", "U", "W", "F", "G")

        val DEFAULT_JSON: String = JSONObject().apply {
            put("inner", JSONArray(DEFAULT_INNER))
            put("outer", JSONArray(DEFAULT_OUTER))
        }.toString()

        /** Example layout with DEL and SHIFT wired in. */
        val SAMPLE_WITH_FUNCTIONS = """
            {"inner": ["T","N","S","R","H","L","SHIFT","C"],
             "outer": ["A","E","I","O","U","W","DEL","G"]}
        """.trimIndent()

        const val TOKEN_DEL = "DEL"
        const val TOKEN_SHIFT = "SHIFT"
        const val TOKEN_SPACE = "SPACE"

        const val RING_SIZE = 8
    }

    var innerRingChars: List<String> = DEFAULT_INNER
        private set

    var outerRingChars: List<String> = DEFAULT_OUTER
        private set

    init {
        loadFromJson(DEFAULT_JSON)
        val custom = SettingsManager.customLayoutJson
        if (!custom.isNullOrEmpty()) {
            loadFromJson(custom)
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

    fun getPrimaryChar(ring: Ring, segment: Int): String {
        if (segment < 0 || segment >= RING_SIZE) return ""
        return when (ring) {
            Ring.INNER -> innerRingChars.getOrElse(segment) { "" }
            Ring.OUTER -> outerRingChars.getOrElse(segment) { "" }
            Ring.NONE  -> ""
        }
    }

    /** True when the given label is a function key token. */
    fun isFunctionKey(label: String): Boolean =
        label == TOKEN_DEL || label == TOKEN_SHIFT || label == TOKEN_SPACE

    fun loadFromJson(json: String) {
        try {
            val root = JSONObject(json)
            innerRingChars = normalizeArray(root.optJSONArray("inner"))
            outerRingChars = normalizeArray(root.optJSONArray("outer"))
        } catch (e: Exception) {
            Log.e("CharacterMap", "Failed to parse layout JSON — keeping previous", e)
        }
    }

    private fun normalizeArray(array: org.json.JSONArray?): List<String> {
        if (array == null) return List(RING_SIZE) { "" }
        return (0 until RING_SIZE).map { i ->
            if (i < array.length()) array.optString(i, "") else ""
        }
    }
}
