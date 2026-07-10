package com.facegate.ui.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.facegate.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * One-time device setup screen.
 *
 * Pairing is admin-initiated: an admin creates the device record on the
 * website (picking a room, giving it a name) and hands the installer a
 * 6-digit pairing code, valid 15 minutes. This screen's only job is to
 * redeem that code via POST /api/v1/devices/pair, which returns the
 * permanent device_id + device_token.
 *
 * Thin view — all pairing logic lives in the ViewModel (matches the pattern
 * used by EnrollmentFragment/EnrollmentViewModel elsewhere in this app), so
 * an in-flight pairing call survives a rotation instead of being cancelled
 * with the fragment's view.
 */
@AndroidEntryPoint
class PairingFragment : Fragment() {

    private val viewModel: PairingViewModel by viewModels()

    private lateinit var etPairingCode: EditText
    private lateinit var tvFeedback: TextView
    private lateinit var btnPair: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_pairing, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etPairingCode = view.findViewById(R.id.etPairingCode)
        tvFeedback = view.findViewById(R.id.tvFeedback)
        btnPair = view.findViewById(R.id.btnPair)
        progressBar = view.findViewById(R.id.progressBar)

        btnPair.setOnClickListener {
            viewModel.pair(pairingCode = etPairingCode.text.toString().trim())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pairingState.collect { state -> render(state) }
        }
    }

    private fun render(state: PairingState) {
        when (state) {
            is PairingState.Idle -> {
                setLoading(false)
                hideFeedback()
            }
            is PairingState.Loading -> {
                setLoading(true)
                hideFeedback()
            }
            is PairingState.Success -> {
                setLoading(false)
                Toast.makeText(requireContext(), "Device paired successfully!", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_pairing_to_dashboard)
                viewModel.resetState() // avoid re-navigating if this state is re-collected (e.g. rotation)
            }
            is PairingState.Failed -> {
                setLoading(false)
                showFeedback("Pairing failed: ${state.reason}")
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnPair.isEnabled = !isLoading
        etPairingCode.isEnabled = !isLoading
    }

    private fun showFeedback(message: String) {
        tvFeedback.text = message
        tvFeedback.visibility = View.VISIBLE
    }

    private fun hideFeedback() {
        tvFeedback.visibility = View.GONE
    }
}
