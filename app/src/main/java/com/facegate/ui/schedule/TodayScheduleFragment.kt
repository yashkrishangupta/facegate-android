package com.facegate.ui.schedule

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
import com.facegate.databinding.DialogExtraPeriodBinding
import com.facegate.databinding.FragmentTodayScheduleBinding
import com.facegate.databinding.ItemScheduleRowBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class TodayScheduleFragment : Fragment() {

    private var _binding: FragmentTodayScheduleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TodayScheduleViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTodayScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvDate.text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())

        binding.btnBack.setOnClickListener {
            // TodayScheduleFragment is the START destination when entered via the
            // "Take Attendance" front door, so findNavController().navigateUp()
            // has nothing to pop and silently does nothing. Going through the
            // activity's back dispatcher instead lets MainActivity's callback fall
            // back to showing the role selector when the nav back stack is empty.
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnAddExtraPeriod.setOnClickListener { showExtraPeriodDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is ScheduleState.Loading -> {
                        binding.bannerHoliday.visibility = View.GONE
                        binding.tvEmptyState.visibility  = View.GONE
                        binding.scrollView.visibility    = View.GONE
                    }
                    is ScheduleState.Holiday -> {
                        binding.bannerHoliday.visibility = View.VISIBLE
                        binding.tvHolidayMessage.text    = "${state.name} — No Classes"
                        if (state.extraItems.isEmpty()) {
                            binding.tvEmptyState.visibility = View.GONE
                            binding.scrollView.visibility   = View.GONE
                        } else {
                            binding.tvEmptyState.visibility = View.GONE
                            binding.scrollView.visibility   = View.VISIBLE
                            populateSchedule(state.extraItems)
                        }
                    }
                    is ScheduleState.WeeklyOff -> {
                        binding.bannerHoliday.visibility = View.VISIBLE
                        binding.tvHolidayMessage.text    = "${state.dayName} — Weekly Off"
                        if (state.extraItems.isEmpty()) {
                            binding.tvEmptyState.visibility = View.GONE
                            binding.scrollView.visibility   = View.GONE
                        } else {
                            binding.tvEmptyState.visibility = View.GONE
                            binding.scrollView.visibility   = View.VISIBLE
                            populateSchedule(state.extraItems)
                        }
                    }
                    is ScheduleState.NoSchedule -> {
                        binding.bannerHoliday.visibility = View.GONE
                        binding.tvEmptyState.visibility  = View.VISIBLE
                        binding.scrollView.visibility    = View.GONE
                    }
                    is ScheduleState.Loaded -> {
                        binding.bannerHoliday.visibility = View.GONE
                        binding.tvEmptyState.visibility  = View.GONE
                        binding.scrollView.visibility    = View.VISIBLE
                        populateSchedule(state.items)
                    }
                }
            }
        }

        viewModel.loadToday()
    }

    // ── Period row builder ────────────────────────────────────────────────────

    private fun createRowView(item: ScheduleItem): View {
        val itemBinding = ItemScheduleRowBinding.inflate(
            LayoutInflater.from(requireContext()), binding.periodListContainer, false
        )

        itemBinding.tvPeriod.text = item.label
        itemBinding.tvSubject.text = item.subject
        itemBinding.tvBatch.text = item.batch
        itemBinding.tvTime.text =
            String.format("%02d:%02d", item.scheduledHour, item.scheduledMinute)

        itemBinding.tvStatusChip.text = item.status.name
        itemBinding.tvStatusChip.setBackgroundResource(when (item.status) {
            ScheduleItem.Status.UPCOMING -> com.facegate.R.drawable.chip_pending
            ScheduleItem.Status.ACTIVE   -> com.facegate.R.drawable.chip_present
            ScheduleItem.Status.DONE     -> com.facegate.R.drawable.chip_absent
        })

        // Start button — ACTIVE only.
        // UPCOMING: not shown yet (window hasn't started — pressing Start early would give
        //           the teacher a fresh full window instead of counting from scheduled time).
        // DONE:     window closed.
        itemBinding.btnStart.visibility =
            if (item.status == ScheduleItem.Status.ACTIVE) View.VISIBLE else View.GONE
        itemBinding.btnStart.setOnClickListener {
            val entry = item.timetableEntry
            if (entry != null) {
                viewModel.startSession(entry) { sessionId, scheduledStartTimeMs ->
                    navigateToAttendance(
                        sessionId            = sessionId,
                        subject              = item.subject,
                        batch                = item.batch,
                        windowMinutes         = item.windowMinutes,
                        scheduledStartTimeMs = scheduledStartTimeMs,
                    )
                }
            } else {
                // Extra period — its session already exists from when it was
                // added, so re-entering it just navigates back in rather than
                // starting a second session for the same row.
                navigateToAttendance(
                    sessionId            = item.existingSessionId!!,
                    subject              = item.subject,
                    batch                = item.batch,
                    windowMinutes        = item.windowMinutes,
                    scheduledStartTimeMs = item.scheduledStartTimeMs ?: 0L,
                )
            }
        }

        return itemBinding.root
    }

    private fun populateSchedule(items: List<ScheduleItem>) {
        binding.periodListContainer.removeAllViews()
        items.forEach { binding.periodListContainer.addView(createRowView(it)) }
    }

    // ── Extra period dialog ─────────────────────────────────────────────────

    private fun showExtraPeriodDialog() {
        val dialogBinding = DialogExtraPeriodBinding.inflate(LayoutInflater.from(requireContext()))

        // NOTE: no setPositiveButton/setNegativeButton here — the dialog layout
        // already supplies its own styled Cancel/Confirm buttons (btnCancel /
        // btnConfirm), matching the dialog_student_info.xml pattern used across
        // the app's other custom dialogs.
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirm.setOnClickListener {
            val subject = dialogBinding.etSubject.text.toString().trim()
            val batch   = dialogBinding.etBatch.text.toString().trim()
            val window  = dialogBinding.etWindow.text.toString().toIntOrNull() ?: 10
            val reason  = dialogBinding.etReason.text.toString().trim()

            dialog.dismiss()
            viewModel.startExtraPeriod(subject, batch, window, reason) { sessionId, scheduledStartTimeMs ->
                // onStarted runs on Main — safe to navigate.
                navigateToAttendance(sessionId, subject, batch, window, scheduledStartTimeMs)
            }
        }

        dialog.show()
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToAttendance(
        sessionId           : String,
        subject             : String,
        batch               : String,
        windowMinutes       : Int,
        scheduledStartTimeMs: Long = 0L,
    ) {
        findNavController().navigate(
            TodayScheduleFragmentDirections.actionScheduleToAttendance(
                sessionId            = sessionId,
                subject              = subject,
                batch                = batch,
                windowMinutes        = windowMinutes,
                scheduledStartTimeMs = scheduledStartTimeMs,
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}