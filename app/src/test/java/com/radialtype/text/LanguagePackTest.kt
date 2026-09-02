package com.radialtype.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Module 15 arrangement logic (pure Kotlin, no Android deps).
 */
class LanguagePackTest {

    private fun pack(
        letters: Map<String, Double>,
        bigrams: Map<String, List<Pair<String, Double>>> = emptyMap()
    ): LanguagePack {
        val tables = bigrams.mapValues { (_, pairs) ->
            pairs.map { LanguagePack.BigramEntry(it.first, it.second) }
        }
        return LanguagePack("test", letters, tables)
    }

    private fun blendedFromScores(scores: Map<String, Double>): BlendedFrequencies =
        BlendedFrequencies(scores, emptyMap())

    // ── Normalization ────────────────────────────────────────────

    @Test
    fun `normalized letters sum to 1`() {
        val p = pack(mapOf("T" to 1.0, "E" to 1.0))
        val norm = p.normalizedLetters()
        assertEquals(1.0, norm.values.sum(), 0.0001)
        assertEquals(0.5, norm["T"]!!, 0.0001)
        assertEquals(0.5, norm["E"]!!, 0.0001)
    }

    @Test
    fun `degenerate letter table normalizes to zeros`() {
        val p = pack(mapOf("T" to 0.0))
        assertEquals(0.0, p.normalizedLetters()["T"]!!, 0.0001)
    }

    // ── Blend ────────────────────────────────────────────────────

    @Test
    fun `single language ignores weight`() {
        val p = pack(mapOf("T" to 1.0, "E" to 1.0))
        val b = LayoutArranger.blend(p, null, 0.2)
        assertEquals(0.5, b.letterScores["T"]!!, 0.0001)
        assertEquals(0.5, b.letterScores["E"]!!, 0.0001)
    }

    @Test
    fun `two languages blend with weight`() {
        val a = pack(mapOf("T" to 1.0, "E" to 1.0))
        val b = pack(mapOf("T" to 1.0))
        val blended = LayoutArranger.blend(a, b, 0.7)
        assertEquals(0.65, blended.letterScores["T"]!!, 0.0001)
        assertEquals(0.35, blended.letterScores["E"]!!, 0.0001)
    }

    @Test
    fun `bigrams blend with per-key normalization`() {
        val a = pack(mapOf("T" to 1.0), mapOf("T" to listOf("TH" to 3.0, "TR" to 1.0)))
        val b = pack(mapOf("T" to 1.0), mapOf("T" to listOf("TO" to 4.0)))
        val blended = LayoutArranger.blend(a, b, 0.5)
        assertEquals(listOf("TO", "TH", "TR"), blended.bigramRanking["T"])
    }

    // ── Arrangement ──────────────────────────────────────────────

    @Test
    fun `rank 0 lands at segment 6 north, inner ring first`() {
        val scores = linkedMapOf(
            "T" to 8.0, "E" to 7.0, "S" to 7.0, "A" to 6.0,
            "O" to 5.0, "I" to 4.0, "N" to 3.0, "R" to 2.0
        )
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        assertEquals("T", layout.inner[6])
        assertEquals("E", layout.inner[0])
        assertEquals("S", layout.inner[7])
        assertEquals("A", layout.inner[5])
        assertEquals("O", layout.inner[1])
        assertEquals("I", layout.inner[4])
        assertEquals("N", layout.inner[2])
        assertEquals("R", layout.inner[3])
    }

    @Test
    fun `zero-score letters are excluded`() {
        val scores = mapOf("T" to 5.0, "E" to 0.0, "S" to 0.0)
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        val placed = layout.inner.filter { it.isNotEmpty() } + layout.outer.filter { it.isNotEmpty() }
        assertFalse(placed.contains("E"))
        assertFalse(placed.contains("S"))
        assertTrue(placed.contains("T"))
    }

