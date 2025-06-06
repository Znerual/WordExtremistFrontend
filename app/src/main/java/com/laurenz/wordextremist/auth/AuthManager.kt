package com.laurenz.wordextremist.auth


import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.laurenz.wordextremist.model.DeviceLoginRequestData
import com.laurenz.wordextremist.network.ApiClient
import com.laurenz.wordextremist.util.KeystoreHelper
import com.laurenz.wordextremist.util.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A singleton to manage user authentication state and perform silent re-logins.
 */
object AuthManager {

    // A Mutex ensures that if multiple API calls trigger a re-login at the same time,
    // only one re-login process actually runs.
    private val reauthMutex = Mutex()

    /**
     * Ensures a valid auth token is available, performing a silent re-login if necessary.
     * This is the main function to be called from activities before making API calls.
     *
     * @param context The application or activity context.
     * @return True if a valid token is available after the check, false otherwise.
     */
    suspend fun ensureValidToken(context: Context): Boolean {
        // If token is already valid, we are done.
        if (TokenManager.isTokenValid(context)) {
            Log.d("AuthManager", "Token is already valid.")
            return true
        }

        // If token is not valid, acquire a lock to perform re-authentication.
        // If another coroutine is already re-authenticating, this will wait.
        return reauthMutex.withLock {
            // After acquiring the lock, check again. The token might have been
            // refreshed by the coroutine that held the lock before this one.
            if (TokenManager.isTokenValid(context)) {
                Log.d("AuthManager", "Token was refreshed by another process while waiting for lock. All good.")
                return@withLock true
            }

            Log.w("AuthManager", "Token is invalid or expired. Performing silent re-login.")
            // Perform the actual silent login
            silentDeviceLogin(context.applicationContext)
        }
    }

    /**
     * Performs a device login in the background without user interaction.
     *
     * @param context Must be application context to avoid memory leaks.
     * @return True on success, false on failure.
     */
    @SuppressLint("HardwareIds")
    private suspend fun silentDeviceLogin(context: Context): Boolean {
        val clientId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (clientId.isNullOrEmpty()) {
            Log.e("AuthManager", "Critical Error: Cannot perform silent login, no device ID.")
            return false
        }

        var clientPassword = KeystoreHelper.getStoredPassword(context)
        if (clientPassword == null) {
            // This case should be rare after the first-ever login.
            // If it happens, it means keystore data was lost. Generate a new password.
            clientPassword = KeystoreHelper.generateAndStorePassword(context)
            Log.i("AuthManager", "No password in Keystore. Generated a new one for silent login.")
        }

        return try {
            val loginRequest = DeviceLoginRequestData(
                clientProvidedId = clientId,
                clientGeneratedPassword = clientPassword
            )
            Log.d("AuthManager", "Calling /device-login silently...")
            val response = ApiClient.instance.deviceLogin(loginRequest)

            if (response.isSuccessful && response.body() != null) {
                val tokenResponse = response.body()!!
                val expiresIn = tokenResponse.expiresIn ?: 3600
                TokenManager.saveToken(context, tokenResponse.accessToken, expiresIn)
                Log.i("AuthManager", "Silent re-login successful. New token saved.")
                true // Success
            } else {
                Log.e("AuthManager", "Silent re-login failed. Server response: ${response.code()}")
                // If auth fails with 401, the password might be wrong. Clear it so a new one is
                // generated on the next attempt.
                if (response.code() == 401) {
                    KeystoreHelper.clearStoredPassword(context)
                }
                false // Failure
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Exception during silent re-login.", e)
            false // Failure
        }
    }
}