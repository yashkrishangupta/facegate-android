package com.facegate.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.facegate.alignment.FaceAligner
import com.facegate.decision.AttendanceDecisionEngine
import com.facegate.quality.QualityChecker
import com.facegate.recognition.FaceEmbedder
import com.facegate.similarity.EnrolledTemplate
import com.facegate.similarity.SimilaritySearch
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AttendanceEntity
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
 * NOTE on StudentEntity.embedding:
 *   Room stores the embedding as a String (comma-separated floats).
 *   We convert FloatArray <-> String at the storage boundary.
 *   The rest of the pipeline works with FloatArray internally.
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

    // ── In-memory template cache (loaded from DB at session start) ───────────
    // Held in memory during session for fast cosine search (~<5ms for 500 students)
    // Cleared at endSession() for security
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


    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Load ONNX model + warmup inference.
     * Call once at app start from FaceGateApp or ViewModel.init block.
     */
    suspend fun init() {
        faceEmbedder.init()
        faceEmbedder.warmup()
    }

    /**
     * Start an attendance session.
     * Loads enrolled students from SQLite into memory and starts buffering.
     */
    suspend fun startSession(sessionId: String) {
        this.sessionId = sessionId
        alreadyMarkedMap.clear()
        startFrameBuffering()

        // Load templates from DB into in-memory list for fast search
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

    /**
     * End the session — clears biometric data from memory immediately.
     */
    fun endSession() {
        enrolledTemplates.clear()
        alreadyMarkedMap.clear()
        frameBuffer.clear()
        bufferingActive = false
        sessionId = null
    }

    /**
     * Release all resources. Call from ViewModel.onCleared() / Activity.onDestroy().
     */
    fun destroy() {
        endSession()
        faceDetector.close()
        faceEmbedder.close()
    }


    // ═════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Process one camera frame through all 8 stages.
     * Called from CameraX ImageAnalysis at ~10fps.
     * Returns PipelineFrameStatus — the UI observes this via StateFlow.
     */
    suspend fun processFrame(bitmap: Bitmap): PipelineFrameStatus {
        sessionId ?: return PipelineFrameStatus.NoSession
        val t0 = SystemClock.elapsedRealtime()

        // ── Stage 1: ML Kit Face Detection ───────────────────────────────────
        val tDetect = SystemClock.elapsedRealtime()
        val image   = InputImage.fromBitmap(bitmap, 0)
        val faces   = faceDetector.process(image).await()
        val detectionMs = SystemClock.elapsedRealtime() - tDetect

        // ── Stage 2: Face Count Check ─────────────────────────────────────────
        val rawFace: Face = when (faces.size) {
            0    -> return PipelineFrameStatus.NoFace
            1    -> faces[0]
            else -> return PipelineFrameStatus.MultipleFaces
        }

        // ── Stage 3: Quality Check ────────────────────────────────────────────
        val tQuality = SystemClock.elapsedRealtime()
        val quality  = qualityChecker.check(bitmap, rawFace)
        val qualityMs = SystemClock.elapsedRealtime() - tQuality

        if (!quality.passed) {
            return PipelineFrameStatus.QualityFailed(quality.failReasons)
        }

        // ── Stage 4: Frame Buffer ─────────────────────────────────────────────
        bufferFrame(bitmap, rawFace, quality.qualityScore)

        if (!isBufferReady()) {
            return PipelineFrameStatus.Buffering(
                framesCollected = frameBuffer.size,
                framesNeeded    = PipelineConfig.FRAME_BUFFER_SIZE,
            )
        }

        val bestFrame = frameBuffer.peek()!!
        frameBuffer.clear()
        startFrameBuffering()

        // ── Stage 5: Face Alignment ───────────────────────────────────────────
        val tAlign  = SystemClock.elapsedRealtime()
        val aligned = faceAligner.align(bestFrame.bitmap, bestFrame.face)
        val alignmentMs = SystemClock.elapsedRealtime() - tAlign

        // AlignmentResult → AlignedFace (bridge between FaceAligner and FaceEmbedder)
        val alignedFace = AlignedFace(
            bitmap      = aligned.alignedBitmap,
            sourceFrame = bestFrame.bitmap,
        )

        // ── Stage 6: Embedding ────────────────────────────────────────────────
        val embedding   = faceEmbedder.embed(alignedFace)
        val inferenceMs = embedding.inferenceTimeMs

        // ── Stage 7: Similarity Search ────────────────────────────────────────
        val tSearch     = SystemClock.elapsedRealtime()
        val match       = similaritySearch.search(embedding, enrolledTemplates)
        val similarityMs = SystemClock.elapsedRealtime() - tSearch

        // ── Stage 8: Decision ─────────────────────────────────────────────────
        val decision = decisionEngine.makeDecision(match, alreadyMarkedMap)

        val totalMs = SystemClock.elapsedRealtime() - t0

        // ── Side-effects (DB write) ───────────────────────────────────────────
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
            )
        )
    }


    // ═════════════════════════════════════════════════════════════════════════
    // ENROLLMENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Enroll a new student.
     * Detects, checks quality, aligns, embeds, checks duplicate, saves to DB.
     */
    suspend fun enrollStudent(
        studentId   : String,
        studentName : String,
        studentClass: String,
        photo       : Bitmap,
    ): EnrollmentResult {

        // Detect face
        val image = InputImage.fromBitmap(photo, 0)
        val faces = faceDetector.process(image).await()

        when (faces.size) {
            0    -> return EnrollmentResult.NoFaceDetected
            1    -> { /* continue */ }
            else -> return EnrollmentResult.MultipleFacesDetected
        }
        val rawFace = faces[0]

        // Quality check
        val quality = qualityChecker.check(photo, rawFace)
        if (!quality.passed) {
            return EnrollmentResult.QualityFailed(quality.failReasons)
        }

        // Align + embed
        val aligned     = faceAligner.align(photo, rawFace)
        val alignedFace = AlignedFace(aligned.alignedBitmap, photo)
        val embedding   = faceEmbedder.embed(alignedFace)

        // Duplicate check against in-memory templates
        val duplicate = similaritySearch.findDuplicateRisk(embedding, enrolledTemplates)
        if (duplicate != null) {
            return EnrollmentResult.DuplicateRisk(
                existingStudentId   = duplicate.studentId,
                existingStudentName = duplicate.studentName,
            )
        }

        // Save to DB (embedding stored as comma-separated string)
        repository.addStudent(
            StudentEntity(
                studentId = studentId,
                name      = studentName,
                embedding = embedding.vector.joinToString(","),
            )
        )

        // Add to in-memory cache so this session can detect them immediately
        enrolledTemplates.add(
            EnrolledTemplate(
                studentId   = studentId,
                studentName = studentName,
                embedding   = embedding.vector,
            )
        )

        return EnrollmentResult.Success
    }

    /** How many students are loaded in the current session. */
    fun enrolledCount(): Int = enrolledTemplates.size


    // ═════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handle decision side-effects — write to DB, update in-memory state.
     */
    private suspend fun handleDecision(decision: AttendanceDecision) {
        when (decision) {
            is AttendanceDecision.Accept -> {
                val timestamp = System.currentTimeMillis()
                alreadyMarkedMap[decision.studentId] = timestamp

                // Write attendance record to Room
                repository.addAttendance(
                    AttendanceEntity(
                        studentId = decision.studentId,
                        timeStamp = timestamp,
                        synced    = false,
                    )
                )
            }
            is AttendanceDecision.Ambiguous -> {
                // Conflict — no DB write yet (conflict queue not in TemplateRepository yet)
                // TODO: repository.addConflict(...) when ConflictEntity is added
            }
            is AttendanceDecision.Reject,
            is AttendanceDecision.AlreadyMarked -> {
                // No DB write needed
            }
        }
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

    /**
     * Parse a comma-separated float string back to FloatArray.
     * Used when loading StudentEntity.embedding from Room.
     * Returns null if the stored string is corrupted/empty.
     */
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