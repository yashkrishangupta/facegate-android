package com.facegate.ui.reports

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentAttendanceReportBinding
import com.facegate.storage.dao.ClassAttendanceSummary
import com.facegate.storage.dao.DailyAttendanceCount
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class AttendanceReportFragment : Fragment() {

    private var _binding: FragmentAttendanceReportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReportViewModel by viewModels()

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
        observeStats()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeStats() {
        lifecycleScope.launch {
            viewModel.stats.collect { stats ->

                val isMonth = stats.period == ReportPeriod.MONTH
                applyTabSelected(binding.tabPeriodToday, !isMonth)
                applyTabSelected(binding.tabPeriodMonth,  isMonth)

                binding.monthNavRow.visibility = if (isMonth) View.VISIBLE else View.GONE
                if (isMonth) {
                    binding.tvMonthLabel.text = stats.monthLabel
                    binding.btnNextMonth.alpha     = if (stats.canGoForward) 1f else 0.35f
                    binding.btnNextMonth.isEnabled = stats.canGoForward
                }

                binding.tvOverviewTitle.text =
                    if (isMonth) "Monthly Overview — ${stats.monthLabel}"
                    else         "Today's Overview"

                if (stats.isHoliday) {
                    binding.tvMonthlyPct.text   = "Holiday"
                    binding.tvPresentCount.text = "-"
                    binding.tvAbsentCount.text  = "-"
                    binding.tvTopClass.text     = "Holiday — ${stats.holidayName}"
                    binding.classBreakdownContainer?.removeAllViews()
                    return@collect
                }

                binding.tvMonthlyPct.text   = stats.attendancePct
                binding.tvPresentCount.text = stats.presentCount.toString()
                binding.tvAbsentCount.text  = if (isMonth) "—" else stats.absentCount.toString()
                binding.tvTopClass.text     = "${stats.totalRecordsEver} total records"

                if (isMonth) {
                    buildMonthCalendar(
                        dailyCounts    = stats.dailyCounts,
                        monthLabel     = stats.monthLabel,
                        totalStudents  = stats.totalStudents,
                        periodsByDate  = stats.periodsByDate,
                    )
                } else {
                    // Clear any lingering month calendar
                    binding.classBreakdownContainer?.let { c ->
                        (c.parent as? ViewGroup)
                            ?.findViewWithTag<View>("monthCalendar")
                            ?.let { (c.parent as ViewGroup).removeView(it) }
                    }
                }

                buildBatchFilterTabs(stats.batches, stats.selectedBatch)
                buildClassBreakdown(stats.classBreakdown, stats.totalStudents)
                buildPeriodBreakdown(stats.periodSummaries)
            }
        }
    }

    // ── Period toggle ──────────────────────────────────────────────────────────

    private fun applyTabSelected(tv: TextView, selected: Boolean) {
        tv.setTextColor(Color.parseColor(if (selected) "#27500A" else "#042C53"))
        tv.setBackgroundResource(
            if (selected) com.facegate.R.drawable.chip_active
            else          com.facegate.R.drawable.chip_pending
        )
        tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    // ── Monthly calendar heatmap ───────────────────────────────────────────────

    private fun buildMonthCalendar(
        dailyCounts   : List<DailyAttendanceCount>,
        monthLabel    : String,
        totalStudents : Int,
        periodsByDate : Map<String, List<PeriodSummary>>,
    ) {
        val container = binding.classBreakdownContainer?.parent as? ViewGroup ?: return

        container.findViewWithTag<View>("monthCalendar")?.let { container.removeView(it) }

        val calendarCard = androidx.cardview.widget.CardView(requireContext()).apply {
            tag = "monthCalendar"
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dpI(16) }
            setContentPadding(dpI(16), dpI(16), dpI(16), dpI(16))
            radius        = dpI(16).toFloat()
            cardElevation = dpI(1).toFloat()
            setCardBackgroundColor(Color.parseColor("#1A2436"))
        }

        val inner = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        // Day-of-week header
        val dayNames = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        dayNames.forEach { d -> headerRow.addView(calCell(d, Color.parseColor("#90A6BD"), bold = true)) }
        inner.addView(headerRow)

        val sdf    = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val counts = dailyCounts.associateBy { it.dateStr }

        val mFmt   = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val mCal   = Calendar.getInstance()
        for (i in 0..11) {
            if (mFmt.format(mCal.time) == monthLabel) break
            mCal.add(Calendar.MONTH, -1)
        }
        mCal.set(Calendar.DAY_OF_MONTH, 1)

        val firstDow    = ((mCal.get(Calendar.DAY_OF_WEEK) + 5) % 7)  // 0 = Mon
        val daysInMonth = mCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val totalCells  = firstDow + daysInMonth
        val rows        = (totalCells + 6) / 7
        var cellIndex   = 0

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(java.util.Date())

        for (r in 0 until rows) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            for (c in 0 until 7) {
                val dayNum = cellIndex - firstDow + 1
                if (cellIndex < firstDow || dayNum > daysInMonth) {
                    row.addView(calCell("", Color.TRANSPARENT))
                } else {
                    mCal.set(Calendar.DAY_OF_MONTH, dayNum)
                    val dateStr = sdf.format(mCal.time)
                    val count   = counts[dateStr]?.presentCount ?: 0
                    val isToday = dateStr == todayStr

                    val bgColor = when {
                        count == 0         -> Color.parseColor(if (isToday) "#1A3A5C" else "#151F2F")
                        totalStudents == 0 -> Color.parseColor("#BBF7D0")
                        else -> {
                            val pct = count.toFloat() / totalStudents
                            when {
                                pct >= 0.90f -> Color.parseColor("#16A34A")
                                pct >= 0.70f -> Color.parseColor("#4ADE80")
                                pct >= 0.50f -> Color.parseColor("#BBF7D0")
                                else         -> Color.parseColor("#FEF9C3")
                            }
                        }
                    }
                    val textColor = if (isToday && count == 0) Color.parseColor("#2563EB")
                    else if (count == 0 || (totalStudents > 0 && count.toFloat() / totalStudents < 0.5f))
                        Color.parseColor("#90A6BD")
                    else Color.WHITE

                    val cell = calCell("$dayNum", textColor, bgColor = bgColor,
                        bold = isToday)

                    // ── Make cell tappable when it has data or sessions ────────
                    val periodsForDay = periodsByDate[dateStr]
                    val hasSessions   = !periodsForDay.isNullOrEmpty()
                    val hasCounts     = count > 0

                    if (hasSessions || hasCounts) {
                        cell.isClickable  = true
                        cell.isFocusable  = true
                        // Subtle ring to hint interactivity
                        if (!hasSessions && hasCounts) {
                            // Count data but no period detail loaded yet — show basic info
                        }
                        cell.setOnClickListener {
                            showDayDetail(dateStr, count, periodsForDay ?: emptyList(), totalStudents)
                        }
                    }

                    row.addView(cell)
                }
                cellIndex++
            }
            inner.addView(row)
        }

        // Legend
        val legendRow = LinearLayout(requireContext()).apply {
            orientation  = LinearLayout.HORIZONTAL
            gravity      = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpI(12) }
        }
        listOf(
            Pair("#16A34A", "≥90%"),
            Pair("#4ADE80", "≥70%"),
            Pair("#BBF7D0", "≥50%"),
            Pair("#FEF9C3", "<50%"),
            Pair("#151F2F", "None"),
        ).forEach { (hex, label) ->
            val dot = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor(hex))
                layoutParams = LinearLayout.LayoutParams(dpI(12), dpI(12))
                    .apply { marginEnd = dpI(4) }
            }
            val lbl = TextView(requireContext()).apply {
                text     = label
                textSize = 10f
                setTextColor(Color.parseColor("#90A6BD"))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                    .apply { marginEnd = dpI(12) }
            }
            legendRow.addView(dot)
            legendRow.addView(lbl)
        }

        // Tap hint
        val hint = TextView(requireContext()).apply {
            text     = "Tap a highlighted date to see details"
            textSize = 10f
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                .apply { topMargin = dpI(6) }
        }
        inner.addView(legendRow)
        inner.addView(hint)

        calendarCard.addView(inner)

        val breakdownCard = binding.classBreakdownContainer?.parent as? View
        val insertIdx = container.indexOfChild(breakdownCard).coerceAtLeast(0)
        container.addView(calendarCard, insertIdx)
    }

    /**
     * Show an AlertDialog with that day's period breakdown when a calendar
     * cell is tapped.
     */
    private fun showDayDetail(
        dateStr      : String,
        presentCount : Int,
        periods      : List<PeriodSummary>,
        totalStudents: Int,
    ) {
        if (!isAdded) return
        val displayFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val parseFmt   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val title      = try { displayFmt.format(parseFmt.parse(dateStr)!!) } catch (_: Exception) { dateStr }

        val sb = StringBuilder()
        if (periods.isEmpty()) {
            sb.append("Present: $presentCount")
            if (totalStudents > 0) sb.append(" / $totalStudents students")
        } else {
            periods.forEach { p ->
                sb.append("${p.subject}  •  ${p.batch}\n")
                sb.append("${p.periodLabel}  •  ${p.uniquePresent} present")
                if (p.sessionCount > 1) sb.append(" (${p.sessionCount} runs merged)")
                sb.append("\n\n")
            }
            sb.trimEnd()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Batch filter tabs ─────────────────────────────────────────────────────

    private fun buildBatchFilterTabs(batches: List<String>, selected: String?) {
        val container = binding.classBreakdownContainer ?: return
        val batchRow  = (container.parent as? android.view.ViewGroup)
            ?.findViewWithTag<android.widget.HorizontalScrollView>("batchFilter")
            ?: createBatchRow(container)

        val chipRow = batchRow.getChildAt(0) as? android.widget.LinearLayout ?: return
        chipRow.removeAllViews()
        chipRow.addView(batchChip("All batches", selected == null) { viewModel.filterByBatch(null) })
        batches.forEach { batch ->
            chipRow.addView(batchChip(batch, selected == batch) { viewModel.filterByBatch(batch) })
        }
    }

    private fun createBatchRow(anchor: android.view.View): android.widget.HorizontalScrollView {
        val chipRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val scroll = android.widget.HorizontalScrollView(requireContext()).apply {
            tag = "batchFilter"
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP)
                .apply { bottomMargin = dpI(12) }
            addView(chipRow)
        }
        (anchor.parent as? android.view.ViewGroup)?.let { parent ->
            val idx = parent.indexOfChild(anchor)
            if (idx >= 0) parent.addView(scroll, (idx - 1).coerceAtLeast(0))
        }
        return scroll
    }

    private fun batchChip(label: String, selected: Boolean, onClick: () -> Unit) =
        android.widget.TextView(requireContext()).apply {
            text     = label
            textSize = 12f
            gravity  = android.view.Gravity.CENTER
            setPadding(dpI(14), dpI(7), dpI(14), dpI(7))
            typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD
            else          android.graphics.Typeface.DEFAULT
            setTextColor(if (selected) Color.parseColor("#5DA9FF") else Color.parseColor("#90A6BD"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpI(20).toFloat()
                setColor(if (selected) Color.parseColor("#1A3A5C") else Color.parseColor("#1E2E44"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(WRAP, WRAP)
                .apply { marginEnd = dpI(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    // ── Class-wise breakdown ──────────────────────────────────────────────────

    private fun buildClassBreakdown(breakdown: List<ClassAttendanceSummary>, totalStudents: Int) {
        val container = binding.classBreakdownContainer ?: return
        container.removeAllViews()
        if (breakdown.isEmpty()) {
            container.addView(emptyTextView("No attendance recorded"))
            return
        }
        breakdown.forEach { summary ->
            val row = rowLayout()
            row.addView(labelText("Class ${summary.studentClass}", bold = true, flex = 1f))
            row.addView(labelText("${summary.presentCount} present", color = "#1D9E75"))
            container.addView(row)
            container.addView(divider())
        }
    }

    // ── Period breakdown (one row per unique teaching slot) ───────────────────

    private fun buildPeriodBreakdown(periods: List<PeriodSummary>) {
        val container = binding.sessionBreakdownContainer ?: return
        container.removeAllViews()
        if (periods.isEmpty()) {
            container.addView(emptyTextView("No sessions recorded"))
            return
        }

        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

        periods.forEach { p ->
            // Header row: Subject • Batch
            val headerRow = rowLayout()
            headerRow.addView(labelText("${p.subject}  •  ${p.batch}", bold = true, flex = 1f))
            val presentLabel = if (p.sessionCount > 1)
                "${p.uniquePresent} present*"   // asterisk = merged
            else
                "${p.uniquePresent} present"
            headerRow.addView(labelText(presentLabel, color = "#1D9E75"))
            container.addView(headerRow)

            // Sub row: Period label • time • window
            val subRow = rowLayout()
            val timeStr = timeFmt.format(java.util.Date(p.startTime))
            val periodInfo = "${p.periodLabel}  •  $timeStr"
            subRow.addView(labelText(periodInfo, color = "#888780", flex = 1f))
            subRow.addView(labelText("${p.windowMinutes} min", color = "#888780"))
            container.addView(subRow)

            // If multiple sessions were merged, show a note
            if (p.sessionCount > 1) {
                val noteRow = rowLayout()
                noteRow.addView(labelText(
                    "* ${p.sessionCount} session runs merged — unique students counted once",
                    color  = "#90A6BD",
                    flex   = 1f,
                ))
                container.addView(noteRow)
            }

            container.addView(divider())
        }
    }

    // ── Calendar cell ──────────────────────────────────────────────────────────

    private fun calCell(
        text      : String,
        textColor : Int,
        bgColor   : Int     = Color.TRANSPARENT,
        bold      : Boolean = false,
    ) = TextView(requireContext()).apply {
        this.text = text
        textSize  = 11f
        gravity   = Gravity.CENTER
        setTextColor(textColor)
        setBackgroundColor(bgColor)
        typeface  = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        layoutParams = LinearLayout.LayoutParams(0, dpI(36), 1f).apply {
            setMargins(dpI(2), dpI(2), dpI(2), dpI(2))
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun dpI(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rowLayout() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(0, 10, 0, 10)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun labelText(
        text  : String,
        bold  : Boolean = false,
        flex  : Float   = 0f,
        color : String  = "#1A202C",
    ) = TextView(requireContext()).apply {
        this.text = text
        textSize  = 13f
        typeface  = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(Color.parseColor(color))
        layoutParams = if (flex > 0f)
            LinearLayout.LayoutParams(0, WRAP, flex)
        else
            LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END }
    }

    private fun divider() = View(requireContext()).apply {
        setBackgroundColor(Color.parseColor("#1E2E44"))
        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    }

    private fun emptyTextView(msg: String) = TextView(requireContext()).apply {
        text     = msg
        textSize = 13f
        gravity  = Gravity.CENTER
        setTextColor(Color.parseColor("#90A6BD"))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 24 }
    }

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.tabPeriodToday.setOnClickListener { viewModel.setPeriod(ReportPeriod.TODAY) }
        binding.tabPeriodMonth.setOnClickListener  { viewModel.setPeriod(ReportPeriod.MONTH) }
        binding.btnPrevMonth.setOnClickListener    { viewModel.stepMonth(-1) }
        binding.btnNextMonth.setOnClickListener    { viewModel.stepMonth(+1) }
        binding.btnExportExcel.setOnClickListener  { /* TODO */ }
        binding.btnExportPdf.setOnClickListener    { /* TODO */ }
    }
}