    @Test
    fun `placement is deterministic across runs`() {
        val scores = linkedMapOf(
            "E" to 12.0, "N" to 9.0, "I" to 7.6, "S" to 7.3,
            "R" to 7.0, "A" to 6.5, "T" to 6.2, "D" to 5.1,
            "H" to 4.8, "U" to 4.4
        )
        val l1 = LayoutArranger.generate(blendedFromScores(scores), "en+de", 0.7)
        val l2 = LayoutArranger.generate(blendedFromScores(scores), "en+de", 0.7)
        assertEquals(l1.inner, l2.inner)
        assertEquals(l1.outer, l2.outer)
        assertEquals(l1.syllables, l2.syllables)
    }

    // ── Package A — residual pool ────────────────────────────────

    @Test
    fun `overflow letters go to the residual pool sorted by score`() {
        val scores = ('A'..'R').withIndex().associate { (i, c) -> "$c" to (20.0 - i).toDouble() }
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        assertEquals(listOf("Q", "R"), layout.residualLetters)
    }

    @Test
    fun `default layout reserves no segments for function tokens`() {
        val scores = ('A'..'P').withIndex().associate { (i, c) -> "$c" to (20.0 - i).toDouble() }
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        assertFalse(layout.inner.contains(CharacterMap.TOKEN_SHIFT))
        assertFalse(layout.outer.contains(CharacterMap.TOKEN_ENTER))
        assertEquals(16, layout.inner.count { it.isNotEmpty() } + layout.outer.count { it.isNotEmpty() })
        assertTrue(layout.residualLetters.isEmpty())
    }

    @Test
    fun `explicit reservations still shrink the letter budget when requested`() {
        val scores = ('A'..'N').withIndex().associate { (i, c) -> "$c" to (20.0 - i).toDouble() }
        val layout = LayoutArranger.generate(
            blendedFromScores(scores), "test", 1.0,
            reservedOuterSegments = mapOf(2 to CharacterMap.TOKEN_SHIFT)
        )
        assertEquals(CharacterMap.TOKEN_SHIFT, layout.outer[2])
        // 14 letters vs a 15-slot budget: everything still places, and
        // the reserved slot holds the token, not a letter.
        val lettersPlaced = layout.inner.count { it.isNotEmpty() } +
            layout.outer.count { it.isNotEmpty() } - 1
        assertEquals(14, lettersPlaced)
        assertTrue(layout.residualLetters.isEmpty())

        // 16 letters vs the same 15-slot budget: exactly one letter
        // must overflow to the residual pool because of the reservation.
        val tightScores = ('A'..'P').withIndex().associate { (i, c) -> "$c" to (20.0 - i).toDouble() }
        val tight = LayoutArranger.generate(
            blendedFromScores(tightScores), "test", 1.0,
            reservedOuterSegments = mapOf(2 to CharacterMap.TOKEN_SHIFT)
        )
        assertEquals(listOf("P"), tight.residualLetters)
    }

    @Test
    fun `all 16 primary slots filled when enough letters exist`() {
        val scores = ('A'..'P').withIndex().associate { (i, c) -> "$c" to (20.0 - i).toDouble() }
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        assertEquals(8, layout.inner.count { it.isNotEmpty() })
        assertEquals(8, layout.outer.count { it.isNotEmpty() })
        assertEquals("A", layout.inner[6])
        assertTrue(layout.residualLetters.isEmpty())
    }

    @Test
    fun `fewer letters than slots leaves no residual and empty slots stay empty`() {
        val scores = mapOf("E" to 5.0, "T" to 4.0, "A" to 3.0)
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        val placed = layout.inner.filter { it.isNotEmpty() } + layout.outer.filter { it.isNotEmpty() }
        assertEquals(3, placed.size)
        assertTrue(layout.residualLetters.isEmpty())
    }

