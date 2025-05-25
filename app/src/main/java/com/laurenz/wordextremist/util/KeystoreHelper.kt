package com.laurenz.wordextremist.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 // For encoding/decoding
import android.util.Log
import java.nio.charset.Charset
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "WordExtremistClientPasswordKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFS_FILE_NAME = "word_extremist_secure_prefs"
    private const val ENCRYPTED_PASSWORD_PREF_KEY = "encrypted_client_password_v1"
    private const val IV_PREF_KEY = "password_iv_v1"


    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256) // AES-256

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setUserAuthenticationRequired(true) // For biometric/lock screen auth, more complex
            }
            keyGenerator.init(builder.build())
            return keyGenerator.generateKey()
        }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun encrypt(data: String, secretKey: SecretKey): Pair<ByteArray, ByteArray> { // Returns encryptedData, iv
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv // Save this IV
        val encryptedData = cipher.doFinal(data.toByteArray(Charset.forName("UTF-8")))
        return Pair(encryptedData, iv)
    }

    private fun decrypt(encryptedData: ByteArray, secretKey: SecretKey, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv) // Use the saved IV
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decryptedData = cipher.doFinal(encryptedData)
        return String(decryptedData, Charset.forName("UTF-8"))
    }

    // Generates a new password, encrypts, and stores it. Returns the plain password.
    fun generateAndStorePassword(context: Context): String {
        val plainPassword = UUID.randomUUID().toString()
        val secretKey = getSecretKey()
        val (encryptedPasswordBytes, iv) = encrypt(plainPassword, secretKey)

        val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putString(ENCRYPTED_PASSWORD_PREF_KEY, Base64.encodeToString(encryptedPasswordBytes, Base64.DEFAULT))
            putString(IV_PREF_KEY, Base64.encodeToString(iv, Base64.DEFAULT))
            apply()
        }
        Log.i("KeystoreHelper", "New password generated and stored securely.")
        return plainPassword
    }

    // Retrieves and decrypts the password. Returns null if not found or decryption fails.
    fun getStoredPassword(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        val encryptedPasswordStr = prefs.getString(ENCRYPTED_PASSWORD_PREF_KEY, null)
        val ivStr = prefs.getString(IV_PREF_KEY, null)

        if (encryptedPasswordStr == null || ivStr == null) {
            Log.w("KeystoreHelper", "No stored password or IV found.")
            return null
        }

        return try {
            val secretKey = getSecretKey()
            val encryptedPasswordBytes = Base64.decode(encryptedPasswordStr, Base64.DEFAULT)
            val iv = Base64.decode(ivStr, Base64.DEFAULT)
            decrypt(encryptedPasswordBytes, secretKey, iv)
        } catch (e: Exception) {
            Log.e("KeystoreHelper", "Failed to decrypt stored password", e)
            // Could indicate tampering or key invalidation. Might need to clear stored prefs.
            clearStoredPassword(context) // Clear potentially corrupted data
            null
        }
    }
    fun clearStoredPassword(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            remove(ENCRYPTED_PASSWORD_PREF_KEY)
            remove(IV_PREF_KEY)
            apply()
        }
        Log.i("KeystoreHelper", "Stored password and IV cleared.")
    }
}