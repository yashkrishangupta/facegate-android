package com.facegate.decision

import com.facegate.pipeline.AttendanceDecision
import com.facegate.pipeline.PipelineConfig
import com.facegate.pipeline.SimilarityMatch

/**
 * AttendanceDecisionEngine applies business logic and thresholds to the similarity
 * search results to determine the final attendance status for a frame.
 */
class AttendanceDecisionEngine {

    /**
     * Threshold for the "Twin Problem" / Ambiguity check.
     * If the difference between the top-1 and top-2 match similarity is less than this margin,
     * the system flags it as ambiguous to avoid misidentification.
     */
    private val AMBIGUITY_MARGIN = 0.12f

    /**
     * Evaluates the similarity match result and decides the attendance outcome.
     * 
     * @param match The result of the similarity search.
     * @param alreadyMarkedMap A map of student IDs to their attendance timestamp for the current session.
     * @return An AttendanceDecision (Accept, Reject, Ambiguous, or AlreadyMarked).
     */
    fun makeDecision(
        match: SimilarityMatch,
        alreadyMarkedMap: Map<String, Long> = emptyMap()
    ): AttendanceDecision {
        val top = match.topMatch
        val second = match.secondMatch

        // 1. REJECT if no match was found or top similarity is below the minimum rejection threshold.
        if (top == null || top.cosineSimilarity < PipelineConfig.THRESHOLD_REJECT) {
            return AttendanceDecision.Reject(
                topSimilarity = top?.cosineSimilarity ?: 0f,
                reason = "Face not recognized."
            )
        }

        // 2. AMBIGUOUS — two sub-cases:
        //    a) Score is in the gray zone (0.40–0.60): not confident enough to Accept.
        //       Bug 4 fix: previously this only fired when second != null, so with
        //       1 enrolled student gray-zone scores fell through to step 5 (Reject)
        //       with no explanation. Now gray zone always → Ambiguous regardless.
        //    b) Top score is above Accept threshold but too close to second match.
        if (top.cosineSimilarity < PipelineConfig.THRESHOLD_ACCEPT) {
            return AttendanceDecision.Ambiguous(
                topCandidate    = top,
                secondCandidate = second,
                reason          = "Low-confidence match. Please reposition your face."
            )
        }
        if (second != null) {
            val similarityDifference = top.cosineSimilarity - second.cosineSimilarity
            if (similarityDifference < AMBIGUITY_MARGIN) {
                return AttendanceDecision.Ambiguous(
                    topCandidate    = top,
                    secondCandidate = second,
                    reason          = "Ambiguous match: Too similar to another student (Twin risk)."
                )
            }
        }

        // 3. ALREADY MARKED if the student is already in the attendance list for this session.
        if (alreadyMarkedMap.containsKey(top.studentId)) {
            return AttendanceDecision.AlreadyMarked(
                studentId = top.studentId,
                markedAt = alreadyMarkedMap[top.studentId] ?: System.currentTimeMillis()
            )
        }

        // 4. ACCEPT — score is above threshold and not ambiguous.
        return AttendanceDecision.Accept(
            studentId   = top.studentId,
            studentName = top.studentName,
            confidence  = top.cosineSimilarity
        )
    }
}