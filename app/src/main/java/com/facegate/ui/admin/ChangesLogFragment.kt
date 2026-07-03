package com.facegate.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.databinding.FragmentChangesLogBinding
import com.facegate.databinding.ItemChangeLogCardBinding
import com.facegate.storage.entity.OverrideEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class ChangesLogFragment : Fragment() {

    private var _binding: FragmentChangesLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChangesLogViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChangesLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        observeOverrides()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Observe overrides ────────────────────────────────────────────────────

    private fun observeOverrides() {
        lifecycleScope.launch {
            viewModel.overrides.collect { overrides ->
                buildList(overrides)
            }
        }
    }

    // ── Build list ────────────────────────────────────────────────────────────

    private fun buildList(overrides: List<OverrideEntity>) {
        binding.changesContainer.removeAllViews()

        if (overrides.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }
        binding.emptyState.visibility = View.GONE

        overrides.forEach { override ->
            binding.changesContainer.addView(buildCard(override))
        }
    }

    // ── Card per override ─────────────────────────────────────────────────────

    private fun buildCard(override: OverrideEntity): View {
        val itemBinding = ItemChangeLogCardBinding.inflate(
            LayoutInflater.from(requireContext()), binding.changesContainer, false
        )

        itemBinding.tvFieldChip.text = override.fieldChanged.uppercase()
        itemBinding.tvSession.text = "Session: ${override.sessionId.take(8)}..."
        itemBinding.tvChange.text = "${override.oldValue}  →  ${override.newValue}"

        val dateStr = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
            .format(Date(override.changedAt))
        val reasonStr = if (override.reason.isNotEmpty()) "  •  ${override.reason}" else ""
        itemBinding.tvMeta.text = "$dateStr$reasonStr"

        return itemBinding.root
    }
}