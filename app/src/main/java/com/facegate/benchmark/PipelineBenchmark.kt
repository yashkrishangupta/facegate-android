package com.facegate.benchmark

import android.util.Log

/**
 * PipelineBenchmark
 * =================
 * Measures and validates the latency of each pipeline stage on the real device.
 * Call logAndValidate() at the end of every processed frame (after all stages
 * complete) to log timings and check the <1s total budget.
 *
 * Usage (from AttendancePipeline, once all stages are wired in):
 *   PipelineBenchmark.logAndValidate(detectionMs, qualityMs, alignmentMs,
 *                                     inferenceMs, similarityMs)
 */
object PipelineBenchmark {

    private const val TAG = "PipelineBenchmark"

    // ── Total budget ──────────────────────────────────────────────────────────
    private const val BUDGET_TOTAL_MS: Long = 1000L

    // ── Per-stage budgets (for targeted diagnostics) ──────────────────────────
    private const val BUDGET_DETECTION_MS:  Long = 80L
    private const val BUDGET_QUALITY_MS:    Long = 20L
    private const val BUDGET_ALIGNMENT_MS:  Long = 30L
    private const val BUDGET_INFERENCE_MS:  Long = 150L
    private const val BUDGET_SIMILARITY_MS: Long = 20L

    // ── Rolling average accumulator (30-frame window) ─────────────────────────
    private const val AVERAGE_WINDOW = 30
    private val history = mutableListOf<Long>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * logAndValidate()
     * Logs all per-stage timings and the total, warns on any stage that
     * exceeds its per-stage budget, and returns false if total > 1000 ms.
     *
     * Also accumulates timings into a 30-frame rolling average window.
     * When the window fills it logs the average and clears the window.
     *
     * @return true if totalMs < BUDGET_TOTAL_MS, false otherwise.
     */
    fun logAndValidate(
        detectionMs:  Long,
        qualityMs:    Long,
        alignmentMs:  Long,
        inferenceMs:  Long,
        similarityMs: Long,
    ): Boolean {
        val totalMs = detectionMs + qualityMs + alignmentMs + inferenceMs + similarityMs
        val isValid = totalMs < BUDGET_TOTAL_MS

        // ── Per-stage log + budget warnings ───────────────────────────────────
        Log.d(TAG, "=== PIPELINE BENCHMARK ===")
        logStage("Detection ",  detectionMs,  BUDGET_DETECTION_MS)
        logStage("Quality   ",  qualityMs,    BUDGET_QUALITY_MS)
        logStage("Alignment ",  alignmentMs,  BUDGET_ALIGNMENT_MS)
        logStage("Inference ",  inferenceMs,  BUDGET_INFERENCE_MS)
        logStage("Similarity",  similarityMs, BUDGET_SIMILARITY_MS)
        Log.d(TAG, "----------------------------------")
        Log.d(TAG, "Total:  $totalMs ms  (Budget: $BUDGET_TOTAL_MS ms)")

        if (isValid) {
            Log.d(TAG, "Result: ✅ WITHIN BUDGET")
        } else {
            Log.w(TAG, "Result: ❌ OVER BUDGET by ${totalMs - BUDGET_TOTAL_MS} ms")
        }

        Log.d(TAG, "==================================")

        // ── Rolling 30-frame average ──────────────────────────────────────────
        recordToHistory(totalMs)

        return isValid
    }

    /**
     * resetHistory()
     * Clears the rolling average window.
     * Call this at the start of each new session so averages don't bleed
     * across sessions.
     */
    fun resetHistory() {
        history.clear()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun logStage(label: String, actualMs: Long, budgetMs: Long) {
        if (actualMs > budgetMs) {
            Log.w(TAG, "  $label: $actualMs ms  ⚠️ over per-stage budget of $budgetMs ms")
        } else {
            Log.d(TAG, "  $label: $actualMs ms  ✅")
        }
    }

    private fun recordToHistory(totalMs: Long) {
        history.add(totalMs)
        if (history.size >= AVERAGE_WINDOW) {
            val avg = history.average().toLong()
            val max = history.max()
            val min = history.min()
            Log.d(TAG, "--- 30-frame rolling avg: $avg ms | min: $min ms | max: $max ms ---")
            history.clear()
        }
    }
}
