package com.example.buddy.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** The bits of a Google account Buddy actually uses. */
data class GoogleAccount(
    val displayName: String,
    val email: String,
    val givenName: String
)

/**
 * Sign in with Google through Android's Credential Manager.
 *
 * Google requires a **Web** OAuth client id (the "server client id") whose SHA-1
 * matches the signing key of this build. The user pastes it in Settings; without
 * one we fail fast with a clear message instead of a cryptic Play-services error.
 */
object GoogleSignInManager {

    private const val TAG = "Buddy"

    suspend fun signIn(context: Context, serverClientId: String): Result<GoogleAccount> {
        if (serverClientId.isBlank()) {
            return Result.failure(IllegalStateException("No Google client id set"))
        }
        // Prefer accounts already used with this app (nicer sheet), then fall back
        // to showing every account on the device.
        val authorized = attempt(context, serverClientId, filterByAuthorizedAccounts = true)
        if (authorized.isSuccess) return authorized
        return attempt(context, serverClientId, filterByAuthorizedAccounts = false)
    }

    private suspend fun attempt(
        context: Context,
        serverClientId: String,
        filterByAuthorizedAccounts: Boolean
    ): Result<GoogleAccount> = try {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val response = CredentialManager.create(context).getCredential(context, request)
        Result.success(toAccount(response))
    } catch (e: GetCredentialException) {
        Log.w(TAG, "Google sign-in error type=${e.type} msg=${e.message}")
        Result.failure(e)
    } catch (t: Throwable) {
        Log.w(TAG, "Google sign-in failed", t)
        Result.failure(t)
    }

    private fun toAccount(response: GetCredentialResponse): GoogleAccount {
        val credential = response.credential
        check(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type: ${credential.type}"
        }
        val token = GoogleIdTokenCredential.createFrom(credential.data)
        val email = token.id
        val display = token.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
        val given = token.givenName?.takeIf { it.isNotBlank() } ?: display.substringBefore(' ')
        return GoogleAccount(displayName = display, email = email, givenName = given)
    }
}
