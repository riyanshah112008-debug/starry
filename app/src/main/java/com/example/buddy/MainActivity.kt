package com.example.buddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.buddy.agent.AgentEvent
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.databinding.ActivityMainBinding
import com.example.buddy.security.SecureVault
import com.example.buddy.ui.ConfirmDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main chat interface for Buddy using ViewBinding (no Jetpack Compose).
 * Features:
 * - Warm chat bubble timeline backed by Room Flow
 * - Voice input button powered by Android's [SpeechRecognizer]
 * - Text input field with IME Send support
 * - Settings gear dialog to save the OpenAI API key in [SecureVault] and jump to
 *   Accessibility Settings
 * - Observes [AgentEvent]s and renders Thinking, Tool result, Confirmation, and Error
 *   bubbles in Buddy's warm voice.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter

    private val app: AiAgentApp
        get() = application as AiAgentApp

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(
                this,
                getString(R.string.voice_permission_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupInputs()
        setupSpeechRecognizer()
        observeChatHistory()
        observeAgentEvents()
        seedWelcomeMessageIfNeeded()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = chatAdapter
    }

    private fun setupInputs() {
        binding.btnSend.setOnClickListener {
            submitCurrentMessage()
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCurrentMessage()
                true
            } else {
                false
            }
        }

        binding.btnVoice.setOnClickListener {
            toggleVoiceInput()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun submitCurrentMessage() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        binding.etMessage.setText("")

        if (!SecureVault.hasApiKey()) {
            lifecycleScope.launch {
                app.chatRepository.addUserMessage(SESSION_ID, text)
                app.chatRepository.addEventBubble(
                    sessionId = SESSION_ID,
                    role = ChatRepository.ROLE_ERROR,
                    content = getString(R.string.error_no_key)
                )
            }
            return
        }

        lifecycleScope.launch {
            app.agentOrchestrator.handleUserInput(
                sessionId = SESSION_ID,
                userText = text,
                confirmationGate = { toolName, args ->
                    ConfirmDialog.confirm(this@MainActivity, toolName, args)
                }
            )
        }
    }

    private fun observeChatHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.chatRepository.observeMessages(SESSION_ID).collectLatest { messages ->
                    chatAdapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            binding.rvChat.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun observeAgentEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.agentOrchestrator.events.collect { event ->
                    when (event) {
                        is AgentEvent.Thinking -> {
                            binding.tvStatusSubtitle.text = getString(R.string.status_thinking)
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            app.chatRepository.addEventBubble(
                                sessionId = SESSION_ID,
                                role = ChatRepository.ROLE_THINKING,
                                content = getString(R.string.bubble_thinking)
                            )
                        }

                        is AgentEvent.ToolCompleted -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            app.chatRepository.addEventBubble(
                                sessionId = SESSION_ID,
                                role = ChatRepository.ROLE_TOOL_RESULT,
                                content = getString(R.string.bubble_tool_result, event.result),
                                tool = event.toolName
                            )
                        }

                        is AgentEvent.ConfirmationNeeded -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            app.chatRepository.addEventBubble(
                                sessionId = SESSION_ID,
                                role = ChatRepository.ROLE_CONFIRMATION,
                                content = getString(R.string.bubble_confirmation),
                                tool = event.toolName,
                                args = event.args.toString()
                            )
                        }

                        is AgentEvent.AssistantReply -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            binding.tvStatusSubtitle.text = getString(R.string.status_done)
                        }

                        is AgentEvent.Error -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            binding.tvStatusSubtitle.text = getString(R.string.error_generic)
                            val bubbleText = if (event.isMissingKey) {
                                getString(R.string.error_no_key)
                            } else {
                                getString(R.string.bubble_error, event.reason)
                            }
                            app.chatRepository.addEventBubble(
                                sessionId = SESSION_ID,
                                role = ChatRepository.ROLE_ERROR,
                                content = bubbleText
                            )
                        }
                    }
                }
            }
        }
    }

    private fun seedWelcomeMessageIfNeeded() {
        lifecycleScope.launch {
            val recent = app.chatRepository.getRecentHistory(SESSION_ID, limit = 1)
            if (recent.isEmpty()) {
                app.chatRepository.addAssistantMessage(
                    sessionId = SESSION_ID,
                    content = getString(R.string.welcome_message)
                )
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    binding.tvStatusSubtitle.text = getString(R.string.voice_listening)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    binding.tvStatusSubtitle.text = getString(R.string.status_ready)
                }

                override fun onError(error: Int) {
                    isListening = false
                    binding.tvStatusSubtitle.text = getString(R.string.status_ready)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.voice_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    binding.tvStatusSubtitle.text = getString(R.string.status_ready)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim().orEmpty()
                    if (spoken.isNotEmpty()) {
                        binding.etMessage.setText(spoken)
                        binding.etMessage.setSelection(spoken.length)
                        submitCurrentMessage()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleVoiceInput() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            binding.tvStatusSubtitle.text = getString(R.string.status_ready)
        } else {
            startVoiceRecognition()
        }
    }

    private fun startVoiceRecognition() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Toast.makeText(this, getString(R.string.voice_error), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 4)
        }

        val apiInput = EditText(this).apply {
            hint = getString(R.string.settings_api)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SecureVault.getApiKey().orEmpty())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_secondary))
        }
        container.addView(apiInput)

        val accessibilityBtn = MaterialButton(this).apply {
            text = getString(R.string.settings_enable_accessibility)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        container.addView(accessibilityBtn)

        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.settings_title))
            .setView(container)
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val newKey = apiInput.text?.toString()?.trim().orEmpty()
                SecureVault.saveApiKey(newKey)
                Toast.makeText(
                    this,
                    getString(R.string.settings_saved),
                    Toast.LENGTH_SHORT
                ).show()
                binding.tvStatusSubtitle.text = getString(R.string.status_ready)
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "default_buddy_session"
    }
}