    @Test
    fun `one-sided low-frequency letter is still placed`() {
        val a = pack(mapOf("Q" to 100.0, "Z" to 1.0))
        val b = pack(mapOf("X" to 1.0))
        val blended = LayoutArranger.blend(a, b, 0.8)
        val layout = LayoutArranger.generate(blended, "t", 0.8)
        val placed = layout.inner.filter { it.isNotEmpty() } + layout.outer.filter { it.isNotEmpty() }
        assertTrue(placed.contains("Q"))
    }

    @Test
    fun `placement is deterministic across runs with overflow`() {
        val scores = linkedMapOf(
            "E" to 12.0, "N" to 9.0, "I" to 7.6, "S" to 7.3,
            "R" to 7.0, "A" to 6.5, "T" to 6.2, "D" to 5.1,
            "H" to 4.8, "U" to 4.4, "L" to 4.0, "C" to 3.5,
            "G" to 2.0, "M" to 2.4, "X" to 0.1, "Q" to 0.1
        )
        val l1 = LayoutArranger.generate(blendedFromScores(scores), "en+de", 0.7)
        val l2 = LayoutArranger.generate(blendedFromScores(scores), "en+de", 0.7)
        assertEquals(l1.inner, l2.inner)
        assertEquals(l1.outer, l2.outer)
        assertEquals(l1.syllables, l2.syllables)
    }

    // ── Package B — residual letters on secondary menus ──────────

    @Test
    fun `umlaut is hosted by its base vowel on the secondary menu`() {
        // 17 letters: A..P fill the 16 primary slots, O, P, Ä overflow.
        val letters = ('A'..'P').associate { c -> "$c" to (20.0 - (c - 'A')).toDouble() }
        val a = pack(letters + mapOf("Ä" to 0.5))
        val layout = LayoutArranger.generate(LayoutArranger.blend(a, null, 1.0), "t", 1.0)
        assertTrue(layout.residualLetters.contains("Ä"))
        assertTrue(layout.syllables["A"]!!.contains("Ä"))
    }

    @Test
    fun `higher-scoring bigram takes the better slot than a residual letter`() {
        // 20 letters: A,B,Z and A..P (top 16) place; U, W, Ä go residual,
        // all hosted by A (Ä via base letter, U/W/Z via fallback).
        val scores = linkedMapOf(
            "A" to 0.30, "T" to 0.06, "S" to 0.055, "E" to 0.05,
            "I" to 0.045, "N" to 0.04, "O" to 0.04, "R" to 0.035,
            "H" to 0.03, "D" to 0.025, "L" to 0.02, "C" to 0.015,
            "M" to 0.012, "P" to 0.010, "G" to 0.009, "B" to 0.0085,
            "Z" to 0.008, "U" to 0.007, "W" to 0.006, "Ä" to 0.001
        )
        val bigrams = mapOf(
            "A" to listOf(
                ScoredEntry("AE", 0.9, isResidualLetter = false),
                ScoredEntry("AN", 0.1, isResidualLetter = false)
            )
        )
        val layout = LayoutArranger.generate(BlendedFrequencies(scores, bigrams), "t", 1.0)
        assertTrue(layout.residualLetters.containsAll(listOf("U", "W", "Ä")))
        val aMenu = layout.syllables["A"]!!
        // Bigram 0.9 beats every hosted letter (max 0.008) for the best
        // slot; Ä still appears — inclusion priority.
        assertEquals("AE", aMenu[0])
        assertTrue(aMenu.contains("Ä"))
        assertTrue(aMenu.indexOf("AE") < aMenu.indexOf("Ä"))
        assertTrue(aMenu.size <= 16)
    }

    @Test
    fun `secondary menus never exceed 16 entries`() {
        val entries = ('A'..'R').map { LanguagePack.BigramEntry("T$it", 1.0) }
        val p = LanguagePack("test", mapOf("T" to 1.0), mapOf("T" to entries))
        val layout = LayoutArranger.generate(LayoutArranger.blend(p, null, 1.0), "t", 1.0)
        assertEquals(16, layout.syllables["T"]!!.size)
    }

