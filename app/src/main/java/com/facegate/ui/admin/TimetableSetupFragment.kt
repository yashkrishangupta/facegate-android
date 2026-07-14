package com.facegate.ui.admin

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.DialogPeriodSetupBinding
import com.facegate.databinding.FragmentTimetableSetupBinding
import com.facegate.databinding.ItemFilterTabBinding
import com.facegate.databinding.ItemPeriodCardBinding
import com.facegate.storage.entity.TimetableEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimetableSetupFragment : Fragment() {

    private var _binding: FragmentTimetableSetupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TimetableSetupViewModel by viewModels()
    private var selectedDay = 1  // default Monday

    // Backend timetable.day_of_week is CHECK-constrained to Monday..Saturday
    // (schema.sql) — Sunday was never a valid value to create a period on
    // and is dropped from the tabs entirely rather than showing a day that
    // can only ever be empty.
    private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val dayTabButtons = mutableListOf<TextView>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTimetableSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        buildDayTabs()
        viewModel.loadSubjectsAndBatches()
        observeEntries()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Day tabs ──────────────────────────────────────────────────────────────

    private fun buildDayTabs() {
        binding.dayTabRow.removeAllViews()
        dayTabButtons.clear()
        dayNames.forEachIndexed { index, name ->
            val itemBinding = ItemFilterTabBinding.inflate(
                LayoutInflater.from(requireContext()), binding.dayTabRow, false
            )
            val tab = itemBinding.tvTabLabel.apply {
                text = name
                setTextColor(if (index == 0) Color.WHITE else Color.parseColor("#90A6BD"))
                setBackgroundColor(if (index == 0) Color.parseColor("#1D9E75") else Color.TRANSPARENT)
                setOnClickListener { onDaySelected(index + 1) }
            }
            dayTabButtons.add(tab)
            binding.dayTabRow.addView(itemBinding.root)
        }
    }

    // ── Observe entries ──────────────────────────────────────────────────────

    private fun observeEntries() {
        lifecycleScope.launch {
            viewModel.allEntries.collect {
                refreshPeriodList()
            }
        }
    }

    // ── Day tab selection ────────────────────────────────────────────────────

    private fun onDaySelected(day: Int) {
        selectedDay = day
        dayTabButtons.forEachIndexed { i, tab ->
            if (i + 1 == day) {
                tab.setTextColor(Color.WHITE)
                tab.setBackgroundColor(Color.parseColor("#1D9E75"))
            } else {
                tab.setTextColor(Color.parseColor("#90A6BD"))
                tab.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        refreshPeriodList()
    }

    // ── Refresh period list ──────────────────────────────────────────────────

    private fun refreshPeriodList() {
        val entries = viewModel.getForDay(selectedDay)
        binding.periodListContainer.removeAllViews()
        binding.periodListContainer.addView(binding.emptyState)

        if (entries.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }
        binding.emptyState.visibility = View.GONE

        entries.forEach { entry ->
            binding.periodListContainer.addView(buildPeriodCard(entry))
        }
    }

    // ── Period card ───────────────────────────────────────────────────────────

    private fun buildPeriodCard(entry: TimetableEntity): View {
        val itemBinding = ItemPeriodCardBinding.inflate(
            LayoutInflater.from(requireContext()), binding.periodListContainer, false
        )
        itemBinding.tvPeriodChip.text = "P${entry.periodNumber}"
        itemBinding.tvSubject.text = entry.subject
        itemBinding.tvBatchDetails.text =
            "${entry.batch}  •  ${entry.scheduledHour}:${entry.scheduledMinute.toString().padStart(2, '0')}  •  ${entry.windowMinutes}min"

        itemBinding.btnEdit.setOnClickListener { showAddPeriodDialog(entry) }
        itemBinding.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete Period")
                .setMessage("Delete P${entry.periodNumber} — ${entry.subject}?")
                .setPositiveButton("Delete") { _, _ -> viewModel.deletePeriod(entry.id) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        return itemBinding.root
    }

    // ── Add / Edit dialog ────────────────────────────────────────────────────

    private fun showAddPeriodDialog(existing: TimetableEntity? = null) {
        val dialogBinding = DialogPeriodSetupBinding.inflate(LayoutInflater.from(requireContext()))

        val periods = (1..8).map { "Period $it" }
        dialogBinding.spinnerPeriod.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, periods)
        existing?.let { dialogBinding.spinnerPeriod.setSelection(it.periodNumber - 1) }

        dialogBinding.etSubject.setText(existing?.subject ?: "")
        dialogBinding.etBatch.setText(existing?.batch ?: "")

        var selectedHour   = existing?.scheduledHour   ?: 8
        var selectedMinute = existing?.scheduledMinute ?: 0

        dialogBinding.tvTime.apply {
            text = "Time: $selectedHour:${selectedMinute.toString().padStart(2, '0')}"
            setOnClickListener {
                TimePickerDialog(requireContext(), { _, h, m ->
                    selectedHour   = h
                    selectedMinute = m
                    text = "Time: $h:${m.toString().padStart(2, '0')}"
                }, selectedHour, selectedMinute, true).show()
            }
        }

        dialogBinding.etWindow.setText(existing?.windowMinutes?.toString() ?: "10")

        dialogBinding.tvDialogTitle.text = if (existing == null) "Add Period" else "Edit Period"

        // NOTE: no setPositiveButton/setNegativeButton here — the dialog layout
        // already supplies its own styled Cancel/Save buttons (btnCancel /
        // btnSave), matching the dialog_student_info.xml pattern used across
        // the app's other custom dialogs.
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener {
            val entry = TimetableEntity(
                id              = existing?.id ?: 0,
                dayOfWeek       = selectedDay,
                periodNumber    = dialogBinding.spinnerPeriod.selectedItemPosition + 1,
                subject         = dialogBinding.etSubject.text.toString().trim(),
                batch           = dialogBinding.etBatch.text.toString().trim(),
                scheduledHour   = selectedHour,
                scheduledMinute = selectedMinute,
                windowMinutes   = dialogBinding.etWindow.text.toString().toIntOrNull() ?: 10,
            )
            viewModel.addOrUpdatePeriod(entry)
            dialog.dismiss()
        }

        dialog.show()
    }
}