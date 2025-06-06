package com.laurenz.wordextremist.util // Or your utils package

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

object TokenManager {
    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_TOKEN_SAVED_AT_MS = "token_saved_at"
    private const val KEY_TOKEN_EXPIRES_IN_S = "token_expires_in"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveToken(context: Context, token: String, expiresInSeconds: Int) {
        getPrefs(context).edit {
            putString(KEY_AUTH_TOKEN, token)
            putLong(KEY_TOKEN_SAVED_AT_MS, System.currentTimeMillis())
            putInt(KEY_TOKEN_EXPIRES_IN_S, expiresInSeconds)
        }
        Log.i("TokenManager", "New token saved. Expires in: $expiresInSeconds seconds.")
    }

    fun getToken(context: Context): String? {
        return getPrefs(context).getString(KEY_AUTH_TOKEN, null)
    }

    fun isTokenValid(context: Context, safetyMarginSeconds: Int = 60): Boolean {
        val prefs = getPrefs(context)
        val token = prefs.getString(KEY_AUTH_TOKEN, null)
        if (token.isNullOrEmpty()) {
            return false
        }

        val savedAtMs = prefs.getLong(KEY_TOKEN_SAVED_AT_MS, 0)
        val expiresInS = prefs.getInt(KEY_TOKEN_EXPIRES_IN_S, 0)
        if (savedAtMs == 0L || expiresInS == 0) {
            return false // Not enough info to check validity, assume invalid
        }

        val expirationTimeMs = savedAtMs + (expiresInS * 1000L)
        val currentTimeWithMargin = System.currentTimeMillis() + (safetyMarginSeconds * 1000L)

        val isValid = currentTimeWithMargin < expirationTimeMs
        Log.d("TokenManager", "Token validity check: $isValid. Expires at: $expirationTimeMs, Current time + margin: $currentTimeWithMargin")
        return isValid
    }


    fun clearToken(context: Context) {
        val editor = getPrefs(context).edit()
        editor.remove(KEY_AUTH_TOKEN)
        editor.remove(KEY_TOKEN_SAVED_AT_MS)
        editor.remove(KEY_TOKEN_EXPIRES_IN_S)
        editor.apply()
        Log.i("TokenManager", "Auth token and data cleared.")
    }
}