    @Test
    fun `fallback host when base vowel is not placed`() {
        val scores = ('A'..'P').associate { c -> "$c" to (20.0 - (c - 'A')).toDouble() } +
            mapOf("Ü" to 0.5)
        val layout = LayoutArranger.generate(blendedFromScores(scores), "test", 1.0)
        val allSecondary = layout.syllables.values.flatten()
        assertTrue(allSecondary.contains("Ü"))
    }

    @Test
    fun `baseLetter strips umlauts`() {
        assertEquals("A", LayoutArranger.baseLetter("Ä"))
        assertEquals("O", LayoutArranger.baseLetter("Ö"))
        assertEquals("U", LayoutArranger.baseLetter("Ü"))
        assertEquals("", LayoutArranger.baseLetter("ß"))
        assertEquals("", LayoutArranger.baseLetter("A"))
    }

    // ── Package C — explicit host overrides ──────────────────────

    @Test
    fun `eszett is hosted by S via explicit override`() {
        val a = pack(
            mapOf("A" to 30.0, "T" to 20.0, "S" to 18.0, "E" to 20.0,
                  "I" to 16.0, "O" to 14.0, "R" to 13.0, "H" to 12.0,
                  "D" to 11.0, "L" to 10.0, "C" to 9.0, "U" to 8.0,
                  "M" to 7.0, "N" to 6.0, "W" to 5.0, "P" to 3.0,
                  "ß" to 0.5)
        )
        val layout = LayoutArranger.generate(LayoutArranger.blend(a, null, 1.0), "t", 1.0)
        // Under Package D all 16 letters place — ß alone is residual.
        assertEquals(listOf("ß"), layout.residualLetters)
        // Override host S is placed → ß lands on S's menu.
        assertEquals(
            "S",
            layout.syllables.entries.firstOrNull { it.value.contains("ß") }?.key
        )
    }
    
    @Test
    fun `generated layout carries special tokens for the symbols menu`() {
        val scores = ('A'..'H').associate { c -> "$c" to 1.0 }
        val layout = LayoutArranger.generate(blendedFromScores(scores), "t", 1.0)
        assertEquals(
            listOf(CharacterMap.TOKEN_SHIFT, CharacterMap.TOKEN_ENTER, CharacterMap.TOKEN_SPACE),
            layout.symbolTokens
        )
    }

    @Test
    fun `regression - Y is reachable in an en-de blend at 60 percent`() {
        val en = pack(
            ('A'..'Z').associate { c -> "$c" to (30.0 - (c - 'A')).toDouble() } +
                mapOf("Ä" to 0.1, "Ö" to 0.1, "Ü" to 0.1, "ß" to 0.1)
        )
        val de = pack(
            mapOf("E" to 30.0, "N" to 25.0, "I" to 25.0, "S" to 25.0,
                  "R" to 25.0, "A" to 25.0, "T" to 25.0, "D" to 20.0,
                  "H" to 18.0, "U" to 15.0, "L" to 15.0, "C" to 12.0,
                  "G" to 12.0, "M" to 10.0, "O" to 10.0, "B" to 8.0,
                  "W" to 8.0, "F" to 7.0, "K" to 6.0, "Z" to 5.0,
                  "P" to 3.0, "V" to 3.0, "Ä" to 3.0, "Ö" to 1.0,
                  "Ü" to 1.0, "ß" to 1.0, "J" to 1.0, "Y" to 0.2, "Q" to 0.1)
        )
        val blended = LayoutArranger.blend(en, de, 0.6)
        val layout = LayoutArranger.generate(blended, "en+de", 0.6)
        // With ~14 residual letters sharing fallback hosts, the old truncation
        // dropped late-assigned letters like Y. Redistribution must prevent that.
        val reachable = layout.inner + layout.outer + layout.syllables.values.flatten()
        assertTrue("Y must be reachable", reachable.contains("Y"))
    }

}
