package com.example.buddy.ui

import android.view.LayoutInflater
import androidx.fragment.app.FragmentActivity
import com.example.buddy.R
import com.example.buddy.databinding.DialogConfirmBinding
import com.example.buddy.security.SecurityManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Displays Buddy's warm, non-scary confirmation dialog for sensitive actions
 * (SMS, shell commands, or destructive clicks).
 *
 * Uses exact copy from strings.xml:
 * - Default:
 *   Title: "Heads up"
 *   Body:  "Buddy wants to <action>. That's a big deal - okay to go?"
 *   Allow: "Yeah, do it"
 *   Deny:  "Nope, cancel"
 * - SMS:
 *   Title: "Sending a text"
 *   Body:  "Buddy's about to text <number>. Look good?"
 *   Allow: "Send it"
 *   Deny:  "Hold on"
 * - Shell:
 *   Title: "Running a command"
 *   Body:  "Buddy wants to run: <cmd>. Cool with that?"
 *   Allow: "Go for it"
 *   Deny:  "Stop"
 */
object ConfirmDialog {

    suspend fun confirm(
        activity: FragmentActivity,
        toolName: String,
        args: JsonObject
    ): Boolean {
        val approvedInDialog = showWarmDialog(activity, toolName, args)
        if (!approvedInDialog) return false

        // Optional biometric verification after the user clicks Allow
        return SecurityManager.authenticateIfAvailable(activity)
    }

    private suspend fun showWarmDialog(
        activity: FragmentActivity,
        toolName: String,
        args: JsonObject
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val binding = DialogConfirmBinding.inflate(LayoutInflater.from(activity))

        when (toolName) {
            "send_sms" -> {
                val number = args.get("number")?.asString?.ifBlank { "that number" } ?: "that number"
                binding.tvConfirmTitle.text = activity.getString(R.string.confirm_sms_title)
                binding.tvConfirmBody.text = activity.getString(R.string.confirm_sms_body, number)
                binding.btnAllow.text = activity.getString(R.string.confirm_sms_allow)
                binding.btnDeny.text = activity.getString(R.string.confirm_sms_deny)
            }

            "run_shell" -> {
                val cmd = args.get("cmd")?.asString?.ifBlank { "sh" } ?: "sh"
                binding.tvConfirmTitle.text = activity.getString(R.string.confirm_shell_title)
                binding.tvConfirmBody.text = activity.getString(R.string.confirm_shell_body, cmd)
                binding.btnAllow.text = activity.getString(R.string.confirm_shell_allow)
                binding.btnDeny.text = activity.getString(R.string.confirm_shell_deny)
            }

            else -> {
                val actionDesc = if (toolName == "click_text") {
                    val target = args.get("text")?.asString.orEmpty()
                    "tap \"$target\""
                } else {
                    toolName
                }
                binding.tvConfirmTitle.text = activity.getString(R.string.confirm_default_title)
                binding.tvConfirmBody.text = activity.getString(R.string.confirm_default_body, actionDesc)
                binding.btnAllow.text = activity.getString(R.string.confirm_default_allow)
                binding.btnDeny.text = activity.getString(R.string.confirm_default_deny)
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Buddy_Dialog)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        binding.btnAllow.setOnClickListener {
            dialog.dismiss()
            if (continuation.isActive) {
                continuation.resume(true)
            }
        }

        binding.btnDeny.setOnClickListener {
            dialog.dismiss()
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }

        continuation.invokeOnCancellation {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
