package com.radialtype.text

import android.content.Context
import android.util.Log
import com.radialtype.engine.GeometryEngine.Ring

/**
 * Module 9 (+15) — Loads and serves two-letter syllable data for the
 * SECONDARY menu, ranked by English letter-pair (bigram) frequency.
 *
 * Data sources, in override order:
 * 1. The "syllables" object embedded in a GENERATED layout JSON
 *    (SettingsManager.customLayoutJson, Module 15) — see [maybeReloadFromLayout].
 * 2. assets/syllables.json
 * 3. Compiled-in [DEFAULT_JSON]
 *
 * The compiled/asset tables form the BASE table (captured once at init);
 * generated per-key lists REPLACE the base entries for their keys, but never
 * mutate the base, so clearing/regenerating the layout restores defaults.
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
     * Total secondary-menu capacity: the full 8-segment ERGONOMIC_ORDER
     * is applied per ring, so INNER holds ranks 0–7 (all 8 segments)
     * and OUTER holds ranks 8–15 (same order, all 8 segments) —
     * 16 populated cells total, matching the primary menu's geometry.
     */
    const val SYLLABLES_PER_CHAR = 16

    /** Segments per ring. */
    const val SEGMENTS_PER_RING = 8

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

    /**
     * Immutable base table (defaults + asset) captured after init. Generated
     * layouts overlay on top of this; [rebuild] recomposes syllableMap from
     * it on every layout change.
     */
    private val baseMap = LinkedHashMap<String, List<String>>()

    /**
     * Raw custom-layout JSON the overlay was last applied from. Null = no
     * generated syllables applied. String-compare makes repeat calls free.
     */
    private var loadedLayoutJson: String? = null

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
        synchronized(syllableMap) { baseMap.putAll(syllableMap) }
        // Apply a generated layout from settings, if one exists.
        maybeReloadFromLayout(com.radialtype.settings.SettingsManager.customLayoutJson)
    }

    /**
     * Replaces the in-memory syllable table. Keys are uppercase primary
     * characters; values are up to [SYLLABLES_PER_CHAR] two-letter
     * syllables, most frequent first.
     *
     * NOTE: this mutates the LIVE table, not [baseMap]. Runtime callers
     * after init should use [maybeReloadFromLayout] instead.
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
     * Re-applies the base table, then overlays the "syllables" object from
     * [layoutJson] (a generated layout JSON, or "" for none). No-op when the
     * input matches the previously applied JSON — safe to call on every
     * ACTION_DOWN, mirroring CharacterMap.maybeReload().
     */
    fun maybeReloadFromLayout(layoutJson: String) {
        if (layoutJson == loadedLayoutJson) return
        loadedLayoutJson = layoutJson
        synchronized(syllableMap) {
            syllableMap.clear()
            syllableMap.putAll(baseMap)
            if (layoutJson.isBlank()) return
            try {
                val syllables = org.json.JSONObject(layoutJson)
                    .optJSONObject("syllables") ?: return
                syllables.keys().forEach { key ->
                    val arr = syllables.optJSONArray(key) ?: return@forEach
                    val list = (0 until minOf(arr.length(), SYLLABLES_PER_CHAR))
                        .mapNotNull { i -> arr.optString(i, "").ifEmpty { null } }
                    if (list.isNotEmpty()) syllableMap[key.uppercase()] = list
                }
            } catch (e: Exception) {
                Log.e("SyllableProvider", "Failed to apply generated syllables — keeping base data", e)
            }
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
   * Both rings use the full ERGONOMIC_ORDER (rank 0 = segment 6, north),
   * with the OUTER ring continuing at rank 8 — so rank r resolves as:
   * - r in 0..7  → inner ring, segment ERGONOMIC_ORDER[r]
   * - r in 8..15 → outer ring, segment ERGONOMIC_ORDER[r - 8]
   *
   * @param primaryChar The character that was dwelled on.
   * @param ring        Secondary ring — INNER (ranks 0–7) or OUTER
   *                    (ranks 8–15). NONE → "".
   * @param segment     Secondary-ring segment index 0–7.
   * @return The syllable string, or "" if none is mapped for this
   *         slot (callers fall back to the primary char).
   */
  fun getSyllable(primaryChar: String, ring: Ring, segment: Int): String {
      if (primaryChar.isEmpty() || segment !in 0 until SEGMENTS_PER_RING) return ""
      val list = getSyllables(primaryChar)
      if (list.isEmpty()) return ""

      val posInRing = when (ring) {
          Ring.INNER -> ERGONOMIC_ORDER.indexOf(segment)
          Ring.OUTER -> ERGONOMIC_ORDER.indexOf(segment)
          Ring.NONE  -> return ""
      }
      if (posInRing < 0) return ""

      val ringOffset = when (ring) {
          Ring.INNER -> 0
          Ring.OUTER -> SEGMENTS_PER_RING
          Ring.NONE  -> return ""
      }
      val rank = ringOffset + posInRing
      if (rank >= list.size) return ""
      return list[rank]
  }
}
