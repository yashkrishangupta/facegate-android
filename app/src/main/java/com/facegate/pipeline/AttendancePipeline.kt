package com.facegate.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.facegate.alignment.FaceAligner
import com.facegate.decision.AttendanceDecisionEngine
import com.facegate.quality.QualityChecker
import com.facegate.recognition.FaceEmbedder
import com.facegate.similarity.EnrolledTemplate
import com.facegate.similarity.SimilaritySearch
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import com.facegate.storage.entity.StudentEntity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import java.util.PriorityQueue

/**
 * ATTENDANCE PIPELINE — FULLY INTEGRATED
 * ========================================
 * All 8 stages wired. Storage reads/writes active.
 *
 *   Camera frame
 *       -> [1] ML Kit face detection          ✅
 *       -> [2] Face count check               ✅
 *       -> [3] Quality checks                 ✅ QualityChecker
 *       -> [4] Frame buffer / best frame      ✅
 *       -> [5] Face alignment                 ✅ FaceAligner + OpenCV
 *       -> [6] MobileFaceNet embedding        ✅ FaceEmbedder + ONNX
 *       -> [7] Cosine similarity search       ✅ SimilaritySearch
 *       -> [8] Threshold decision             ✅ AttendanceDecisionEngine
 *       -> DB write                           ✅ TemplateRepository
 *       -> Backend sync                       ⏳ SyncRepository (separate)
 *
 * Enrollment embedding strategy:
 *   All 5 quality-verified photos are individually embedded and then
 *   averaged into a single 128-D vector (element-wise mean, then L2-normalised).
 *   This blended template is far more robust than a lucky first-pass because
 *   it captures pose, lighting, and expression variation from all five shots.
 */
