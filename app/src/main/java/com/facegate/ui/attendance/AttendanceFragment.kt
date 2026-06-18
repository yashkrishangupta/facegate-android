package com.facegate.ui.attendance

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentAttendanceBinding
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/**
 * ATTENDANCE FRAGMENT
 * Camera screen with face oval and scan simulation
 * Properly observes ViewModel state
 */
class AttendanceFragment : Fragment() {

    private var _binding: FragmentAttendanceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AttendanceViewModel by viewModels()

    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    private val requestPermissionLauncher =
    registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        }
    }

    // ── LIFECYCLE ────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAttendanceBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeViewModel()
        resetToIdle()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearScan()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        clearScan()
    }

    override fun onResume() {
        super.onResume()
        resetToIdle()
    }

    // ── VIEWMODEL OBSERVER ───────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.scanState.collect { state ->
                when (state) {
                    is ScanState.Idle -> {
                        resetToIdle()
                    }
                    is ScanState.Scanning -> {
                        showScanningState()
                    }
                    is ScanState.Processing -> {
                        showProcessingState()
                    }
                    is ScanState.Success -> {
                        showSuccessState(state)
                    }
                    is ScanState.Failed -> {
                        showFailState()
                    }
                }
            }
        }
    }

    // ── SCAN STATE MACHINE ───────────────────────────

    private fun resetToIdle() {
        clearScan()
        binding.faceOval.setImageResource(R.drawable.oval_face_guide)
        binding.tvScanBadge.text  = "Searching…"
        binding.tvStatusLabel.text = "READY"
        binding.tvStatusMain.text  = "Place your face inside the oval"
        binding.tvStatusSub.text   = "Keep your face centered and look straight"
        binding.processingDots.visibility = View.GONE
        binding.scanLine.visibility = View.GONE

        scanRunnable = Runnable { showScanningState() }
        handler.postDelayed(scanRunnable!!, 1400)
    }

    private fun showScanningState() {
        binding.faceOval.setImageResource(R.drawable.oval_face_scanning)
        binding.tvScanBadge.text   = "Face detected"
        binding.tvStatusLabel.text = "SCANNING"
        binding.tvStatusMain.text  = "Hold still — scanning…"
        binding.tvStatusSub.text   = "Analyzing facial features"
        binding.scanLine.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(
            requireContext(), R.anim.scan_line
        )
        binding.scanLine.startAnimation(anim)
    }

    private fun showProcessingState() {
        clearScan()
        binding.faceOval.setImageResource(R.drawable.oval_face_scanning)
        binding.tvScanBadge.text   = "Processing…"
        binding.tvStatusLabel.text = "PROCESSING"
        binding.tvStatusMain.text  = "Identifying student…"
        binding.tvStatusSub.text   = "Matching against database"
        binding.processingDots.visibility = View.VISIBLE
        binding.scanLine.visibility = View.GONE
        animateProcessingDots()
    }

    private fun showSuccessState(state: ScanState.Success) {
        binding.faceOval.setImageResource(R.drawable.oval_face_success)
        binding.tvScanBadge.text   = "Recognized ✓"
        binding.tvStatusLabel.text = "SUCCESS"
        binding.tvStatusMain.text  = "Attendance Marked!"
        binding.tvStatusSub.text   = "${state.studentName} — ${state.studentClass}"
        binding.processingDots.visibility = View.GONE
    }

    private fun showFailState() {
        binding.faceOval.setImageResource(R.drawable.oval_face_fail)
        binding.tvScanBadge.text   = "Not Recognized"
        binding.tvStatusLabel.text = "FAILED"
        binding.tvStatusMain.text  = "Face Not Recognized"
        binding.tvStatusSub.text   = "Please try again"
        binding.processingDots.visibility = View.GONE
    }

    private fun clearScan() {
        scanRunnable?.let { handler.removeCallbacks(it) }
        binding.scanLine.clearAnimation()
    }

    // ── PROCESSING DOTS ──────────────────────────────

    private fun animateProcessingDots() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        dots.forEachIndexed { index, dot ->
            handler.postDelayed({
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

    // ── CLICK LISTENERS ──────────────────────────────

    private fun setupClickListeners() {
        binding.btnShutter.setOnClickListener {
            viewModel.processScan()
        }
        binding.btnRetry.setOnClickListener   {
            viewModel.resetScan()
        }
        binding.btnBack.setOnClickListener    {
            clearScan()
            findNavController().popBackStack()
        }
        binding.btnCancel.setOnClickListener  {
            clearScan()
            findNavController().popBackStack()
        }
    }

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()

            preview.setSurfaceProvider(
                binding.cameraPreview.surfaceProvider
            )

            val cameraSelector =
                CameraSelector.DEFAULT_FRONT_CAMERA

            try {

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }     
}
