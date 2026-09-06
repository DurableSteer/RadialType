package com.radialtype.bench

import android.content.Context
import com.radialtype.engine.GeometryEngine.Ring
import com.radialtype.settings.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for benchmark sessions (Packet 4). One JSON file per
 * session under filesDir/bench_sessions/, named by completion
 * timestamp. Stores aggregate + per-cell results, the seed (playlist
 * identity), and a snapshot of every classification-relevant setting,
 * so a saved run is self-describing for later comparison.
 */
object BenchStore {

    private const val DIR_NAME = "bench_sessions"

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun save(context: Context, json: JSONObject) {
        val id = json.optLong("timestampMs", System.currentTimeMillis())
        File(dir(context), "session_$id.json").writeText(json.toString())
    }

    /** Newest first. Corrupt files are skipped, not fatal. */
    fun list(context: Context): List<JSONObject> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.optLong("timestampMs") }
            ?: emptyList()

    fun delete(context: Context, timestampMs: Long) {
        File(dir(context), "session_$timestampMs.json").delete()
    }

    // ── Serialization ────────────────────────────────────────────

    fun sessionToJson(
        timestampMs: Long,
        seed: Long,
        trialsPerTarget: Int,
        includeSecondary: Boolean,
        stats: SessionStats,
        configLabel: String
    ): JSONObject {
        val cells = JSONArray()
        for ((k, v) in stats.perCell) {
            cells.put(JSONObject().apply {
                put("level", k.level.name)
                put("ring", k.ring.name)
                put("segment", k.segment)
                put("attempts", v.attempts)
                put("hits", v.hits)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("timestampMs", timestampMs)
            put("configLabel", configLabel)
            put("seed", seed)
            put("trialsPerTarget", trialsPerTarget)
            put("includeSecondary", includeSecondary)
            put("hits", stats.hits)
            put("attempts", stats.attempts)
            put("misses", stats.misses)
            put("aborted", stats.aborted)
            put("meanMovementMs", stats.meanMovementMs.toDouble())
            put("perCell", cells)
            put("settings", configSnapshot())
        }
    }

    /**
     * Compact human-readable label of everything that influences
     * classification, for the history list. Deliberately terse — it's
     * an identifier aid, not a settings dump.
     */
    fun autoLabel(): String {
        if (!SettingsManager.isInitialized) return "defaults"
        val onset = if (SettingsManager.onsetExitAngleEnabled)
            "on(${(SettingsManager.onsetPositionWeight * 100).toInt()}" +
                "/${SettingsManager.onsetVelocityWindowMs}" +
                "/${(SettingsManager.onsetMinSpeedDpPerMs * 100).toInt()})"
        else "off"
        return "lock=${if (SettingsManager.angleLockEnabled) "on" else "off"} " +
            "onset=$onset pad=${SettingsManager.innerPaddingDp.toInt()}dp"
    }

    private fun configSnapshot(): JSONObject = JSONObject().apply {
        if (!SettingsManager.isInitialized) return@apply
        val reach = JSONArray()
        SettingsManager.reachProfile.forEach { reach.put((it * 100).toInt()) }
        put("reach", reach)
        put("innerPadding", SettingsManager.innerPaddingDp.toDouble())
        put("angleLock", SettingsManager.angleLockEnabled)
        put("onsetEnabled", SettingsManager.onsetExitAngleEnabled)
        put("onsetPositionWeight", SettingsManager.onsetPositionWeight.toDouble())
        put("onsetWindowMs", SettingsManager.onsetVelocityWindowMs)
        put("onsetMinSpeed", SettingsManager.onsetMinSpeedDpPerMs.toDouble())
    }

    // ── Deserialization helpers ──────────────────────────────────

    fun aggregateHitRate(json: JSONObject): Double {
        val n = json.optInt("attempts")
        val h = json.optInt("hits")
        return if (n == 0) 0.0 else h.toDouble() / n
    }

    fun perCell(json: JSONObject): Map<BenchTarget, PerCellStats> {
        val map = HashMap<BenchTarget, PerCellStats>()
        val arr = json.optJSONArray("perCell") ?: return map
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val t = BenchTarget(
                    TargetLevel.valueOf(o.getString("level")),
                    Ring.valueOf(o.getString("ring")),
                    o.getInt("segment")
                )
                map[t] = PerCellStats(o.getInt("attempts"), o.getInt("hits"))
            }
        }
        return map
    }

    fun seedOf(json: JSONObject): Long = json.optLong("seed")
    fun labelOf(json: JSONObject): String = json.optString("configLabel", "?")
    fun attemptsOf(json: JSONObject): Int = json.optInt("attempts")
    fun hitsOf(json: JSONObject): Int = json.optInt("hits")
}
