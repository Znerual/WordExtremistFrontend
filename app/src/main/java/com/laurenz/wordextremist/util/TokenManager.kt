package com.laurenz.wordextremist.util // Or your utils package

import android.content.Context
import android.util.Log

object TokenManager {
    private const val PREFS_NAME = "auth_prefs"
    private const val JWT_TOKEN_KEY = "jwt_token"

    fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(JWT_TOKEN_KEY, token).apply()
        Log.d("TokenManager", "JWT Token saved.")
    }

    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(JWT_TOKEN_KEY, null)
    }

    fun clearToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(JWT_TOKEN_KEY).apply()
        Log.d("TokenManager", "JWT Token cleared.")
    }
}