package com.facegate.ui.admin

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentConflictQueueBinding
import com.facegate.databinding.ItemConflictRowBinding
import com.facegate.databinding.ItemEmptyMessageBinding
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
        val itemBinding = ItemConflictRowBinding.inflate(LayoutInflater.from(requireContext()), container, false)

        val initials = conflict.topStudentName
            .split(" ")
            .mapNotNull { it.firstOrNull()?.toString() }
            .take(2)
            .joinToString("")
            .ifEmpty { "??" }

        itemBinding.tvAvatar.text = initials
        itemBinding.tvName.text = conflict.topStudentName
        itemBinding.tvReason.text =
            "vs ${conflict.secondStudentName} (${String.format("%.2f", conflict.topScore)})"
        itemBinding.tvDate.text = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault())
            .format(Date(conflict.timestamp))
        itemBinding.btnResolve.setOnClickListener { showResolveDialog(conflict) }

        container.addView(itemBinding.root)

        // Divider (except last)
        if (index < total - 1) {
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#0F000000"))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = 40; marginEnd = 40 }
            }
            container.addView(divider)
        }
    }

    private fun showResolveDialog(conflict: ConflictEntity) {
        val top    = conflict.topStudentName
        val second = conflict.secondStudentName

        val topScore    = String.format("%.0f%%", conflict.topScore    * 100)
        val secondScore = String.format("%.0f%%", conflict.secondScore * 100)

        AlertDialog.Builder(requireContext())
            .setTitle("Who was it?")
            .setMessage(
                "The camera couldn't tell these two apart:\n\n" +
                "1. $top ($topScore match)\n" +
                "2. $second ($secondScore match)\n\n" +
                "Mark the correct person present."
            )
            .setPositiveButton("✓ $top Present") { _, _ ->
                viewModel.resolveConflict(
                    conflict        = conflict,
                    presentStudentId = conflict.topStudentId,
                    presentStudentName = top,
                )
            }
            .setNegativeButton("✓ $second Present") { _, _ ->
                viewModel.resolveConflict(
                    conflict        = conflict,
                    presentStudentId = conflict.secondStudentId,
                    presentStudentName = second,
                )
            }
            .setNeutralButton("Both Absent") { _, _ ->
                viewModel.resolveConflict(
                    conflict           = conflict,
                    presentStudentId   = null,
                    presentStudentName = null,
                )
            }
            .show()
    }

    private fun showEmptyState(message: String) {
        val itemBinding = ItemEmptyMessageBinding.inflate(
            LayoutInflater.from(requireContext()), binding.conflictContainer, false
        )
        itemBinding.tvMessage.text = message
        binding.conflictContainer.addView(itemBinding.root)
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}