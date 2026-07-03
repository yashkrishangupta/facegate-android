package com.facegate.similarity

import com.facegate.pipeline.FaceEmbedding
import com.facegate.pipeline.MatchCandidate
import com.facegate.pipeline.PipelineConfig
import com.facegate.pipeline.SimilarityMatch

/**
 * Data model for an enrolled face template.
 * In a full implementation, this might be a Room Entity in the storage module.
 */
data class EnrolledTemplate(
    val studentId: String,
    val studentName: String,
    val embedding: FloatArray
)

/**
 * SimilaritySearch handles the comparison between a live face embedding
 * and the database of enrolled student templates.
 */
class SimilaritySearch {

    /**
     * Performs a cosine similarity search across all enrolled templates.
     * 
     * @param queryEmbedding The 128-D embedding of the detected face.
     * @param enrolledTemplates The list of templates to search against.
     * @return SimilarityMatch object containing the top two candidates and search latency.
     */


    fun search(
        queryEmbedding: FaceEmbedding,
        enrolledTemplates: List<EnrolledTemplate>
    ): SimilarityMatch {
        val startTime = System.currentTimeMillis()

        if (enrolledTemplates.isEmpty()) {
            return SimilarityMatch(
                topMatch = null,
                secondMatch = null,
                searchTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Calculate cosine similarity for all templates.
        // Since MobileFaceNet embeddings are L2-normalized, 
        // cosine similarity is equivalent to the dot product.
        val candidates = enrolledTemplates.map { template ->
            val similarity = calculateDotProduct(queryEmbedding.vector, template.embedding)
            MatchCandidate(
                studentId = template.studentId,
                studentName = template.studentName,
                cosineSimilarity = similarity
            )
        }.sortedByDescending { it.cosineSimilarity }

        val topMatch = candidates.getOrNull(0)
        val secondMatch = candidates.getOrNull(1)

        val duration = System.currentTimeMillis() - startTime
        return SimilarityMatch(topMatch, secondMatch, duration)
    }

    /**
     * Checks if a new face embedding is too similar to any existing student.
     * Useful for preventing duplicate enrollments.
     * 
     * @param embedding The embedding to check.
     * @param enrolledTemplates Current database of templates.
     * @param threshold Similarity threshold for considering it a duplicate
     *   (default PipelineConfig.DUPLICATE_RISK_THRESHOLD).
     * @return The existing template if a duplicate risk is found, null otherwise.
     */
    fun findDuplicateRisk(
        embedding: FaceEmbedding,
        enrolledTemplates: List<EnrolledTemplate>,
        threshold: Float = PipelineConfig.DUPLICATE_RISK_THRESHOLD
    ): EnrolledTemplate? {
        return enrolledTemplates.firstOrNull {
            calculateDotProduct(embedding.vector, it.embedding) >= threshold
        }
    }

    /**
     * Computes the dot product of two vectors.
     * Assumes vectors are of equal length (128-D).
     */
    private fun calculateDotProduct(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
        }
        return dotProduct
    }
}