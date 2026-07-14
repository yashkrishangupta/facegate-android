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
import androidx.navigation.fragment.navArgs
import com.facegate.R
import com.facegate.databinding.FragmentEnrollmentBinding
import dagger.hilt.android.AndroidEntryPoint
import com.facegate.ui.enrollment.EnrollmentEvent
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.facegate.pipeline.PipelineConfig   


@AndroidEntryPoint
class EnrollmentFragment : Fragment() {

    private var _binding: FragmentEnrollmentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EnrollmentViewModel by viewModels()
    private val args by navArgs<EnrollmentFragmentArgs>()

    /** True when we arrived here from the Students list to finish a PENDING enrollment. */
    private val isCompletingPendingStudent: Boolean
        get() = args.studentId.isNotBlank()

    // ── Camera ───────────────────────────────────────────────────────────────

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val totalPhotos = PipelineConfig.ENROLLMENT_SHOT_COUNT

    private val photoDots by lazy {
        listOf(
            binding.photo1dot,
            binding.photo2dot,
            binding.photo3dot,
            binding.photo4dot,
            binding.photo5dot
        )
    }

    // Guard: don't show the info dialog more than once if state fires twice
    private var infoDialogShown = false

    // ── Permission launcher ──────────────────────────────────────────────────

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(
                requireContext(),
                "Camera permission is required to enroll a student.",
                Toast.LENGTH_LONG
            ).show()
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

        setupClickListeners()
        observeViewModel()
        updatePhotoUI()

        binding.btnCapture.isClickable = false

        if (isCompletingPendingStudent) {
            // Details already known from the website sync (roll number,
            // registration number, gender, etc. — see loadExistingStudentInfo) —
            // go straight to capture instead of re-prompting for details the
            // admin already entered on the website.
            viewLifecycleOwner.lifecycleScope.launch {
                val loaded = viewModel.loadExistingStudentInfo(args.studentId)
                infoDialogShown = true
                if (loaded) {
                    startEnrollmentCapture()
                } else {
                    // Shouldn't happen — navigated here from this exact row —
                    // but fall back to manual entry rather than getting stuck.
                    promptStudentInfo()
                }
            }
        } else {
            // Brand-new student — collect details FIRST. Camera + capture button
            // stay disabled until the dialog is confirmed (see promptStudentInfo()).
            promptStudentInfo()
        }
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

    private fun capturePhoto() {
        val capture = imageCapture ?: return

        // Disable button while the shutter + quality check is in progress
        binding.btnCapture.isClickable = false

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val bitmap = image.toBitmap()
                    image.close()

                    binding.root.post {
                        viewModel.capturePhoto(bitmap, rotationDegrees)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    binding.root.post {
                        binding.btnCapture.isClickable = true
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

    // ── Student info dialog (shown FIRST, before any photo is taken) ───────────

    private fun promptStudentInfo() {
        if (infoDialogShown) return
        infoDialogShown = true

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_student_info, null)

        val etName    = dialogView.findViewById<EditText>(R.id.etStudentName)
        val etId      = dialogView.findViewById<EditText>(R.id.etStudentId)
        val etRegNo   = dialogView.findViewById<EditText>(R.id.etRegistrationNumber)
        val etClass   = dialogView.findViewById<EditText>(R.id.etStudentClass)
        val rgGender  = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgGender)
        val etYear    = dialogView.findViewById<EditText>(R.id.etAdmissionYear)
        val etEmail   = dialogView.findViewById<EditText>(R.id.etEmail)
        val etPhone   = dialogView.findViewById<EditText>(R.id.etPhone)
        val btnCancel   = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btnContinue)

        // Prefill with the current year — the common case — so admins usually
        // only need to touch this field for older re-admissions.
        etYear.setText(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString())

        // NOTE: no setPositiveButton/setNegativeButton here — the dialog layout
        // already supplies its own styled Cancel/Continue buttons (btnCancel /
        // btnContinue). Adding AlertDialog's built-in buttons on top of those
        // produced two "Continue" buttons stacked in the dialog, with the
        // default (unstyled) one rendering outside the intended UI bounds.
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            // No photos have been taken yet at this point — just leave enrollment.
            dialog.dismiss()
            findNavController().navigateUp()
        }

