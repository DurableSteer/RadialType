package com.radialtype.text

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Module 15 (Packages A+B) — Language packs and frequency-driven layout
 * arrangement. Arrangement runs ONLY on explicit user regeneration; the
 * output is a frozen layout JSON consumed via customLayoutJson.
 *
 * Letters and bigrams are normalized to comparable scales per language,
 * weighted by the mix ratio, and ties break alphabetically so identical
 * inputs always freeze identical layouts.
 *
 * Reachability contract (Packages A+B): every letter of both languages with
 * a positive blended score is reachable. The top letters occupy the primary
 * menu; the rest (residual) are placed on secondary menus with inclusion
 * priority over bigrams, but their slot position follows blended score.
 */
class LanguagePack(
    val lang: String,
    /** Letter label (uppercase, must match layout label strings) → raw frequency, any scale. */
    val letterFrequency: Map<String, Double>,
    /** Key letter (uppercase) → bigram entries for that key. */
    val bigrams: Map<String, List<BigramEntry>>
) {

    data class BigramEntry(val text: String, val freq: Double)

    companion object {
        /**
         * Case-normalizes a letter/bigram token. Per-character so that "ß"
         * (which has no single-char uppercase form and would explode to
         * "SS" under String.uppercase(), colliding with S-key entries)
         * is preserved as-is.
         */
        private fun normalizeToken(raw: String): String =
            raw.map { ch ->
                if (ch == 'ß') 'ß' else ch.uppercaseChar()
            }.joinToString("")

        fun fromJson(json: String): LanguagePack {
            val root = JSONObject(json)
            val letters = LinkedHashMap<String, Double>()
            root.optJSONObject("letterFrequency")?.let { lf ->
                lf.keys().forEach { key ->
                    letters[normalizeToken(key)] = lf.optDouble(key, 0.0)
                }
            }
            val bigrams = LinkedHashMap<String, List<BigramEntry>>()
            root.optJSONObject("bigrams")?.let { bg ->
                bg.keys().forEach { key ->
                    val arr = bg.optJSONArray(key) ?: return@forEach
                    val entries = (0 until arr.length()).mapNotNull { i ->
                        val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                        val text = normalizeToken(pair.optString(0, ""))
                        if (text.isEmpty()) null
                        else BigramEntry(text, pair.optDouble(1, 0.0))
                    }
                    if (entries.isNotEmpty()) {
                        bigrams[normalizeToken(key)] = entries
                    }
                }
            }
            return LanguagePack(root.optString("lang", ""), letters, bigrams)
        }
    }

    /** Normalizes the letter table so values sum to 1 (all 0 if degenerate). */
    fun normalizedLetters(): Map<String, Double> {
        val total = letterFrequency.values.sum()
        if (total <= 0.0) return letterFrequency.mapValues { 0.0 }
        return letterFrequency.mapValues { it.value / total }
    }
}

/** One secondary-menu candidate: a bigram or a residual letter, with score. */
data class ScoredEntry(val text: String, val score: Double, val isResidualLetter: Boolean)

/** Result of blending one or two language packs. Feeds [LayoutArranger.generate]. */
class BlendedFrequencies(
    /** Letter → blended normalized score (> 0 = eligible for placement). */
    val letterScores: Map<String, Double>,
    /** Key letter → scored bigram candidates, sorted most-frequent-first. */
    val bigramScores: Map<String, List<ScoredEntry>>
) {
    /** Bigram texts only, ranked — kept for logging and host heuristics. */
    val bigramRanking: Map<String, List<String>>
        get() = bigramScores.mapValues { (_, list) -> list.map { it.text } }
}

object LayoutArranger {

    /** Rank 0 sits at segment 6 (north), fanning out — legacy convention. */
    val ERGONOMIC_ORDER = intArrayOf(6, 0, 7, 5, 1, 4, 2, 3)

    /**
     * Package D: no tokens on the normal menu. SHIFT/DEL live in the
     * special-characters menu emitted by [GeneratedLayout]. The reservation
     * mechanism stays available for custom layouts via the parameter.
     */
    val DEFAULT_RESERVED_OUTER: Map<Int, String> = emptyMap()

    /**
     * Explicit residual-host overrides for letters whose base cannot be
     * derived by Unicode decomposition. Consulted before the base-letter
     * heuristic; the host must still be on the placed set for it to apply.
     */
    val HOST_OVERRIDES: Map<String, String> = mapOf(
        "ß" to "S"
    )

    /** Secondary menu cap: 16 slots per key (8 inner + 8 outer), matching
     *  the primary menu's 16-cell geometry. Residual letters still take
     *  inclusion priority; the borrow-fill quota now extends to 16. */
    const val SYLLABLES_PER_KEY = 16

