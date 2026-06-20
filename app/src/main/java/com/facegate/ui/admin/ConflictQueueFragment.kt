package com.facegate.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentConflictQueueBinding
import com.facegate.storage.entity.ConflictEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class ConflictQueueFragment : Fragment() {

    private var _binding: FragmentConflictQueueBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConflictQueueViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConflictQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        setupClickListeners()
        observeConflicts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Back button ──────────────────────────────────────────────────────────

    private fun setupBackButton() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().navigateUp()
                }
            }
        )
    }

    // ── Observe real DB data ─────────────────────────────────────────────────

    private fun observeConflicts() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.conflictContainer.removeAllViews()
                when (state) {
                    is ConflictQueueState.Loading -> {
                        showEmptyState("Loading conflicts…")
                    }
                    is ConflictQueueState.Empty -> {
                        showEmptyState("No unresolved conflicts")
                    }
                    is ConflictQueueState.Loaded -> {
                        state.conflicts.forEachIndexed { index, conflict ->
                            buildConflictRow(conflict, index, state.conflicts.size)
                        }
                    }
                }
            }
        }
    }

    // ── Build conflict row from real ConflictEntity ──────────────────────────

    private fun buildConflictRow(
        conflict: ConflictEntity,
        index: Int,
        total: Int,
    ) {
        val container = binding.conflictContainer

        val rowLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 28, 40, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Avatar — initials from topStudentName
        val initials = conflict.topStudentName
            ?.split(" ")
            ?.mapNotNull { it.firstOrNull()?.toString() }
            ?.take(2)
            ?.joinToString("") ?: "??"

        val avatar = TextView(requireContext()).apply {
            text     = initials
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity  = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#854F0B"))
            setBackgroundResource(R.drawable.chip_pending)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                marginEnd = 28
            }
        }

        // Info column
        val infoColumn = LinearLayout(requireContext()).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val nameText = TextView(requireContext()).apply {
            text     = conflict.topStudentName ?: "Unknown Student"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#1A202C"))
        }

        // Show both candidates if available
        val reasonText = TextView(requireContext()).apply {
            val scoreInfo = if (conflict.topScore != null && conflict.secondStudentName != null) {
                "vs ${conflict.secondStudentName} (${String.format("%.2f", conflict.topScore)})"
            } else {
                conflict.reason ?: "Ambiguous match"
            }
            text     = scoreInfo
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#888780"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 4 }
        }

        val dateText = TextView(requireContext()).apply {
            text = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault())
                .format(Date(conflict.timestamp))
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#B4B2A9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 4 }
        }

        infoColumn.addView(nameText)
        infoColumn.addView(reasonText)
        infoColumn.addView(dateText)

        // Resolve button
        val resolveBtn = TextView(requireContext()).apply {
            text     = "Resolve"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity  = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.icon_brand_bg)
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 80
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { showResolveDialog(conflict) }
        }

        rowLayout.addView(avatar)
        rowLayout.addView(infoColumn)
        rowLayout.addView(resolveBtn)
        container.addView(rowLayout)

        // Divider (except last)
        if (index < total - 1) {
            val divider = View(requireContext()).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#0F000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = 40; marginEnd = 40 }
            }
            container.addView(divider)
        }
    }

    /**
     * Bug fix: the Resolve button used to silently mark the conflict resolved
     * with no record of the outcome. Now it asks the admin explicitly whether
     * the flagged person should be marked Present or left Absent.
     */
    private fun showResolveDialog(conflict: ConflictEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(conflict.topStudentName ?: "Unknown Student")
            .setMessage("Mark this person present or absent for today?")
            .setPositiveButton("Mark Present") { _, _ ->
                viewModel.resolveConflict(conflict, markPresent = true)
            }
            .setNegativeButton("Mark Absent") { _, _ ->
                viewModel.resolveConflict(conflict, markPresent = false)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showEmptyState(message: String) {
        val tv = TextView(requireContext()).apply {
            text     = message
            textSize = 14f
            gravity  = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#888780"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 80 }
        }
        binding.conflictContainer.addView(tv)
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}