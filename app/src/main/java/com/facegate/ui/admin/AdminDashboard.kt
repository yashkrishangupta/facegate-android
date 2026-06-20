package com.facegate.ui.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentAdminDashboardBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class AdminDashboard : Fragment() {

    private var _binding: FragmentAdminDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AdminDashboardViewModel by viewModels()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        updateDate()
        clockHandler.post(clockRunnable)
        observeStats()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        _binding = null
    }

    // ── Clock & Date ─────────────────────────────────────────────────────────

    private fun updateClock() {
        binding.tvClock.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    private fun updateDate() {
        binding.tvDate.text = SimpleDateFormat(
            "EEEE, d MMMM yyyy", Locale.getDefault()
        ).format(Date())
    }

    // ── Stats from DB ────────────────────────────────────────────────────────

    private fun observeStats() {
        lifecycleScope.launch {
            viewModel.stats.collect { stats ->

                // ── Stat cards ────────────────────────────────────────────────
                binding.tvTotalStudents.text = stats.totalStudents.toString()
                binding.tvPresentToday.text  = stats.presentToday.toString()
                binding.tvAbsentToday.text   = stats.absentToday.toString()
                binding.tvHolidaysLeft.text  = stats.pendingConflicts.toString()

                // ── Progress bars (live percentages) ──────────────────────────
                // Total students bar is always full — it's a reference, not a ratio
                binding.progressTotalStudents.progress = if (stats.totalStudents > 0) 100 else 0
                binding.progressPresent.progress       = stats.attendancePct
                binding.progressAbsent.progress        = stats.absentPct
                // Conflicts bar: capped at 100 so it looks proportional up to 10 conflicts
                binding.progressConflicts.progress     =
                    (stats.pendingConflicts * 10).coerceAtMost(100)

                // ── Dynamic subtitles ─────────────────────────────────────────
                binding.tvPresentSubtitle.text = "${stats.attendancePct}% attendance rate"
                binding.tvAbsentSubtitle.text  = "${stats.absentPct}% absent today"
                binding.tvConflictsSubtitle.text = when (stats.pendingConflicts) {
                    0    -> "No unresolved matches"
                    1    -> "1 match needs review"
                    else -> "${stats.pendingConflicts} matches need review"
                }

                // ── Tile subtitles ────────────────────────────────────────────
                binding.tvTileStudentsSub.text = when (stats.totalStudents) {
                    0    -> "No students yet"
                    1    -> "1 enrolled"
                    else -> "${stats.totalStudents} enrolled"
                }
                binding.tvTileManualSub.text = when (stats.presentToday) {
                    0    -> "None marked yet"
                    1    -> "1 present today"
                    else -> "${stats.presentToday} present today"
                }
                binding.tvTileReportsSub.text = "${stats.attendancePct}% today"

                // ── Conflict banner (visible only when conflicts > 0) ─────────
                if (stats.pendingConflicts > 0) {
                    binding.conflictBanner.visibility = View.VISIBLE
                    binding.tvConflictTitle.text = when (stats.pendingConflicts) {
                        1    -> "1 Open Conflict"
                        else -> "${stats.pendingConflicts} Open Conflicts"
                    }
                } else {
                    binding.conflictBanner.visibility = View.GONE
                }
            }
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.tileStudents.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_students)
        }
        binding.tileManual.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_manual)
        }
        binding.tileHolidays.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_holidays)
        }
        binding.tileReports.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_reports)
        }
        binding.btnResolve.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_conflicts)
        }
        binding.navHome.setOnClickListener { /* already here */ }
        binding.navStudents.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_students)
        }
        binding.navReports.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_reports)
        }
        binding.navConflicts.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_conflicts)
        }
        binding.navExit.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}