class AttendancePipeline(
    private val context: Context,
    private val repository: TemplateRepository,
) {

    // ── Component instances ──────────────────────────────────────────────────
    private val faceDetector    = buildFaceDetector()
    private val qualityChecker  = QualityChecker()
    private val faceAligner     = FaceAligner()
    private val faceEmbedder    = FaceEmbedder(context)
    private val similaritySearch = SimilaritySearch()
    private val decisionEngine  = AttendanceDecisionEngine()

    // ── Session state ────────────────────────────────────────────────────────
    private var sessionId: String? = null
    private val alreadyMarkedMap  = mutableMapOf<String, Long>()

    // ── Attendance window tracking ───────────────────────────────────────────
    private var windowMinutes    : Int = 10
    private var sessionStartTime : Long = 0L

    // ── In-memory template cache (loaded from DB at session start) ───────────
    private val enrolledTemplates = mutableListOf<EnrolledTemplate>()

    // ── Frame buffer ─────────────────────────────────────────────────────────
    private data class BufferedFrame(
        val bitmap       : Bitmap,
        val face         : Face,
        val qualityScore : Float,
    )

    private val frameBuffer = PriorityQueue<BufferedFrame>(
        PipelineConfig.FRAME_BUFFER_SIZE,
        compareByDescending { it.qualityScore }
    )
    private var bufferingActive = false


    // LIFECYCLE

    suspend fun init() {
        faceEmbedder.init()
        faceEmbedder.warmup()
    }

    suspend fun startSession(sessionId: String, windowMinutes: Int = PipelineConfig.DEFAULT_WINDOW_MINUTES) {
        this.sessionId = sessionId
        this.windowMinutes = windowMinutes
        this.sessionStartTime = System.currentTimeMillis()
        alreadyMarkedMap.clear()
        startFrameBuffering()

        val startOfDay = System.currentTimeMillis()
            .let { it - (it % (24 * 60 * 60 * 1000)) }
        repository.getTodayAttendance(startOfDay).forEach { record ->
            alreadyMarkedMap[record.studentId] = record.timeStamp
        }

        val students = repository.getStudents()
        enrolledTemplates.clear()
        enrolledTemplates.addAll(
            students.mapNotNull { entity ->
                val embedding = parseEmbedding(entity.embedding) ?: return@mapNotNull null
                EnrolledTemplate(
                    studentId   = entity.studentId,
                    studentName = entity.name,
                    embedding   = embedding,
                )
            }
        )
    }



    fun endSession() {
        enrolledTemplates.clear()
        alreadyMarkedMap.clear()
        frameBuffer.clear()
        bufferingActive = false
        sessionId = null
    }

    fun destroy() {
        endSession()
        faceDetector.close()
        faceEmbedder.close()
    }

    /** Returns true if the current time is still within the attendance window for this session. */
    fun isWithinWindow(): Boolean {
        val elapsed = System.currentTimeMillis() - sessionStartTime
        return elapsed <= windowMinutes * 60 * 1000L
    }

    /** Returns remaining milliseconds in the attendance window (0 if expired). */
    fun remainingWindowMs(): Long {
        val elapsed = System.currentTimeMillis() - sessionStartTime
        return (windowMinutes * 60 * 1000L - elapsed).coerceAtLeast(0L)
    }


    // MAIN ENTRY POINT

    suspend fun processFrame(bitmap: Bitmap, rotationDegrees: Int = 0): PipelineFrameStatus {
        sessionId ?: return PipelineFrameStatus.NoSession
        val t0 = SystemClock.elapsedRealtime()

        val uprightBitmap = rotateIfNeeded(bitmap, rotationDegrees)

        val tDetect = SystemClock.elapsedRealtime()
        val image   = InputImage.fromBitmap(uprightBitmap, 0)
        val faces   = faceDetector.process(image).await()
        val detectionMs = SystemClock.elapsedRealtime() - tDetect

        val rawFace: Face = when (faces.size) {
            0    -> return PipelineFrameStatus.NoFace
            1    -> faces[0]
            else -> return PipelineFrameStatus.MultipleFaces
        }

        val tQuality = SystemClock.elapsedRealtime()
        val quality  = qualityChecker.check(uprightBitmap, rawFace)
        val qualityMs = SystemClock.elapsedRealtime() - tQuality

        if (!quality.passed) {
            return PipelineFrameStatus.QualityFailed(quality.failReasons, quality)
        }

        bufferFrame(uprightBitmap, rawFace, quality.qualityScore)

        if (!isBufferReady()) {
            return PipelineFrameStatus.Buffering(
                framesCollected = frameBuffer.size,
                framesNeeded    = PipelineConfig.FRAME_BUFFER_SIZE,
                quality         = quality,
            )
        }

        val bestFrame = frameBuffer.peek()!!
        frameBuffer.clear()
        startFrameBuffering()

        val tAlign  = SystemClock.elapsedRealtime()
        val aligned = faceAligner.align(bestFrame.bitmap, bestFrame.face)
        val alignmentMs = SystemClock.elapsedRealtime() - tAlign

        val alignedFace = AlignedFace(
            bitmap      = aligned.alignedBitmap,
            sourceFrame = bestFrame.bitmap,
        )

        val embedding   = faceEmbedder.embed(alignedFace)
        val inferenceMs = embedding.inferenceTimeMs

        val tSearch     = SystemClock.elapsedRealtime()
        val match       = similaritySearch.search(embedding, enrolledTemplates)
        val similarityMs = SystemClock.elapsedRealtime() - tSearch

        val decision = decisionEngine.makeDecision(match, alreadyMarkedMap)

        val totalMs = SystemClock.elapsedRealtime() - t0

        handleDecision(decision)

        return PipelineFrameStatus.Decision(
            PipelineResult(
                decision     = decision,
                detectionMs  = detectionMs,
                qualityMs    = qualityMs,
                alignmentMs  = alignmentMs,
                inferenceMs  = inferenceMs,
                similarityMs = similarityMs,
                totalMs      = totalMs,
                quality      = quality,
            )
        )
    }


    // ENROLLMENT  — per-shot quality gate  +  averaged embedding

    suspend fun checkCaptureQuality(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        forEnrollment: Boolean = false,
        shotIndex: Int = 0,
    ): CaptureQualityResult {
        val upright = rotateIfNeeded(bitmap, rotationDegrees)
        val scaled  = scaleBitmapToMaxWidth(upright, 640)

        val image = InputImage.fromBitmap(scaled, 0)
        val faces = faceDetector.process(image).await()

        return when (faces.size) {
            0    -> CaptureQualityResult.Fail(CaptureRejectReason.NO_FACE)
            1    -> {
                val quality = qualityChecker.check(
                    scaled,
                    faces[0],
                    poseToleranceMultiplier = if (forEnrollment)
                        PipelineConfig.ENROLLMENT_POSE_TOLERANCE_MULTIPLIER else 1.0f,
                    expectedPose = if (forEnrollment)
                        EnrollmentPose.forShotIndex(shotIndex) else EnrollmentPose.FRONTAL,
                )
                if (quality.passed) {
                    CaptureQualityResult.Pass(scaled, quality.qualityScore)
                } else {
                    CaptureQualityResult.Fail(
                        reason     = CaptureRejectReason.QUALITY,
                        failDetail = quality.failReasons,
                    )
                }
            }
            else -> CaptureQualityResult.Fail(CaptureRejectReason.MULTIPLE_FACES)
        }
    }

    suspend fun enrollStudentFromEmbeddings(
        studentId    : String,
        studentName  : String,
        studentClass : String,
        verifiedBitmaps: List<Bitmap>,
    ): EnrollmentResult {
        require(verifiedBitmaps.isNotEmpty()) { "No verified bitmaps supplied" }

        data class CapturedEmbedding(
            val vector: FloatArray,
            val landmarksFound: Boolean,
            val weight: Float,
        )
        val captured = mutableListOf<CapturedEmbedding>()

        for ((index, bmp) in verifiedBitmaps.withIndex()) {
            // Re-detect to get the Face landmark for alignment
            val image = InputImage.fromBitmap(bmp, 0)
            val faces = faceDetector.process(image).await()
            val face  = faces.firstOrNull() ?: continue   // extremely unlikely after quality gate

            val aligned     = faceAligner.align(bmp, face)
            val alignedFace = AlignedFace(aligned.alignedBitmap, bmp)
            val embedding   = faceEmbedder.embed(alignedFace)

            // The first shot is the straight-on / frontal capture, which
            // ML models embed most reliably (no pose-induced distortion).
            // Shots 2-5 are deliberately off-axis to add some pose
            // robustness, but shouldn't dilute the frontal shot's
            // contribution as much as a plain average would. We also fold
            // in this capture's own quality score, so a sharper/better-lit
            // shot counts more than a borderline one that barely passed.
            val quality = qualityChecker.check(bmp, face, expectedPose = EnrollmentPose.forShotIndex(index))
            val poseWeight = if (index == 0) PipelineConfig.FRONTAL_SHOT_WEIGHT else 1.0f
            val weight = poseWeight * quality.qualityScore.coerceAtLeast(0.1f)

            captured.add(CapturedEmbedding(embedding.vector, aligned.landmarksFound, weight))
        }

        if (captured.isEmpty()) {
            return EnrollmentResult.NoFaceDetected
        }

        val landmarkAligned = captured.filter { it.landmarksFound }
        val weighted = if (landmarkAligned.isNotEmpty()) landmarkAligned else captured

        // ── Weighted average of all vectors, then L2-normalise ────────────────
        val dim = PipelineConfig.EMBEDDING_SIZE
        val averaged = FloatArray(dim)
        var totalWeight = 0f
        for (item in weighted) {
            totalWeight += item.weight
            for (i in 0 until dim) averaged[i] += item.vector[i] * item.weight
        }
        val safeTotalWeight = totalWeight.coerceAtLeast(1e-6f)
        for (i in 0 until dim) averaged[i] = averaged[i] / safeTotalWeight

        val norm = Math.sqrt(averaged.map { it * it.toDouble() }.sum()).toFloat()
            .coerceAtLeast(1e-10f)
        val normalised = FloatArray(dim) { averaged[it] / norm }

        // ── Duplicate check — always load fresh from DB ──────────────────────
        val blendedEmbedding = FaceEmbedding(vector = normalised, inferenceTimeMs = 0)
        val dbTemplates = repository.getStudents().mapNotNull { entity ->
            val emb = parseEmbedding(entity.embedding) ?: return@mapNotNull null
            EnrolledTemplate(studentId = entity.studentId, studentName = entity.name, embedding = emb)
        }
        val duplicate = similaritySearch.findDuplicateRisk(blendedEmbedding, dbTemplates)
        if (duplicate != null) {
            return EnrollmentResult.DuplicateRisk(
                existingStudentId   = duplicate.studentId,
                existingStudentName = duplicate.studentName,
            )
        }

        // ── Save blended template to DB ───────────────────────────────────────
        repository.addStudent(
            StudentEntity(
                studentId    = studentId,
                name         = studentName,
                studentClass = studentClass,
                embedding    = normalised.joinToString(","),
            )
        )

        enrolledTemplates.add(
            EnrolledTemplate(
                studentId   = studentId,
                studentName = studentName,
                embedding   = normalised,
            )
        )

        return EnrollmentResult.Success
    }

    suspend fun enrollStudent(
        studentId   : String,
        studentName : String,
        studentClass: String,
        photo       : Bitmap,
        rotationDegrees: Int = 0,
    ): EnrollmentResult {
        val uprightPhoto = rotateIfNeeded(photo, rotationDegrees)
        val scaledPhoto  = scaleBitmapToMaxWidth(uprightPhoto, 640)

        val image = InputImage.fromBitmap(scaledPhoto, 0)
        val faces = faceDetector.process(image).await()

        when (faces.size) {
            0    -> return EnrollmentResult.NoFaceDetected
            1    -> { /* continue */ }
            else -> return EnrollmentResult.MultipleFacesDetected
        }
        val rawFace = faces[0]

        val quality = qualityChecker.check(scaledPhoto, rawFace)
        if (!quality.passed) return EnrollmentResult.QualityFailed(quality.failReasons)

        val aligned     = faceAligner.align(scaledPhoto, rawFace)
        val alignedFace = AlignedFace(aligned.alignedBitmap, scaledPhoto)
        val embedding   = faceEmbedder.embed(alignedFace)

        val duplicate = similaritySearch.findDuplicateRisk(embedding, enrolledTemplates)
        if (duplicate != null) {
            return EnrollmentResult.DuplicateRisk(
                existingStudentId   = duplicate.studentId,
                existingStudentName = duplicate.studentName,
            )
        }

        repository.addStudent(
            StudentEntity(
                studentId    = studentId,
                name         = studentName,
                studentClass = studentClass,
                embedding    = embedding.vector.joinToString(","),
            )
        )

        enrolledTemplates.add(
            EnrolledTemplate(
                studentId   = studentId,
                studentName = studentName,
                embedding   = embedding.vector,
            )
        )

        return EnrollmentResult.Success
    }


    suspend fun forceEnrollStudent(
        studentId       : String,
        studentName     : String,
        studentClass    : String,
        verifiedBitmaps : List<Bitmap>,
    ) {
        require(verifiedBitmaps.isNotEmpty()) { "No verified bitmaps supplied" }

        data class CapturedVec(val vector: FloatArray, val weight: Float)
        val vectors = mutableListOf<CapturedVec>()
        for ((index, bmp) in verifiedBitmaps.withIndex()) {
            val image = InputImage.fromBitmap(bmp, 0)
            val faces = faceDetector.process(image).await()
            val face  = faces.firstOrNull() ?: continue
            val aligned     = faceAligner.align(bmp, face)
            val alignedFace = AlignedFace(aligned.alignedBitmap, bmp)
            val quality = qualityChecker.check(bmp, face, expectedPose = EnrollmentPose.forShotIndex(index))
            val poseWeight = if (index == 0) PipelineConfig.FRONTAL_SHOT_WEIGHT else 1.0f
            val weight = poseWeight * quality.qualityScore.coerceAtLeast(0.1f)
            vectors.add(CapturedVec(faceEmbedder.embed(alignedFace).vector, weight))
        }
        if (vectors.isEmpty()) return

        val dim = PipelineConfig.EMBEDDING_SIZE
        val averaged = FloatArray(dim)
        var totalWeight = 0f
        for (item in vectors) {
            totalWeight += item.weight
            for (i in 0 until dim) averaged[i] += item.vector[i] * item.weight
        }
        val safeTotalWeight = totalWeight.coerceAtLeast(1e-6f)
        for (i in 0 until dim) averaged[i] = averaged[i] / safeTotalWeight
        val norm = Math.sqrt(averaged.map { it * it.toDouble() }.sum())
            .toFloat().coerceAtLeast(1e-10f)
        val normalised = FloatArray(dim) { averaged[it] / norm }

        repository.addStudent(
            StudentEntity(
                studentId    = studentId,
                name         = studentName,
                studentClass = studentClass,
                embedding    = normalised.joinToString(","),
            )
        )
        enrolledTemplates.add(
            EnrolledTemplate(
                studentId   = studentId,
                studentName = studentName,
                embedding   = normalised,
            )
        )
    }

    fun enrolledCount(): Int = enrolledTemplates.size

    fun markAlreadyMarked(studentId: String, timestamp: Long = System.currentTimeMillis()) {
        alreadyMarkedMap[studentId] = timestamp
    }

    fun removeStudentFromSession(studentId: String) {
        enrolledTemplates.removeAll { it.studentId == studentId }
        alreadyMarkedMap.remove(studentId)
    }


    // PRIVATE HELPERS

    private suspend fun handleDecision(decision: AttendanceDecision) {
        when (decision) {
            is AttendanceDecision.Accept -> {
                val timestamp = System.currentTimeMillis()

                if (!isWithinWindow()) {
                    repository.addConflict(
                        ConflictEntity(
                            topStudentId      = decision.studentId,
                            topStudentName    = decision.studentName,
                            topScore          = decision.confidence,
                            secondStudentId   = "",
                            secondStudentName = "",
                            secondScore       = 0f,
                            reason            = "Marked outside $windowMinutes-min window",
                            sessionId         = sessionId ?: "no_session",
                            timestamp         = timestamp,
                            resolved          = false,
                        )
                    )
                    return
                }

                alreadyMarkedMap[decision.studentId] = timestamp

                repository.addAttendance(
                    AttendanceEntity(
                        studentId = decision.studentId,
                        sessionId = this.sessionId, // ADD THIS
                        timeStamp = timestamp,
                        synced    = false,
                    )
                )
                repository.resolveAllConflictsForStudent(decision.studentId)
            }
            is AttendanceDecision.Ambiguous -> {
                val topId    = decision.topCandidate?.studentId ?: "unknown"
                val secondId = decision.secondCandidate?.studentId ?: "unknown"

                // Look up an existing open conflict for this same pair of people,
                // regardless of which one is currently ranked top vs second. Two
                // conflicting people should only ever occupy a single open row.
                val existing = if (secondId != "unknown") {
                    repository.findOpenConflictForPair(sessionId ?: "no_session", topId, secondId)
                } else {
                    // No second candidate identified (rare) — fall back to matching on topId alone.
                    repository.findOpenConflict(sessionId ?: "no_session", topId)
                }

                val newTopScore    = decision.topCandidate?.cosineSimilarity ?: 0f
                val newSecondScore = decision.secondCandidate?.cosineSimilarity ?: 0f
                val newTopName     = decision.topCandidate?.studentName ?: "Unknown"
                val newSecondName  = decision.secondCandidate?.studentName ?: "Unknown"

                if (existing != null) {
                    // Always re-rank so the higher-scoring candidate (top OR second, this
                    // round or last) ends up in the topStudent slot. This handles the case
                    // where the same two people clash again with their scores flipped, and
                    // avoids ever creating a second conflict row for the same pair.
                    val candidates = listOf(
                        Triple(topId, newTopName, newTopScore),
                        Triple(secondId, newSecondName, newSecondScore),
                        Triple(existing.topStudentId, existing.topStudentName, existing.topScore),
                        Triple(existing.secondStudentId, existing.secondStudentName, existing.secondScore),
                    ).distinctBy { it.first }

                    val ranked = candidates.sortedByDescending { it.third }
                    val (finalTopId, finalTopName, finalTopScore) = ranked.getOrElse(0) {
                        Triple(topId, newTopName, newTopScore)
                    }
                    val (finalSecondId, finalSecondName, finalSecondScore) = ranked.getOrElse(1) {
                        Triple(secondId, newSecondName, newSecondScore)
                    }

                    repository.updateConflict(
                        id                 = existing.id,
                        topStudentId       = finalTopId,
                        topStudentName     = finalTopName,
                        topScore           = finalTopScore,
                        secondStudentId    = finalSecondId,
                        secondStudentName  = finalSecondName,
                        secondScore        = finalSecondScore,
                        reason             = decision.reason,
                        timestamp          = System.currentTimeMillis(),
                    )
                } else {
                    repository.addConflict(
                        ConflictEntity(
                            topStudentId      = topId,
                            topStudentName    = newTopName,
                            topScore          = newTopScore,
                            secondStudentId   = secondId,
                            secondStudentName = newSecondName,
                            secondScore       = newSecondScore,
                            reason            = decision.reason,
                            sessionId         = sessionId ?: "no_session",
                            timestamp         = System.currentTimeMillis(),
                            resolved          = false,
                        )
                    )
                }
            }
            is AttendanceDecision.Reject,
            is AttendanceDecision.AlreadyMarked -> { /* no DB write needed */ }
        }
    }

    private fun scaleBitmapToMaxWidth(bitmap: Bitmap, maxPx: Int): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxDim
        val w = (bitmap.width  * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun rotateIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun buildFaceDetector(): FaceDetector {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        return FaceDetection.getClient(options)
    }

    private fun startFrameBuffering() {
        frameBuffer.clear()
        bufferingActive = true
    }

    private fun bufferFrame(bitmap: Bitmap, face: Face, qualityScore: Float) {
        if (!bufferingActive) return
        if (frameBuffer.size >= PipelineConfig.FRAME_BUFFER_SIZE) return
        frameBuffer.add(BufferedFrame(bitmap, face, qualityScore))
    }

    private fun isBufferReady(): Boolean =
        frameBuffer.size >= PipelineConfig.FRAME_BUFFER_SIZE

    private fun parseEmbedding(raw: String): FloatArray? {
        return try {
            val parts = raw.split(",")
            if (parts.size != PipelineConfig.EMBEDDING_SIZE) return null
            FloatArray(parts.size) { parts[it].trim().toFloat() }
        } catch (e: Exception) {
            null
        }
    }
}
// ── Capture quality result types ─────────────────────────────────────────────

enum class CaptureRejectReason { NO_FACE, MULTIPLE_FACES, QUALITY }

sealed class CaptureQualityResult {
    data class Pass(val bitmap: Bitmap, val qualityScore: Float = 1.0f) : CaptureQualityResult()
    data class Fail(
        val reason     : CaptureRejectReason,
        val failDetail : List<com.facegate.pipeline.QualityFailReason> = emptyList(),
    ) : CaptureQualityResult()
}