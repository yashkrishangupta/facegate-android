package com.facegate.ui.enrollment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentEnrollmentBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


@AndroidEntryPoint
class EnrollmentFragment : Fragment() {

    private var _binding: FragmentEnrollmentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EnrollmentViewModel by viewModels()

    // ── Camera ───────────────────────────────────────────────────────────────

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // ── Photo tracking ───────────────────────────────────────────────────────

    // Driven by ViewModel now; we just mirror capturedCount() for UI
    private val totalPhotos = 5

    private val photoDots by lazy {
        listOf(
            binding.photo1dot,
            binding.photo2dot,
            binding.photo3dot,
            binding.photo4dot,
            binding.photo5dot
        )
    }

    // ── Permission launcher ──────────────────────────────────────────────────

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(
                    requireContext(),
                    "Camera permission is required to enroll a student.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

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

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        setupClickListeners()
        observeViewModel()
        updatePhotoUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }

    // ── Camera setup ─────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    /**
     * Takes a photo via ImageCapture and hands the Bitmap to the ViewModel.
     * When all 5 are taken, prompts for student info before enrolling.
     */
    private fun capturePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val bitmap = image.toBitmap()
                    image.close()

                    // Post UI work back to main thread
                    binding.root.post {
                        val done = viewModel.capturePhoto(bitmap, rotationDegrees)
                        updatePhotoUI()
                        if (done) promptStudentInfo()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    binding.root.post {
                        Toast.makeText(
                            requireContext(),
                            "Photo capture failed: ${exc.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // ── Student info dialog ───────────────────────────────────────────────────

    /**
     * Inflates dialog_student_info.xml and collects name / ID / class
     * before handing off to the ViewModel for enrollment.
     * Called once all 5 photos are captured.
     */
    private fun promptStudentInfo() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_student_info, null)

        val etName  = dialogView.findViewById<EditText>(R.id.etStudentName)
        val etId    = dialogView.findViewById<EditText>(R.id.etStudentId)
        val etClass = dialogView.findViewById<EditText>(R.id.etStudentClass)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Enroll", null) // set null here, override below for validation
            .setNegativeButton("Cancel") { _, _ ->
                viewModel.reset()
                updatePhotoUI()
            }
            .setCancelable(false)
            .create()

        // Override positive button to validate before dismissing
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name  = etName.text.toString().trim()
                val id    = etId.text.toString().trim()
                val cls   = etClass.text.toString().trim()

                when {
                    name.isEmpty()  -> etName.error  = "Required"
                    id.isEmpty()    -> etId.error    = "Required"
                    cls.isEmpty()   -> etClass.error = "Required"
                    else -> {
                        dialog.dismiss()
                        viewModel.enrollStudent(name, id, cls)
                    }
                }
            }
        }

        dialog.show()
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updatePhotoUI() {
        val photoCount = viewModel.capturedCount()

        binding.tvPhotoCount.text = if (photoCount < totalPhotos) {
            "PHOTO ${photoCount + 1} OF $totalPhotos"
        } else {
            "ALL PHOTOS CAPTURED"
        }

        binding.tvInstMain.text = when (photoCount) {
            0 -> "Look straight at the camera"
            1 -> "Turn slightly to the left"
            2 -> "Turn slightly to the right"
            3 -> "Tilt your head slightly up"
            4 -> "Tilt your head slightly down"
            else -> "Processing enrollment…"
        }

        binding.tvInstSub.text = when {
            photoCount >= totalPhotos -> "Saving your face data securely…"
            else -> "We need $totalPhotos photos from different angles"
        }

        photoDots.forEachIndexed { index, dot ->
            dot.animate()
                .alpha(if (index < photoCount) 1.0f else 0.3f)
                .setDuration(300)
                .start()
        }
    }

    // ── ViewModel observer ────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.enrollmentState.collect { state ->
                when (state) {
                    is EnrollmentState.Success -> {
                        Toast.makeText(requireContext(), "Student enrolled successfully!", Toast.LENGTH_SHORT).show()
                        findNavController().navigateUp()
                    }

                    is EnrollmentState.DuplicateFace -> {
                        binding.tvDuplicateWarning.visibility = View.VISIBLE
                        viewModel.reset()
                        updatePhotoUI()
                    }

                    is EnrollmentState.Failed -> {
                        binding.tvDuplicateWarning.visibility = View.GONE
                        binding.tvInstSub.text = state.reason
                        viewModel.reset()
                        updatePhotoUI()
                    }

                    is EnrollmentState.Processing -> {
                        binding.tvInstMain.text = "Processing enrollment…"
                        binding.btnCapture.isClickable = false
                    }

                    is EnrollmentState.Capturing, is EnrollmentState.Idle -> {
                        binding.btnCapture.isClickable = true
                        binding.tvDuplicateWarning.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}