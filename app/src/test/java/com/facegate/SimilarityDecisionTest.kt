package com.facegate

import com.facegate.decision.AttendanceDecisionEngine
import com.facegate.decision.AttendanceDecision
import com.facegate.pipeline.FaceEmbedding
import com.facegate.pipeline.PipelineConfig
import com.facegate.similarity.EnrolledTemplate
import com.facegate.similarity.SimilaritySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityDecisionTest {

    @Test
    fun testSimilarityAndDecision() {
        val search = SimilaritySearch()
        val engine = AttendanceDecisionEngine()

        // 1. Setup mock enrolled templates
        val aliceEmbedding = FloatArray(128) { 0.1f } // Mock vector
        // Normalize it (simplified for test)
        val norm = Math.sqrt(aliceEmbedding.map { (it * it).toDouble() }.sum()).toFloat()
        for (i in aliceEmbedding.indices) aliceEmbedding[i] /= norm

        val bobEmbedding = FloatArray(128) { -0.1f }
        val bobNorm = Math.sqrt(bobEmbedding.map { (it * it).toDouble() }.sum()).toFloat()
        for (i in bobEmbedding.indices) bobEmbedding[i] /= bobNorm

        val enrolled = listOf(
            EnrolledTemplate("1", "Alice", aliceEmbedding),
            EnrolledTemplate("2", "Bob", bobEmbedding)
        )

        // 2. Mock a query embedding (close to Alice)
        val queryVector = aliceEmbedding.copyOf()
        queryVector[0] += 0.01f // Slight variation
        val qNorm = Math.sqrt(queryVector.map { (it * it).toDouble() }.sum()).toFloat()
        for (i in queryVector.indices) queryVector[i] /= qNorm
        
        val queryEmbedding = FaceEmbedding(queryVector, 10L)

        // 3. Run Search
        val match = search.search(queryEmbedding, enrolled)
        
        println("Top Match: ${match.topMatch?.studentName} with similarity ${match.topMatch?.cosineSimilarity}")

        // 4. Run Decision
        val decision = engine.makeDecision(match)

        // 5. Assertions
        assertTrue(decision is AttendanceDecision.Accept)
        val accept = decision as AttendanceDecision.Accept
        assertEquals("Alice", accept.studentName)
        assertTrue(accept.confidence > PipelineConfig.THRESHOLD_ACCEPT)
    }

    @Test
    fun testAmbiguityDetection() {
        val search = SimilaritySearch()
        val engine = AttendanceDecisionEngine()

        // Create two very similar embeddings
        val vec1 = FloatArray(128) { 0.1f }
        val vec2 = vec1.copyOf()
        vec2[0] += 0.01f // Very small difference

        // Normalize
        fun norm(v: FloatArray) {
            val n = Math.sqrt(v.map { (it * it).toDouble() }.sum()).toFloat()
            for (i in v.indices) v[i] /= n
        }
        norm(vec1)
        norm(vec2)

        val enrolled = listOf(
            EnrolledTemplate("1", "Twin A", vec1),
            EnrolledTemplate("2", "Twin B", vec2)
        )

        val queryEmbedding = FaceEmbedding(vec1, 5L)
        val match = search.search(queryEmbedding, enrolled)
        val decision = engine.makeDecision(match)

        println("Ambiguity Test Result: $decision")
        assertTrue("Decision should be Ambiguous for very similar faces", decision is AttendanceDecision.Ambiguous)
    }
}
