package com.facegate.ui.enrollment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentEnrollmentBinding
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

/**
 * ENROLLMENT FRAGMENT
 * Admin screen to capture and enroll a new student face
 * Captures 5 photos from different angles
 * Matches: enrollment flow in project blueprint
 */
@AndroidEntryPoint
class EnrollmentFragment : Fragment() {

    private var _binding: FragmentEnrollmentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EnrollmentViewModel by viewModels()

    // Track how many photos captured
    // 5 photos needed for good enrollment
    private var photoCount = 0
    private val totalPhotos = 5

    // Photo dots for UI tracking
    private val photoDots by lazy {
        listOf(
            binding.photo1dot,
            binding.photo2dot,
            binding.photo3dot,
            binding.photo4dot,
            binding.photo5dot
        )
    }

    // ── LIFECYCLE ────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnrollmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        observeViewModel()
        updatePhotoUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── PHOTO CAPTURE ────────────────────────────────

    /**
     * Called when shutter button is pressed
     * Captures one photo and advances the counter
     */
    private fun capturePhoto() {
        if (photoCount >= totalPhotos) return

        // Tell ViewModel to capture
        viewModel.capturePhoto(photoCount)

        // Advance count
        photoCount++
        updatePhotoUI()

        // Check if all 5 done
        if (photoCount == totalPhotos) {
            onAllPhotosCaptured()
        }
    }

    /**
     * Updates the dot indicators and instruction text
     * after each photo capture
     */
    private fun updatePhotoUI() {
        // Update instruction text
        binding.tvPhotoCount.text = if (photoCount < totalPhotos) {
            "PHOTO ${photoCount + 1} OF $totalPhotos"
        } else {
            "ALL PHOTOS CAPTURED"
        }

        // Update instruction per photo
        binding.tvInstMain.text = when (photoCount) {
            0 -> "Look straight at the camera"
            1 -> "Turn slightly to the left"
            2 -> "Turn slightly to the right"
            3 -> "Tilt your head slightly up"
            4 -> "Tilt your head slightly down"
            else -> "Processing enrollment…"
        }

        binding.tvInstSub.text = when (photoCount) {
            totalPhotos -> "Saving your face data securely…"
            else -> "We need $totalPhotos photos from different angles"
        }

        // Illuminate dots up to current count
        photoDots.forEachIndexed { index, dot ->
            dot.animate()
                .alpha(if (index < photoCount) 1.0f else 0.3f)
                .setDuration(300)
                .start()
        }
    }

    /**
     * Called when all 5 photos are captured
     */
    private fun onAllPhotosCaptured() {
        viewModel.enrollStudent()
    }

    // ── VIEWMODEL OBSERVER ───────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.enrollmentState.collect { state ->
                when (state) {
                    is EnrollmentState.Success -> {
                        // Go back to dashboard
                        requireActivity().supportFragmentManager.popBackStack()
                    }
                    is EnrollmentState.DuplicateFace -> {
                        // Show duplicate warning
                        binding.tvDuplicateWarning.visibility = View.VISIBLE
                        // Reset to try again
                        photoCount = 0
                        updatePhotoUI()
                    }
                    is EnrollmentState.Failed -> {
                        binding.tvInstSub.text = "Enrollment failed. Please try again."
                        photoCount = 0
                        updatePhotoUI()
                    }
                    else -> {}
                }
            }
        }
    }

    // ── CLICK LISTENERS ──────────────────────────────

    private fun setupClickListeners() {

        // Capture button → take photo
        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }

        // Back button → go back
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}