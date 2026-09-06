package com.radialtype.bench

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.radialtype.engine.GeometryEngine.Ring
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved-session browser (Packet 4). Long-press a row to delete;
 * check exactly two sessions to enable the comparison panel, which
 * shows aggregate hit rates with Wilson intervals and a per-cell
 * hit-rate delta grid (A = older session row context, B = newer).
 */
class BenchHistoryActivity : AppCompatActivity() {

    companion object {
        private val COL_TEXT = 0xFFEAF0FA.toInt()
        private val COL_MUTED = 0xFF9AA5CE.toInt()
        private val COL_GOOD = 0xFF9ECE6A.toInt()
        private val COL_BAD = 0xFFF7768E.toInt()
    }

    private val selected = LinkedHashSet<Long>()
    private var compareBtn: Button? = null
    private var deleteBtn: Button? = null
    private var comparePanel: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    private fun buildView(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF10121C.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "Benchmark history"
            textSize = 20f
            setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })

        val sessions = BenchStore.list(this)
        if (sessions.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "No saved sessions yet. Complete a benchmark run first."
                setTextColor(COL_MUTED)
                textSize = 15f
            })
            return scroll
        }

        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        for (s in sessions) {
            val id = s.optLong("timestampMs")
            val rate = BenchStore.aggregateHitRate(s) * 100
            val n = BenchStore.attemptsOf(s)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(CheckBox(this).apply {
                isChecked = selected.contains(id)
                setOnCheckedChangeListener { _, c ->
                    if (c) selected.add(id) else selected.remove(id)
                    deleteBtn?.isEnabled = selected.isNotEmpty()
                    compareBtn?.isEnabled = selected.size == 2
                }
                tag = id
            })
            val rateStr = if (n > 0) String.format(Locale.US, "%.1f%%", rate) else "—"
            row.addView(TextView(this).apply {
                text = "${fmt.format(Date(id))}   $rateStr (${n})\n" +
                    BenchStore.labelOf(s) + "   seed ${BenchStore.seedOf(s)}"
                textSize = 13f
                setTextColor(COL_TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnLongClickListener {
                BenchStore.delete(this@BenchHistoryActivity, id)
                selected.remove(id)
                setContentView(buildView())
                true
            }
            col.addView(row)
            val divider = View(this).apply {
                setBackgroundColor(0x33E3E6FD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
            }
            col.addView(divider)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        fun barButton(label: String, enabled: Boolean, onClick: () -> Unit) =
            Button(this).apply {
                text = label
                isEnabled = enabled
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(8) }
            }

        deleteBtn = barButton("Delete selected", selected.isNotEmpty()) {
            confirmAndDelete(col)
        }
        bar.addView(deleteBtn)

        compareBtn = barButton("Compare selected", selected.size == 2) {
            rebuildComparePanel(col)
        }
        bar.addView(compareBtn)
        col.addView(bar)
        
        comparePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(comparePanel)
        return scroll
    }

    private fun rebuildComparePanel(col: LinearLayout) {
        comparePanel?.removeAllViews()
        if (selected.size != 2) return
        val ids = selected.toList()
        val all = BenchStore.list(this)
        val aOld = all.firstOrNull { it.optLong("timestampMs") == ids[0] } ?: return
        val bNew = all.firstOrNull { it.optLong("timestampMs") == ids[1] } ?: return

        fun line(text: String, color: Int = COL_MUTED, size: Float = 14f) {
            comparePanel?.addView(TextView(this).apply {
                this.text = text; textSize = size; setTextColor(color)
            })
        }

        val p = comparePanel ?: return
        p.addView(TextView(this).apply {
            text = "Comparison"
            textSize = 17f
            setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        })

        val rateA = BenchStore.aggregateHitRate(aOld)
        val rateB = BenchStore.aggregateHitRate(bNew)
        val deltaPts = (rateB - rateA) * 100
        val ciA = BenchSession.wilsonInterval(BenchStore.hitsOf(aOld), BenchStore.attemptsOf(aOld))
        val ciB = BenchSession.wilsonInterval(BenchStore.hitsOf(bNew), BenchStore.attemptsOf(bNew))
        line(String.format(Locale.US, "Older:      %.1f%%  (CI %.1f–%.1f)", rateA * 100, ciA.start * 100, ciA.endInclusive * 100))
        line(String.format(Locale.US, "Newer:    %.1f%%  (CI %.1f–%.1f)", rateB * 100, ciB.start * 100, ciB.endInclusive * 100))
        line(String.format(Locale.US, "Δ hit rate: %+.1f pts", deltaPts),
            if (deltaPts > 0) COL_GOOD else COL_BAD)
        line(if (ciA.start > ciB.endInclusive || ciB.start > ciA.endInclusive)
            "Intervals disjoint — difference is statistically real at 95%."
        else
            "Intervals overlap — the difference is NOT yet significant. Run more trials.")

        if (BenchStore.seedOf(aOld) == BenchStore.seedOf(bNew)) {
            line("Same seed: identical playlist, order variance eliminated.", COL_GOOD)
        } else {
            line("Different seeds: order variance present — prefer Repeat same playlist.", COL_BAD)
        }

        // Per-cell delta grid per shared level.
        val cellsA = BenchStore.perCell(aOld)
        val cellsB = BenchStore.perCell(bNew)
        for (level in TargetLevel.entries) {
            if (cellsA.keys.none { it.level == level } ||
                cellsB.keys.none { it.level == level }) continue
            p.addView(TextView(this).apply {
                text = if (level == TargetLevel.PRIMARY) "Primary — Δ hit rate (pts)"
                       else "Secondary — Δ hit rate (pts)"
                textSize = 14f; setTextColor(COL_TEXT)
                setPadding(0, dp(12), 0, dp(4))
            })
            val grid = GridLayout(this).apply { columnCount = 9 }
            fun cell(text: String, color: Int = COL_MUTED) {
                grid.addView(TextView(this).apply {
                    this.text = text; textSize = 13f; setTextColor(color)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                })
            }
            cell("")
            BenchActivity.DIRS.forEach { cell(it) }
            for (ring in listOf(Ring.INNER, Ring.OUTER)) {
                cell(ring.name.lowercase())
                for (seg in 0 until 8) {
                    val key = BenchTarget(level, ring, seg)
                    val ca = cellsA[key]; val cb = cellsB[key]
                    if (ca == null || cb == null || ca.attempts == 0 || cb.attempts == 0) {
                        cell("–")
                    } else {
                        val d = (cb.hitRate - ca.hitRate) * 100
                        cell(String.format(Locale.US, "%+.0f", d),
                            if (d > 0) COL_GOOD else if (d < 0) COL_BAD else COL_MUTED)
                    }
                }
            }
            p.addView(grid)
        }
    }
    
    private fun confirmAndDelete(anchor: LinearLayout) {
        val n = selected.size
        if (n == 0) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete $n session${if (n == 1) "" else "s"}?")
            .setMessage("Saved benchmark sessions can't be recovered.")
            .setPositiveButton("Delete") { _, _ ->
                for (id in selected) BenchStore.delete(this, id)
                selected.clear()
                // Rebuild resets both buttons' enabled state from the empty set.
                setContentView(buildView())
                android.widget.Toast.makeText(
                    this, "Deleted $n session${if (n == 1) "" else "s"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
