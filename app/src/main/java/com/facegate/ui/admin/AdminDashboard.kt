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
        // Refresh stats every time the admin returns to this screen
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
                binding.tvTotalStudents.text  = stats.totalStudents.toString()
                binding.tvPresentToday.text   = stats.presentToday.toString()
                binding.tvAbsentToday.text    = stats.absentToday.toString()
                // Show conflict badge count instead of "holidays" (no holidays table in DB)
                binding.tvHolidaysLeft.text   = stats.pendingConflicts.toString()
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
        binding.navExit.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}