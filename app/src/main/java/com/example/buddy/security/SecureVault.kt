package com.example.buddy.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed EncryptedSharedPreferences wrapper for storing sensitive secrets
 * like the user's OpenAI API key. No API keys are ever hardcoded.
 */
object SecureVault {

    private const val FILE_NAME = "buddy_secure_vault"
    private const val KEY_OPENAI_API_KEY = "openai_api_key"
    private const val KEY_OPENAI_MODEL = "openai_model"
    private const val DEFAULT_MODEL = "gpt-4o-mini"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                val appContext = context.applicationContext
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                prefs = EncryptedSharedPreferences.create(
                    appContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }
    }

    private fun requirePrefs(): SharedPreferences {
        return checkNotNull(prefs) {
            "SecureVault must be initialized in AiAgentApp.onCreate() before use."
        }
    }

    fun saveApiKey(apiKey: String) {
        requirePrefs().edit()
            .putString(KEY_OPENAI_API_KEY, apiKey.trim())
            .apply()
    }

    fun getApiKey(): String? {
        val value = requirePrefs().getString(KEY_OPENAI_API_KEY, null)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    fun getModelName(): String {
        return requirePrefs().getString(KEY_OPENAI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModelName(model: String) {
        requirePrefs().edit()
            .putString(KEY_OPENAI_MODEL, model.trim().ifEmpty { DEFAULT_MODEL })
            .apply()
    }
}
