package com.facegate.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentAttendanceReportBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * ATTENDANCE REPORT FRAGMENT
 * Matches: #s-reports in HTML
 * Shows monthly stats, class wise bars, export buttons
 */
@AndroidEntryPoint
class AttendanceReportFragment : Fragment() {

    private var _binding: FragmentAttendanceReportBinding? = null
    private val binding get() = _binding!!

    // ── LIFECYCLE ────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAttendanceReportBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        loadReportData()
        setupClickListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── LOAD DATA ────────────────────────────────────

    /**
     * Loads report statistics into UI
     * In real app: fetch from Database via ViewModel
     */
    private fun loadReportData() {
        binding.tvMonthlyPct.text   = "85.1%"
        binding.tvPresentCount.text = "211"
        binding.tvAbsentCount.text  = "37"
        binding.tvTopClass.text     = "92.4%"
    }

    // ── EXPORT ───────────────────────────────────────

    /**
     * Export attendance to Excel (.xlsx)
     * In real app: generate file and share via Intent
     */
    private fun exportToExcel() {
        // TODO: implement Excel export
    }

    /**
     * Export attendance to PDF
     * In real app: generate PDF and share via Intent
     */
    private fun exportToPdf() {
        // TODO: implement PDF export
    }

    // ── CLICK LISTENERS ──────────────────────────────

    private fun setupClickListeners() {

        // Back button
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Export Excel button
        binding.btnExportExcel.setOnClickListener {
            exportToExcel()
        }

        // Export PDF button
        binding.btnExportPdf.setOnClickListener {
            exportToPdf()
        }
    }
}