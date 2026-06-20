package com.facegate.ui.admin

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentStudentBinding
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
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Build student row ────────────────────────────────────────────────────

    private fun buildStudentRow(
        student : StudentEntity,
        index   : Int,
        total   : Int,
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(40, 28, 40, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Avatar — initials
        val initials = student.name
            .split(" ")
            .mapNotNull { it.firstOrNull()?.toString() }
            .take(2)
            .joinToString("")

        val avatar = TextView(requireContext()).apply {
            text      = initials
            textSize  = 13f
            typeface  = Typeface.DEFAULT_BOLD
            gravity   = Gravity.CENTER
            setTextColor(Color.parseColor("#1D9E75"))
            setBackgroundResource(R.drawable.chip_active)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { marginEnd = 28 }
        }

        // Info column
        val infoCol = LinearLayout(requireContext()).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(requireContext()).apply {
            text     = student.name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A202C"))
        }

        val subText = TextView(requireContext()).apply {
            text     = "${student.studentId}  •  Class ${student.studentClass}"
            textSize = 11f
            setTextColor(Color.parseColor("#888780"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 4 }
        }

        infoCol.addView(nameText)
        infoCol.addView(subText)

        // Edit button
        val editBtn = TextView(requireContext()).apply {
            text     = "✎"
            textSize = 16f
            gravity  = Gravity.CENTER
            setTextColor(Color.parseColor("#1D9E75"))
            layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 8 }
            isClickable = true
            isFocusable = true
            setOnClickListener { showEditDialog(student) }
        }

        // Delete button
        val deleteBtn = TextView(requireContext()).apply {
            text     = "✕"
            textSize = 14f
            gravity  = Gravity.CENTER
            setTextColor(Color.parseColor("#D85A30"))
            layoutParams = LinearLayout.LayoutParams(64, 64)
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmDelete(student) }
        }

        row.addView(avatar)
        row.addView(infoCol)
        row.addView(editBtn)
        row.addView(deleteBtn)
        binding.studentListContainer.addView(row)

        // Divider except last
        if (index < total - 1) {
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#0F000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = 40; marginEnd = 40 }
            }
            binding.studentListContainer.addView(divider)
        }
    }

    // ── Edit dialog ──────────────────────────────────────────────────────────

    private fun showEditDialog(student: StudentEntity) {
        val ctx = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding * 2, padding, padding * 2, padding)
        }

        val etRollNo = EditText(ctx).apply {
            hint = "Roll number"
            setText(student.studentId)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = padding }
        }

        val etName = EditText(ctx).apply {
            hint = "Full name"
            setText(student.name)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = padding }
        }

        val etClass = EditText(ctx).apply {
            hint = "Class (e.g. 10A)"
            setText(student.studentClass)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Informational note so admin knows face data is safe
        val tvNote = TextView(ctx).apply {
            text     = "ℹ Face embedding is preserved — attendance history follows the new roll number."
            textSize = 11f
            setTextColor(Color.parseColor("#888780"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding }
        }

        container.addView(etRollNo)
        container.addView(etName)
        container.addView(etClass)
        container.addView(tvNote)

        AlertDialog.Builder(ctx)
            .setTitle("Edit Student")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newRollNo = etRollNo.text.toString().trim()
                val newName   = etName.text.toString().trim()
                val newClass  = etClass.text.toString().trim()
                if (newRollNo.isNotEmpty() && newName.isNotEmpty()) {
                    viewModel.updateStudentRollNo(student.studentId, newRollNo, newName, newClass)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun showMessage(msg: String) {
        val tv = TextView(requireContext()).apply {
            text      = msg
            textSize  = 14f
            gravity   = Gravity.CENTER
            setTextColor(Color.parseColor("#888780"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 80 }
        }
        binding.studentListContainer.addView(tv)
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