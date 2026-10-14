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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.buddy.agent.AgentEvent
import com.example.buddy.ai.ModelCatalog
import com.example.buddy.ai.ProviderKind
import com.example.buddy.ai.RoutingStrategy
import com.example.buddy.auth.GoogleSignInManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.buddy.data.model.SessionRow
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.databinding.ActivityMainBinding
import com.example.buddy.security.SecurityManager
import com.example.buddy.security.SecureVault
import com.example.buddy.ui.ConfirmDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ChatGPT-style shell around Buddy:
 * - a history drawer with every conversation
 * - a model switcher in the header
 * - right-aligned user bubbles and plain assistant replies
 * - a pill composer with voice + hands-free
 * - Sign in with Google to personalise replies
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var sessionAdapter: SessionAdapter

    private val app: AiAgentApp get() = application as AiAgentApp

    private var currentSessionId: String = newSessionId()
    private var sessionJob: Job? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var handsFree = false
    private var voiceFailures = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else toast(getString(R.string.voice_permission_needed))
    }

    private val voiceActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            voiceFailures = 0
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
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

        savedInstanceState?.getString(STATE_SESSION)?.let { currentSessionId = it }

        setupRecyclerViews()
        setupTopBar()
        setupComposer()
        setupChips()
        setupAccount()
        setupBackHandling()
        setupSpeechRecognizer()
        setupTts()

        handsFree = SecureVault.isHandsFree()

        observeChat()
        observeSessions()
        observeAgentEvents()
        refreshAccount()
        refreshTopBar()
    }

    override fun onResume() {
        super.onResume()
        refreshTopBar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SESSION, currentSessionId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupRecyclerViews() {
        chatAdapter = ChatAdapter()
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.adapter = chatAdapter

        sessionAdapter = SessionAdapter(
            onClick = { row -> openSession(row) },
            onLongClick = { row -> confirmDeleteSession(row) }
        )
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = sessionAdapter
    }

    private fun setupTopBar() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnNewChat.setOnClickListener { startNewChat() }
        binding.modelSwitcher.setOnClickListener { showProviderPicker() }
    }

    private fun setupComposer() {
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
        binding.btnVoice.setOnLongClickListener {
            toggleHandsFree()
            true
        }
    }

    private fun setupChips() {
        val chips = listOf(binding.chip1, binding.chip2, binding.chip3, binding.chip4)
        chips.forEach { chip ->
            chip.setOnClickListener {
                binding.etMessage.setText(chip.text)
                binding.etMessage.setSelection(chip.text.length)
                submitCurrentMessage()
            }
        }
    }

    // ---------------- conversations ----------------

    private fun observeChat() {
        sessionJob?.cancel()
        sessionJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.chatRepository.observeMessages(currentSessionId).collectLatest { messages ->
                    chatAdapter.submitList(messages) {
                        val empty = messages.isEmpty()
                        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                        binding.rvChat.visibility = if (empty) View.GONE else View.VISIBLE
                        if (!empty) binding.rvChat.smoothScrollToPosition(messages.size - 1)
                    }
                }
            }
        }
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.chatRepository.observeSessions().collectLatest { rows ->
                    sessionAdapter.submitList(rows)
                }
            }
        }
    }

    private fun startNewChat() {
        currentSessionId = newSessionId()
        binding.etMessage.setText("")
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        observeChat()
        refreshTopBar()
    }

    private fun openSession(row: SessionRow) {
        currentSessionId = row.sessionId
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        observeChat()
        refreshTopBar()
    }

    private fun confirmDeleteSession(row: SessionRow) {
        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.delete_chat_title))
            .setMessage(getString(R.string.delete_chat_body))
            .setPositiveButton(getString(R.string.delete_chat_ok)) { _, _ ->
                lifecycleScope.launch {
                    app.chatRepository.deleteSession(row.sessionId)
                    if (row.sessionId == currentSessionId) startNewChat()
                }
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun submitCurrentMessage() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.etMessage.setText("")
        lifecycleScope.launch {
            app.agentOrchestrator.handleUserInput(
                sessionId = currentSessionId,
                userText = text,
                confirmationGate = { tool, args ->
                    ConfirmDialog.confirm(this@MainActivity, tool, args)
                }
            )
        }
    }

    private fun observeAgentEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.agentOrchestrator.events.collect { event ->
                    when (event) {
                        is AgentEvent.Thinking -> {
                            app.chatRepository.clearThinkingBubbles(currentSessionId)
                            app.chatRepository.addEventBubble(
                                currentSessionId,
                                ChatRepository.ROLE_THINKING,
                                getString(R.string.bubble_thinking)
                            )
                        }

                        is AgentEvent.ToolCompleted -> {
                            app.chatRepository.clearThinkingBubbles(currentSessionId)
                            app.chatRepository.addEventBubble(
                                currentSessionId,
                                ChatRepository.ROLE_TOOL_RESULT,
                                getString(R.string.bubble_tool_result, event.result),
                                tool = event.toolName
                            )
                        }

                        is AgentEvent.ConfirmationNeeded -> {
                            app.chatRepository.clearThinkingBubbles(currentSessionId)
                            app.chatRepository.addEventBubble(
                                currentSessionId,
                                ChatRepository.ROLE_CONFIRMATION,
                                getString(R.string.bubble_confirmation),
                                tool = event.toolName,
                                args = event.args.toString()
                            )
                        }

                        is AgentEvent.AssistantReply -> {
                            app.chatRepository.clearThinkingBubbles(currentSessionId)
                            refreshTopBar()
                            speak(event.summary)
                            if (handsFree) scheduleRestart()
                        }

                        is AgentEvent.Error -> {
                            app.chatRepository.clearThinkingBubbles(currentSessionId)
                            val text = if (event.isMissingKey) {
                                getString(R.string.error_no_key)
                            } else {
                                getString(R.string.bubble_error, event.reason)
                            }
                            app.chatRepository.addEventBubble(
                                currentSessionId, ChatRepository.ROLE_ERROR, text
                            )
                            refreshTopBar()
                            if (handsFree) scheduleRestart()
                        }
                    }
                }
            }
        }
    }

    private fun refreshTopBar() {
        val model = ModelCatalog.find(SecureVault.getModel())?.label ?: SecureVault.getModel()
        binding.tvTopSubtitle.text = "$model  -  ${app.agentOrchestrator.budgetSnapshot()}"
        binding.tvGreeting.text = SecureVault.getAccountGivenName()
            ?.let { getString(R.string.empty_greeting_named, it) }
            ?: getString(R.string.empty_greeting)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------- voice in / voice out ----------------

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
                                if (handsFree) scheduleRestart() else
                                    toast(getString(R.string.voice_heard_nothing))

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
                            ?.firstOrNull()?.trim().orEmpty()
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
            recognizer.cancel()
            recognizer.startListening(intent)
            isListening = true
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            isListening = false
            launchSystemRecognizer()
        }
    }

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

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Throwable) {
            // nothing to do
        }
    }

    private fun scheduleRestart() {
        if (!handsFree) return
        mainHandler.postDelayed({
            if (handsFree && !isFinishing && !isListening) startListening()
        }, RESTART_DELAY_MS)
    }

    private fun toggleHandsFree() {
        handsFree = !handsFree
        SecureVault.setHandsFree(handsFree)
        if (handsFree) {
            toast(getString(R.string.hands_free_hint))
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            stopListening()
            toast(getString(R.string.hands_free_off))
        }
    }

    private fun stopHandsFree() {
        handsFree = false
        SecureVault.setHandsFree(false)
        isListening = false
    }

    // ---------------- Google account ----------------

    private fun setupAccount() {
        binding.accountRow.setOnClickListener {
            if (SecureVault.isSignedIn()) showSignOutDialog() else signInWithGoogle()
        }
        binding.btnGoogle.setOnClickListener {
            if (SecureVault.isSignedIn()) showSignOutDialog() else signInWithGoogle()
        }
    }

    private fun refreshAccount() {
        val name = SecureVault.getAccountName()
        val email = SecureVault.getAccountEmail()
        if (name != null && email != null) {
            binding.tvAccountName.text = name
            binding.tvAccountEmail.text = email
            binding.btnGoogle.text = getString(R.string.sign_out)
            binding.tvAvatar.text = SecureVault.getAccountGivenName()?.firstOrNull()?.uppercase()
                ?: name.firstOrNull()?.uppercase()
                ?: "B"
        } else {
            binding.tvAccountName.text = getString(R.string.not_signed_in)
            binding.tvAccountEmail.text = getString(R.string.sign_in_subtitle)
            binding.btnGoogle.text = getString(R.string.sign_in_with_google)
            binding.tvAvatar.text = "B"
        }
        refreshTopBar()
    }

    private fun signInWithGoogle() {
        val clientId = SecureVault.getGoogleClientId()
        if (clientId.isNullOrBlank()) {
            MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
                .setTitle(getString(R.string.sign_in_with_google))
                .setMessage(getString(R.string.google_no_client_id))
                .setPositiveButton(getString(R.string.settings_title)) { _, _ -> showAiSettings() }
                .setNegativeButton(getString(R.string.settings_cancel), null)
                .show()
            return
        }

        lifecycleScope.launch {
            val result = GoogleSignInManager.signIn(this@MainActivity, clientId)
            result.onSuccess { account ->
                SecureVault.saveAccount(account.displayName, account.email, account.givenName)
                refreshAccount()
                toast(getString(R.string.google_welcome, account.email, account.givenName))
            }.onFailure { error ->
                Log.w(TAG, "sign-in failed", error)
                when (error) {
                    is GetCredentialCancellationException ->
                        toast(getString(R.string.google_signin_cancelled))

                    is NoCredentialException ->
                        toast(getString(R.string.google_no_accounts))

                    else -> toast(
                        getString(
                            R.string.google_signin_failed,
                            error.message?.take(120) ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    private fun showSignOutDialog() {
        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.sign_out_title))
            .setMessage(getString(R.string.sign_out_body))
            .setPositiveButton(getString(R.string.sign_out)) { _, _ ->
                SecureVault.clearAccount()
                refreshAccount()
                toast(getString(R.string.google_signed_out))
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    // ---------------- settings ----------------

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

        fun input(hintText: String, value: String, password: Boolean = false): EditText =
            EditText(this).apply {
                hint = hintText
                setText(value)
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
            }

        col.addView(caption(getString(R.string.settings_current, "${provider.label} - ${SecureVault.getModel()}")))
        col.addView(caption(getString(R.string.settings_budget_used, app.agentOrchestrator.budgetSnapshot())))

        val changeBtn = MaterialButton(this).apply { text = getString(R.string.settings_change_model) }
        col.addView(changeBtn)

        col.addView(caption(getString(R.string.settings_key, provider.label)))
        val keyInput = input(getString(R.string.settings_api), SecureVault.getKey(provider.id).orEmpty(), true)
        col.addView(keyInput)

        col.addView(caption(getString(R.string.settings_base_url)))
        val baseInput = input(provider.defaultBaseUrl ?: "https://...", SecureVault.getBaseUrl(provider.id).orEmpty())
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
                        else getString(R.string.settings_test_fail, result.exceptionOrNull()?.message ?: "?")
                    )
                    text = getString(R.string.settings_test)
                    refreshTopBar()
                }
            }
        }
        col.addView(testBtn)

        val strategyBtn = MaterialButton(this).apply {
            text = getString(R.string.settings_routing, RoutingStrategy.fromId(SecureVault.getStrategy()).label)
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
        addSwitch(getString(R.string.hands_free_switch), SecureVault.isHandsFree()) { on ->
            if (on != handsFree) toggleHandsFree()
        }

        col.addView(caption(getString(R.string.google_client_id_label)))
        val googleInput = input(
            getString(R.string.google_client_id_hint),
            SecureVault.getGoogleClientId().orEmpty()
        )
        col.addView(googleInput)

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
                SecureVault.saveGoogleClientId(googleInput.text.toString())
                toast(getString(R.string.settings_saved))
                refreshTopBar()
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
                refreshTopBar()
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun promptCustomModel(provider: ProviderKind) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            hint = getString(R.string.custom_model_hint)
            setText(SecureVault.getModel())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.buddy_text_primary))
        }
        MaterialAlertDialogBuilder(this, R.style.Theme_Buddy_Dialog)
            .setTitle(getString(R.string.custom_model))
            .setView(LinearLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(field)
            })
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val id = field.text.toString().trim()
                if (id.isNotEmpty()) {
                    SecureVault.saveModel(id)
                    toast(getString(R.string.settings_current, "${provider.label} - $id"))
                    refreshTopBar()
                }
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun openAccessibilitySettings() {
        toast(getString(R.string.accessibility_explainer))
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    companion object {
        private const val TAG = "Buddy"
        private const val RESTART_DELAY_MS = 700L
        private const val STATE_SESSION = "current_session"

        private fun newSessionId(): String = "s" + System.currentTimeMillis()
    }
}
