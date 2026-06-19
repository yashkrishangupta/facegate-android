package com.facegate.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.facegate.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class StudentsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Programmatic layout — no separate XML needed for stub
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(android.graphics.Color.parseColor("#F5F7FA"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val btnBack = Button(requireContext()).apply {
            text = "← Back"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#1976D2"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { findNavController().navigateUp() }
        }

        val tvTitle = TextView(requireContext()).apply {
            text = "Students"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#1A237E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }

        val btnEnroll = Button(requireContext()).apply {
            text = "Enroll New Student"
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
            setOnClickListener {
                findNavController().navigate(R.id.action_students_to_enrollment)
            }
        }

        root.addView(btnBack)
        root.addView(tvTitle)
        root.addView(btnEnroll)
        return root
    }
}