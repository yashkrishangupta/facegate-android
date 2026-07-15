package com.facegate.ui.admin

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.DialogStudentEditBinding
import com.facegate.databinding.FragmentStudentBinding
import com.facegate.databinding.ItemEmptyMessageBinding
import com.facegate.databinding.ItemStudentRowBinding
import com.facegate.storage.entity.StudentEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class StudentsFragment : Fragment() {

    private var _binding: FragmentStudentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StudentsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStudentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeStudents()
        observeErrors()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadStudents()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Observe DB ───────────────────────────────────────────────────────────

    private fun observeStudents() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.studentListContainer.removeAllViews()
                when (state) {
                    is StudentsState.Loading -> showMessage("Loading students…")
                    is StudentsState.Empty   -> showMessage("No students enrolled yet.\nTap + to enrol a student.")
                    is StudentsState.Loaded  -> {
                        updateStudentCount(state.students.size)
                        state.students.forEachIndexed { index, student ->
                            buildStudentRow(student, index, state.students.size)
                        }
                    }
                }
            }
        }
    }

    // ── Observe one-shot error events (e.g. duplicate roll number) ───────────

    private fun observeErrors() {
        lifecycleScope.launch {
            viewModel.errorEvents.collect { message ->
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Build student row ────────────────────────────────────────────────────

    private fun buildStudentRow(
        student : StudentEntity,
        index   : Int,
        total   : Int,
    ) {
        val itemBinding = ItemStudentRowBinding.inflate(
            LayoutInflater.from(requireContext()), binding.studentListContainer, false
        )

        val initials = student.name
            .split(" ")
            .mapNotNull { it.firstOrNull()?.toString() }
            .take(2)
            .joinToString("")

        itemBinding.tvAvatar.text = initials
        itemBinding.tvName.text = student.name
        val displayRoll = student.rollNumber.ifBlank { student.studentId }
        itemBinding.tvSubInfo.text = "Roll No. $displayRoll  •  Class ${student.studentClass}"

        val isPending = student.enrollmentStatus == "PENDING"

        // Always-visible status — was previously a badge that only appeared
        // for pending rows, easy to miss. Now every row clearly shows where
        // it stands.
        if (isPending) {
            itemBinding.tvEnrollmentStatus.text = "⏳ NEEDS ENROLLMENT"
            itemBinding.tvEnrollmentStatus.setTextColor(Color.parseColor("#BA7517")) // Amber
        } else {
            itemBinding.tvEnrollmentStatus.text = "✓ ENROLLED"
            itemBinding.tvEnrollmentStatus.setTextColor(Color.parseColor("#3B6D11")) // Green
        }

        // Dedicated button (instead of relying on a whole-row tap, which was
        // easy to miss and clashed with the edit/delete icons) — takes a
        // pending student straight into the enrollment capture flow,
        // pre-filled with the details already synced from the website.
        if (isPending) {
            itemBinding.btnCompleteEnrollment.visibility = View.VISIBLE
            itemBinding.btnCompleteEnrollment.setOnClickListener {
                findNavController().navigate(
                    StudentsFragmentDirections.actionStudentsToEnrollment(
                        studentId    = student.studentId,
                        studentName  = student.name,
                        studentClass = student.studentClass,
                    )
                )
            }
        } else {
            itemBinding.btnCompleteEnrollment.visibility = View.GONE
        }

        itemBinding.btnEdit.setOnClickListener { showEditDialog(student) }
        itemBinding.btnDelete.setOnClickListener { confirmDelete(student) }

        binding.studentListContainer.addView(itemBinding.root)

        // Divider except last
        if (index < total - 1) {
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#0F000000"))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = dp(14); marginEnd = dp(14) }
            }
            binding.studentListContainer.addView(divider)
        }
    }

    // ── Edit dialog ──────────────────────────────────────────────────────────

    private fun showEditDialog(student: StudentEntity) {
        val dialogBinding = DialogStudentEditBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.etRollNo.setText(student.rollNumber.ifBlank { student.studentId })
        dialogBinding.etRegistrationNumber.setText(student.registrationNumber)
        dialogBinding.etName.setText(student.name)
        dialogBinding.etClass.setText(student.studentClass)
        dialogBinding.etEmail.setText(student.email ?: "")
        dialogBinding.etPhone.setText(student.phone ?: "")
        dialogBinding.etDateOfBirth.setText(student.dateOfBirth ?: "")
        dialogBinding.etProfilePhotoUrl.setText(student.profilePhotoUrl ?: "")
        when (student.gender) {
            "Female" -> dialogBinding.rgGender.check(dialogBinding.rbFemale.id)
            "Other"  -> dialogBinding.rgGender.check(dialogBinding.rbOther.id)
            else     -> dialogBinding.rgGender.check(dialogBinding.rbMale.id)
        }

        // DOB is stored as "yyyy-MM-dd" (matches the backend's DATE column
        // and what mergeStudent/pushPendingEnrollments already read/write) —
        // a plain non-editable EditText that opens a DatePickerDialog on tap
        // keeps that format guaranteed correct rather than trusting free-text
        // entry.
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        dialogBinding.etDateOfBirth.setOnClickListener {
            val calendar = Calendar.getInstance()
            student.dateOfBirth?.let { existing ->
                try { calendar.time = dateFormat.parse(existing) ?: calendar.time } catch (_: Exception) { }
            }
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    dialogBinding.etDateOfBirth.setText(dateFormat.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.maxDate = System.currentTimeMillis() // no future birthdates
            }.show()
        }

        // NOTE: no setPositiveButton/setNegativeButton here — the dialog layout
        // already supplies its own styled Cancel/Save buttons (btnCancel /
        // btnSave), matching the dialog_student_info.xml pattern used across
        // the app's other custom dialogs.
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener {
            val newRollNo = dialogBinding.etRollNo.text.toString().trim()
            val newRegNo  = dialogBinding.etRegistrationNumber.text.toString().trim()
            val newName   = dialogBinding.etName.text.toString().trim()
            val newClass  = dialogBinding.etClass.text.toString().trim()
            val newEmail  = dialogBinding.etEmail.text.toString().trim()
            val newPhone  = dialogBinding.etPhone.text.toString().trim()
            val newDob    = dialogBinding.etDateOfBirth.text.toString().trim()
            val newPhotoUrl = dialogBinding.etProfilePhotoUrl.text.toString().trim()
            val newGender = when (dialogBinding.rgGender.checkedRadioButtonId) {
                dialogBinding.rbFemale.id -> "Female"
                dialogBinding.rbOther.id  -> "Other"
                else                      -> "Male"
            }

            if (newRollNo.isEmpty()) {
                dialogBinding.etRollNo.error = "Required"
                return@setOnClickListener
            }
            if (newName.isEmpty()) {
                dialogBinding.etName.error = "Required"
                return@setOnClickListener
            }

            viewModel.updateStudentInfo(
                studentId = student.studentId,
                name = newName,
                studentClass = newClass,
                rollNumber = newRollNo,
                registrationNumber = newRegNo.ifEmpty { newRollNo },
                gender = newGender,
                email = newEmail.ifEmpty { null },
                phone = newPhone.ifEmpty { null },
                dateOfBirth = newDob.ifEmpty { null },
                profilePhotoUrl = newPhotoUrl.ifEmpty { null },
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDelete(student: StudentEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove student?")
            .setMessage("Remove ${student.name} from the system? This cannot be undone.")
            .setPositiveButton("Remove") { _, _ -> viewModel.deleteStudent(student.studentId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStudentCount(count: Int) {
        binding.tvStudentCount.text = "$count students enrolled"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showMessage(msg: String) {
        val itemBinding = ItemEmptyMessageBinding.inflate(
            LayoutInflater.from(requireContext()), binding.studentListContainer, false
        )
        itemBinding.tvMessage.text = msg
        binding.studentListContainer.addView(itemBinding.root)
    }

    // ── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnEnrollNew.setOnClickListener {
            findNavController().navigate(R.id.action_students_to_enrollment)
        }
    }
}