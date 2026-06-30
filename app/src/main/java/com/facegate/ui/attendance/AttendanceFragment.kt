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
import androidx.navigation.fragment.navArgs
import com.facegate.R
import com.facegate.databinding.FragmentAttendanceBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

// ATTENDANCE FRAGMENT
@AndroidEntryPoint
class AttendanceFragment : Fragment() {

    private var _binding: FragmentAttendanceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AttendanceViewModel by viewModels()

    private val args by navArgs<AttendanceFragmentArgs>()

    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera()
        }
    
    private var lastState: ScanState? = null

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

        // Session args now come from TodayScheduleFragment via nav args,
        // not generated inside the ViewModel.
        viewModel.startSession(args.sessionId, args.subject, args.batch, args.windowMinutes)

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
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(
                ContextCompat.getMainExecutor(requireContext())
            ) { imageProxy ->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap()
                viewModel.processFrame(bitmap, rotationDegrees)
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
                viewModel.scanState.collect { state ->
                if (state == lastState) return@collect  // skip duplicate states
                lastState = state
                when (state) {
                    is ScanState.Idle       -> resetToIdle()
                    is ScanState.Scanning   -> {
                        handler.removeCallbacksAndMessages(null)
                        showScanningState(state.message)
                    }
                    is ScanState.Processing -> {
                        handler.removeCallbacksAndMessages(null)
                        showProcessingState()
                    }
                    is ScanState.Success    -> {
                        handler.removeCallbacksAndMessages(null)
                        showSuccessState(state)
                        handler.postDelayed({ viewModel.resetScan() }, 3000)
                    }
                    is ScanState.Failed     -> {
                        handler.removeCallbacksAndMessages(null)
                        showFailState(state.title, state.message)
                        handler.postDelayed({ viewModel.resetScan() }, 2000)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.windowCountdown.collect { remainingMs ->
                updateCountdown(remainingMs)
            }
        }

        lifecycleScope.launch {
            viewModel.liveQuality.collect { quality ->
                updateLiveQuality(quality)
            }
        }
    }

    /** Renders the real-time Lighting / Face Position / Recognition readout. No fixed values. */
    private fun updateLiveQuality(quality: com.facegate.ui.attendance.LiveQuality?) {
        if (_binding == null) return
        if (quality == null) {
            binding.tvLighting.text  = "—"
            binding.tvLighting.setTextColor(android.graphics.Color.parseColor("#90FFFFFF"))
            binding.tvPosition.text  = "—"
            binding.tvPosition.setTextColor(android.graphics.Color.parseColor("#90FFFFFF"))
            binding.tvConfidence.text = "--%"
            return
        }

        val ok    = android.graphics.Color.parseColor("#4DFF91")
        val bad   = android.graphics.Color.parseColor("#FF6B6B")
        val amber = android.graphics.Color.parseColor("#FFC857")

        binding.tvLighting.text = if (quality.lightingOk) "${quality.lightingLabel} ✓" else quality.lightingLabel
        binding.tvLighting.setTextColor(if (quality.lightingOk) ok else bad)

        binding.tvPosition.text = if (quality.positionOk) "${quality.positionLabel} ✓" else quality.positionLabel
        binding.tvPosition.setTextColor(if (quality.positionOk) ok else bad)

        binding.tvConfidence.text = "${quality.confidencePercent}%"
        binding.tvConfidence.setTextColor(
            when {
                quality.confidencePercent >= 80 -> ok
                quality.confidencePercent >= 50 -> amber
                else -> bad
            }
        )

        binding.tvRecognition.text = quality.recognitionLabel
        binding.tvRecognition.setTextColor(
            if (quality.confidencePercent >= 80) ok else amber
        )
    }

    private fun updateCountdown(remainingMs: Long) {
        if (_binding == null) return
        if (remainingMs <= 0L) {
            binding.tvCountdown.visibility = View.GONE
            return
        }
        binding.tvCountdown.visibility = View.VISIBLE
        val totalSeconds = remainingMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvCountdown.text = String.format("%d:%02d remaining", minutes, seconds)
        binding.tvCountdown.setTextColor(
            if (remainingMs < 60_000L) android.graphics.Color.parseColor("#FF5252")
            else android.graphics.Color.parseColor("#64FFDA")
        )
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
        binding.scanLine.visibility       = View.GONE
        binding.btnRetry.visibility       = View.INVISIBLE
    }

    private fun showScanningState(message: String) {
        binding.faceOval.setImageResource(R.drawable.oval_face_scanning)
        binding.tvScanBadge.text   = "Face detected"
        binding.tvStatusLabel.text = "SCANNING"
        binding.tvStatusMain.text  = message
        binding.tvStatusSub.text   = "Analyzing facial features"
        binding.processingDots.visibility = View.GONE
        binding.btnRetry.visibility       = View.INVISIBLE

        // Only start animation if not already running — prevents jitter
        if (binding.scanLine.animation == null) {
            binding.scanLine.visibility = View.VISIBLE
            val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.scan_line)
            binding.scanLine.startAnimation(anim)
        }
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
        binding.btnRetry.visibility       = View.INVISIBLE
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
        binding.btnRetry.visibility       = View.INVISIBLE
        binding.tvRecognition.text = "Matched ✓"
        binding.tvRecognition.setTextColor(android.graphics.Color.parseColor("#4DFF91"))
        state.confidencePercent?.let { pct ->
            binding.tvConfidence.text = "$pct%"
            binding.tvConfidence.setTextColor(android.graphics.Color.parseColor("#4DFF91"))
        }
    }

    private fun showFailState(title: String, message: String) {
        binding.scanLine.clearAnimation()
        binding.scanLine.visibility = View.GONE
        binding.faceOval.setImageResource(R.drawable.oval_face_fail)
        binding.tvScanBadge.text   = "Not Recognized"
        binding.tvStatusLabel.text = "FAILED"
        binding.tvStatusMain.text  = title
        binding.tvStatusSub.text   = message
        binding.processingDots.visibility = View.GONE
        binding.btnRetry.visibility       = View.VISIBLE
    }

    // ── PROCESSING DOTS ANIMATION ────────────────────────────────────────────

    private fun animateProcessingDots() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        dots.forEachIndexed { index, dot ->
            handler.postDelayed({
                if (_binding == null) return@postDelayed  
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