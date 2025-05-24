package com.laurenz.wordextremist

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View // Import View
import android.widget.Toast
// Removed activity viewModels import
import androidx.appcompat.app.AppCompatActivity
// Removed Observer import
import androidx.lifecycle.lifecycleScope
import com.laurenz.wordextremist.databinding.ActivityMatchmakingBinding
import com.laurenz.wordextremist.model.MatchmakingResponse
import com.laurenz.wordextremist.network.ApiClient
// Import ApiClient if you make a real call, otherwise remove if only simulating
// import com.laurenz.wordextremist.network.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random
import retrofit2.HttpException // Import for Retrofit errors
import java.io.IOException // Import for Network errors

class MatchmakingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMatchmakingBinding
    private var matchmakingJob: Job? = null

    // Removed authViewModel
    // Removed currentBackendToken

    // Temporary Player Identification (for testing without auth)
    private var temporaryUserId: Int = -1
    private lateinit var temporaryUsername: String

    companion object {
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_USER_ID = "extra_user_id"
        // Removed EXTRA_BACKEND_TOKEN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchmakingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Generate temporary ID and Username
        temporaryUserId = Random.nextInt(100000, 999999) // Generate a random ID for testing
        temporaryUsername = "Player${temporaryUserId}"
        Log.i("MatchmakingActivity", "Using temporary userId: $temporaryUserId, username: $temporaryUsername")

        binding.buttonCancelMatchmaking.setOnClickListener {
            cancelMatchmaking()
        }


        // Removed observeViewModel() call

        // Directly start matchmaking when the activity is created
        startMatchmaking()
    }

    // Removed observeViewModel() function

    private fun startMatchmaking() {
        // Prevent starting multiple matchmaking jobs
        if (matchmakingJob?.isActive == true) {
            Log.d("MatchmakingActivity", "Matchmaking already in progress.")
            return
        }

        Log.i("MatchmakingActivity", "Starting matchmaking process for user $temporaryUserId...")
        binding.textViewStatus.text = "Connecting to matchmaking..."
        binding.progressBarMatchmaking.visibility = View.VISIBLE
        binding.buttonCancelMatchmaking.isEnabled = true // Enable cancel button

        // Launch the matchmaking polling coroutine
        matchmakingJob = lifecycleScope.launch {
            var matchFound = false
            val initialDelay = 500L // Short delay before first poll
            delay(initialDelay)

            while (isActive && !matchFound) {
                try {
                    Log.d("MatchmakingActivity", "Polling matchmaking API...")
                    // Make the actual API call using the existing ApiClient instance
                    val response = ApiClient.instance.findMatch(
                        userId = temporaryUserId,
                        username = temporaryUsername
                    )

                    // Check if the coroutine was cancelled after the network call returned
                    if (!isActive) {
                        Log.d("MatchmakingActivity", "Matchmaking job cancelled after API response.")
                        break
                    }

                    if (response.isSuccessful) {
                        val matchmakingStatus = response.body()
                        if (matchmakingStatus != null) {
                            when (matchmakingStatus.status) {
                                "matched" -> {
                                    if (matchmakingStatus.game_id != null) {
                                        // MATCH FOUND!
                                        Log.i("MatchmakingActivity", "Match found! Game ID: ${matchmakingStatus.game_id}, Opponent: ${matchmakingStatus.opponent_name}")
                                        binding.textViewStatus.text = "Match Found!"
                                        binding.progressBarMatchmaking.visibility = View.GONE // Hide progress bar
                                        Toast.makeText(this@MatchmakingActivity, "Match found with ${matchmakingStatus.opponent_name ?: "Player"}!", Toast.LENGTH_LONG).show()
                                        matchFound = true // Set flag to exit loop
                                        binding.buttonCancelMatchmaking.isEnabled = false // Disable cancel after match
                                        // Proceed to the game screen
                                        proceedToGame(matchmakingStatus.game_id, temporaryUserId)
                                    } else {
                                        // Should not happen if status is matched, but handle defensively
                                        Log.e("MatchmakingActivity", "Error: Matched status received but game_id is null!")
                                        binding.textViewStatus.text = "Error: Invalid match data."
                                        delay(5000) // Wait longer before retrying on this specific error
                                    }
                                }
                                "waiting" -> {
                                    // Still waiting, update UI and continue polling
                                    binding.textViewStatus.text = "Searching for opponent..."
                                    Log.d("MatchmakingActivity", "Status: waiting...")
                                }
                                else -> {
                                    // Handle other potential statuses defined by the backend (e.g., "error", "cancelled")
                                    Log.w("MatchmakingActivity", "Received unexpected status: ${matchmakingStatus.status}")
                                    binding.textViewStatus.text = "Searching... (${matchmakingStatus.status})"
                                }
                            }
                        } else {
                            // Handle case where response is successful (2xx) but body is null
                            Log.e("MatchmakingActivity", "Matchmaking response body is null despite successful status.")
                            binding.textViewStatus.text = "Error: Empty response."
                            delay(5000) // Wait before retrying
                        }
                    } else {
                        // Handle API error response (non-2xx status code)
                        val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                        Log.e("MatchmakingActivity", "Matchmaking API error: ${response.code()} - $errorBody")
                        binding.textViewStatus.text = "Server Error (${response.code()}). Retrying..."
                        delay(5000) // Wait longer on HTTP error before retrying
                    }

                } catch (e: IOException) {
                    // Handle network connectivity errors
                    if (!isActive) break // Exit if cancelled during exception handling
                    Log.e("MatchmakingActivity", "Network error during matchmaking poll: ${e.message}")
                    binding.textViewStatus.text = "Network error. Check connection."
                    delay(5000) // Wait before retrying network error
                } catch (e: HttpException) {
                    // Handle non-2xx HTTP errors caught by Retrofit/OkHttp
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "HTTP error during matchmaking poll: ${e.code()} - ${e.message()}")
                    binding.textViewStatus.text = "Server error (${e.code()}). Retrying..."
                    delay(5000)
                } catch (e: Exception) {
                    // Handle any other unexpected errors (like JSON parsing issues, etc.)
                    if (!isActive) break
                    Log.e("MatchmakingActivity", "Unexpected error during matchmaking poll: ${e.message}", e)
                    binding.textViewStatus.text = "An error occurred. Retrying..."
                    delay(5000)
                }

                // Only delay if no match was found and the loop is still active
                if (!matchFound && isActive) {
                    val pollInterval = 3000L // Poll every 3 seconds
                    Log.d("MatchmakingActivity", "Waiting ${pollInterval}ms before next poll.")
                    delay(pollInterval)
                }
            } // End while loop

            // If the loop finished but no match was found and the job wasn't cancelled
            if (!matchFound && isActive) {
                Log.w("MatchmakingActivity", "Matchmaking loop finished without finding a match.")
                binding.textViewStatus.text = "Could not find a match. Try again?"
                binding.progressBarMatchmaking.visibility = View.GONE
                binding.buttonCancelMatchmaking.isEnabled = false // Disable cancel if loop ends naturally
                // Consider adding a "Try Again" button here
            }
        }
    }


    // Navigates to MainActivity with necessary data
    private fun proceedToGame(gameId: String, userId: Int) {
        // Ensure polling stops if it hasn't already (e.g., if called outside the loop)
        if (matchmakingJob?.isActive == true) {
            matchmakingJob?.cancel()
            matchmakingJob = null
        }
        binding.progressBarMatchmaking.visibility = View.GONE // Ensure progress bar is hidden

        Log.i("MatchmakingActivity", "Proceeding to game: $gameId for user: $userId")
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_GAME_ID, gameId)
            putExtra(EXTRA_USER_ID, userId)
            // Clear this activity from the back stack so pressing back doesn't return here
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish() // Close MatchmakingActivity definitively
    }

    // Cancels the matchmaking process both locally and on the backend
    private fun cancelMatchmaking() {
        // Check if there's an active job to cancel
        if (matchmakingJob?.isActive != true) {
            Log.d("MatchmakingActivity", "Matchmaking not active or already cancelled.")
            // Ensure UI reflects cancelled state if needed, even if job was already done/null
            binding.textViewStatus.text = "Matchmaking Cancelled."
            binding.progressBarMatchmaking.visibility = View.GONE
            binding.buttonCancelMatchmaking.isEnabled = false
            return
        }

        val userIdToCancel = temporaryUserId
        Log.i("MatchmakingActivity", "Attempting to cancel matchmaking for user $userIdToCancel...")

        // Update UI immediately
        binding.buttonCancelMatchmaking.isEnabled = false // Disable button during cancellation
        binding.textViewStatus.text = "Cancelling matchmaking..."
        binding.progressBarMatchmaking.visibility = View.VISIBLE // Show progress during cancel API call

        // Cancel the local polling job first
        matchmakingJob?.cancel()
        matchmakingJob = null // Clear the job reference

        // Call the backend API to remove the player from the pool
        lifecycleScope.launch {
            try {
                Log.d("MatchmakingActivity", "Calling cancelMatchmaking API for user $userIdToCancel...")
                val response = ApiClient.instance.cancelMatchmaking(userIdToCancel)

                if (response.isSuccessful) {
                    // Backend confirmed cancellation
                    Log.i("MatchmakingActivity", "Matchmaking successfully cancelled on backend.")
                    binding.textViewStatus.text = "Matchmaking Cancelled."
                    Toast.makeText(this@MatchmakingActivity, "Matchmaking cancelled", Toast.LENGTH_SHORT).show()
                    // Maybe finish the activity automatically after successful cancel?

                } else {
                    // Backend returned an error during cancellation
                    val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                    Log.e("MatchmakingActivity", "Failed to cancel matchmaking on backend: ${response.code()} - $errorBody")
                    binding.textViewStatus.text = "Cancellation failed (Server Error ${response.code()})"
                    // Re-enable cancel button? Or let user back out? Keep disabled for now.
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
                // Ensure progress bar is hidden after cancellation attempt finishes
                binding.progressBarMatchmaking.visibility = View.GONE
                // Keep cancel button disabled as the process is finished (successfully or not)
                binding.buttonCancelMatchmaking.isEnabled = false
            }
        }
        val intent = Intent(this, LauncherActivity::class.java).apply {
            // Clear this activity from the back stack so pressing back doesn't return here
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure the matchmaking job is cancelled if the activity is destroyed
        // This prevents the coroutine from leaking and continuing network calls
        if (matchmakingJob?.isActive == true) {
            Log.d("MatchmakingActivity", "onDestroy called, cancelling active matchmaking job.")
            matchmakingJob?.cancel()
            matchmakingJob = null
            // Consider if backend cancellation should be attempted here as well.
            // This depends on whether you want the player removed from the pool
            // if they forcefully close the app during matchmaking.
            // It requires launching another short-lived coroutine for the API call.
            // Example (optional):

            lifecycleScope.launch {
                try {
                    Log.i("MatchmakingActivity", "Attempting backend cancellation on destroy for user $temporaryUserId")
                    ApiClient.instance.cancelMatchmaking(temporaryUserId)
                } catch (e: Exception) {
                    Log.w("MatchmakingActivity", "Error during backend cancellation on destroy: ${e.message}")
                }
            }

        }
    }
}