package com.facegate.ui.admin

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.DialogHolidayNameBinding
import com.facegate.databinding.FragmentHolidaysBinding
import com.facegate.databinding.ItemHolidayCardBinding
import com.facegate.storage.entity.HolidayEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class HolidaysFragment : Fragment() {

    private var _binding: FragmentHolidaysBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HolidaySetupViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHolidaysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnAddHoliday.setOnClickListener { showAddHolidayDialog() }
        observeHolidays()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Observe holidays ─────────────────────────────────────────────────────

    private fun observeHolidays() {
        lifecycleScope.launch {
            viewModel.holidays.collect { holidays ->
                buildList(holidays)
            }
        }
    }

    // ── Build list ────────────────────────────────────────────────────────────

    private fun buildList(holidays: List<HolidayEntity>) {
        binding.listContainer.removeAllViews()

        if (holidays.isEmpty()) {
            val itemBinding = ItemHolidayCardBinding.inflate(
                LayoutInflater.from(requireContext()), binding.listContainer, false
            )
            itemBinding.tvHolidayName.text = "No holidays added yet"
            itemBinding.tvHolidayDate.visibility = View.GONE
            itemBinding.btnDelete.visibility = View.GONE
            binding.listContainer.addView(itemBinding.root)
            return
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        holidays.forEach { holiday ->
            val isPast = holiday.date < today
            binding.listContainer.addView(buildHolidayCard(holiday, isPast))
        }
    }

    // ── Holiday card ──────────────────────────────────────────────────────────

    private fun buildHolidayCard(holiday: HolidayEntity, isPast: Boolean): View {
        val itemBinding = ItemHolidayCardBinding.inflate(
            LayoutInflater.from(requireContext()), binding.listContainer, false
        )

        val textColor = if (isPast) "#7A8799" else "#FFFFFF"
        itemBinding.tvHolidayName.text = holiday.name
        itemBinding.tvHolidayName.setTextColor(Color.parseColor(textColor))
        itemBinding.tvHolidayDate.text = holiday.date
        itemBinding.tvHolidayDate.setTextColor(
            Color.parseColor(if (isPast) "#5B6B84" else "#90A6BD")
        )

        itemBinding.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete Holiday")
                .setMessage("Remove ${holiday.name}?")
                .setPositiveButton("Delete") { _, _ -> viewModel.deleteHoliday(holiday.date) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        return itemBinding.root
    }

    // ── Add holiday dialog ───────────────────────────────────────────────────

    private fun showAddHolidayDialog() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val dateStr = String.format("%04d-%02d-%02d", year, month + 1, day)
                showNameDialog(dateStr)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun showNameDialog(dateStr: String) {
        val dialogBinding = DialogHolidayNameBinding.inflate(LayoutInflater.from(requireContext()))
        AlertDialog.Builder(requireContext())
            .setTitle("Holiday name for $dateStr")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = dialogBinding.etHolidayName.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.addHoliday(HolidayEntity(
                        date      = dateStr,
                        name      = name,
                        createdAt = System.currentTimeMillis(),
                    ))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}