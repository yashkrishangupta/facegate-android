package com.facegate.alignment

import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

// ─────────────────────────────────────────────────────────────────────────────
// Data class
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result returned by [FaceAligner.align].
 * [alignedBitmap] is always 112×112 RGB, ready for the embedding model.
 * [landmarksFound] tells the pipeline which 5-point set was used.
 */
data class AlignmentResult(
    val alignedBitmap: Bitmap,
    val landmarksFound: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// Reference 5-point template for MobileFaceNet 112×112
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Canonical 5-point landmark positions in the 112×112 output space.
 * These match the ArcFace / MobileFaceNet alignment convention.
 *
 * Order: LEFT_EYE, RIGHT_EYE, NOSE_BASE, MOUTH_LEFT, MOUTH_RIGHT
 * (left/right are from the *subject's* perspective)
 */
private val REFERENCE_LANDMARKS_112 = arrayOf(
    Point(38.2946,  51.6963),   // left  eye
    Point(73.5318,  51.5014),   // right eye
    Point(56.0252,  71.7366),   // nose tip
    Point(41.5493,  92.3655),   // mouth left corner
    Point(70.7299,  92.2041)    // mouth right corner
)

// ─────────────────────────────────────────────────────────────────────────────
// FaceAligner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Aligns a detected face to the canonical 112×112 crop expected by MobileFaceNet.
 *
 * ### What it does
 * 1. Extracts the 5-point landmarks from ML Kit's [Face] object.
 * 2. Estimates the affine transform mapping those landmarks → the reference template.
 * 3. Warps the full-frame bitmap using the transform.
 * 4. Returns a 112×112 RGB [Bitmap] ready for [FaceEmbedder].
 *
 * ### Fallback
 * If ML Kit did not find all 5 landmarks (e.g. landmark detection was not enabled
 * in the detector options), the aligner falls back to a simple bounding-box crop
 * with centre-padding so the pipeline does not hard-fail.
 *
 * ### Prerequisites
 * Ensure OpenCV is loaded before constructing this class:
 * ```kotlin
 * OpenCVLoader.initLocal()           // or initAsync in Application.onCreate
 * val aligner = FaceAligner()
 * ```
 *
 * ### Usage
 * ```kotlin
 * val result = aligner.align(fullFrameBitmap, face)
 * // result.alignedBitmap → pass to FaceEmbedder
 * ```
 */
class FaceAligner {

    companion object {
        const val OUTPUT_SIZE = 112   // MobileFaceNet canonical input dimension
    }

    /**
     * Align the face described by [face] within [sourceBitmap].
     *
     * @param sourceBitmap Full camera frame (any resolution). Not modified.
     * @param face         ML Kit [Face] detected in [sourceBitmap].
     * @return [AlignmentResult] with a 112×112 aligned crop.
     */
    fun align(sourceBitmap: Bitmap, face: Face): AlignmentResult {
        val srcPoints = extractLandmarkPoints(face)

        return if (srcPoints != null) {
            alignWithLandmarks(sourceBitmap, srcPoints)
        } else {
            alignWithBoundingBox(sourceBitmap, face)
        }
    }

    // ── Landmark-based alignment (preferred path) ─────────────────────────

    private fun alignWithLandmarks(
        sourceBitmap: Bitmap,
        srcPoints: Array<Point>
    ): AlignmentResult {
        val srcMat  = bitmapToMat(sourceBitmap)
        val dstMat  = Mat(OUTPUT_SIZE, OUTPUT_SIZE, CvType.CV_8UC3)

        // Build source and destination point matrices for getAffineTransform.
        // We use 3 points (left eye, right eye, nose) for the affine estimate —
        // a 3-point affine is exact and numerically stable.
        val srcPts3 = org.opencv.core.MatOfPoint2f(
            srcPoints[0],   // left eye
            srcPoints[1],   // right eye
            srcPoints[2]    // nose tip
        )
        val dstPts3 = org.opencv.core.MatOfPoint2f(
            REFERENCE_LANDMARKS_112[0],
            REFERENCE_LANDMARKS_112[1],
            REFERENCE_LANDMARKS_112[2]
        )

        val affineMatrix = Imgproc.getAffineTransform(srcPts3, dstPts3)

        Imgproc.warpAffine(
            srcMat,
            dstMat,
            affineMatrix,
            Size(OUTPUT_SIZE.toDouble(), OUTPUT_SIZE.toDouble()),
            Imgproc.INTER_LINEAR,
            org.opencv.core.Core.BORDER_REFLECT_101
        )

        val alignedBitmap = matToBitmap(dstMat)

        srcMat.release()
        dstMat.release()
        affineMatrix.release()
        srcPts3.release()
        dstPts3.release()

        return AlignmentResult(alignedBitmap = alignedBitmap, landmarksFound = true)
    }

    // ── Bounding-box fallback ─────────────────────────────────────────────

    /**
     * When 5-point landmarks are unavailable, crop the face bounding box with
     * a 20 % padding and resize to 112×112. Less accurate than the landmark
     * path but prevents a pipeline hard-failure.
     */
    private fun alignWithBoundingBox(sourceBitmap: Bitmap, face: Face): AlignmentResult {
        val box     = face.boundingBox
        val padding = (box.width() * 0.20f).toInt()

        val left   = (box.left   - padding).coerceAtLeast(0)
        val top    = (box.top    - padding).coerceAtLeast(0)
        val right  = (box.right  + padding).coerceAtMost(sourceBitmap.width)
        val bottom = (box.bottom + padding).coerceAtMost(sourceBitmap.height)

        val cropW = (right  - left).coerceAtLeast(1)
        val cropH = (bottom - top ).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(sourceBitmap, left, top, cropW, cropH)
        val resized  = Bitmap.createScaledBitmap(cropped, OUTPUT_SIZE, OUTPUT_SIZE, true)

        if (cropped !== resized) cropped.recycle()

        return AlignmentResult(alignedBitmap = resized, landmarksFound = false)
    }

    // ── Landmark extraction ───────────────────────────────────────────────

    /**
     * Extract the 5 canonical landmark [Point]s from an ML Kit [Face].
     * Returns null if any required landmark is missing (triggers fallback).
     *
     * ML Kit landmark types used:
     *   LEFT_EYE  → subject's left eye
     *   RIGHT_EYE → subject's right eye
     *   NOSE_BASE → nose tip
     *   MOUTH_LEFT  → left mouth corner
     *   MOUTH_RIGHT → right mouth corner
     *
     * Note: ML Kit uses IMAGE coordinates (left = screen-left), while the
     * canonical reference uses SUBJECT coordinates. We preserve image
     * coordinates here because the reference template is already expressed
     * in image-space for a front-facing camera.
     */
    private fun extractLandmarkPoints(face: Face): Array<Point>? {
        val leftEye    = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye   = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val noseBase   = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthLeft  = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        if (leftEye == null || rightEye == null || noseBase == null ||
            mouthLeft == null || mouthRight == null) {
            return null
        }

        return arrayOf(
            Point(leftEye.x.toDouble(),    leftEye.y.toDouble()),
            Point(rightEye.x.toDouble(),   rightEye.y.toDouble()),
            Point(noseBase.x.toDouble(),   noseBase.y.toDouble()),
            Point(mouthLeft.x.toDouble(),  mouthLeft.y.toDouble()),
            Point(mouthRight.x.toDouble(), mouthRight.y.toDouble())
        )
    }

    // ── Bitmap ↔ Mat helpers ──────────────────────────────────────────────

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        // Ensure the bitmap is in ARGB_8888 so Utils works correctly
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                   else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(argb, mat)
        // Convert RGBA → BGR for OpenCV (warpAffine keeps BGR, then we convert back)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        return mat
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        // Convert BGR → RGBA before writing to Bitmap
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }
}