    /**
     * Diacritic-stripped base letter: "Ä"→"A", "Ö"→"O", "Ü"→"U", "É"→"E".
     * Returns "" when the letter does not decompose to a single base
     * character (e.g. "ß") — those rely on overrides or fallback hosts.
     */
    fun baseLetter(letter: String): String {
        val decomposed = Normalizer.normalize(letter, Normalizer.Form.NFD)
        val stripped = decomposed.filter {
            Character.getType(it) != Character.NON_SPACING_MARK.toInt()
        }
        val result = stripped.uppercase()
        return if (result.length == 1 && result != letter) result else ""
    }

    /**
     * Weighted blend of 1–2 language packs. With [packB] null, [weightA] is
     * forced to 1.0 (single-language mode).
     */
    fun blend(packA: LanguagePack, packB: LanguagePack?, weightA: Double): BlendedFrequencies {
        val wA = if (packB == null) 1.0 else weightA.coerceIn(0.0, 1.0)
        val wB = 1.0 - wA

        val normA = packA.normalizedLetters()
        val normB = packB?.normalizedLetters() ?: emptyMap()
        val letterScores = (normA.keys + normB.keys).associateWith { key ->
            normA.getOrDefault(key, 0.0) * wA + normB.getOrDefault(key, 0.0) * wB
        }

        val bigramScores = LinkedHashMap<String, List<ScoredEntry>>()
        for (key in letterScores.keys) {
            val ranked = blendKeyBigrams(packA, packB, key, wA, wB)
            if (ranked.isNotEmpty()) bigramScores[key] = ranked
        }
        return BlendedFrequencies(letterScores, bigramScores)
    }

    /**
     * Weighted blend of one key's bigram tables. Frequencies are normalized
     * within the key per language; bigrams unique to one language draw 0
     * from the other. Ties break alphabetically.
     */
    private fun blendKeyBigrams(
        packA: LanguagePack,
        packB: LanguagePack?,
        key: String,
        wA: Double,
        wB: Double
    ): List<ScoredEntry> {
        val tableA = packA.bigrams[key] ?: emptyList()
        val tableB = packB?.bigrams?.get(key) ?: emptyList()
        if (tableA.isEmpty() && tableB.isEmpty()) return emptyList()

        val scores = LinkedHashMap<String, Double>()

        fun accumulate(entries: List<LanguagePack.BigramEntry>, weight: Double) {
            if (weight <= 0.0) return
            val sum = entries.sumOf { it.freq }
            if (sum <= 0.0) return
            for (e in entries) {
                scores[e.text] = (scores[e.text] ?: 0.0) + (e.freq / sum) * weight
            }
        }

        accumulate(tableA, wA)
        if (packB != null) accumulate(tableB, wB)

        return scores.entries
            .map { ScoredEntry(it.key, it.value, isResidualLetter = false) }
            .sortedWith(compareByDescending<ScoredEntry> { it.score }.thenBy { it.text })
    }

        /**
     * Freezes the arrangement into the layout schema.
     *
     * Primary: eligible letters ranked by blended score (ties alphabetical).
     * All 16 slots are letters by default ([DEFAULT_RESERVED_OUTER] is empty);
     * custom reservations via [reservedOuterSegments] shrink the budget.
     * Rank 0 lands at segment 6 (north) on the INNER ring, fanning out via
     * [ERGONOMIC_ORDER], then the OUTER ring's free segments. Overflow forms
     * the residual pool; fewer letters than slots leaves extras empty.
     *
     * Secondary: each placed key's menu = hosted residual letters (inclusion
     * priority, never displaced) + the key's own bigrams + borrowed bigrams
     * from the global pool, capped at [SYLLABLES_PER_KEY]. Slot order is
     * purely blended score among included entries. Borrowing only kicks in
     * when a key's own candidates don't fill its menu, and — critically —
     * borrowed bigrams MUST start with the host key's letter: a menu for
     * "N" only ever shows entries beginning with "N". If the global pool
     * has no such candidates left, the menu stays partially empty rather
     * than displaying foreign-prefix bigrams.
     */
    fun generate(
        blended: BlendedFrequencies,
        languagesTag: String,
        mixWeight: Double,
        reservedOuterSegments: Map<Int, String> = DEFAULT_RESERVED_OUTER
    ): GeneratedLayout {
        val eligible = blended.letterScores.entries
            .filter { it.value > 0.0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key }

        val inner = arrayOfNulls<String>(8)
        val outer = arrayOfNulls<String>(8)
        reservedOuterSegments.forEach { (seg, token) -> outer[seg] = token }

        val outerFreeSegments = ERGONOMIC_ORDER.filterNot { reservedOuterSegments.containsKey(it) }

        val residual = mutableListOf<String>()
        eligible.forEachIndexed { rank, letter ->
            when {
                rank < 8 -> inner[ERGONOMIC_ORDER[rank]] = letter
                rank - 8 < outerFreeSegments.size -> outer[outerFreeSegments[rank - 8]] = letter
                else -> residual.add(letter)
            }
        }

        val placed = eligible.take(8 + outerFreeSegments.size)

        val residualByHost = assignHosts(residual, placed, blended)

        // Global bigram pool for borrow-filling underfull menus.
        val globalBigrams = blended.bigramScores.values
            .flatten()
            .sortedWith(compareByDescending<ScoredEntry> { it.score }.thenBy { it.text })

        val syllables = LinkedHashMap<String, List<String>>()
        for (letter in placed) {
            val hosted = (residualByHost[letter] ?: emptyList())
                .sortedWith(compareByDescending<ScoredEntry> { it.score }.thenBy { it.text })
                .take(SYLLABLES_PER_KEY)
            val own = blended.bigramScores[letter]
                .orEmpty()
                .take((SYLLABLES_PER_KEY - hosted.size).coerceAtLeast(0))

            val used = (hosted + own).map { it.text }.toSet()
            val borrowQuota = SYLLABLES_PER_KEY - hosted.size - own.size
            val borrowed = if (borrowQuota > 0) {
                globalBigrams
                    .filter { it.text !in used && it.text.startsWith(letter) }
                    .take(borrowQuota)
            } else emptyList()

            val ranked = (hosted + own + borrowed)
                .sortedWith(compareByDescending<ScoredEntry> { it.score }.thenBy { it.text })
                .map { it.text }
            if (ranked.isNotEmpty()) syllables[letter] = ranked
        }

        return GeneratedLayout(
            inner = inner.map { it ?: "" },
            outer = outer.map { it ?: "" },
            syllables = syllables,
            languagesTag = languagesTag,
            mixWeight = mixWeight,
            residualLetters = residual
        )
    }

