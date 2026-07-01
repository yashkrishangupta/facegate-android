package com.facegate.quality

import android.graphics.Bitmap
import com.facegate.pipeline.EnrollmentPose
import com.facegate.pipeline.PipelineConfig
import com.facegate.pipeline.QualityFailReason
import com.facegate.pipeline.QualityResult
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Runs 5 quality checks on every camera frame before the embedding step.
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

    /**
     * @param poseToleranceMultiplier Multiplies MAX_YAW/PITCH/ROLL_DEGREES
     *   before gating. 1.0 = normal (attendance) limits. Enrollment passes a
     *   higher value (see PipelineConfig.ENROLLMENT_POSE_TOLERANCE_MULTIPLIER)
     *   since shots 2-5 deliberately ask for "slightly" turned/tilted angles
     *   that would otherwise always exceed the strict attendance limits.
     * @param expectedPose Which on-screen pose prompt this shot corresponds
     *   to (frontal / turn-left / turn-right / tilt-up / tilt-down). When set
     *   to anything other than FRONTAL, the measured yaw/pitch must actually
     *   deviate in that direction by at least MIN_DIRECTIONAL_*_DEGREES, on
     *   top of staying under the usual max-tolerance ceiling. This stops a
     *   straight-on photo from being accepted as a posed shot.
     */
    fun check(
        bitmap: Bitmap,
        face: Face,
        poseToleranceMultiplier: Float = 1.0f,
        expectedPose: EnrollmentPose = EnrollmentPose.FRONTAL,
    ): QualityResult {
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

        val maxYaw   = PipelineConfig.MAX_YAW_DEGREES   * poseToleranceMultiplier
        val maxPitch = PipelineConfig.MAX_PITCH_DEGREES * poseToleranceMultiplier
        val maxRoll  = PipelineConfig.MAX_ROLL_DEGREES  * poseToleranceMultiplier

        if (abs(yaw)   > maxYaw)   failReasons.add(QualityFailReason.HEAD_TURNED_YAW)
        if (abs(pitch) > maxPitch) failReasons.add(QualityFailReason.HEAD_TILTED_PITCH)
        if (abs(roll)  > maxRoll)  failReasons.add(QualityFailReason.HEAD_ROTATED_ROLL)

        // ── Check 4b: Pose actually matches what this shot asked for ────
        // Only applies once the shot is already within the generic ceiling
        // above — this just additionally requires the *right* pose, with
        // enough deviation in the *right* direction.
        when (expectedPose) {
            EnrollmentPose.FRONTAL -> {
                // Frontal shot — should stay close to center. A pose that's
                // already flagged by Check 4 covers the "too far off" case;
                // nothing extra needed here.
            }
            EnrollmentPose.TURN_LEFT -> {
                // ML Kit headEulerAngleY: positive = turned toward the
                // device's left from the camera's point of view.
                if (yaw < PipelineConfig.MIN_DIRECTIONAL_YAW_DEGREES) {
                    failReasons.add(QualityFailReason.WRONG_POSE_DIRECTION)
                }
            }
            EnrollmentPose.TURN_RIGHT -> {
                if (yaw > -PipelineConfig.MIN_DIRECTIONAL_YAW_DEGREES) {
                    failReasons.add(QualityFailReason.WRONG_POSE_DIRECTION)
                }
            }
            EnrollmentPose.TILT_UP -> {
                // headEulerAngleX: positive = chin down / looking up.
                if (pitch < PipelineConfig.MIN_DIRECTIONAL_PITCH_DEGREES) {
                    failReasons.add(QualityFailReason.WRONG_POSE_DIRECTION)
                }
            }
            EnrollmentPose.TILT_DOWN -> {
                if (pitch > -PipelineConfig.MIN_DIRECTIONAL_PITCH_DEGREES) {
                    failReasons.add(QualityFailReason.WRONG_POSE_DIRECTION)
                }
            }
        }

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