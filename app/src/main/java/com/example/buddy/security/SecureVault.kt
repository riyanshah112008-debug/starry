package com.example.buddy.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.buddy.ai.ModelCatalog
import com.example.buddy.ai.ProviderKind

/**
 * Encrypted key/value store for provider API keys and Buddy's settings.
 * Nothing is ever hardcoded: the user supplies every key at runtime, and it
 * never leaves the device except to the provider it belongs to.
 */
object SecureVault {

    private const val FILE_NAME = "buddy_secure_vault"
    private const val LEGACY_OPENAI_KEY = "openai_api_key"

    private const val PREFIX_KEY = "api_key_"
    private const val PREFIX_BASE_URL = "base_url_"
    private const val KEY_PROVIDER = "provider_id"
    private const val KEY_MODEL = "model_id"
    private const val KEY_STRATEGY = "strategy"
    private const val KEY_MAX_CALLS = "max_calls"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_HANDS_FREE = "hands_free"
    private const val KEY_SPEAK_REPLIES = "speak_replies"
    private const val KEY_SEND_SNAPSHOT = "send_snapshot"
    private const val KEY_MULTI_STEP = "multi_step"
    private const val KEY_GOOGLE_CLIENT_ID = "google_client_id"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_ACCOUNT_EMAIL = "account_email"
    private const val KEY_ACCOUNT_GIVEN = "account_given"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                val app = context.applicationContext
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    app,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }
    }

    private fun p(): SharedPreferences = checkNotNull(prefs) {
        "SecureVault.init() must run in AiAgentApp.onCreate()"
    }

    // ---------------- API keys (one per provider) ----------------

    fun saveKey(providerId: String, value: String) {
        p().edit().putString(PREFIX_KEY + providerId, value.trim()).apply()
    }

    fun getKey(providerId: String): String? {
        val stored = p().getString(PREFIX_KEY + providerId, null)?.trim()
        if (!stored.isNullOrEmpty()) return stored
        // one-time migration from the original single-key layout
        if (providerId == ProviderKind.OPENAI.id) {
            val legacy = p().getString(LEGACY_OPENAI_KEY, null)?.trim()
            if (!legacy.isNullOrEmpty()) return legacy
        }
        return null
    }

    fun hasAnyKey(): Boolean = ProviderKind.values().any { !getKey(it.id).isNullOrBlank() }

    fun savedKeyCount(): Int = ProviderKind.values().count { !getKey(it.id).isNullOrBlank() }

    // ---------------- base URLs (custom / ollama) ----------------

    fun saveBaseUrl(providerId: String, value: String) {
        p().edit().putString(PREFIX_BASE_URL + providerId, value.trim()).apply()
    }

    fun getBaseUrl(providerId: String): String? {
        val value = p().getString(PREFIX_BASE_URL + providerId, null)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    // ---------------- selected provider / model / strategy ----------------

    fun saveProvider(id: String) = p().edit().putString(KEY_PROVIDER, id).apply()

    fun getProvider(): ProviderKind =
        ProviderKind.fromId(p().getString(KEY_PROVIDER, null) ?: ProviderKind.OPENAI.id)

    fun saveModel(id: String) = p().edit().putString(KEY_MODEL, id).apply()

    fun getModel(): String =
        p().getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: ModelCatalog.defaultFor(getProvider())

    fun saveStrategy(id: String) = p().edit().putString(KEY_STRATEGY, id).apply()

    fun getStrategy(): String = p().getString(KEY_STRATEGY, null) ?: "auto"

    // ---------------- spend budget ----------------

    fun saveBudget(maxCalls: Int, maxTokens: Int) {
        p().edit().putInt(KEY_MAX_CALLS, maxCalls).putInt(KEY_MAX_TOKENS, maxTokens).apply()
    }

    fun getMaxCalls(): Int = p().getInt(KEY_MAX_CALLS, 8)

    fun getMaxTokens(): Int = p().getInt(KEY_MAX_TOKENS, 9000)

    // ---------------- behaviour toggles ----------------

    fun setHandsFree(on: Boolean) = p().edit().putBoolean(KEY_HANDS_FREE, on).apply()

    fun isHandsFree(): Boolean = p().getBoolean(KEY_HANDS_FREE, false)

    fun setSpeakReplies(on: Boolean) = p().edit().putBoolean(KEY_SPEAK_REPLIES, on).apply()

    fun isSpeakReplies(): Boolean = p().getBoolean(KEY_SPEAK_REPLIES, true)

    fun setSendSnapshot(on: Boolean) = p().edit().putBoolean(KEY_SEND_SNAPSHOT, on).apply()

    fun isSendSnapshot(): Boolean = p().getBoolean(KEY_SEND_SNAPSHOT, false)

    fun setMultiStep(on: Boolean) = p().edit().putBoolean(KEY_MULTI_STEP, on).apply()

    fun isMultiStep(): Boolean = p().getBoolean(KEY_MULTI_STEP, false)

    // ---------------- Google account ----------------

    fun saveGoogleClientId(value: String) =
        p().edit().putString(KEY_GOOGLE_CLIENT_ID, value.trim()).apply()

    fun getGoogleClientId(): String? =
        p().getString(KEY_GOOGLE_CLIENT_ID, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveAccount(displayName: String, email: String, givenName: String) {
        p().edit()
            .putString(KEY_ACCOUNT_NAME, displayName)
            .putString(KEY_ACCOUNT_EMAIL, email)
            .putString(KEY_ACCOUNT_GIVEN, givenName)
            .apply()
    }

    fun getAccountName(): String? =
        p().getString(KEY_ACCOUNT_NAME, null)?.takeIf { it.isNotBlank() }

    fun getAccountEmail(): String? =
        p().getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun getAccountGivenName(): String? =
        p().getString(KEY_ACCOUNT_GIVEN, null)?.takeIf { it.isNotBlank() }

    fun isSignedIn(): Boolean = getAccountEmail() != null

    fun clearAccount() {
        p().edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_GIVEN)
            .apply()
    }

    /** Kept for compatibility with older call sites. */
    fun hasApiKey(): Boolean = hasAnyKey()
}
