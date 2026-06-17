package com.facegate.quality

import com.facegate.pipeline.PipelineConfig
import com.facegate.pipeline.QualityFailReason
import com.facegate.pipeline.QualityResult
import org.junit.Assert.*
import org.junit.Test

class QualityCheckerTest {

    @Test
    fun `QualityChecker can be instantiated`() {
        val checker = QualityChecker()
        assertNotNull(checker)
    }

    @Test
    fun `PipelineConfig blur threshold is positive`() {
        assertTrue(PipelineConfig.MIN_LAPLACIAN_VARIANCE > 0)
    }

    @Test
    fun `PipelineConfig brightness range is valid`() {
        assertTrue(PipelineConfig.MIN_BRIGHTNESS > 0)
        assertTrue(PipelineConfig.MAX_BRIGHTNESS > PipelineConfig.MIN_BRIGHTNESS)
    }

    @Test
    fun `PipelineConfig pose thresholds are positive`() {
        assertTrue(PipelineConfig.MAX_YAW_DEGREES > 0)
        assertTrue(PipelineConfig.MAX_PITCH_DEGREES > 0)
        assertTrue(PipelineConfig.MAX_ROLL_DEGREES > 0)
    }

    @Test
    fun `PipelineConfig face size ratio is valid`() {
        assertTrue(PipelineConfig.MIN_FACE_SIZE_RATIO > 0f)
        assertTrue(PipelineConfig.MIN_FACE_SIZE_RATIO < 1f)
    }

    @Test
    fun `QualityResult passed is false when failReasons not empty`() {
        val result = QualityResult(
            passed             = false,
            sharpness          = 10f,
            brightness         = 30f,
            faceSizeRatio      = 0.01f,
            yaw                = 0f,
            pitch              = 0f,
            roll               = 0f,
            landmarkConfidence = 0.9f,
            failReasons        = listOf(QualityFailReason.TOO_BLURRY, QualityFailReason.TOO_DARK),
            qualityScore       = 0.2f
        )
        assertFalse(result.passed)
        assertEquals(2, result.failReasons.size)
        assertTrue(result.failReasons.contains(QualityFailReason.TOO_BLURRY))
        assertTrue(result.failReasons.contains(QualityFailReason.TOO_DARK))
    }

    @Test
    fun `QualityResult passed is true when failReasons empty`() {
        val result = QualityResult(
            passed             = true,
            sharpness          = 200f,
            brightness         = 130f,
            faceSizeRatio      = 0.25f,
            yaw                = 5f,
            pitch              = 3f,
            roll               = 2f,
            landmarkConfidence = 0.9f,
            failReasons        = emptyList(),
            qualityScore       = 0.9f
        )
        assertTrue(result.passed)
        assertTrue(result.failReasons.isEmpty())
    }

    @Test
    fun `QualityFailReason all values exist`() {
        val reasons = QualityFailReason.values()
        assertTrue(reasons.contains(QualityFailReason.TOO_BLURRY))
        assertTrue(reasons.contains(QualityFailReason.TOO_DARK))
        assertTrue(reasons.contains(QualityFailReason.TOO_BRIGHT))
        assertTrue(reasons.contains(QualityFailReason.FACE_TOO_SMALL))
        assertTrue(reasons.contains(QualityFailReason.HEAD_TURNED_YAW))
        assertTrue(reasons.contains(QualityFailReason.HEAD_TILTED_PITCH))
        assertTrue(reasons.contains(QualityFailReason.HEAD_ROTATED_ROLL))
        assertTrue(reasons.contains(QualityFailReason.LOW_LANDMARK_CONFIDENCE))
    }

    @Test
    fun `qualityScore is between 0 and 1`() {
        val result = QualityResult(
            passed             = true,
            sharpness          = 200f,
            brightness         = 130f,
            faceSizeRatio      = 0.25f,
            yaw                = 0f,
            pitch              = 0f,
            roll               = 0f,
            landmarkConfidence = 1f,
            failReasons        = emptyList(),
            qualityScore       = 0.95f
        )
        assertTrue(result.qualityScore in 0f..1f)
    }
}
