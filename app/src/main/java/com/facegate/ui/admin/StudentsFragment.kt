package com.facegate.ui.admin

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
        itemBinding.tvSubInfo.text = "${student.studentId}  •  Class ${student.studentClass}"
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
        dialogBinding.etRollNo.setText(student.studentId)
        dialogBinding.etName.setText(student.name)
        dialogBinding.etClass.setText(student.studentClass)

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
            val newName   = dialogBinding.etName.text.toString().trim()
            val newClass  = dialogBinding.etClass.text.toString().trim()
            if (newRollNo.isNotEmpty() && newName.isNotEmpty()) {
                viewModel.updateStudentRollNo(student.studentId, newRollNo, newName, newClass)
                dialog.dismiss()
            }
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