        btnContinue.setOnClickListener {
            val name     = etName.text.toString().trim()
            val rollNo   = etId.text.toString().trim()
            val regNo    = etRegNo.text.toString().trim()
            val cls      = etClass.text.toString().trim()
            val yearText = etYear.text.toString().trim()
            val email    = etEmail.text.toString().trim()
            val phone    = etPhone.text.toString().trim()
            val gender = when (rgGender.checkedRadioButtonId) {
                R.id.rbFemale -> "Female"
                R.id.rbOther  -> "Other"
                else          -> "Male"
            }

            when {
                name.isEmpty()     -> etName.error = "Required"
                rollNo.isEmpty()   -> etId.error    = "Required"
                cls.isEmpty()      -> etClass.error = "Required"
                yearText.isEmpty() || yearText.toIntOrNull() == null ->
                    etYear.error = "Enter a valid year"
                else -> {
                    viewModel.setStudentInfo(
                        com.facegate.pipeline.StudentEnrollmentInfo(
                            name = name,
                            rollNumber = rollNo,
                            registrationNumber = regNo.ifEmpty { rollNo },
                            studentClass = cls,
                            gender = gender,
                            admissionYear = yearText.toInt(),
                            email = email.ifEmpty { null },
                            phone = phone.ifEmpty { null },
                        )
                    )
                    dialog.dismiss()
                    startEnrollmentCapture()
                }
            }
        }

        dialog.show()
    }

    /** Called once student details are confirmed — now we open the camera for the 5 photos. */
    private fun startEnrollmentCapture() {
        binding.btnCapture.isClickable = true
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
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
            else -> "We need $totalPhotos clear photos from different angles"
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
        // ── One-shot shot events (never dropped) ─────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is EnrollmentEvent.ShotAccepted -> {
                        binding.btnCapture.isClickable = true
                        updatePhotoUI()
                    }
                    is EnrollmentEvent.ShotRejected -> {
                        binding.btnCapture.isClickable = true
                        binding.tvInstSub.text = "⚠ ${event.reason} — retake this shot"
                        Toast.makeText(requireContext(), event.reason, Toast.LENGTH_SHORT).show()
                        updatePhotoUI()
                    }
                }
            }
        }

        // ── Persistent state ──────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.enrollmentState.collect { state ->
                when (state) {

                    is EnrollmentState.Idle -> {
                        // Don't force-enable here: on first load the button is
                        // deliberately disabled until startEnrollmentCapture() runs
                        // (after the student-details dialog is confirmed).
                        binding.tvDuplicateWarning.visibility = View.GONE
                        updatePhotoUI()
                    }

                    is EnrollmentState.Processing -> {
                        // All 5 photos passed quality — submitting under the details
                        // already collected via promptStudentInfo() at the start.
                        binding.btnCapture.isClickable = false
                        updatePhotoUI()
                    }

                    is EnrollmentState.Success -> {
                        Toast.makeText(
                            requireContext(),
                            "Student enrolled successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                        findNavController().navigateUp()
                    }

                    is EnrollmentState.DuplicateFace -> {
                        infoDialogShown = false
                        binding.tvDuplicateWarning.visibility = View.GONE
                        showDuplicateDialog(state.existingName)
                    }

                    is EnrollmentState.Failed -> {
                        infoDialogShown = false
                        binding.tvDuplicateWarning.visibility = View.GONE
                        binding.tvInstSub.text = state.reason
                        binding.btnCapture.isClickable = true
                        viewModel.reset()
                        updatePhotoUI()
                    }
                }
            }
        }
    }


    // ── Duplicate face dialog ─────────────────────────────────────────────────

    private fun showDuplicateDialog(existingName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Possible Duplicate Face")
            .setMessage(
                "This face closely matches an already enrolled student: $existingName.\n\n" +
                        "Are you sure this is a different person and want to enroll them anyway?"
            )
            .setPositiveButton("Enroll Anyway") { _, _ ->
                // User confirmed — force-enroll by calling enroll directly with a flag
                viewModel.forceEnroll()
            }
            .setNegativeButton("Cancel — Retake") { _, _ ->
                viewModel.reset()
                binding.btnCapture.isClickable = true
                updatePhotoUI()
            }
            .setCancelable(false)
            .show()
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnCapture.setOnClickListener { capturePhoto() }
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
    }
}