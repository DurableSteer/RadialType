package com.radialtype.text

import android.content.Context
import android.util.Log
import com.radialtype.engine.GeometryEngine.Ring

/**
 * Module 9 — Loads and serves two-letter syllable data for the
 * SECONDARY menu, ranked by English letter-pair (bigram) frequency.
 *
 * Data source: `assets/syllables.json`, keyed by primary character:
 * ```json
 * { "T": ["TH", "TR", "TE", "TO", "TI", "TA", "TU", "TW"], ... }
 * ```
 *
 * Spatial arrangement — TWO secondary rings:
 * The frequency-ranked list is split: the INNER secondary ring holds
 * ranks 0–3, the OUTER ring holds ranks 4–7. Within each ring, the
 * ergonomic rank→segment mapping puts the most frequent entries at the
 * most accessible segments (top of the ring first, fanning out).
 * Segments that hold no entry for a character resolve to "".
 */
class SyllableProvider(context: Context? = null) {

    companion object {
        /**
         * Maps frequency rank → segment index for a full 8-slot ring
         * (rank 0 = most frequent → segment 6, due "north").
         */
        val ERGONOMIC_ORDER = intArrayOf(6, 0, 7, 5, 1, 4, 2, 3)

        /**
         * Segments holding the INNER secondary ring (syllable ranks 0–3),
         * in ergonomic order.
         */
        val SECONDARY_INNER_SEGMENTS = intArrayOf(6, 0, 7, 5)

        /**
         * Segments holding the OUTER secondary ring (ranks 4–7),
         * continuing the ergonomic fan-out.
         */
        val SECONDARY_OUTER_SEGMENTS = intArrayOf(1, 4, 2, 3)

        const val SYLLABLES_PER_CHAR = 8

        /** Embedded fallback, mirrors assets/syllables.json. */
        val DEFAULT_JSON = """{
            "T": ["TH", "TR", "TE", "TO", "TI", "TA", "TU", "TW"],
            "N": ["ND", "NG", "NE", "NO", "NA", "NI", "NT", "NS"],
            "S": ["ST", "SE", "SO", "SA", "SI", "SU", "SH", "SP"],
            "R": ["RE", "RA", "RO", "RI", "RU", "RD", "RS", "RL"],
            "H": ["HE", "HA", "HI", "HO", "HU", "HM", "HW", "HN"],
            "L": ["LE", "LA", "LI", "LO", "LU", "LL", "LD", "LF"],
            "D": ["DE", "DA", "DO", "DI", "DU", "DS", "DR", "DW"],
            "C": ["CO", "CA", "CE", "CI", "CU", "CR", "CL", "CW"],
            "A": ["AN", "AT", "AL", "AR", "AD", "AC", "AM", "AB"],
            "E": ["ER", "EN", "ES", "EL", "ED", "EM", "EV", "EC"],
            "I": ["IN", "IT", "IS", "IC", "IR", "IL", "IO", "ID"],
            "O": ["ON", "OF", "OR", "OT", "OM", "OB", "OC", "OD"],
            "U": ["UN", "UP", "US", "UT", "UM", "UR", "UB", "UD"],
            "W": ["WA", "WE", "WI", "WH", "WO", "WR", "WS", "WY"],
            "F": ["FO", "FR", "FI", "FA", "FE", "FL", "FU", "FY"],
            "G": ["GR", "GA", "GE", "GO", "GI", "GU", "GL", "GS"]
        }"""
    }

    private val syllableMap = LinkedHashMap<String, List<String>>()

    init {
        loadFromJson(DEFAULT_JSON)
        // Asset overrides the compiled-in default when present.
        context?.let { ctx ->
            try {
                ctx.assets.open("syllables.json").bufferedReader().use { reader ->
                    loadFromJson(reader.readText())
                }
            } catch (e: Exception) {
                Log.w("SyllableProvider", "syllables.json not found in assets — using defaults", e)
            }
        }
    }

    /**
     * Replaces the in-memory syllable table. Keys are uppercase primary
     * characters; values are up to [SYLLABLES_PER_CHAR] two-letter
     * syllables, most frequent first.
     */
    fun loadFromJson(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val newMap = LinkedHashMap<String, List<String>>()
            obj.keys().forEach { key ->
                val arr = obj.optJSONArray(key) ?: return@forEach
                val syllables = (0 until minOf(arr.length(), SYLLABLES_PER_CHAR))
                    .mapNotNull { i ->
                        val s = arr.optString(i, "")
                        s.ifEmpty { null }
                    }
                if (syllables.isNotEmpty()) newMap[key.uppercase()] = syllables
            }
            if (newMap.isNotEmpty()) {
                synchronized(syllableMap) {
                    syllableMap.clear()
                    syllableMap.putAll(newMap)
                }
            }
        } catch (e: Exception) {
            Log.e("SyllableProvider", "Failed to parse syllables JSON — keeping previous data", e)
        }
    }

    /**
     * Returns the syllable list for [primaryChar] (up to
     * [SYLLABLES_PER_CHAR] items, most frequent first), or an empty
     * list if the character has no data.
     */
    fun getSyllables(primaryChar: String): List<String> {
        return syllableMap[primaryChar.uppercase()].orEmpty()
    }

    /**
     * Returns the syllable assigned to the given secondary ring/segment.
     *
     * @param primaryChar The character that was dwelled on.
     * @param ring        Secondary ring — INNER (ranks 0–3) or OUTER
     *                    (ranks 4–7). NONE → "".
     * @param segment     Secondary-ring segment index 0–7.
     * @return The syllable string, or "" if none is mapped for this
     *         slot (callers fall back to the primary char).
     */
    fun getSyllable(primaryChar: String, ring: Ring, segment: Int): String {
        if (primaryChar.isEmpty() || segment !in 0 until SYLLABLES_PER_CHAR) return ""
        val list = getSyllables(primaryChar)
        if (list.isEmpty()) return ""

        val segments: IntArray
        val ringOffset: Int
        when (ring) {
            Ring.INNER -> { segments = SECONDARY_INNER_SEGMENTS; ringOffset = 0 }
            Ring.OUTER -> { segments = SECONDARY_OUTER_SEGMENTS; ringOffset = SECONDARY_INNER_SEGMENTS.size }
            Ring.NONE  -> return ""
        }
        val posInRing = segments.indexOf(segment)
        if (posInRing < 0) return ""
        val rank = ringOffset + posInRing
        if (rank >= list.size) return ""
        return list[rank]
    }
}
