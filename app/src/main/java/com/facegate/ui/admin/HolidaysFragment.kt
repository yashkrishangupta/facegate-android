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

    private val viewModel: HolidaysViewModel by viewModels()

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

        // item_holiday_card.xml has a light @color/surface_card background (matching the
        // other list cards in the app), so its text needs the dark on-surface palette —
        // not the light/white tones meant for text on the dark page background.
        val nameColor = if (isPast) "#8A94A3" else "#101828"
        val dateColor = if (isPast) "#ADB4BF" else "#5B6B84"
        itemBinding.tvHolidayName.text = holiday.name
        itemBinding.tvHolidayName.setTextColor(Color.parseColor(nameColor))
        itemBinding.tvHolidayDate.text = holiday.date
        itemBinding.tvHolidayDate.setTextColor(Color.parseColor(dateColor))

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

        // Prefilled from the date picker, but left as a normal editable field so the
        // user can tweak it by hand before saving.
        dialogBinding.etHolidayDate.setText(dateStr)

        // NOTE: no setPositiveButton/setNegativeButton here — the dialog layout
        // already supplies its own styled Cancel/Save buttons (btnCancel /
        // btnSave), matching the dialog_student_info.xml pattern used across
        // the app's other custom dialogs.
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener {
            val date = dialogBinding.etHolidayDate.text.toString().trim()
            val name = dialogBinding.etHolidayName.text.toString().trim()

            when {
                date.isEmpty() || !date.matches(Regex("""\d{4}-\d{2}-\d{2}""")) ->
                    dialogBinding.etHolidayDate.error = "Use YYYY-MM-DD"
                name.isEmpty() ->
                    dialogBinding.etHolidayName.error = "Required"
                else -> {
                    viewModel.addHoliday(HolidayEntity(
                        date      = date,
                        name      = name,
                        createdAt = System.currentTimeMillis(),
                    ))
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }
}