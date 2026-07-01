package com.facegate.ui.schedule

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentTodayScheduleBinding
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

        binding.btnAddUnplanned.setOnClickListener { showUnplannedSessionDialog() }

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
                        binding.tvEmptyState.visibility  = View.GONE
                        binding.scrollView.visibility    = View.GONE
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
        val ctx = requireContext()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(com.facegate.R.drawable.card_dark)          // dark card, not white
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        // Left: period chip "P1"
        val tvPeriod = TextView(ctx).apply {
            text = "P${item.entry.periodNumber}"
            setBackgroundResource(com.facegate.R.drawable.chip_active)
            setTextColor(Color.parseColor("#090909"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        // Centre: subject (bold 14sp) + batch (11sp hint)
        val centerCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(16) }
        }
        centerCol.addView(TextView(ctx).apply {
            text = item.entry.subject
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        centerCol.addView(TextView(ctx).apply {
            text = item.entry.batch
            textSize = 11f
            setTextColor(Color.parseColor("#90A6BD"))
        })

        // Right: HH:MM + status chip
        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(16) }
        }
        rightCol.addView(TextView(ctx).apply {
            text = String.format("%02d:%02d", item.entry.scheduledHour, item.entry.scheduledMinute)
            textSize = 12f
            setTextColor(Color.parseColor("#90A6BD"))
            gravity = Gravity.END
        })
        rightCol.addView(TextView(ctx).apply {
            text = item.status.name
            textSize = 10f
            setTextColor(Color.parseColor("#101828"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            setBackgroundResource(when (item.status) {
                ScheduleItem.Status.UPCOMING -> com.facegate.R.drawable.chip_pending
                ScheduleItem.Status.ACTIVE   -> com.facegate.R.drawable.chip_present
                ScheduleItem.Status.DONE     -> com.facegate.R.drawable.chip_absent
            })
        })

        // Start button — ACTIVE only.
        // UPCOMING: not shown yet (window hasn't started — pressing Start early would give
        //           the teacher a fresh full window instead of counting from scheduled time).
        // DONE:     window closed.
        val btnStart = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Start"
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundResource(com.facegate.R.drawable.button_primary)
            setTextColor(Color.WHITE)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            visibility = if (item.status == ScheduleItem.Status.ACTIVE) View.VISIBLE else View.GONE
            setOnClickListener {
                viewModel.startSession(item.entry) { sessionId, scheduledStartTimeMs ->
                    navigateToAttendance(
                        sessionId            = sessionId,
                        subject              = item.entry.subject,
                        batch                = item.entry.batch,
                        windowMinutes        = item.entry.windowMinutes,
                        scheduledStartTimeMs = scheduledStartTimeMs,
                    )
                }
            }
        }

        row.addView(tvPeriod)
        row.addView(centerCol)
        row.addView(rightCol)
        row.addView(btnStart)
        return row
    }

    private fun populateSchedule(items: List<ScheduleItem>) {
        binding.periodListContainer.removeAllViews()
        items.forEach { binding.periodListContainer.addView(createRowView(it)) }
    }

    // ── Unplanned session dialog ───────────────────────────────────────────────

    private fun showUnplannedSessionDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }

        val subjectInput = EditText(ctx).apply { hint = "Subject" }
        val batchInput   = EditText(ctx).apply { hint = "Batch" }
        val windowInput  = EditText(ctx).apply {
            hint = "Window (minutes)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("10")
        }
        val reasonInput = EditText(ctx).apply { hint = "Reason (optional)" }

        layout.addView(subjectInput)
        layout.addView(batchInput)
        layout.addView(windowInput)
        layout.addView(reasonInput)

        AlertDialog.Builder(ctx)
            .setTitle("Add Unplanned Session")
            .setView(layout)
            .setPositiveButton("Confirm") { _, _ ->
                val subject = subjectInput.text.toString().trim()
                val batch   = batchInput.text.toString().trim()
                val window  = windowInput.text.toString().toIntOrNull() ?: 10
                val reason  = reasonInput.text.toString().trim()

                viewModel.startUnplannedSession(subject, batch, window, reason) { sessionId, scheduledStartTimeMs ->
                    // onStarted runs on Main — safe to navigate.
                    navigateToAttendance(sessionId, subject, batch, window, scheduledStartTimeMs)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}