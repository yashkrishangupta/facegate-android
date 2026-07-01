package com.facegate.pipeline

import android.graphics.Bitmap
import android.graphics.PointF


// CONSTANTS

object PipelineConfig {

    // ── MobileFaceNet ──────────────────────────────────────
    const val MODEL_INPUT_SIZE   = 112          // px, fixed by MobileFaceNet
    const val EMBEDDING_SIZE     = 128          // fixed by MobileFaceNet
    const val MODEL_ASSET_PATH   = "models/mobilefacenet.onnx"

    // ── Camera ─────────────────────────────────────────────
    const val ANALYSIS_FPS       = 10
    const val FRAME_BUFFER_SIZE  = 8

    // ── Attendance window ───────────────────────────────────
    const val DEFAULT_WINDOW_MINUTES = 15

    // ── Quality thresholds ─────────────────────────────────
    const val MIN_FACE_SIZE_RATIO     = 0.05f
    const val MAX_YAW_DEGREES         = 30f
    const val MAX_PITCH_DEGREES       = 20f
    const val MAX_ROLL_DEGREES        = 15f
    const val MIN_LANDMARK_CONFIDENCE = 0.7f
    const val MIN_LAPLACIAN_VARIANCE  = 80.0
    const val MIN_BRIGHTNESS          = 60f
    const val MAX_BRIGHTNESS          = 220f

    const val ENROLLMENT_POSE_TOLERANCE_MULTIPLIER = 1.5f

    // Directional enrollment shots (turn left/right, tilt up/down) must show
    // at least this much deviation in the requested direction — otherwise a
    // near-frontal shot would slip through as if it were a posed shot, and
    // the averaged embedding would just be five copies of the same frontal
    // pose instead of genuine pose coverage.
    const val MIN_DIRECTIONAL_YAW_DEGREES   = 8f
    const val MIN_DIRECTIONAL_PITCH_DEGREES = 6f

    // Relative weight given to the frontal (1st) enrollment shot vs the 4
    // posed shots when averaging embeddings — the frontal shot is the most
    // reliable, distortion-free input, so it should anchor the template
    // rather than be diluted equally with the others.
    const val FRONTAL_SHOT_WEIGHT = 2.0f

    // ── Similarity thresholds ──────────────────────────────
    const val THRESHOLD_ACCEPT    = 0.60f
    const val THRESHOLD_REJECT    = 0.40f

    // ── Normalization ───────────────────────────────────────
    val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    val STD  = floatArrayOf(0.5f, 0.5f, 0.5f)
}


// STEP 1 — Face Detection Result

data class DetectedFace(
    val boundingBox: android.graphics.Rect,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val landmarks: List<PointF>,
    val landmarkConfidence: Float,
    val frameWidth: Int,
    val frameHeight: Int,
)

enum class FaceCountStatus {
    NO_FACE,
    SINGLE_FACE,
    MULTIPLE_FACES,
}


// STEP 2 — Quality Assessment Result

data class QualityResult(
    val passed: Boolean,
    val sharpness: Float,
    val brightness: Float,
    val faceSizeRatio: Float,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val landmarkConfidence: Float,
    val failReasons: List<QualityFailReason> = emptyList(),
    val qualityScore: Float = 0f,
)

enum class QualityFailReason {
    TOO_BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
    FACE_TOO_SMALL,
    HEAD_TURNED_YAW,
    HEAD_TILTED_PITCH,
    HEAD_ROTATED_ROLL,
    LOW_LANDMARK_CONFIDENCE,
    WRONG_POSE_DIRECTION,
}

/**
 * Which of the 5 enrollment shots is being captured, so the quality checker
 * can confirm the head is actually posed the way the on-screen prompt asked
 * for — not just "somewhere under the generic tolerance". Previously every
 * shot was checked against the same symmetric yaw/pitch tolerance, so e.g. a
 * straight-on photo would silently pass as the "turn left" shot, defeating
 * the purpose of capturing varied poses for the embedding.
 */
enum class EnrollmentPose {
    FRONTAL, TURN_LEFT, TURN_RIGHT, TILT_UP, TILT_DOWN;

    companion object {
        /** Mirrors the shot order shown in EnrollmentFragment.updatePhotoUI(). */
        fun forShotIndex(index: Int): EnrollmentPose = when (index) {
            0 -> FRONTAL
            1 -> TURN_LEFT
            2 -> TURN_RIGHT
            3 -> TILT_UP
            4 -> TILT_DOWN
            else -> FRONTAL
        }
    }
}


