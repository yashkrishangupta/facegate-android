package com.facegate.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.facegate.pipeline.AlignedFace
import com.facegate.pipeline.FaceEmbedding
import com.facegate.pipeline.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FaceEmbedder(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * init()
     * Loads mobilefacenet.onnx from assets into an ONNX Runtime session.
     * Must be called once before any call to embed() or warmup().
     */
    fun init() {
        ortEnv = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(PipelineConfig.MODEL_ASSET_PATH).readBytes()
        val sessionOptions = OrtSession.SessionOptions()
        ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
    }

    /**
     * warmup()
     * Runs one dummy inference so the first real frame is not slow.
     * Must be called after init().
     */
    suspend fun warmup() {
        val dummyBitmap = Bitmap.createBitmap(
            PipelineConfig.MODEL_INPUT_SIZE,
            PipelineConfig.MODEL_INPUT_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val dummyAligned = AlignedFace(dummyBitmap, dummyBitmap)
        embed(dummyAligned)
        dummyBitmap.recycle()
    }

    /**
     * embed(alignedFace)
     * Converts a 112×112 aligned face bitmap → 128-D L2-normalized embedding.
     * Runs on Dispatchers.Default (CPU-bound, ~50-300 ms).
     * Returns FaceEmbedding with the vector and measured inference time.
     */
    suspend fun embed(alignedFace: AlignedFace): FaceEmbedding = withContext(Dispatchers.Default) {
        val env     = ortEnv     ?: throw IllegalStateException("OrtEnvironment not initialized — call init() first")
        val session = ortSession ?: throw IllegalStateException("OrtSession not initialized — call init() first")

        val startTime = SystemClock.elapsedRealtime()

        val floatBuffer = allocateAndPreprocess(alignedFace.bitmap)

        val inputName = session.inputNames.iterator().next()
        val shape     = longArrayOf(1, 3,
            PipelineConfig.MODEL_INPUT_SIZE.toLong(),
            PipelineConfig.MODEL_INPUT_SIZE.toLong())

        // OnnxTensor.createTensor requires a direct ByteBuffer-backed FloatBuffer
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, shape)

        val normalizedArray: FloatArray
        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                // Output shape: [1][128]
                val rawArray = (result[0].value as Array<FloatArray>)[0]
                normalizedArray = l2Normalize(rawArray)
            }
        }

        val inferenceTimeMs = SystemClock.elapsedRealtime() - startTime
        FaceEmbedding(normalizedArray, inferenceTimeMs)
    }

    /**
     * close()
     * Releases the ONNX session and environment. Call from onCleared() / onDestroy().
     */
    fun close() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * allocateAndPreprocess(bitmap)
     * Converts a 112×112 ARGB bitmap → CHW-layout direct FloatBuffer,
     * normalized to [-1, 1] using MEAN=0.5 / STD=0.5.
     *
     * ONNX Runtime on Android requires a direct (native-memory) FloatBuffer.
     * FloatBuffer.allocate() creates a heap buffer and is not accepted.
     */
    private fun allocateAndPreprocess(bitmap: Bitmap): java.nio.FloatBuffer {
        val size = PipelineConfig.MODEL_INPUT_SIZE

        // Bug fix: assert bitmap dimensions before processing so a wrong-size
        // bitmap from FaceAligner is caught immediately instead of silently
        // corrupting the tensor.
        require(bitmap.width == size && bitmap.height == size) {
            "FaceEmbedder expects a ${size}×${size} bitmap, " +
            "got ${bitmap.width}×${bitmap.height}. " +
            "FaceAligner must output exactly MODEL_INPUT_SIZE."
        }

        val pixelCount = size * size
        val intValues  = IntArray(pixelCount)
        bitmap.getPixels(intValues, 0, size, 0, 0, size, size)

        // Bug fix: use a DIRECT ByteBuffer backed by native memory.
        // OnnxTensor.createTensor() on Android NDK requires native-order direct buffers.
        val floatBuffer = ByteBuffer
            .allocateDirect(3 * pixelCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Write in CHW order: all R values, then all G, then all B
        for (i in 0 until pixelCount) {
            val pixel = intValues[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr  8) and 0xFF) / 255.0f
            val b = ( pixel         and 0xFF) / 255.0f

            floatBuffer.put(i,                  (r - PipelineConfig.MEAN[0]) / PipelineConfig.STD[0])
            floatBuffer.put(i + pixelCount,     (g - PipelineConfig.MEAN[1]) / PipelineConfig.STD[1])
            floatBuffer.put(i + 2 * pixelCount, (b - PipelineConfig.MEAN[2]) / PipelineConfig.STD[2])
        }

        return floatBuffer
    }

    /**
     * l2Normalize(vector)
     * Divides each element by the vector's L2 norm so cosine similarity
     * reduces to a simple dot product during search.
     * Returns the original array unchanged if norm == 0 (zero vector).
     */
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0f
        for (v in vector) sumSq += v * v
        val norm = Math.sqrt(sumSq.toDouble()).toFloat()

        val out = FloatArray(PipelineConfig.EMBEDDING_SIZE)
        if (norm > 0f) {
            for (i in vector.indices) out[i] = vector[i] / norm
        } else {
            vector.copyInto(out)
        }
        return out
    }
}