    private fun assignHosts(
        residual: List<String>,
        placed: List<String>,
        blended: BlendedFrequencies
    ): Map<String, List<ScoredEntry>> {
        if (residual.isEmpty() || placed.isEmpty()) return emptyMap()

        // Bigram second char → candidate host keys (placed, iteration order).
        val hostsBySecond = HashMap<String, MutableList<String>>()
        for (host in placed) {
            for (entry in blended.bigramScores[host].orEmpty()) {
                if (entry.text.length == 2) {
                    hostsBySecond.getOrPut(entry.text.substring(1)) { mutableListOf() }.add(host)
                }
            }
        }

        val result = LinkedHashMap<String, MutableList<ScoredEntry>>()
        for (letter in residual) {
            val base = baseLetter(letter)
            val override = HOST_OVERRIDES[letter]
            val preferred: String? = when {
                override != null && override in placed -> override
                base.isNotEmpty() && base != letter && base in placed -> base
                else -> hostsBySecond[letter]
                    ?.maxByOrNull { blended.letterScores.getOrDefault(it, 0.0) }
            }

            val host: String? = when {
                preferred != null && (result[preferred]?.size ?: 0) < SYLLABLES_PER_KEY -> preferred
                else -> placed.minByOrNull { result[it]?.size ?: 0 }
            }
            if (host != null) {
                result.getOrPut(host) { mutableListOf() }
                    .add(ScoredEntry(letter, blended.letterScores[letter] ?: 0.0, isResidualLetter = true))
            }
        }
        return result
    }
}
/**
 * Frozen output. Shape-compatible with the existing layout JSON schema; the
 * "syllables" lists may now mix single letters and bigrams. [residualLetters]
 * records which letters did not make the primary menu; after Package B they
 * are reachable through their host keys' secondary lists.
 */
class GeneratedLayout(
    val inner: List<String>,
    val outer: List<String>,
    /** Key letter (uppercase) → ranked entries, ≤ 8 each; may mix letters and bigrams. */
    val syllables: Map<String, List<String>>,
    val languagesTag: String,
    val mixWeight: Double,
    /** Letters that lost primary placement, most frequent first. */
    val residualLetters: List<String> = emptyList(),
    /** Entries for the special-characters menu (SHIFT, DEL). */
    val symbolTokens: List<String> = listOf(CharacterMap.TOKEN_SHIFT, CharacterMap.TOKEN_DEL)
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("inner", JSONArray(inner))
        root.put("outer", JSONArray(outer))
        root.put("syllables", JSONObject().apply {
            syllables.forEach { (key, list) -> put(key, JSONArray(list)) }
        })
        if (symbolTokens.isNotEmpty()) {
            val symbols = arrayOfNulls<String>(8)
            LayoutArranger.ERGONOMIC_ORDER.copyOf(symbolTokens.size)
                .forEachIndexed { i, seg -> symbols[seg] = symbolTokens[i] }
            root.put("symbols", JSONObject().apply {
                put("inner", JSONArray(symbols.map { it ?: "" }))
                put("outer", JSONArray(List(8) { "" }))
            })
        }
        root.put("meta", JSONObject().apply {
            put("generator", GENERATOR_ID)
            put("languages", languagesTag)
            put("mix", mixWeight)
            if (residualLetters.isNotEmpty()) {
                put("residualLetters", JSONArray(residualLetters))
            }
        })
        return root.toString()
    }

    companion object {
        const val GENERATOR_ID = "frequency_v2_packageD"
    }
}
