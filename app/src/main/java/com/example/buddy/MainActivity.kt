package com.example.buddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.buddy.agent.AgentEvent
import com.example.buddy.ai.ModelCatalog
import com.example.buddy.ai.ProviderKind
import com.example.buddy.ai.RoutingStrategy
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.databinding.ActivityMainBinding
import com.example.buddy.security.SecurityManager
import com.example.buddy.security.SecureVault
import com.example.buddy.ui.ConfirmDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Buddy's chat screen.
 *
 * Notable behaviours:
 * - Most commands work with **no API key and no accessibility** (local fast path).
 * - Hands-free mode keeps the mic open in a loop and speaks replies out loud.
 * - The header shows whether accessibility is on, and lets you fix it in one tap.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter

    private val app: AiAgentApp get() = application as AiAgentApp

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var handsFree = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Consecutive in-process recogniser failures; drives the fallback. */
    private var voiceFailures = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else toast(getString(R.string.voice_permission_needed))
    }

    /** Fallback path: the system's own voice-input screen. */
    private val voiceActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            voiceFailures = 0
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                binding.etMessage.setText(spoken)
                submitCurrentMessage()
            } else if (handsFree) {
                scheduleRestart()
            }
        } else if (handsFree) {
            scheduleRestart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupInputs()
        setupSpeechRecognizer()
        setupTts()
        observeChatHistory()
        observeAgentEvents()
        seedWelcomeIfNeeded()

        handsFree = SecureVault.isHandsFree()
        renderHandsFreeButton()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.adapter = chatAdapter
    }

    private fun setupInputs() {
        binding.btnSend.setOnClickListener { submitCurrentMessage() }
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCurrentMessage()
                true
            } else {
                false
            }
        }
        binding.btnVoice.setOnClickListener { toggleVoiceInput() }
        binding.btnHandsFree.setOnClickListener { toggleHandsFree() }
        binding.btnSettings.setOnClickListener { showAiSettings() }
        binding.tvAccessibility.setOnClickListener { openAccessibilitySettings() }
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
                                SESSION_ID,
                                ChatRepository.ROLE_THINKING,
                                getString(R.string.bubble_thinking)
                            )
                        }

                        is AgentEvent.ToolCompleted -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            app.chatRepository.addEventBubble(
                                SESSION_ID,
                                ChatRepository.ROLE_TOOL_RESULT,
                                getString(R.string.bubble_tool_result, event.result),
                                tool = event.toolName
                            )
                        }

                        is AgentEvent.ConfirmationNeeded -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            app.chatRepository.addEventBubble(
                                SESSION_ID,
                                ChatRepository.ROLE_CONFIRMATION,
                                getString(R.string.bubble_confirmation),
                                tool = event.toolName,
                                args = event.args.toString()
                            )
                        }

                        is AgentEvent.AssistantReply -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            refreshStatus()
                            speak(event.summary)
                            if (handsFree) scheduleRestart()
                        }

                        is AgentEvent.Error -> {
                            app.chatRepository.clearThinkingBubbles(SESSION_ID)
                            val text = if (event.isMissingKey) {
                                getString(R.string.error_no_key)
                            } else {
                                getString(R.string.bubble_error, event.reason)
                            }
                            app.chatRepository.addEventBubble(
                                SESSION_ID, ChatRepository.ROLE_ERROR, text
                            )
                            refreshStatus()
                            if (handsFree) scheduleRestart()
                        }
                    }
                }
            }
        }
    }

    private fun submitCurrentMessage() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.etMessage.setText("")
        lifecycleScope.launch {
            app.agentOrchestrator.handleUserInput(
                sessionId = SESSION_ID,
                userText = text,
                confirmationGate = { tool, args ->
                    ConfirmDialog.confirm(this@MainActivity, tool, args)
                }
            )
        }
    }

    private fun seedWelcomeIfNeeded() {
        lifecycleScope.launch {
            if (app.chatRepository.getRecentHistory(SESSION_ID, limit = 1).isEmpty()) {
                app.chatRepository.addAssistantMessage(
                    SESSION_ID, getString(R.string.welcome_message)
                )
            }
        }
    }

    private fun refreshStatus() {
        val enabled = SecurityManager.isAccessibilityEnabled(this)
        binding.tvAccessibility.text = getString(
            if (enabled) R.string.accessibility_on else R.string.accessibility_off
        )
        binding.tvAccessibility.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.buddy_amber_primary else R.color.buddy_text_secondary
            )
        )
        binding.tvStatusSubtitle.text = if (handsFree && isListening) {
            getString(R.string.voice_listening)
        } else {
            getString(R.string.settings_budget_used, app.agentOrchestrator.budgetSnapshot())
        }
    }

    private fun openAccessibilitySettings() {
        toast(getString(R.string.accessibility_explainer))
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------- voice in ----------------

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun speak(text: String) {
        if (!SecureVault.isSpeakReplies()) return
        val engine = tts ?: return
        if (!ttsReady) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "buddy-${System.currentTimeMillis()}")
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = null
            return
        }
        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        voiceFailures = 0
                        refreshStatus()
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        voiceFailures++
                        Log.w(TAG, "SpeechRecognizer error code=$error (failures=$voiceFailures)")
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                if (handsFree) scheduleRestart() else {
                                    toast(getString(R.string.voice_heard_nothing))
                                    refreshStatus()
                                }

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                mainHandler.postDelayed({ if (handsFree) startListening() }, 500L)

                            else ->
                                if (!handsFree || voiceFailures >= 2) {
                                    toast(getString(R.string.voice_falling_back))
                                    launchSystemRecognizer()
                                } else {
                                    scheduleRestart()
                                }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        voiceFailures = 0
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (spoken.isNotEmpty()) {
                            binding.etMessage.setText(spoken)
                            submitCurrentMessage()
                        } else if (handsFree) {
                            scheduleRestart()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create SpeechRecognizer", t)
            null
        }
    }

    private fun toggleVoiceInput() {
        if (isListening) {
            stopListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            launchSystemRecognizer()
            return
        }
        if (speechRecognizer == null) setupSpeechRecognizer()
        val recognizer = speechRecognizer
        if (recognizer == null) {
            launchSystemRecognizer()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            // cancel() first: startListening on a busy recogniser throws.
            recognizer.cancel()
            recognizer.startListening(intent)
            isListening = true
            refreshStatus()
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            isListening = false
            launchSystemRecognizer()
        }
    }

    /**
     * Fallback that opens the system's own voice-input screen. Less slick than the
     * in-process recogniser, but it works on any device that has a speech app.
     */
    private fun launchSystemRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening))
        }
        if (intent.resolveActivity(packageManager) == null) {
            toast(getString(R.string.voice_unavailable))
            stopHandsFree()
            return
        }
        try {
            voiceActivityLauncher.launch(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "system recogniser launch failed", t)
            toast(getString(R.string.voice_unavailable))
            stopHandsFree()
        }
    }

    private fun stopHandsFree() {
        handsFree = false
        SecureVault.setHandsFree(false)
        renderHandsFreeButton()
        isListening = false
        refreshStatus()
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Throwable) {
            // nothing we can do here
        }
        refreshStatus()
    }

    /** Hands-free: keep the mic cycling so you never have to tap again. */
    private fun scheduleRestart() {
        if (!handsFree) return
        mainHandler.postDelayed({
            if (handsFree && !isFinishing && !isListening) startListening()
        }, RESTART_DELAY_MS)
    }

    private fun toggleHandsFree() {
        handsFree = !handsFree
        SecureVault.setHandsFree(handsFree)
        renderHandsFreeButton()
        if (handsFree) {
            toast(getString(R.string.hands_free_hint))
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            stopListening()
        }
    }

    private fun renderHandsFreeButton() {
        binding.btnHandsFree.text = getString(
            if (handsFree) R.string.hands_free_on else R.string.hands_free_off
        )
    }

    // ---------------- AI settings ----------------

    private fun showAiSettings() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val provider = SecureVault.getProvider()

        fun caption(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_secondary))
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 8)
        }

        col.addView(caption(getString(R.string.settings_current, "${provider.label} - ${SecureVault.getModel()}")))
        col.addView(caption(getString(R.string.settings_budget_used, app.agentOrchestrator.budgetSnapshot())))

        val changeBtn = MaterialButton(this).apply { text = getString(R.string.settings_change_model) }
        col.addView(changeBtn)

        col.addView(caption(getString(R.string.settings_key, provider.label)))
        val keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.settings_api)
            setText(SecureVault.getKey(provider.id).orEmpty())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
        }
        col.addView(keyInput)

        col.addView(caption(getString(R.string.settings_base_url)))
        val baseInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = provider.defaultBaseUrl ?: "https://..."
            setText(SecureVault.getBaseUrl(provider.id).orEmpty())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
        }
        col.addView(baseInput)

        val testBtn = MaterialButton(this).apply {
            text = getString(R.string.settings_test)
            setOnClickListener {
                SecureVault.saveKey(provider.id, keyInput.text.toString())
                SecureVault.saveBaseUrl(provider.id, baseInput.text.toString())
                text = getString(R.string.settings_testing)
                lifecycleScope.launch {
                    val result = app.modelRouter.testConnection()
                    toast(
                        if (result.isSuccess) getString(R.string.settings_test_ok)
                        else getString(
                            R.string.settings_test_fail,
                            result.exceptionOrNull()?.message ?: "?"
                        )
                    )
                    text = getString(R.string.settings_test)
                    refreshStatus()
                }
            }
        }
        col.addView(testBtn)

        val strategyBtn = MaterialButton(this).apply {
            text = getString(
                R.string.settings_routing,
                RoutingStrategy.fromId(SecureVault.getStrategy()).label
            )
            setOnClickListener {
                val all = RoutingStrategy.values()
                val index = all.indexOfFirst { it.id == SecureVault.getStrategy() }
                val next = all[(index + 1) % all.size]
                SecureVault.saveStrategy(next.id)
                text = getString(R.string.settings_routing, next.label)
            }
        }
        col.addView(strategyBtn)

        val budgetBtn = MaterialButton(this).apply {
            text = getString(
                R.string.settings_budget,
                "${SecureVault.getMaxCalls()} calls / ${SecureVault.getMaxTokens()} tokens"
            )
            setOnClickListener {
                val presets = listOf(4 to 4000, 8 to 9000, 20 to 25000, 50 to 60000)
                val index = presets.indexOfFirst { it.first == SecureVault.getMaxCalls() }
                val next = presets[(index + 1) % presets.size]
                SecureVault.saveBudget(next.first, next.second)
                app.modelRouter.setLimits(next.first, next.second)
                text = getString(R.string.settings_budget, "${next.first} calls / ${next.second} tokens")
            }
        }
        col.addView(budgetBtn)

        fun addSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            col.addView(
                SwitchMaterial(this).apply {
                    text = label
                    isChecked = checked
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
                    setOnCheckedChangeListener { _, value -> onChange(value) }
                }
            )
        }

        addSwitch(getString(R.string.toggle_speak), SecureVault.isSpeakReplies()) {
            SecureVault.setSpeakReplies(it)
        }
        addSwitch(getString(R.string.toggle_snapshot), SecureVault.isSendSnapshot()) {
            SecureVault.setSendSnapshot(it)
        }
        addSwitch(getString(R.string.toggle_multistep), SecureVault.isMultiStep()) {
            SecureVault.setMultiStep(it)
        }

        col.addView(
            MaterialButton(this).apply {
                text = getString(R.string.settings_accessibility)
                setOnClickListener { openAccessibilitySettings() }
            }
        )
        col.addView(caption(getString(R.string.accessibility_explainer)))

        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.settings_ai_title))
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                SecureVault.saveKey(provider.id, keyInput.text.toString())
                SecureVault.saveBaseUrl(provider.id, baseInput.text.toString())
                toast(getString(R.string.settings_saved))
                refreshStatus()
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .create()

        changeBtn.setOnClickListener {
            dialog.dismiss()
            showProviderPicker()
        }

        dialog.show()
    }

    private fun showProviderPicker() {
        val providers = ProviderKind.values()
        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.pick_provider))
            .setItems(providers.map { it.label }.toTypedArray()) { _, which ->
                val chosen = providers[which]
                SecureVault.saveProvider(chosen.id)
                SecureVault.saveModel(ModelCatalog.defaultFor(chosen))
                showModelPicker(chosen)
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun showModelPicker(provider: ProviderKind) {
        val models = ModelCatalog.forProvider(provider)
        val labels = (models.map { "${it.label}  (${it.tier.label})" } +
            getString(R.string.custom_model)).toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.pick_model))
            .setItems(labels) { _, which ->
                if (which < models.size) {
                    SecureVault.saveModel(models[which].id)
                    toast(getString(R.string.settings_current, "${provider.label} - ${models[which].id}"))
                } else {
                    promptCustomModel(provider)
                }
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun promptCustomModel(provider: ProviderKind) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.custom_model_hint)
            setText(SecureVault.getModel())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
        }
        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.custom_model))
            .setView(LinearLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            })
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    SecureVault.saveModel(id)
                    toast(getString(R.string.settings_current, "${provider.label} - $id"))
                }
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    companion object {
        private const val SESSION_ID = "default_buddy_session"
        private const val RESTART_DELAY_MS = 700L
        private const val TAG = "Buddy"
    }
}
