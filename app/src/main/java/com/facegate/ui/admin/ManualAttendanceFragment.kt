package com.facegate.ui.admin

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ManualAttendanceFragment : Fragment() {

    private val viewModel: ManualAttendanceViewModel by viewModels()

    private lateinit var classTabRow    : LinearLayout
    private lateinit var studentListCol : LinearLayout
    private lateinit var tvEmptyMsg     : TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F4F8"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Top bar ──────────────────────────────────────────────────────────
        val topBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val btnBack = TextView(requireContext()).apply {
            text     = "← Back"
            textSize = 13f
            gravity  = Gravity.CENTER
            setTextColor(Color.parseColor("#444441"))
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundResource(com.facegate.R.drawable.icon_action_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply { marginEnd = dp(14) }
            setOnClickListener { findNavController().navigateUp() }
        }

        val tvTitle = TextView(requireContext()).apply {
            text     = "Manual Attendance"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A202C"))
        }

        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        // ── Class tabs ────────────────────────────────────────────────────────
        val tabScroll = HorizontalScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setBackgroundColor(Color.WHITE)
        }

        classTabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        tabScroll.addView(classTabRow)
        root.addView(tabScroll)

        // ── Student list ──────────────────────────────────────────────────────
        val scrollView = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val listCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }
        }

        tvEmptyMsg = TextView(requireContext()).apply {
            text       = "No students found"
            textSize   = 14f
            gravity    = Gravity.CENTER
            visibility = View.GONE
            setTextColor(Color.parseColor("#888780"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(48) }
        }

        studentListCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        listCard.addView(tvEmptyMsg)
        listCard.addView(studentListCol)
        scrollView.addView(listCard)
        root.addView(scrollView)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is ManualAttendanceState.Loading -> {
                        classTabRow.removeAllViews()
                        studentListCol.removeAllViews()
                        tvEmptyMsg.visibility = View.GONE
                    }
                    is ManualAttendanceState.Empty -> {
                        classTabRow.removeAllViews()
                        studentListCol.removeAllViews()
                        tvEmptyMsg.text       = "No students enrolled yet.\nGo to Students → Enrol to add students."
                        tvEmptyMsg.visibility = View.VISIBLE
                    }
                    is ManualAttendanceState.Loaded -> {
                        tvEmptyMsg.visibility = View.GONE
                        buildClassTabs(state.classes, state.selectedClass)
                        buildStudentList(state.students)
                    }
                }
            }
        }
    }

    // ── Class tabs ────────────────────────────────────────────────────────────

    private fun buildClassTabs(classes: List<String>, selectedClass: String?) {
        classTabRow.removeAllViews()
        classes.forEach { cls ->
            val isSelected = cls == selectedClass
            val tab = TextView(requireContext()).apply {
                text     = "Class $cls"
                textSize = 12f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity  = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(
                    if (isSelected) Color.parseColor("#1D9E75") else Color.parseColor("#888780")
                )
                setBackgroundColor(
                    if (isSelected) Color.parseColor("#E6F7F2") else Color.TRANSPARENT
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener { viewModel.selectClass(cls) }
            }
            classTabRow.addView(tab)
        }
    }

    // ── Student rows ──────────────────────────────────────────────────────────

    private fun buildStudentList(students: List<StudentWithStatus>) {
        studentListCol.removeAllViews()

        if (students.isEmpty()) {
            tvEmptyMsg.text       = "No students in this class."
            tvEmptyMsg.visibility = View.VISIBLE
            return
        }

        students.forEachIndexed { index, sws ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            // Initials avatar
            val initials = sws.student.name
                .split(" ")
                .mapNotNull { it.firstOrNull()?.toString() }
                .take(2).joinToString("")

            val avatar = TextView(requireContext()).apply {
                text     = initials
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity  = Gravity.CENTER
                setTextColor(Color.parseColor("#1D9E75"))
                setBackgroundResource(com.facegate.R.drawable.chip_active)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) }
            }

            // Name + ID column
            val infoCol = LinearLayout(requireContext()).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvName = TextView(requireContext()).apply {
                text     = sws.student.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A202C"))
            }

            val tvId = TextView(requireContext()).apply {
                text     = sws.student.studentId
                textSize = 11f
                setTextColor(Color.parseColor("#888780"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) }
            }

            infoCol.addView(tvName)
            infoCol.addView(tvId)

            // ── Toggle button: "✓ Present" (tap to mark absent) or "Mark Present" ──
            val actionBtn = TextView(requireContext()).apply {
                gravity     = Gravity.CENTER
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (sws.markedToday) {
                    // Already present — show green chip, tap to undo
                    text     = "✓ Present"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#1D9E75"))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setBackgroundResource(com.facegate.R.drawable.chip_active)
                    setOnClickListener {
                        viewModel.toggleAttendance(sws.student.studentId)
                        Toast.makeText(
                            requireContext(),
                            "${sws.student.name} marked absent",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    // Not yet marked — show "Mark Present" button
                    text     = "Mark Present"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setBackgroundResource(com.facegate.R.drawable.badge_green)
                    setOnClickListener {
                        viewModel.toggleAttendance(sws.student.studentId)
                        Toast.makeText(
                            requireContext(),
                            "${sws.student.name} marked present",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }

            row.addView(avatar)
            row.addView(infoCol)
            row.addView(actionBtn)
            studentListCol.addView(row)

            if (index < students.size - 1) {
                studentListCol.addView(View(requireContext()).apply {
                    setBackgroundColor(Color.parseColor("#0F000000"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = dp(20); marginEnd = dp(20) }
                })
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
