package com.laurenz.wordextremist

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View // Import View
import android.widget.Toast
import com.laurenz.wordextremist.auth.AuthManager
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

    private var ownDatabaseUserId: Int? = null
    private var actualUsername: String? = null
    private var ownLevel: Int? = null

    private var gameLanguageForMatchmaking: String = "en" // Default

    companion object {
        const val EXTRA_SELECTED_LANGUAGE = "extra_selected_language"
        const val EXTRA_OWN_USER_ID = "extra_own_user_id"
        const val EXTRA_OWN_USER_NAME = "extra_own_user_name"
        const val EXTRA_OWN_LEVEL = "extra_own_level"
        const val EXTRA_OPPONENT_USER_NAME = "extra_opponent_user_name"
        const val EXTRA_OPPONENT_LEVEL = "extra_opponent_level"
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_GAME_LANGUAGE_FOR_MAIN = "extra_game_language_for_main"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchmakingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameLanguageForMatchmaking = intent.getStringExtra(EXTRA_SELECTED_LANGUAGE) ?: "en"
        Log.i("MatchmakingActivity", "Received language for matchmaking: $gameLanguageForMatchmaking")


        binding.buttonCancelMatchmaking.setOnClickListener {
            cancelMatchmaking()
        }

        beginMatchmakingProcess()



    }

    private fun beginMatchmakingProcess() {
        binding.textViewStatus.text = "Authenticating..."
        binding.buttonCancelMatchmaking.isEnabled = false

        lifecycleScope.launch {
            val isAuthValid = AuthManager.ensureValidToken(this@MatchmakingActivity)

            if (isAuthValid) {
                // Auth is successful, now fetch the user's profile to get necessary details
                Log.d("MatchmakingActivity", "Authentication successful. Fetching user profile.")
                binding.textViewStatus.text = "Verifying profile..."
                fetchUserProfileAndStartPolling()
            } else {
                // Silent re-login failed. Show an error and don't proceed.
                Log.e("MatchmakingActivity", "AuthManager failed to ensure a valid token.")
                binding.textViewStatus.text = "Authentication failed.\nPlease try again."
                Toast.makeText(this@MatchmakingActivity, "Authentication failed. Check connection.", Toast.LENGTH_LONG).show()
                // Optionally disable the cancel button or change its text to "Back"
                binding.buttonCancelMatchmaking.text = "Back"
                binding.buttonCancelMatchmaking.isEnabled = true
            }
        }
    }


    private fun fetchUserProfileAndStartPolling() {
        lifecycleScope.launch {
            try {
                // At this point, we assume the token is valid.
                val profileResponse = ApiClient.instance.getMyProfile()
                if (profileResponse.isSuccessful && profileResponse.body() != null) {
                    val user = profileResponse.body()!!
                    ownDatabaseUserId = user.id
                    actualUsername = user.username ?: "Player${user.id}"
                    ownLevel = user.level
                    Log.i("MatchmakingActivity", "User profile verified. DB ID: $ownDatabaseUserId, Username: $actualUsername, Lvl: ${user.level}")

                    // Profile is fetched, now we can start the polling for a match.
                    startMatchmakingPolling()
                } else {
                    val errorBody = profileResponse.errorBody()?.string() ?: "Failed to verify profile"
                    Log.e("MatchmakingActivity", "Error verifying profile: ${profileResponse.code()} - $errorBody")
                    binding.textViewStatus.text = "Error: Could not verify user."
                    Toast.makeText(this@MatchmakingActivity, "User verification failed.", Toast.LENGTH_LONG).show()
                    binding.buttonCancelMatchmaking.text = "Back"
                    binding.buttonCancelMatchmaking.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("MatchmakingActivity", "Exception verifying profile: ${e.message}", e)
                binding.textViewStatus.text = "Error: Connection problem."
                Toast.makeText(this@MatchmakingActivity, "Profile verification error.", Toast.LENGTH_LONG).show()
                binding.buttonCancelMatchmaking.text = "Back"
                binding.buttonCancelMatchmaking.isEnabled = true
            }
        }
    }


    private fun startMatchmakingPolling() {
        if (ownDatabaseUserId == null) { // Should be set by fetchUserProfileAndStartPolling
            Log.e("MatchmakingActivity", "Cannot start matchmaking, user DB ID not available.")
            binding.textViewStatus.text = "Error: User ID missing."
            //binding.progressBarMatchmaking.visibility = View.GONE
            return
        }

        if (matchmakingJob?.isActive == true) {
            Log.d("MatchmakingActivity", "Matchmaking polling already in progress.")
            return
        }

        Log.i("MatchmakingActivity", "Starting matchmaking polling for user DB ID: $ownDatabaseUserId (Username: $actualUsername)...")
        binding.textViewStatus.text = "Connecting to matchmaking..."
        //binding.progressBarMatchmaking.visibility = View.VISIBLE
        binding.buttonCancelMatchmaking.isEnabled = true

        matchmakingJob = lifecycleScope.launch {
            var matchFound = false
            delay(50L) // Initial small delay

            while (isActive && !matchFound) {
                try {
                    Log.d("MatchmakingActivity", "Polling matchmaking API with language: $gameLanguageForMatchmaking")
                    // ApiClient's interceptor adds the JWT for findMatch
                    val response = ApiClient.instance.findMatch(language = gameLanguageForMatchmaking)

                    if (!isActive) break // Job cancelled

                    if (response.isSuccessful) {
                        val matchmakingStatus = response.body()
                        if (matchmakingStatus != null) {
                            when (matchmakingStatus.status) {
                                "matched" -> {
                                    if (matchmakingStatus.game_id != null && matchmakingStatus.your_player_id_in_game != null && matchmakingStatus.opponent_name != null && matchmakingStatus.opponent_level != null) {
                                        Log.i("MatchmakingActivity", "Match found! Game ID: ${matchmakingStatus.game_id}, Opponent: ${matchmakingStatus.opponent_name}, Your ID in Game: ${matchmakingStatus.your_player_id_in_game}")

                                        // ownDatabaseUserId should already be our correct ID from /users/me.
                                        // matchmakingStatus.your_player_id_in_game should match it.
                                        if (ownDatabaseUserId != matchmakingStatus.your_player_id_in_game) {
                                            Log.w("MatchmakingActivity", "Mismatch between local ownDatabaseUserId ($ownDatabaseUserId) and server's your_player_id_in_game (${matchmakingStatus.your_player_id_in_game}). Using server's.")
                                            ownDatabaseUserId = matchmakingStatus.your_player_id_in_game
                                        }

                                        binding.textViewStatus.text = "Match Found!"
                                        //binding.progressBarMatchmaking.visibility = View.GONE
                                        Toast.makeText(this@MatchmakingActivity, "Match found with ${matchmakingStatus.opponent_name ?: "Player"}!", Toast.LENGTH_LONG).show()
                                        matchFound = true
                                        // Play Pulse Animation on Morphing Symbol
                                        binding.morphingSymbolView.playMatchFoundPulse()
                                        binding.textViewStatus.text = "Match Found!"
                                        binding.buttonCancelMatchmaking.isEnabled = false
                                        delay(80)
                                        if (!isActive) break
                                        proceedToGame(matchmakingStatus.game_id, ownDatabaseUserId!!, ownLevel!!, matchmakingStatus.language ?: gameLanguageForMatchmaking, matchmakingStatus.opponent_name, matchmakingStatus.opponent_level )
                                    } else {
                                        Log.e("MatchmakingActivity", "Error: Matched status received but game_id or your_player_id_in_game is null!")
                                        binding.textViewStatus.text = "Error: Invalid match data."
                                        if (isActive) delay(5000)
                                    }
                                }
                                "waiting" -> {
                                    binding.textViewStatus.text = "Searching for opponent as ${actualUsername ?: "Player"}..."
                                    Log.d("MatchmakingActivity", "Status: waiting...")
                                    if (binding.morphingSymbolView.visibility != View.VISIBLE) {
                                        binding.morphingSymbolView.visibility = View.VISIBLE
                                    }
                                }
                                else -> {
                                    Log.w("MatchmakingActivity", "Received unexpected status: ${matchmakingStatus.status}")
                                    binding.textViewStatus.text = "Searching... (${matchmakingStatus.status})"
                                    if (!binding.morphingSymbolView.isShown) { // Or a flag in MorphingSymbolView
                                        binding.morphingSymbolView.startMorphing()
                                    }
                                }
                            }
                        } else {
                            Log.e("MatchmakingActivity", "Matchmaking response body is null.")
                            binding.textViewStatus.text = "Error: Empty response."
                            if (isActive) delay(5000)
                        }
                    } else { // HTTP error
                        val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                        Log.e("MatchmakingActivity", "Matchmaking API error: ${response.code()} - $errorBody")
                        binding.textViewStatus.text = "Server Error (${response.code()}). Retrying..."
                        if (response.code() == 401) { // Token might be invalid
                            Toast.makeText(this@MatchmakingActivity, "Session expired. Please restart.", Toast.LENGTH_LONG).show()
                            navigateToLauncher(clearTask = true)
                            break // Exit loop
                        }
                        if (isActive) delay(5000)
                    }
                } catch (e: IOException) { // Network errors
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "Network error during poll: ${e.message}")
                    binding.textViewStatus.text = "Network error. Check connection."
                    if (isActive) delay(5000)
                } catch (e: HttpException) { // Other HTTP errors not caught by response.isSuccessful
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "HTTP error during poll: ${e.code()} - ${e.message()}")
                    binding.textViewStatus.text = "Server error (${e.code()}). Retrying..."
                    if (e.code() == 401) {
                        Toast.makeText(this@MatchmakingActivity, "Session expired. Please restart.", Toast.LENGTH_LONG).show()
                        navigateToLauncher(clearTask = true)
                        break
                    }
                    if (isActive) delay(5000)
                }
                catch (e: Exception) { // Other unexpected errors
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "Unexpected error during poll: ${e.message}", e)
                    binding.textViewStatus.text = "An error occurred. Retrying..."
                    if (isActive) delay(5000)
                }

                if (!matchFound && isActive) {
                    val pollInterval = 500L
                    Log.d("MatchmakingActivity", "Waiting ${pollInterval}ms before next poll.")
                    delay(pollInterval)
                }
            }

            if (!matchFound && isActive) { // Loop finished but no match and not cancelled
                Log.w("MatchmakingActivity", "Matchmaking loop finished without finding a match.")
                binding.textViewStatus.text = "Could not find a match. Try again?"
                //binding.progressBarMatchmaking.visibility = View.GONE
                binding.buttonCancelMatchmaking.isEnabled = false // Or allow retry
            }
        }
    }


    private fun proceedToGame(gameId: String, gameOwnUserId: Int, ownLevel: Int, actualGameLanguage: String, opponentName: String, opponentLevel: Int) {
        matchmakingJob?.cancel() // Ensure polling is stopped
        matchmakingJob = null
        //binding.progressBarMatchmaking.visibility = View.GONE

        val jwtToken = TokenManager.getToken(this) // For WebSocket connection
        if (jwtToken == null) {
            Toast.makeText(this, "Authentication error, cannot start game.", Toast.LENGTH_LONG).show()
            Log.e("MatchmakingActivity", "JWT token is null when proceeding to game.")
            navigateToLauncher(clearTask = true) // Go back to Launcher to re-auth
            return
        }

        Log.i("MatchmakingActivity", "Proceeding to game: $gameId for user DB ID: $gameOwnUserId with language $actualGameLanguage")
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OWN_USER_ID, gameOwnUserId) // User's DB ID for game identification
            putExtra(EXTRA_OWN_USER_NAME, actualUsername)
            putExtra(EXTRA_OWN_LEVEL, ownLevel)
            putExtra(EXTRA_OPPONENT_USER_NAME, opponentName)
            putExtra(EXTRA_OPPONENT_LEVEL, opponentLevel)
            putExtra(EXTRA_GAME_ID, gameId)
            putExtra(EXTRA_GAME_LANGUAGE_FOR_MAIN, actualGameLanguage)
            // MainActivity's GameWebSocketClient will use TokenManager to get the token for its URL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }


    private fun cancelMatchmaking() {
        if (matchmakingJob?.isActive != true) {
            Log.d("MatchmakingActivity", "Matchmaking not active or already cancelled.")
            // If already cancelled or finished, just navigate back
            navigateToLauncher()
            return
        }

        Log.i("MatchmakingActivity", "Attempting to cancel matchmaking polling...")
        binding.buttonCancelMatchmaking.isEnabled = false // Disable during cancellation
        binding.textViewStatus.text = "Cancelling matchmaking..."
        //binding.progressBarMatchmaking.visibility = View.VISIBLE

        matchmakingJob?.cancel() // Cancel the coroutine
        matchmakingJob = null

        // Also call the backend to remove from pool
        lifecycleScope.launch {
            try {
                Log.d("MatchmakingActivity", "Calling cancelMatchmaking API on backend.")
                val response = ApiClient.instance.cancelMatchmaking() // Uses token from interceptor

                if (response.isSuccessful) {
                    Log.i("MatchmakingActivity", "Matchmaking successfully cancelled on backend.")
                    Toast.makeText(this@MatchmakingActivity, "Matchmaking cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                    Log.e("MatchmakingActivity", "Failed to cancel matchmaking on backend: ${response.code()} - $errorBody")
                    Toast.makeText(this@MatchmakingActivity, "Could not cancel on server.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MatchmakingActivity", "Error during backend cancellation: ${e.message}", e)
                Toast.makeText(this@MatchmakingActivity, "Error during cancellation.", Toast.LENGTH_SHORT).show()
            } finally {
                //binding.progressBarMatchmaking.visibility = View.GONE
                // Navigate back to LauncherActivity after cancellation attempt
                navigateToLauncher()
            }
        }
    }

    private fun navigateToLauncher(clearTask: Boolean = false) {
        val intent = Intent(this, LauncherActivity::class.java)
        if (clearTask) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        if (clearTask) finish() // Finish MatchmakingActivity if clearing task
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.morphingSymbolView.stopMorphing() // Ensure it stops on destroy
        if (matchmakingJob?.isActive == true) {
            Log.d("MatchmakingActivity", "onDestroy called, cancelling active matchmaking job.")
            matchmakingJob?.cancel()
            matchmakingJob = null
            // Attempt backend cancellation only if token exists and job was active
            if (TokenManager.getToken(this) != null) {
                lifecycleScope.launch {
                    try {
                        Log.i("MatchmakingActivity", "Attempting backend cancellation on destroy for active job.")
                        ApiClient.instance.cancelMatchmaking()
                    } catch (e: Exception) {
                        Log.w("MatchmakingActivity", "Error during backend cancellation on destroy: ${e.message}")
                    }
                }
            }
        }
    }
}