// STEP 3 — Aligned Face Crop

data class AlignedFace(
    val bitmap: Bitmap,
    val sourceFrame: Bitmap,
)


// STEP 4 — Embedding

data class FaceEmbedding(
    val vector: FloatArray,
    val inferenceTimeMs: Long,
) {
    init {
        require(vector.size == PipelineConfig.EMBEDDING_SIZE) {
            "Expected ${PipelineConfig.EMBEDDING_SIZE}-D embedding, got ${vector.size}"
        }
    }

    fun norm(): Float = Math.sqrt(vector.map { it * it }.sum().toDouble()).toFloat()
}


// STEP 5 — Similarity Match

data class SimilarityMatch(
    val topMatch: MatchCandidate?,
    val secondMatch: MatchCandidate?,
    val searchTimeMs: Long,
)

data class MatchCandidate(
    val studentId: String,
    val studentName: String,
    val cosineSimilarity: Float,
)


// STEP 6 — Attendance Decision

sealed class AttendanceDecision {

    data class Accept(
        val studentId: String,
        val studentName: String,
        val confidence: Float,
    ) : AttendanceDecision()

    data class Reject(
        val topSimilarity: Float,
        val reason: String,
    ) : AttendanceDecision()

    data class Ambiguous(
        val topCandidate: MatchCandidate?,
        val secondCandidate: MatchCandidate?,
        val reason: String,
    ) : AttendanceDecision()

    data class AlreadyMarked(
        val studentId: String,
        val markedAt: Long,
    ) : AttendanceDecision()
}


// FULL PIPELINE RESULT

data class PipelineResult(
    val decision: AttendanceDecision,
    val detectionMs: Long,
    val qualityMs: Long,
    val alignmentMs: Long,
    val inferenceMs: Long,
    val similarityMs: Long,
    val totalMs: Long,
    /** Quality metrics (brightness/pose/etc.) from the frame that produced this decision. */
    val quality: QualityResult? = null,
)


// PIPELINE FRAME STATUS — what UI receives each frame

sealed class PipelineFrameStatus {
    object NoSession                                    : PipelineFrameStatus()
    object Processing                                   : PipelineFrameStatus()
    object NoFace                                       : PipelineFrameStatus()
    object MultipleFaces                                : PipelineFrameStatus()
    data class QualityFailed(
        val reasons: List<QualityFailReason>,
        /** Real-time measured quality (brightness, pose, etc.) for this frame. */
        val quality: QualityResult,
    ) : PipelineFrameStatus()
    data class Buffering(
        val framesCollected: Int,
        val framesNeeded: Int,
        /** Real-time measured quality (brightness, pose, etc.) for this frame. */
        val quality: QualityResult,
    ) : PipelineFrameStatus()
    data class Decision(val result: PipelineResult)     : PipelineFrameStatus()
}


// ENROLLMENT RESULT

sealed class EnrollmentResult {
    object Success                                          : EnrollmentResult()
    object NoFaceDetected                                   : EnrollmentResult()
    object MultipleFacesDetected                            : EnrollmentResult()
    data class QualityFailed(val reasons: List<QualityFailReason>) : EnrollmentResult()
    data class DuplicateRisk(
        val existingStudentId: String,
        val existingStudentName: String,
    ) : EnrollmentResult()
}

// ── Shared quality-fail → human message mapping ──────────────────────────────

fun QualityFailReason.toUserMessage(): String = when (this) {
    QualityFailReason.TOO_BLURRY               -> "Hold still — image is blurry"
    QualityFailReason.TOO_DARK                 -> "Move to a brighter area"
    QualityFailReason.TOO_BRIGHT               -> "Avoid direct light behind you"
    QualityFailReason.FACE_TOO_SMALL           -> "Move closer to the camera"
    QualityFailReason.HEAD_TURNED_YAW          -> "Face the camera directly"
    QualityFailReason.HEAD_TILTED_PITCH        -> "Keep your head level"
    QualityFailReason.HEAD_ROTATED_ROLL        -> "Straighten your head"
    QualityFailReason.LOW_LANDMARK_CONFIDENCE  -> "Ensure your face is fully visible"
    QualityFailReason.WRONG_POSE_DIRECTION     -> "Please follow the on-screen pose for this shot"
}

fun List<QualityFailReason>.toUserMessage(): String =
    firstOrNull()?.toUserMessage() ?: "Photo quality too low — try again"