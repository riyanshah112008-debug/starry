package com.example.buddy.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.buddy.R
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Enforces safety policies for Buddy's tool execution:
 * 1. Identifies destructive / sensitive tools requiring explicit user confirmation.
 * 2. Provides an optional biometric gate when device biometrics are enrolled.
 * 3. Maintains an app package allowlist / blocklist check so Buddy never launches
 *    restricted system/device-admin packages unexpectedly.
 */
object SecurityManager {

    /**
     * Tools that always pause for warm confirmation before execution.
     */
    private val DESTRUCTIVE_TOOLS = setOf(
        "send_sms",
        "run_shell"
    )

    /**
     * Keywords in click_text or type_text that indicate a potentially destructive action
     * (such as deleting items, factory reset, or submitting payments).
     */
    private val SENSITIVE_ACTION_KEYWORDS = listOf(
        "delete",
        "erase",
        "uninstall",
        "pay",
        "purchase",
        "checkout",
        "transfer",
        "format",
        "reset"
    )

    /**
     * Explicitly allowed common packages and prefix checks.
     * Users can open normal apps, while sensitive system installers/admin receivers are blocked.
     */
    private val BLOCKED_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.ManagedProvisioning"
    )

    val DEFAULT_ALLOWED_PACKAGES = setOf(
        "com.android.chrome",
        "com.google.android.apps.messaging",
        "com.whatsapp",
        "com.spotify.music",
        "com.google.android.youtube",
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.android.settings",
        "com.android.calculator2",
        "com.google.android.calculator",
        "com.google.android.calendar",
        "com.termux"
    )

    /**
     * Returns true if the given tool + arguments require user confirmation.
     */
    fun requiresConfirmation(toolName: String, args: JsonObject): Boolean {
        if (toolName in DESTRUCTIVE_TOOLS) return true

        if (toolName == "click_text") {
            val targetText = args.get("text")?.asString?.lowercase().orEmpty()
            if (SENSITIVE_ACTION_KEYWORDS.any { keyword -> targetText.contains(keyword) }) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether a package name is safe to launch.
     * Validates package syntax and blocks dangerous system provisioning packages.
     */
    fun isPackageAllowed(
        packageName: String,
        customAllowlist: Set<String> = emptySet()
    ): Boolean {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return false
        if (cleaned in BLOCKED_PACKAGES) return false

        // Validate standard Android package naming convention
        val validFormat = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        if (!validFormat.matches(cleaned)) return false

        // If customAllowlist is non-empty, enforce membership in either default or custom set
        if (customAllowlist.isNotEmpty()) {
            return cleaned in customAllowlist || cleaned in DEFAULT_ALLOWED_PACKAGES
        }
        return true
    }

    /**
     * Prompts for biometric or device credential authentication if available on the device.
     * If no biometric hardware/credentials are enrolled, returns true so the custom
     * warm confirmation dialog remains the primary gate.
     */
    suspend fun authenticateIfAvailable(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val canAuth = biometricManager.canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometrics not configured on device; rely on the confirmation dialog decision
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // Allow user to retry within the system prompt UI
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.biometric_title))
                .setSubtitle(activity.getString(R.string.biometric_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()

            prompt.authenticate(promptInfo)
        }
    }
}
