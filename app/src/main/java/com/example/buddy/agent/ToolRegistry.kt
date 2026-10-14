package com.example.buddy.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.buddy.AutoService
import com.example.buddy.data.repo.MemoryRepository
import com.example.buddy.security.SecurityManager
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Result of executing a single tool in [ToolRegistry].
 */
data class ToolResult(
    val success: Boolean,
    val output: String
)

/**
 * Executes Buddy's available tools:
 * - open_app
 * - click_text
 * - type_text
 * - scroll
 * - back
 * - home
 * - open_url
 * - send_sms
 * - run_shell
 * - remember
 * - recall
 */
class ToolRegistry(
    private val context: Context,
    private val memoryRepository: MemoryRepository
) {

    suspend fun execute(toolName: String, args: JsonObject): ToolResult {
        return try {
            when (toolName) {
                "open_app" -> openApp(args)
                "click_text" -> clickText(args)
                "type_text" -> typeText(args)
                "scroll" -> scroll(args)
                "back" -> back()
                "home" -> home()
                "open_url" -> openUrl(args)
                "send_sms" -> sendSms(args)
                "run_shell" -> runShell(args)
                "remember" -> remember(args)
                "recall" -> recall(args)
                else -> ToolResult(
                    success = false,
                    output = "Unknown tool '$toolName'."
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                output = e.message ?: "Unexpected error running $toolName."
            )
        }
    }

    private fun openApp(args: JsonObject): ToolResult {
        val pkg = args.get("package")?.asString?.trim().orEmpty()
        if (pkg.isEmpty()) {
            return ToolResult(false, "Missing 'package' argument.")
        }
        if (!SecurityManager.isPackageAllowed(pkg)) {
            return ToolResult(false, "That package ($pkg) isn't allowed for safety.")
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult(false, "The app ($pkg) isn't installed.")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return ToolResult(true, "Opened $pkg")
    }

    private fun clickText(args: JsonObject): ToolResult {
        val text = args.get("text")?.asString?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolResult(false, "Missing 'text' to click.")
        }
        val service = AutoService.instance
            ?: return ToolResult(false, "Accessibility service isn't active yet.")

        val clicked = service.clickByText(text)
        return if (clicked) {
            ToolResult(true, "Tapped '$text'")
        } else {
            ToolResult(false, "Couldn't find '$text' on the screen.")
        }
    }

    private fun typeText(args: JsonObject): ToolResult {
        val text = args.get("text")?.asString.orEmpty()
        if (text.isEmpty()) {
            return ToolResult(false, "Missing 'text' to type.")
        }
        val service = AutoService.instance
            ?: return ToolResult(false, "Accessibility service isn't active yet.")

        val typed = service.typeText(text)
        return if (typed) {
            ToolResult(true, "Typed '$text'")
        } else {
            ToolResult(false, "Couldn't find an input field on screen.")
        }
    }

    private fun scroll(args: JsonObject): ToolResult {
        val direction = args.get("direction")?.asString?.trim()?.lowercase() ?: "forward"
        val service = AutoService.instance
            ?: return ToolResult(false, "Accessibility service isn't active yet.")

        val scrolled = if (direction == "backward" || direction == "up") {
            service.scrollBackward()
        } else {
            service.scrollForward()
        }

        return if (scrolled) {
            ToolResult(true, "Scrolled $direction")
        } else {
            ToolResult(false, "Nothing scrollable found on screen.")
        }
    }

    private fun back(): ToolResult {
        val service = AutoService.instance
            ?: return ToolResult(false, "Accessibility service isn't active yet.")
        val ok = service.back()
        return if (ok) {
            ToolResult(true, "Went back")
        } else {
            ToolResult(false, "Couldn't navigate back.")
        }
    }

    private fun home(): ToolResult {
        val service = AutoService.instance
            ?: return ToolResult(false, "Accessibility service isn't active yet.")
        val ok = service.home()
        return if (ok) {
            ToolResult(true, "Went to Home screen")
        } else {
            ToolResult(false, "Couldn't go to Home screen.")
        }
    }

    private fun openUrl(args: JsonObject): ToolResult {
        val rawUrl = args.get("url")?.asString?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            return ToolResult(false, "Missing 'url' argument.")
        }
        val normalizedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult(true, "Opened $normalizedUrl")
    }

    private fun sendSms(args: JsonObject): ToolResult {
        val number = args.get("number")?.asString?.trim().orEmpty()
        val text = args.get("text")?.asString?.trim().orEmpty()
        if (number.isEmpty() || text.isEmpty()) {
            return ToolResult(false, "Both 'number' and 'text' are needed to send an SMS.")
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPermission) {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, text, null, null)
            return ToolResult(true, "Sent text to $number")
        } else {
            // Fallback to opening the user's SMS composer pre-filled if runtime permission isn't granted
            val smsUri = Uri.parse("smsto:$number")
            val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ToolResult(true, "Opened SMS draft to $number")
        }
    }

    private suspend fun runShell(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cmd = args.get("cmd")?.asString?.trim().orEmpty()
        if (cmd.isEmpty()) {
            return@withContext ToolResult(false, "Missing 'cmd' argument.")
        }

        // First check if Termux is installed for Termux RUN_COMMAND service intent
        val termuxInstalled = try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (termuxInstalled) {
            try {
                val intent = Intent("com.termux.RUN_COMMAND").apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                }
                context.startService(intent)
                return@withContext ToolResult(true, "Sent command to Termux: $cmd")
            } catch (_: Exception) {
                // Fall through to local shell execution if Termux service fails
            }
        }

        // Fallback local process execution for basic non-root shell commands
        return@withContext try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(8, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@withContext ToolResult(false, "Command timed out.")
            }

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }
            val summary = if (output.isEmpty()) "exit ${process.exitValue()}" else output.take(300)
            ToolResult(process.exitValue() == 0, summary)
        } catch (e: Exception) {
            ToolResult(false, "Couldn't run command: ${e.message}")
        }
    }

    private suspend fun remember(args: JsonObject): ToolResult {
        val key = args.get("key")?.asString?.trim().orEmpty()
        val value = args.get("value")?.asString?.trim().orEmpty()
        if (key.isEmpty() || value.isEmpty()) {
            return ToolResult(false, "Need both 'key' and 'value' to remember.")
        }
        memoryRepository.remember(key, value)
        return ToolResult(true, "Remembered $key")
    }

    private suspend fun recall(args: JsonObject): ToolResult {
        val key = args.get("key")?.asString?.trim().orEmpty()
        if (key.isEmpty()) {
            return ToolResult(false, "Missing 'key' to recall.")
        }
        val value = memoryRepository.recall(key)
        return if (value != null) {
            ToolResult(true, "$key = $value")
        } else {
            ToolResult(false, "Nothing saved for '$key' yet.")
        }
    }
}
