package com.facegate.quality

import android.graphics.Bitmap
import com.facegate.pipeline.PipelineConfig
import com.facegate.pipeline.QualityFailReason
import com.facegate.pipeline.QualityResult
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Runs 5 quality checks on every camera frame before the embedding step.
 * Uses PipelineConfig thresholds and PipelineModels types — Yash's shared contract.
 *
 * Usage:
 * ```
 * val checker = QualityChecker()
 * val result  = checker.check(bitmap, face)
 * if (result.passed) { // proceed to FaceAligner }
 * else { showRedPrompt(result.failReasons) }
 * ```
 */
class QualityChecker {

    fun check(bitmap: Bitmap, face: Face): QualityResult {
        val failReasons = mutableListOf<QualityFailReason>()

        // ── Check 1: Blur ──────────────────────────────────────────────
        val sharpness = laplacianVariance(bitmap).toFloat()
        if (sharpness < PipelineConfig.MIN_LAPLACIAN_VARIANCE) {
            failReasons.add(QualityFailReason.TOO_BLURRY)
        }

        // ── Check 2: Brightness ────────────────────────────────────────
        val brightness = meanLuminance(bitmap)
        if (brightness < PipelineConfig.MIN_BRIGHTNESS) {
            failReasons.add(QualityFailReason.TOO_DARK)
        } else if (brightness > PipelineConfig.MAX_BRIGHTNESS) {
            failReasons.add(QualityFailReason.TOO_BRIGHT)
        }

        // ── Check 3: Face size ─────────────────────────────────────────
        val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
        val box = face.boundingBox
        val faceArea = (box.width() * box.height()).toFloat().coerceAtLeast(0f)
        val faceSizeRatio = if (imageArea > 0) faceArea / imageArea else 0f
        if (faceSizeRatio < PipelineConfig.MIN_FACE_SIZE_RATIO) {
            failReasons.add(QualityFailReason.FACE_TOO_SMALL)
        }

        // ── Check 4: Head pose ─────────────────────────────────────────
        val yaw   = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val roll  = face.headEulerAngleZ

        if (abs(yaw)   > PipelineConfig.MAX_YAW_DEGREES)   failReasons.add(QualityFailReason.HEAD_TURNED_YAW)
        if (abs(pitch) > PipelineConfig.MAX_PITCH_DEGREES) failReasons.add(QualityFailReason.HEAD_TILTED_PITCH)
        if (abs(roll)  > PipelineConfig.MAX_ROLL_DEGREES)  failReasons.add(QualityFailReason.HEAD_ROTATED_ROLL)

        // ── Check 5: Landmark confidence ───────────────────────────────
        val landmarkCount      = face.allLandmarks.size
        val landmarkConfidence = (landmarkCount.toFloat() / 6f).coerceIn(0f, 1f)
        if (landmarkConfidence < PipelineConfig.MIN_LANDMARK_CONFIDENCE) {
            failReasons.add(QualityFailReason.LOW_LANDMARK_CONFIDENCE)
        }

        // ── Composite quality score (0..1) ─────────────────────────────
        val blurScore       = (sharpness / (PipelineConfig.MIN_LAPLACIAN_VARIANCE * 3.0).toFloat()).coerceIn(0f, 1f)
        val brightnessScore = when {
            brightness < PipelineConfig.MIN_BRIGHTNESS ->
                brightness / PipelineConfig.MIN_BRIGHTNESS
            brightness > PipelineConfig.MAX_BRIGHTNESS ->
                1f - (brightness - PipelineConfig.MAX_BRIGHTNESS) / (255f - PipelineConfig.MAX_BRIGHTNESS)
            else -> 1f
        }.coerceIn(0f, 1f)
        val sizeScore      = (faceSizeRatio / PipelineConfig.MIN_FACE_SIZE_RATIO).coerceIn(0f, 1f)
        val poseScore      = (1f - (abs(yaw) / (PipelineConfig.MAX_YAW_DEGREES * 2f))).coerceIn(0f, 1f)
        val qualityScore   = (blurScore * 0.30f + brightnessScore * 0.20f +
                              sizeScore * 0.20f + poseScore * 0.20f +
                              landmarkConfidence * 0.10f)

        return QualityResult(
            passed              = failReasons.isEmpty(),
            sharpness           = sharpness,
            brightness          = brightness,
            faceSizeRatio       = faceSizeRatio,
            yaw                 = yaw,
            pitch               = pitch,
            roll                = roll,
            landmarkConfidence  = landmarkConfidence,
            failReasons         = failReasons,
            qualityScore        = qualityScore
        )
    }

    // ── Blur: Laplacian variance ───────────────────────────────────────

    private fun laplacianVariance(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0.0

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val grey = FloatArray(w * h) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr  8) and 0xFF
            val b =  c         and 0xFF
            (0.299f * r + 0.587f * g + 0.114f * b)
        }

        var sum = 0.0; var sumSq = 0.0; var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val lap = -4f * grey[y * w + x] +
                          grey[(y - 1) * w + x] + grey[(y + 1) * w + x] +
                          grey[y * w + x - 1]   + grey[y * w + x + 1]
                sum += lap; sumSq += lap * lap; count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return ((sumSq / count) - mean * mean).toFloat().toDouble()
    }

    // ── Brightness: mean luminance ─────────────────────────────────────

    private fun meanLuminance(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var total = 0L; var count = 0
        for (i in pixels.indices step 4) {
            val c = pixels[i]
            total += (0.299 * ((c shr 16) and 0xFF) +
                      0.587 * ((c shr  8) and 0xFF) +
                      0.114 * ( c         and 0xFF)).toLong()
            count++
        }
        return if (count == 0) 0f else total.toFloat() / count
    }
}
