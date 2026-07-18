package com.facegate.security

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.facegate.databinding.DialogLoginBinding
import kotlinx.coroutines.launch

/**
 * Shared password-prompt dialog for the two on-device login gates (Admin
 * Mode entry in MainActivity, "Start" a period in TodayScheduleFragment).
 * Uses dialog_login.xml — a proper Material-styled card matching the rest
 * of the app (dark theme, outlined password field with a show/hide toggle,
 * inline error text) rather than a bare system EditText.
 */
object AuthPromptDialog {

    fun show(
        context: Context,
        lifecycleScope: LifecycleCoroutineScope,
        title: String,
        message: String? = null,
        onVerify: suspend (password: String) -> AuthResult,
        onSuccess: (AuthResult.Success) -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        val binding = DialogLoginBinding.inflate(LayoutInflater.from(context))

        binding.tvLoginTitle.text = title
        if (message != null) {
            binding.tvLoginMessage.text = message
            binding.tvLoginMessage.visibility = android.view.View.VISIBLE
        } else {
            binding.tvLoginMessage.visibility = android.view.View.GONE
        }

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        // Transparent window background so the MaterialCardView's own
        // rounded corners/stroke show through instead of the default
        // square AlertDialog panel.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun attemptVerify() {
            val password = binding.etPassword.text?.toString().orEmpty()
            binding.tilPassword.error = null

            if (password.isBlank()) {
                binding.tilPassword.error = "Enter a password"
                return
            }

            binding.btnLoginConfirm.isEnabled = false
            binding.btnLoginConfirm.text = "Checking…"

            lifecycleScope.launch {
                val result = onVerify(password)
                binding.btnLoginConfirm.isEnabled = true
                binding.btnLoginConfirm.text = "Confirm"

                when (result) {
                    is AuthResult.Success -> {
                        dialog.dismiss()
                        onSuccess(result)
                    }
                    AuthResult.WrongPassword -> {
                        binding.tilPassword.error = "Incorrect password"
                        binding.etPassword.text?.clear()
                    }
                    AuthResult.NotSyncedYet -> {
                        binding.tilPassword.error =
                            "No accounts synced to this device yet — connect to Wi-Fi and sync, then try again"
                    }
                }
            }
        }

        binding.btnLoginConfirm.setOnClickListener { attemptVerify() }
        binding.etPassword.setOnEditorActionListener { _, _, _ -> attemptVerify(); true }
        binding.btnLoginCancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        dialog.show()
    }
}
