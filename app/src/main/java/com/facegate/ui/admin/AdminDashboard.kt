package com.facegate.ui.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentAdminDashboardBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class AdminDashboard : Fragment() {

    private var _binding: FragmentAdminDashboardBinding? = null
    private val binding get() = _binding!!

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
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminDashboardBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        updateDate()

        clockHandler.post(clockRunnable)

        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        _binding = null
    }

    // ─────────────────────────────────────────────
    // Clock & Date
    // ─────────────────────────────────────────────

    private fun updateClock() {
        val time = SimpleDateFormat(
            "hh:mm a",
            Locale.getDefault()
        ).format(Date())

        binding.tvClock.text = time
    }

    private fun updateDate() {
        val date = SimpleDateFormat(
            "EEEE, d MMMM yyyy",
            Locale.getDefault()
        ).format(Date())

        binding.tvDate.text = date
    }

    // ─────────────────────────────────────────────
    // Dashboard Stats
    // ─────────────────────────────────────────────

    private fun loadStats() {
        binding.tvTotalStudents.text = "248"
        binding.tvPresentToday.text = "211"
        binding.tvAbsentToday.text = "37"
        binding.tvHolidaysLeft.text = "8"
    }

    // ─────────────────────────────────────────────
    // Navigation Click Listeners
    // ─────────────────────────────────────────────

    private fun setupClickListeners() {

        // Students
        binding.tileStudents.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_students
            )
        }

        // Manual Attendance
        binding.tileManual.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_manual
            )
        }

        // Holidays
        binding.tileHolidays.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_holidays
            )
        }

        // Reports
        binding.tileReports.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_reports
            )
        }

        // Conflict Queue
        binding.btnResolve.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_conflicts
            )
        }

        // Bottom Nav → Home (already here — no-op)
        binding.navHome.setOnClickListener {
            // Already on dashboard
        }

        // Bottom Nav → Students
        binding.navStudents.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_students
            )
        }

        // Bottom Nav → Reports
        binding.navReports.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_reports
            )
        }

        // Exit — return to role selector
        binding.navExit.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }


    }
}