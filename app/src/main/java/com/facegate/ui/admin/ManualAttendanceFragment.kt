package com.facegate.ui.admin

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentManualAttendanceBinding
import com.facegate.databinding.ItemFilterTabBinding
import com.facegate.databinding.ItemManualAttendanceRowBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ManualAttendanceFragment : Fragment() {

    private var _binding: FragmentManualAttendanceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManualAttendanceViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentManualAttendanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        observeState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is ManualAttendanceState.Loading -> {
                        binding.dayTabRow.removeAllViews()
                        binding.sessionTabRow.removeAllViews()
                        binding.classTabRow.removeAllViews()
                        binding.studentListCol.removeAllViews()
                        binding.tvEmptyMsg.visibility = View.GONE
                    }
                    is ManualAttendanceState.Empty -> {
                        binding.dayTabRow.removeAllViews()
                        binding.sessionTabRow.removeAllViews()
                        binding.classTabRow.removeAllViews()
                        binding.studentListCol.removeAllViews()
                        binding.tvEmptyMsg.text =
                            "No students enrolled yet.\nGo to Students → Enrol to add students."
                        binding.tvEmptyMsg.visibility = View.VISIBLE
                    }
                    is ManualAttendanceState.Loaded -> {
                        binding.tvEmptyMsg.visibility = View.GONE
                        buildDayTabs(state.days, state.selectedDay)
                        buildSessionTabs(state.sessions, state.selectedSession, state.selectedDay.label)
                        buildClassTabs(state.classes, state.selectedClass)
                        buildStudentList(state.students, state.selectedSession?.sessionId)
                    }
                }
            }
        }
    }

    // ── Day tabs ───────────────────────────────────────────────────────────────

    private fun buildDayTabs(days: List<SelectableDay>, selected: SelectableDay) {
        binding.dayTabRow.removeAllViews()
        days.forEach { day ->
            binding.dayTabRow.addView(buildTab(
                container   = binding.dayTabRow,
                label       = day.label,
                isSelected  = day.startOfDay == selected.startOfDay,
                onClick     = { viewModel.selectDay(day) }
            ))
        }
    }

    // ── Session tabs ──────────────────────────────────────────────────────────

    private fun buildSessionTabs(
        sessions: List<com.facegate.storage.entity.SessionEntity>,
        selected: com.facegate.storage.entity.SessionEntity?,
        dayLabel: String,
    ) {
        binding.sessionTabRow.removeAllViews()

        // Defensive de-dupe: if duplicate session rows exist for the same period
        // (same timetable period + subject/batch, started within a minute of each
        // other), only show the most recent one. The real fix is upstream in
        // TodayScheduleViewModel.startSession() reusing an existing session
        // instead of creating a new row every time "Start" is tapped for a period
        // that's already been started — this is just a display-side safety net
        // for any duplicate rows created before that fix.
        val deduped = sessions
            .groupBy { Triple(it.timetableId, it.subject, it.batch) }
            .values
            .map { group -> group.maxByOrNull { it.startTime }!! }
            .sortedBy { it.startTime }

        // "All (selected day)" tab
        val allTab = buildTab(
            container  = binding.sessionTabRow,
            label      = "All — $dayLabel",
            isSelected = selected == null,
            onClick    = { viewModel.selectSession(null) }
        )
        binding.sessionTabRow.addView(allTab)

        deduped.forEach { session ->
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val label   = "${session.subject} · ${session.batch} · ${timeFmt.format(java.util.Date(session.startTime))}"
            binding.sessionTabRow.addView(buildTab(
                container  = binding.sessionTabRow,
                label      = label,
                isSelected = session.sessionId == selected?.sessionId,
                onClick    = { viewModel.selectSession(session) }
            ))
        }
    }

    private fun buildTab(
        container: LinearLayout,
        label: String,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): View {
        val itemBinding = ItemFilterTabBinding.inflate(LayoutInflater.from(requireContext()), container, false)
        itemBinding.tvTabLabel.apply {
            text     = label
            typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(
                if (isSelected) Color.parseColor("#5DA9FF") else Color.parseColor("#90A6BD")
            )
            setBackgroundColor(
                if (isSelected) Color.parseColor("#1A3A5C") else Color.TRANSPARENT
            )
            setOnClickListener { onClick() }
        }
        return itemBinding.root
    }

    // ── Class tabs ────────────────────────────────────────────────────────────

    private fun buildClassTabs(classes: List<String>, selectedClass: String?) {
        binding.classTabRow.removeAllViews()
        classes.forEach { cls ->
            binding.classTabRow.addView(buildTab(
                container  = binding.classTabRow,
                label      = "Class $cls",
                isSelected = cls == selectedClass,
                onClick    = { viewModel.selectClass(cls) }
            ))
        }
    }

    // ── Student rows ──────────────────────────────────────────────────────────

    private fun buildStudentList(students: List<StudentWithStatus>, sessionId: String?) {
        binding.studentListCol.removeAllViews()

        if (students.isEmpty()) {
            binding.tvEmptyMsg.text       = "No students in this class."
            binding.tvEmptyMsg.visibility = View.VISIBLE
            return
        }

        students.forEachIndexed { index, sws ->
            val rowBinding = ItemManualAttendanceRowBinding.inflate(
                LayoutInflater.from(requireContext()), binding.studentListCol, false
            )

            val initials = sws.student.name
                .split(" ")
                .mapNotNull { it.firstOrNull()?.toString() }
                .take(2).joinToString("")

            rowBinding.tvAvatar.text = initials
            rowBinding.tvName.text = sws.student.name
            rowBinding.tvStudentId.text = sws.student.studentId

            val actionBtn: TextView = rowBinding.btnToggleAttendance
            if (sws.markedToday) {
                // Already present — show green chip, tap to undo
                actionBtn.text     = "✓ Present"
                actionBtn.textSize = 12f
                actionBtn.typeface = Typeface.DEFAULT_BOLD
                actionBtn.setTextColor(Color.parseColor("#1D9E75"))
                actionBtn.setBackgroundResource(com.facegate.R.drawable.chip_active)
                actionBtn.setOnClickListener {
                    viewModel.toggleAttendance(sws.student.studentId)
                    Toast.makeText(
                        requireContext(),
                        "${sws.student.name} marked absent",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                // Not yet marked — show "Mark Present" button
                actionBtn.text     = "Mark Present"
                actionBtn.textSize = 11f
                actionBtn.typeface = Typeface.DEFAULT_BOLD
                actionBtn.setTextColor(Color.WHITE)
                actionBtn.setBackgroundResource(com.facegate.R.drawable.badge_green)
                actionBtn.setOnClickListener {
                    viewModel.toggleAttendance(sws.student.studentId)
                    Toast.makeText(
                        requireContext(),
                        "${sws.student.name} marked present",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            binding.studentListCol.addView(rowBinding.root)

            if (index < students.size - 1) {
                binding.studentListCol.addView(View(requireContext()).apply {
                    setBackgroundColor(Color.parseColor("#DCE6F5"))
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