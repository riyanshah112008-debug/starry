package com.example.buddy.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
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

/** Result of executing one tool. */
data class ToolResult(val success: Boolean, val output: String)

/**
 * Runs Buddy's tools. Most of them work with no accessibility service at all:
 * open_app, open_url, send_sms, volume, flashlight, set_alarm, remember, recall.
 * Only click_text, type_text, scroll, back and home need accessibility.
 */
class ToolRegistry(
    private val context: Context,
    private val memoryRepository: MemoryRepository
) {

    suspend fun execute(toolName: String, args: JsonObject): ToolResult = try {
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
            "volume" -> volume(args)
            "flashlight" -> flashlight(args)
            "set_alarm" -> setAlarm(args)
            "remember" -> remember(args)
            "recall" -> recall(args)
            else -> ToolResult(false, "Unknown tool '$toolName'.")
        }
    } catch (e: Exception) {
        ToolResult(false, e.message ?: "Unexpected error running $toolName.")
    }

    private fun openApp(args: JsonObject): ToolResult {
        val pkg = args.get("package")?.asString?.trim().orEmpty()
        if (pkg.isEmpty()) return ToolResult(false, "Missing 'package' argument.")
        if (!SecurityManager.isPackageAllowed(pkg)) {
            return ToolResult(false, "That package ($pkg) isn't allowed for safety.")
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult(false, "The app ($pkg) isn't installed.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return ToolResult(true, "Opened $pkg")
    }

    private fun clickText(args: JsonObject): ToolResult {
        val text = args.get("text")?.asString?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult(false, "Missing 'text' to click.")
        val service = AutoService.instance
            ?: return ToolResult(false, "I need hands-free mode switched on for tapping things.")
        return if (service.clickByText(text)) ToolResult(true, "Tapped '$text'")
        else ToolResult(false, "Couldn't find '$text' on the screen.")
    }

    private fun typeText(args: JsonObject): ToolResult {
        val text = args.get("text")?.asString.orEmpty()
        if (text.isEmpty()) return ToolResult(false, "Missing 'text' to type.")
        val service = AutoService.instance
            ?: return ToolResult(false, "I need hands-free mode switched on for typing.")
        return if (service.typeText(text)) ToolResult(true, "Typed '$text'")
        else ToolResult(false, "Couldn't find an input field on screen.")
    }

    private fun scroll(args: JsonObject): ToolResult {
        val direction = args.get("direction")?.asString?.trim()?.lowercase() ?: "forward"
        val service = AutoService.instance
            ?: return ToolResult(false, "I need hands-free mode switched on for scrolling.")
        val ok = if (direction == "backward" || direction == "up") service.scrollBackward()
        else service.scrollForward()
        return if (ok) ToolResult(true, "Scrolled $direction")
        else ToolResult(false, "Nothing scrollable found on screen.")
    }

    private fun back(): ToolResult {
        val service = AutoService.instance
            ?: return ToolResult(false, "I need hands-free mode switched on for that.")
        return if (service.back()) ToolResult(true, "Went back")
        else ToolResult(false, "Couldn't navigate back.")
    }

    private fun home(): ToolResult {
        val service = AutoService.instance
            ?: return ToolResult(false, "I need hands-free mode switched on for that.")
        return if (service.home()) ToolResult(true, "Went to Home screen")
        else ToolResult(false, "Couldn't go to Home screen.")
    }

    private fun openUrl(args: JsonObject): ToolResult {
        val raw = args.get("url")?.asString?.trim().orEmpty()
        if (raw.isEmpty()) return ToolResult(false, "Missing 'url' argument.")
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return ToolResult(true, "Opened $url")
    }

    private fun sendSms(args: JsonObject): ToolResult {
        val number = args.get("number")?.asString?.trim().orEmpty()
        val text = args.get("text")?.asString?.trim().orEmpty()
        if (number.isEmpty() || text.isEmpty()) {
            return ToolResult(false, "Both 'number' and 'text' are needed to send an SMS.")
        }

        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

        if (granted) {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendTextMessage(number, null, text, null, null)
            return ToolResult(true, "Sent text to $number")
        }

        // No runtime permission: hand off to the user's SMS composer pre-filled.
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return ToolResult(true, "Opened an SMS draft to $number")
    }

    private suspend fun runShell(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cmd = args.get("cmd")?.asString?.trim().orEmpty()
        if (cmd.isEmpty()) return@withContext ToolResult(false, "Missing 'cmd' argument.")

        val termuxInstalled = try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (termuxInstalled) {
            try {
                context.startService(
                    Intent("com.termux.RUN_COMMAND").apply {
                        setClassName("com.termux", "com.termux.app.RunCommandService")
                        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    }
                )
                return@withContext ToolResult(true, "Sent to Termux: $cmd")
            } catch (_: Exception) {
                // fall through to a local shell
            }
        }

        try {
            val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext ToolResult(false, "Command timed out.")
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            val summary = if (output.isEmpty()) "exit ${process.exitValue()}" else output.take(300)
            ToolResult(process.exitValue() == 0, summary)
        } catch (e: Exception) {
            ToolResult(false, "Couldn't run that: ${e.message}")
        }
    }

    private fun volume(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString?.trim()?.lowercase() ?: "up"
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        when (action) {
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        return ToolResult(true, "Volume $action")
    }

    private fun flashlight(args: JsonObject): ToolResult {
        val on = args.get("on")?.asBoolean ?: true
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val torchId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(false, "This device doesn't have a torch.")
        cm.setTorchMode(torchId, on)
        return ToolResult(true, if (on) "Turned torch on" else "Turned torch off")
    }

    private fun setAlarm(args: JsonObject): ToolResult {
        val hour = args.get("hour")?.asInt ?: return ToolResult(false, "I need an hour for that alarm.")
        val minute = args.get("minute")?.asInt ?: 0
        val label = args.get("label")?.asString.orEmpty()
        context.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return ToolResult(true, "Alarm set for %02d:%02d".format(hour, minute))
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
        if (key.isEmpty()) return ToolResult(false, "Missing 'key' to recall.")
        val value = memoryRepository.recall(key)
        return if (value != null) ToolResult(true, "$key = $value")
        else ToolResult(false, "Nothing saved for '$key' yet.")
    }
}
