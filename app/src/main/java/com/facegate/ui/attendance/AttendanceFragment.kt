package com.facegate.ui.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentAttendanceBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * ATTENDANCE FRAGMENT
 * ====================
 * Changes from original:
 *   - ImageAnalysis added to CameraX — feeds real frames to viewModel.processFrame()
 *   - startSession() called on onResume, stopSession() on onPause
 *   - scanRunnable auto-timer removed — pipeline drives state changes now
 *   - distinctUntilChanged() prevents UI thrashing (Idle fires ~10x/sec from pipeline)
 *   - Buffering state shows frame count progress in badge
 *   - Auto-reset after Success (3s) and Failed (2s)
 *   - btnShutter now resets scan (pipeline runs continuously, no manual trigger needed)
 */
@AndroidEntryPoint
class AttendanceFragment : Fragment() {

    private var _binding: FragmentAttendanceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AttendanceViewModel by viewModels()

    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera()
        }

    // ── LIFECYCLE ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAttendanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeViewModel()
        resetToIdle()

        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // Start session every time the screen becomes active.
        // This loads enrolled students from DB into the pipeline's memory.
        viewModel.startSession()
        resetToIdle()
    }

    override fun onPause() {
        super.onPause()
        // End session — clears biometric data from memory (security requirement)
        viewModel.stopSession()
        handler.removeCallbacksAndMessages(null)
        binding.scanLine.clearAnimation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    // ── CAMERA SETUP ─────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // ── Use case 1: Preview (shows live camera feed on screen) ────────
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)

            // ── Use case 2: ImageAnalysis (feeds frames to the ML pipeline) ───
            // STRATEGY_KEEP_ONLY_LATEST: automatically drops frames if the pipeline
            // is still processing the previous one — prevents frame queue buildup
            // during the 150-400ms ML inference window.
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(
                ContextCompat.getMainExecutor(requireContext())
            ) { imageProxy ->
                // Convert ImageProxy → Bitmap and pass to pipeline
                val bitmap = imageProxy.toBitmap()
                viewModel.processFrame(bitmap)
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                // Bind BOTH use cases — preview shows on screen, analysis feeds pipeline
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── VIEWMODEL OBSERVER ───────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            // distinctUntilChanged() is critical here:
            // The pipeline returns NoFace -> ScanState.Idle ~10 times per second.
            // Without this, resetToIdle() fires 10x/sec, constantly restarting
            // the scan line animation and flickering the UI.
            viewModel.scanState
                .collect { state ->
                    // Cancel any pending auto-reset from a previous Success/Failed
                    handler.removeCallbacksAndMessages(null)

                    when (state) {
                        is ScanState.Idle       -> resetToIdle()
                        is ScanState.Scanning   -> showScanningState()
                        is ScanState.Processing -> showProcessingState()
                        is ScanState.Success    -> {
                            showSuccessState(state)
                            // Auto-reset after 3 seconds so the next student can scan
                            handler.postDelayed({ viewModel.resetScan() }, 3000)
                        }
                        is ScanState.Failed     -> {
                            showFailState()
                            // Auto-reset after 2 seconds
                            handler.postDelayed({ viewModel.resetScan() }, 2000)
                        }
                    }
                }
        }
    }

    // ── UI STATE RENDERERS ───────────────────────────────────────────────────
    // All UI logic unchanged from original — no pipeline logic here.

    private fun resetToIdle() {
        binding.scanLine.clearAnimation()
        binding.faceOval.setImageResource(R.drawable.oval_face_guide)
        binding.tvScanBadge.text   = "Searching…"
        binding.tvStatusLabel.text = "READY"
        binding.tvStatusMain.text  = "Place your face inside the oval"
        binding.tvStatusSub.text   = "Keep your face centered and look straight"
        binding.processingDots.visibility = View.GONE
        binding.scanLine.visibility = View.GONE
    }

    private fun showScanningState(bufferingProgress: String? = null) {
        binding.faceOval.setImageResource(R.drawable.oval_face_scanning)
        binding.tvScanBadge.text   = bufferingProgress ?: "Face detected"
        binding.tvStatusLabel.text = "SCANNING"
        binding.tvStatusMain.text  = "Hold still — scanning…"
        binding.tvStatusSub.text   = "Analyzing facial features"
        binding.scanLine.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.scan_line)
        binding.scanLine.startAnimation(anim)
        binding.processingDots.visibility = View.GONE
    }

    private fun showProcessingState() {
        binding.scanLine.clearAnimation()
        binding.scanLine.visibility = View.GONE
        binding.faceOval.setImageResource(R.drawable.oval_face_scanning)
        binding.tvScanBadge.text   = "Processing…"
        binding.tvStatusLabel.text = "PROCESSING"
        binding.tvStatusMain.text  = "Identifying student…"
        binding.tvStatusSub.text   = "Matching against database"
        binding.processingDots.visibility = View.VISIBLE
        animateProcessingDots()
    }

    private fun showSuccessState(state: ScanState.Success) {
        binding.scanLine.clearAnimation()
        binding.scanLine.visibility = View.GONE
        binding.faceOval.setImageResource(R.drawable.oval_face_success)
        binding.tvScanBadge.text   = "Recognized ✓"
        binding.tvStatusLabel.text = "SUCCESS"
        binding.tvStatusMain.text  = "Attendance Marked!"
        binding.tvStatusSub.text   = "${state.studentName} — ${state.studentClass}"
        binding.processingDots.visibility = View.GONE
    }

    private fun showFailState() {
        binding.scanLine.clearAnimation()
        binding.scanLine.visibility = View.GONE
        binding.faceOval.setImageResource(R.drawable.oval_face_fail)
        binding.tvScanBadge.text   = "Not Recognized"
        binding.tvStatusLabel.text = "FAILED"
        binding.tvStatusMain.text  = "Face Not Recognized"
        binding.tvStatusSub.text   = "Please try again"
        binding.processingDots.visibility = View.GONE
    }

    // ── PROCESSING DOTS ANIMATION ────────────────────────────────────────────

    private fun animateProcessingDots() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        dots.forEachIndexed { index, dot ->
            handler.postDelayed({
                if (_binding == null) return@postDelayed  // guard against destroyed view
                dot.animate()
                    .alpha(1f).scaleX(1.2f).scaleY(1.2f)
                    .setDuration(400)
                    .withEndAction {
                        dot.animate()
                            .alpha(0.3f).scaleX(0.7f).scaleY(0.7f)
                            .setDuration(400).start()
                    }.start()
            }, (index * 200).toLong())
        }
    }

    // ── CLICK LISTENERS ──────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Shutter button — pipeline runs automatically from ImageAnalysis.
        // Button manually resets state (e.g. dismiss a Failed result early)
        binding.btnShutter.setOnClickListener {
            viewModel.resetScan()
        }
        binding.btnRetry.setOnClickListener {
            viewModel.resetScan()
        }
        binding.btnBack.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.btnCancel.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}