package com.facegate.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentAttendanceReportBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

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
        observeStats()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Real DB data ─────────────────────────────────────────────────────────

    private fun observeStats() {
        lifecycleScope.launch {
            viewModel.stats.collect { stats ->
                // Today's attendance percentage
                binding.tvMonthlyPct.text   = stats.attendancePct

                // Today's present / absent
                binding.tvPresentCount.text = stats.presentToday.toString()
                binding.tvAbsentCount.text  = stats.absentToday.toString()

                // Top class — using total enrolled as a proxy until class-wise
                // breakdown is available (requires class field on AttendanceEntity)
                binding.tvTopClass.text     = "${stats.totalStudents} enrolled"
            }
        }
    }

    // ── Export (TODO when backend is ready) ──────────────────────────────────

    private fun exportToExcel() {
        // TODO: generate .xlsx from attendance records and share via Intent
    }

    private fun exportToPdf() {
        // TODO: generate PDF from attendance records and share via Intent
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnExportExcel.setOnClickListener { exportToExcel() }
        binding.btnExportPdf.setOnClickListener   { exportToPdf()   }
    }
}