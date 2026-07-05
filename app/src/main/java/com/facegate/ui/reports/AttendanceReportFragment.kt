package com.facegate.ui.reports

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentAttendanceReportBinding
import com.facegate.databinding.ItemReportStudentRowBinding
import kotlinx.coroutines.launch
import java.util.Calendar
import dagger.hilt.android.AndroidEntryPoint

/**
 * Reports is a **read-only** viewer — it never marks or unmarks attendance.
 * Editing only happens in Manual Attendance (today + past 6 days). This screen
 * shows the last 30 days for reference; anything older is meant to be pulled
 * from the website instead of this app.
 */
@AndroidEntryPoint
class AttendanceReportFragment : Fragment() {

    private var _binding: FragmentAttendanceReportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReportViewModel by viewModels()

    // Guards against the Spinner's onItemSelected firing (and re-triggering a
    // reload) when we're the ones repopulating it, not the user tapping it.
    private var suppressSpinnerCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAttendanceReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is ExplorerState.Loading -> {
                        binding.scrollView.visibility      = View.GONE
                        binding.tvStatusMessage.visibility  = View.GONE
                    }
                    is ExplorerState.Holiday -> {
                        binding.tvDateLabel.text = state.dateLabel
                        binding.scrollView.visibility     = View.GONE
                        binding.tvStatusMessage.visibility = View.VISIBLE
                        binding.tvStatusMessage.text = "Holiday — ${state.holidayName}"
                        clearSpinner(binding.spinnerPeriod)
                        clearSpinner(binding.spinnerBatch)
                    }
                    is ExplorerState.NoSchedule -> {
                        binding.tvDateLabel.text = state.dateLabel
                        binding.scrollView.visibility     = View.GONE
                        binding.tvStatusMessage.visibility = View.VISIBLE
                        binding.tvStatusMessage.text = "No periods scheduled for this day"
                        clearSpinner(binding.spinnerPeriod)
                        clearSpinner(binding.spinnerBatch)
                    }
                    is ExplorerState.Loaded -> {
                        binding.tvDateLabel.text = state.dateLabel
                        binding.btnNextDate.alpha     = if (state.canGoForward) 1f else 0.35f
                        binding.btnNextDate.isEnabled = state.canGoForward
                        binding.btnPrevDate.alpha     = if (state.canGoBack) 1f else 0.35f
                        binding.btnPrevDate.isEnabled = state.canGoBack

                        populatePeriodSpinner(state.periods, state.selectedPeriod)
                        populateBatchSpinner(state.batches, state.selectedBatch)

                        if (state.students.isEmpty()) {
                            binding.scrollView.visibility     = View.GONE
                            binding.tvStatusMessage.visibility = View.VISIBLE
                            binding.tvStatusMessage.text = "No students in this batch"
                        } else {
                            binding.tvStatusMessage.visibility = View.GONE
                            binding.scrollView.visibility     = View.VISIBLE
                            buildStudentList(state.students)
                        }
                    }
                }
            }
        }
    }

    // ── Spinners ──────────────────────────────────────────────────────────────

    private fun populatePeriodSpinner(periods: List<PeriodOption>, selected: Int?) {
        suppressSpinnerCallback = true
        binding.spinnerPeriod.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            periods.map { it.label }
        )
        val idx = periods.indexOfFirst { it.periodNumber == selected }
        if (idx >= 0) binding.spinnerPeriod.setSelection(idx)
        binding.spinnerPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerCallback) { suppressSpinnerCallback = false; return }
                viewModel.selectPeriod(periods[pos].periodNumber)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun populateBatchSpinner(batches: List<BatchOption>, selected: String?) {
        suppressSpinnerCallback = true
        binding.spinnerBatch.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            batches.map { it.label }
        )
        val idx = batches.indexOfFirst { it.batch == selected }
        if (idx >= 0) binding.spinnerBatch.setSelection(idx)
        binding.spinnerBatch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerCallback) { suppressSpinnerCallback = false; return }
                viewModel.selectBatch(batches[pos].batch)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun clearSpinner(spinner: android.widget.Spinner) {
        spinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, emptyList<String>()
        )
    }

    // ── Student list (read-only) ────────────────────────────────────────────

    private fun buildStudentList(students: List<RosterEntry>) {
        binding.studentListCol.removeAllViews()
        students.forEachIndexed { index, entry ->
            binding.studentListCol.addView(buildStudentRow(entry))
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

    private fun buildStudentRow(entry: RosterEntry): View {
        val rowBinding = ItemReportStudentRowBinding.inflate(
            LayoutInflater.from(requireContext()), binding.studentListCol, false
        )

        val initials = entry.student.name
            .split(" ")
            .mapNotNull { it.firstOrNull()?.toString() }
            .take(2).joinToString("")

        rowBinding.tvAvatar.text = initials
        rowBinding.tvName.text = entry.student.name
        rowBinding.tvStudentId.text = entry.student.studentId

        if (entry.marked) {
            rowBinding.tvStatusBadge.text = "Present"
            rowBinding.tvStatusBadge.setTextColor(Color.parseColor("#1D9E75"))
            rowBinding.tvStatusBadge.setBackgroundResource(com.facegate.R.drawable.chip_active)
        } else {
            rowBinding.tvStatusBadge.text = "Absent"
            rowBinding.tvStatusBadge.setTextColor(Color.parseColor("#E24B4A"))
            rowBinding.tvStatusBadge.setBackgroundResource(com.facegate.R.drawable.chip_pending)
        }

        return rowBinding.root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnPrevDate.setOnClickListener { viewModel.stepDate(-1) }
        binding.btnNextDate.setOnClickListener { viewModel.stepDate(+1) }
        binding.tvDateLabel.setOnClickListener { showDatePicker() }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        val dialog = DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                }
                viewModel.selectDate(picked.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        )
        // Reports only ever shows the last 30 days — keep the picker itself
        // from offering dates outside that window rather than silently
        // clamping after the fact.
        dialog.datePicker.minDate = viewModel.earliestSelectableDate()
        dialog.datePicker.maxDate = viewModel.latestSelectableDate()
        dialog.show()
    }
}