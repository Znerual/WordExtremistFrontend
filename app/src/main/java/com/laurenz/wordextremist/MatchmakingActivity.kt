package com.laurenz.wordextremist

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View // Import View
import android.widget.Toast
// Removed activity viewModels import
import androidx.appcompat.app.AppCompatActivity
// Removed Observer import
import androidx.lifecycle.lifecycleScope
import com.laurenz.wordextremist.databinding.ActivityMatchmakingBinding
import com.laurenz.wordextremist.model.CancelMatchmakingRequestData
import com.laurenz.wordextremist.model.DeviceLoginRequestData
import com.laurenz.wordextremist.model.GetOrCreateUserRequestData
import com.laurenz.wordextremist.network.ApiClient
import com.laurenz.wordextremist.util.KeystoreHelper
import com.laurenz.wordextremist.util.TokenManager
// import com.laurenz.wordextremist.network.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException // Import for Retrofit errors
import java.io.IOException // Import for Network errors

class MatchmakingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMatchmakingBinding
    private var matchmakingJob: Job? = null

    private var databaseUserId: Int? = null
    private var actualUsername: String? = null
    private var localClientIdentifier: String? = null
    private var clientGeneratedPasswordForSession: String? = null // Only needed for the initial login call

    companion object {
        const val EXTRA_OWN_USER_ID = "extra_own_user_id"
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_USER_ID = "extra_user_id"
        // Removed EXTRA_BACKEND_TOKEN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchmakingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get the local client identifier (e.g., Android ID)
        localClientIdentifier = getLocalClientIdentifier()
        if (localClientIdentifier == null) {
            Toast.makeText(this, "Could not retrieve a unique client ID. Cannot proceed.", Toast.LENGTH_LONG).show()
            Log.e("MatchmakingActivity", "Failed to get localClientIdentifier.")
            finishAffinity() // Close app if critical ID missing
            return
        }
        Log.i("MatchmakingActivity", "Using client identifier: $localClientIdentifier")

        binding.buttonCancelMatchmaking.setOnClickListener {
            cancelMatchmaking()
        }


        initiateDeviceLoginAndMatchmaking()
    }

    // Removed observeViewModel() function
    @SuppressLint("HardwareIds") // Suppress warning for Settings.Secure.ANDROID_ID
    private fun getLocalClientIdentifier(): String? {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun initiateDeviceLoginAndMatchmaking() {
        binding.textViewStatus.text = "Authenticating device..."
        binding.progressBarMatchmaking.visibility = View.VISIBLE
        binding.buttonCancelMatchmaking.isEnabled = false // Keep disabled until user is registered

        val currentLocalClientId = localClientIdentifier ?: return // Should not be null here

        // Get or generate client password
        var clientPassword = KeystoreHelper.getStoredPassword(this)
        if (clientPassword == null) {
            clientPassword = KeystoreHelper.generateAndStorePassword(this)
        }
        clientGeneratedPasswordForSession = clientPassword // Store for potential re-use in this session if needed

        lifecycleScope.launch {
            try {
                val loginRequest = DeviceLoginRequestData( // Use the correct Pydantic model name
                    clientProvidedId = currentLocalClientId,
                    clientGeneratedPassword = clientPassword
                )
                Log.d("MatchmakingActivity", "Calling /device-login with client ID: $currentLocalClientId")
                val response = ApiClient.instance.deviceLogin(loginRequest) // Call your new deviceLogin endpoint

                if (response.isSuccessful && response.body() != null) {
                    val backendToken = response.body()!!
                    TokenManager.saveToken(this@MatchmakingActivity, backendToken.access_token)
                    Log.i("MatchmakingActivity", "Device login successful. JWT received. Proceeding to matchmaking.")

                    // Now that we have a JWT, matchmaking will use it via the ApiClient interceptor
                    // We don't directly get user ID/name from this response, JWT is opaque to client mostly
                    // The server will identify the user from the JWT for matchmaking.
                    // For UI purposes or if MainActivity needs it immediately, you could decode JWT (not recommended)
                    // or make a /users/me call here. For simplicity, let's assume server handles it.
                    // We will need user ID for WebSocket, so a /users/me call is good practice
                    fetchUserProfileAndStartMatchmaking()

                } else {
                    val errorBody = response.errorBody()?.string() ?: "Device login failed"
                    Log.e("MatchmakingActivity", "Error from /device-login: ${response.code()} - $errorBody")
                    binding.textViewStatus.text = "Authentication Failed (${response.code()})."
                    binding.progressBarMatchmaking.visibility = View.GONE
                    Toast.makeText(this@MatchmakingActivity, "Device authentication failed.", Toast.LENGTH_LONG).show()
                    // Handle failed login (e.g. password mismatch, clear stored pass and retry registration?)
                    if (response.code() == 401) { // Unauthorized usually means wrong password
                        Log.w("MatchmakingActivity", "Potential password mismatch. Clearing stored password.")
                        KeystoreHelper.clearStoredPassword(this@MatchmakingActivity)
                        // Optionally, you could retry initiateDeviceLoginAndMatchmaking() once here to re-register.
                    }
                }
            } catch (e: Exception) { // Catch all for network, http, json etc.
                Log.e("MatchmakingActivity", "Exception during /device-login: ${e.message}", e)
                binding.textViewStatus.text = "Error connecting to auth service."
                binding.progressBarMatchmaking.visibility = View.GONE
                Toast.makeText(this@MatchmakingActivity, "Authentication service error.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchUserProfileAndStartMatchmaking() {
        binding.textViewStatus.text = "Fetching user profile..."
        lifecycleScope.launch {
            try {
                // ApiClient's interceptor will add the JWT to this call
                val profileResponse = ApiClient.instance.getMyProfile() // Assumes getMyProfile uses the JWT
                if (profileResponse.isSuccessful && profileResponse.body() != null) {
                    val user = profileResponse.body()!!
                    databaseUserId = user.id
                    actualUsername = user.username ?: "Player${user.id}"
                    Log.i("MatchmakingActivity", "User profile fetched. DB ID: $databaseUserId, Username: $actualUsername")
                    startMatchmakingPolling()
                } else {
                    val errorBody = profileResponse.errorBody()?.string() ?: "Failed to fetch profile"
                    Log.e("MatchmakingActivity", "Error fetching profile: ${profileResponse.code()} - $errorBody")
                    binding.textViewStatus.text = "Error fetching user profile."
                    binding.progressBarMatchmaking.visibility = View.GONE
                    Toast.makeText(this@MatchmakingActivity, "Could not load user profile.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MatchmakingActivity", "Exception fetching profile: ${e.message}", e)
                binding.textViewStatus.text = "Error loading profile."
                binding.progressBarMatchmaking.visibility = View.GONE
                Toast.makeText(this@MatchmakingActivity, "Profile loading error.", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun startMatchmakingPolling() {
        if (databaseUserId == null) {
            Log.e("MatchmakingActivity", "Cannot start matchmaking, user details not available.")
            binding.textViewStatus.text = "Error: User profile needed for matchmaking."
            binding.progressBarMatchmaking.visibility = View.GONE
            return
        }

        // Prevent starting multiple matchmaking jobs
        if (matchmakingJob?.isActive == true) {
            Log.d("MatchmakingActivity", "Matchmaking polling already in progress.")
            return
        }

        Log.i("MatchmakingActivity", "Starting matchmaking polling for user DB ID: $databaseUserId (Username: $actualUsername)...")
        binding.textViewStatus.text = "Connecting to matchmaking..."
        binding.progressBarMatchmaking.visibility = View.VISIBLE
        binding.buttonCancelMatchmaking.isEnabled = true // Enable cancel button now

        matchmakingJob = lifecycleScope.launch {
            var matchFound = false
            val initialDelay = 250L
            delay(initialDelay)

            while (isActive && !matchFound) {
                try {
                    Log.d("MatchmakingActivity", "Polling matchmaking API with user_id: $databaseUserId")
                    val response = ApiClient.instance.findMatch()

                    if (!isActive) {
                        Log.d("MatchmakingActivity", "Matchmaking job cancelled after API response.")
                        break
                    }

                    if (response.isSuccessful) {
                        val matchmakingStatus = response.body()
                        if (matchmakingStatus != null) {
                            when (matchmakingStatus.status) {
                                "matched" -> {
                                    if (matchmakingStatus.game_id != null && matchmakingStatus.your_player_id_in_game != null) {
                                        Log.i("MatchmakingActivity", "Match found! Game ID: ${matchmakingStatus.game_id}, Opponent: ${matchmakingStatus.opponent_name}, Your ID in Game: ${matchmakingStatus.your_player_id_in_game}")
                                        databaseUserId = matchmakingStatus.your_player_id_in_game
                                        binding.textViewStatus.text = "Match Found!"
                                        binding.progressBarMatchmaking.visibility = View.GONE
                                        Toast.makeText(this@MatchmakingActivity, "Match found with ${matchmakingStatus.opponent_name ?: "Player"}!", Toast.LENGTH_LONG).show()
                                        matchFound = true
                                        binding.buttonCancelMatchmaking.isEnabled = false
                                        // Ensure we use the correct ID from the match response (should be same as currentDbUserId)
                                        proceedToGame(matchmakingStatus.game_id, databaseUserId!!)
                                    } else {
                                        Log.e("MatchmakingActivity", "Error: Matched status received but game_id or your_player_id_in_game is null!")
                                        binding.textViewStatus.text = "Error: Invalid match data."
                                        delay(5000)
                                    }
                                }
                                "waiting" -> {
                                    binding.textViewStatus.text = "Searching for opponent as ${actualUsername ?: "Player"}..."
                                    Log.d("MatchmakingActivity", "Status: waiting...")
                                }
                                else -> {
                                    Log.w("MatchmakingActivity", "Received unexpected status: ${matchmakingStatus.status}")
                                    binding.textViewStatus.text = "Searching... (${matchmakingStatus.status})"
                                }
                            }
                        } else {
                            Log.e("MatchmakingActivity", "Matchmaking response body is null.")
                            binding.textViewStatus.text = "Error: Empty response."
                            delay(5000)
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                        Log.e("MatchmakingActivity", "Matchmaking API error: ${response.code()} - $errorBody")
                        binding.textViewStatus.text = "Server Error (${response.code()}). Retrying..."
                        delay(5000)
                    }
                } catch (e: IOException) {
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "Network error during matchmaking poll: ${e.message}")
                    binding.textViewStatus.text = "Network error. Check connection."
                    delay(5000)
                } catch (e: HttpException) {
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "HTTP error during matchmaking poll: ${e.code()} - ${e.message()}")
                    binding.textViewStatus.text = "Server error (${e.code()}). Retrying..."
                    delay(5000)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "Unexpected error during matchmaking poll: ${e.message}", e)
                    binding.textViewStatus.text = "An error occurred. Retrying..."
                    delay(5000)
                }

                if (!matchFound && isActive) {
                    val pollInterval = 3000L
                    Log.d("MatchmakingActivity", "Waiting ${pollInterval}ms before next poll.")
                    delay(pollInterval)
                }
            }

            if (!matchFound && isActive) {
                Log.w("MatchmakingActivity", "Matchmaking loop finished without finding a match.")
                binding.textViewStatus.text = "Could not find a match. Try again?"
                binding.progressBarMatchmaking.visibility = View.GONE
                binding.buttonCancelMatchmaking.isEnabled = false
            }
        }
    }

    private fun proceedToGame(gameId: String, ownDbUserId: Int) {
        matchmakingJob?.cancel()
        matchmakingJob = null
        binding.progressBarMatchmaking.visibility = View.GONE

        val jwtToken = TokenManager.getToken(this) // Get current JWT for WebSocket
        if (jwtToken == null) {
            Toast.makeText(this, "Authentication error, cannot start game.", Toast.LENGTH_LONG).show()
            Log.e("MatchmakingActivity", "JWT token is null when proceeding to game.")
            // Optional: navigate back to launcher or try re-auth
            initiateDeviceLoginAndMatchmaking() // Simple retry, could be more sophisticated
            return
        }

        Log.i("MatchmakingActivity", "Proceeding to game: $gameId for user DB ID: $ownDbUserId")
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OWN_USER_ID, ownDbUserId)
            putExtra(EXTRA_GAME_ID, gameId)
            putExtra(EXTRA_USER_ID, ownDbUserId) // If MainActivity uses this for self
            // NO LONGER PASS TOKEN VIA INTENT if WebSocket uses it in URL
            // putExtra(MainActivity.EXTRA_BACKEND_TOKEN, jwtToken) // If MainActivity needs it for other things
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun cancelMatchmaking() {
        val userIdToCancelWith = databaseUserId // Use the fetched DB ID
        if (userIdToCancelWith == null) {
            Log.w("MatchmakingActivity", "Cannot cancel matchmaking, user ID not available.")
            binding.textViewStatus.text = "Matchmaking Cancelled (User not registered)."
            binding.progressBarMatchmaking.visibility = View.GONE
            binding.buttonCancelMatchmaking.isEnabled = false // Keep disabled
            // Potentially navigate back or allow retry of registration
            navigateToLauncher()
            return
        }

        if (matchmakingJob?.isActive != true) {
            Log.d("MatchmakingActivity", "Matchmaking not active or already cancelled for user ID $userIdToCancelWith.")
            binding.textViewStatus.text = "Matchmaking Cancelled."
            binding.progressBarMatchmaking.visibility = View.GONE
            binding.buttonCancelMatchmaking.isEnabled = false
            navigateToLauncher()
            return
        }

        Log.i("MatchmakingActivity", "Attempting to cancel matchmaking for user DB ID: $userIdToCancelWith...")
        binding.buttonCancelMatchmaking.isEnabled = false
        binding.textViewStatus.text = "Cancelling matchmaking..."
        binding.progressBarMatchmaking.visibility = View.VISIBLE

        matchmakingJob?.cancel()
        matchmakingJob = null

        lifecycleScope.launch {
            try {
                Log.d("MatchmakingActivity", "Calling cancelMatchmaking API for user DB ID: $userIdToCancelWith")
                val response = ApiClient.instance.cancelMatchmaking()

                if (response.isSuccessful) {
                    Log.i("MatchmakingActivity", "Matchmaking successfully cancelled on backend for user DB ID: $userIdToCancelWith.")
                    binding.textViewStatus.text = "Matchmaking Cancelled."
                    Toast.makeText(this@MatchmakingActivity, "Matchmaking cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                    Log.e("MatchmakingActivity", "Failed to cancel matchmaking on backend: ${response.code()} - $errorBody")
                    binding.textViewStatus.text = "Cancellation failed (Server Error ${response.code()})"
                    Toast.makeText(this@MatchmakingActivity, "Could not cancel on server.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.e("MatchmakingActivity", "Network error during cancellation: ${e.message}")
                binding.textViewStatus.text = "Cancellation failed (Network Error)"
                Toast.makeText(this@MatchmakingActivity, "Network error during cancellation.", Toast.LENGTH_SHORT).show()
            } catch (e: HttpException) {
                Log.e("MatchmakingActivity", "HTTP error during cancellation: ${e.code()} - ${e.message()}")
                binding.textViewStatus.text = "Cancellation failed (Server Error ${e.code()})"
                Toast.makeText(this@MatchmakingActivity, "Server error during cancellation.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MatchmakingActivity", "Unexpected error during cancellation: ${e.message}", e)
                binding.textViewStatus.text = "Cancellation failed (Unknown Error)"
                Toast.makeText(this@MatchmakingActivity, "Error during cancellation.", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBarMatchmaking.visibility = View.GONE
                binding.buttonCancelMatchmaking.isEnabled = false
                navigateToLauncher() // Navigate back after cancellation attempt
            }
        }
    }

    private fun navigateToLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }


    override fun onDestroy() {
        super.onDestroy()
        val currentDbUserId = databaseUserId // Capture current value
        if (matchmakingJob?.isActive == true) {
            Log.d("MatchmakingActivity", "onDestroy called, cancelling active matchmaking job for user DB ID: $currentDbUserId")
            matchmakingJob?.cancel()
            matchmakingJob = null

            if (TokenManager.getToken(this) != null) { // Only if authenticated
                lifecycleScope.launch {
                    try {
                        Log.i("MatchmakingActivity", "Attempting backend cancellation on destroy")
                        ApiClient.instance.cancelMatchmaking() // JWT identifies
                    } catch (e: Exception) {
                        Log.w("MatchmakingActivity", "Error during backend cancellation on destroy: ${e.message}")
                    }
                }
            }
        }
    }
}