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

        // 2. AMBIGUOUS if the top match is too close to the second match (Twin Risk).
        // This handles cases where two students look very similar.
        if (second != null) {
            val similarityDifference = top.cosineSimilarity - second.cosineSimilarity
            if (similarityDifference < AMBIGUITY_MARGIN) {
                return AttendanceDecision.Ambiguous(
                    topCandidate = top,
                    secondCandidate = second,
                    reason = "Ambiguous match: Too similar to another student (Twin risk)."
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

        // 4. ACCEPT if the top similarity is above the acceptance threshold.
        if (top.cosineSimilarity >= PipelineConfig.THRESHOLD_ACCEPT) {
            return AttendanceDecision.Accept(
                studentId = top.studentId,
                studentName = top.studentName,
                confidence = top.cosineSimilarity
            )
        }

        // 5. REJECT if confidence is between the rejection and acceptance thresholds.
        // This prompts the user to adjust their position or lighting for a better match.
        return AttendanceDecision.Reject(
            topSimilarity = top.cosineSimilarity,
            reason = "Similarity score too low. Please center your face."
        )
    }
}
