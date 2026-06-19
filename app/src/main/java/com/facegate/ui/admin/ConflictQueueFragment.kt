package com.facegate.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.facegate.R
import com.facegate.databinding.FragmentConflictQueueBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Data class for a conflict record
 */
data class ConflictRecord(
    val studentName  : String,
    val studentClass : String,
    val initials     : String,
    val reason       : String,
    val date         : String
)

/**
 * CONFLICT QUEUE FRAGMENT
 * Lists ambiguous attendance cases for admin review
 */

@AndroidEntryPoint
class ConflictQueueFragment : Fragment() {

    private var _binding: FragmentConflictQueueBinding? = null
    private val binding get() = _binding!!

    // Sample conflict data
    private val conflicts = listOf(
        ConflictRecord(
            studentName  = "Arjun Kumar",
            studentClass = "Class 9-B · Roll 14",
            initials     = "AK",
            reason       = "Marked present by face scan, absent by admin",
            date         = "Today 08:42 AM"
        ),
        ConflictRecord(
            studentName  = "Priya Sharma",
            studentClass = "Class 9-B · Roll 15",
            initials     = "PS",
            reason       = "Scanned twice with different results",
            date         = "Today 09:01 AM"
        ),
        ConflictRecord(
            studentName  = "Rahul Mehta",
            studentClass = "Class 9-A · Roll 8",
            initials     = "RM",
            reason       = "Low confidence match score (0.38)",
            date         = "Today 09:15 AM"
        )
    )

    // ── LIFECYCLE ────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConflictQueueBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        buildConflictList()
        setupClickListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── BACK BUTTON ──────────────────────────────────

    /**
     * Handles back button press
     * Uses new OnBackPressedCallback instead of
     * deprecated onBackPressed()
     */
    private fun setupBackButton() {
        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        findNavController().navigateUp()
                    }
                }
            )
    }

    // ── BUILD CONFLICT LIST ───────────────────────────

    /**
     * Dynamically builds conflict rows
     * without needing a separate layout file
     */
    private fun buildConflictList() {
        val container = binding.conflictContainer

        conflicts.forEachIndexed { index, conflict ->

            // ── ROW CONTAINER ──
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                setPadding(40, 28, 40, 28)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // ── AVATAR CIRCLE ──
            val avatar = TextView(requireContext()).apply {
                text      = conflict.initials
                textSize  = 13f
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
                gravity   = android.view.Gravity.CENTER
                setTextColor(
                    android.graphics.Color.parseColor("#854F0B")
                )
                setBackgroundResource(R.drawable.chip_pending)
                layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                    marginEnd = 28
                }
            }

            // ── INFO COLUMN ──
            val infoColumn = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val nameText = TextView(requireContext()).apply {
                text     = conflict.studentName
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#1A202C"))
            }

            val reasonText = TextView(requireContext()).apply {
                text     = conflict.reason
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#888780"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }

            val dateText = TextView(requireContext()).apply {
                text     = conflict.date
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#B4B2A9"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }

            infoColumn.addView(nameText)
            infoColumn.addView(reasonText)
            infoColumn.addView(dateText)

            // ── RESOLVE BUTTON ──
            val resolveBtn = TextView(requireContext()).apply {
                text     = "Resolve"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity  = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.icon_brand_bg)
                setPadding(24, 16, 24, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    80
                )
                isClickable  = true
                isFocusable  = true
                setOnClickListener {
                    onResolveClicked(conflict)
                }
            }

            // ── ADD VIEWS TO ROW ──
            rowLayout.addView(avatar)
            rowLayout.addView(infoColumn)
            rowLayout.addView(resolveBtn)

            // ── ADD DIVIDER (except last) ──
            container.addView(rowLayout)

            if (index < conflicts.size - 1) {
                val divider = View(requireContext()).apply {
                    setBackgroundColor(
                        android.graphics.Color.parseColor("#0F000000")
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        marginStart = 40
                        marginEnd   = 40
                    }
                }
                container.addView(divider)
            }
        }
    }

    /**
     * Called when admin taps Resolve on a conflict
     */
    private fun onResolveClicked(conflict: ConflictRecord) {
        // TODO: show dialog to mark present or absent
        // Then update Room database
    }

    // ── CLICK LISTENERS ──────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}