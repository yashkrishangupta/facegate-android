package com.facegate.ui.admin

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HolidaysFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val btnBack = Button(requireContext()).apply {
            text = "← Back"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1976D2"))
            textSize = 16f
            setOnClickListener { findNavController().navigateUp() }
        }

        val tvTitle = TextView(requireContext()).apply {
            text = "Holidays"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }

        val tvSub = TextView(requireContext()).apply {
            text = "Manage school holidays and exceptions. Attendance is not recorded on holiday dates."
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        root.addView(btnBack)
        root.addView(tvTitle)
        root.addView(tvSub)
        return root
    }
}