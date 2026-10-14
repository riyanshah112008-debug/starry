package com.example.buddy.security

import android.content.Context
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.buddy.AutoService
import com.example.buddy.R
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Safety policies for tool execution:
 * 1. Which tools need explicit user confirmation.
 * 2. Optional biometric gate.
 * 3. Package allow/block checks for open_app.
 * 4. Whether the accessibility service is actually switched on.
 */
object SecurityManager {

    /** Tools that always pause for warm confirmation before running. */
    private val DESTRUCTIVE_TOOLS = setOf("send_sms", "run_shell")

    /** Words in a tapped label that mark it as potentially destructive. */
    private val SENSITIVE_ACTION_KEYWORDS = listOf(
        "delete", "erase", "uninstall", "pay", "purchase",
        "checkout", "transfer", "format", "reset"
    )

    /** Packages we refuse to launch even if installed. */
    private val BLOCKED_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.ManagedProvisioning"
    )

    fun requiresConfirmation(toolName: String, args: JsonObject): Boolean {
        if (toolName in DESTRUCTIVE_TOOLS) return true
        if (toolName == "click_text") {
            val target = args.get("text")?.asString?.lowercase().orEmpty()
            if (SENSITIVE_ACTION_KEYWORDS.any { target.contains(it) }) return true
        }
        return false
    }

    /**
     * Validates a package name before launching it.
     */
    fun isPackageAllowed(packageName: String): Boolean {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return false
        if (cleaned in BLOCKED_PACKAGES) return false
        return Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+\$").matches(cleaned)
    }

    /**
     * True when the user has switched Buddy's accessibility service on.
     * Android requires this to be done by hand - no app can enable itself.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        if (AutoService.isRunning()) return true
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceName = AutoService::class.java.name
        return enabled.split(':').any { it.endsWith(serviceName) }
    }

    /**
     * Biometric prompt if the device supports it; otherwise allow and rely on
     * the warm confirmation dialog that already happened.
     */
    suspend fun authenticateIfAvailable(activity: FragmentActivity): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(activity).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return true

        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // let the user retry in the system UI
                    }
                }
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.biometric_title))
                .setSubtitle(activity.getString(R.string.biometric_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()

            prompt.authenticate(info)
        }
    }
}
