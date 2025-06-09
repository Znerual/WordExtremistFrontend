package com.laurenz.wordextremist

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.laurenz.wordextremist.databinding.ActivityUserProfileDetailBinding
import com.laurenz.wordextremist.model.WordVaultEntry
import com.laurenz.wordextremist.network.ApiClient
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period
import androidx.activity.addCallback
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow

class UserProfileDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileDetailBinding
    private var userId: Int = -1

    private var gameTurnEndTimeMillis: Long = 0L
    private var warningTimer: CountDownTimer? = null

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_TURN_END_TIME_MILLIS = "extra_turn_end_time_millis" // New extra
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this /* LifecycleOwner */) {
            // This block is executed when the back button is pressed.
            Log.d("UserProfileDetail", "Back press handled by OnBackPressedDispatcher.")
            setResult(Activity.RESULT_CANCELED)
            // Important: Call finish() if you want the activity to close.
            // The enabled = false and remove() below would only stop further processing
            // of this callback, not close the activity unless you call finish().
            finish()
            // Alternatively, if you wanted to conditionally handle back press:
            // isEnabled = false // Disable this callback
            // requireActivity().onBackPressedDispatcher.onBackPressed() // Then invoke default behavior or other callbacks
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            // Explicitly set result CANCELED if user uses back arrow,
            // so MainActivity knows it wasn't a timer-induced finish.
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        userId = intent.getIntExtra(EXTRA_USER_ID, -1)
        gameTurnEndTimeMillis = intent.getLongExtra(EXTRA_TURN_END_TIME_MILLIS, 0L)
        Log.d("UserProfileDetail", "Received UserID: $userId, Received turnEndTimeMillis: $gameTurnEndTimeMillis")

        if (userId == -1) {
            Toast.makeText(this, "Error: User ID not found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        fetchUserProfile()
        fetchUserBestWords()

        if (gameTurnEndTimeMillis > 0) {
            setupTurnWarningTimer()
        } else {
            // --- ADD THIS LOG STATEMENT ---
            Log.w("UserProfileDetail", "GameTurnEndTimeMillis is 0 or less, not starting warning timer.")
            binding.profileTimerWarning.visibility = View.GONE // Ensure it's hidden if no timer
        }

    }

    private fun setupTurnWarningTimer() {
        val currentTime = System.currentTimeMillis()
        val initialTimeLeft = gameTurnEndTimeMillis - currentTime

        Log.d("UserProfileDetailTimer", "setupTurnWarningTimer called.")
        Log.d("UserProfileDetailTimer", "CurrentTime: $currentTime, GameTurnEndTime: $gameTurnEndTimeMillis, InitialTimeLeft: $initialTimeLeft")

        if (initialTimeLeft <= 0) {
            binding.profileTimerWarning.text = "Time's Up!"
            binding.profileTimerWarning.visibility = View.VISIBLE
            Log.d("UserProfileDetailTimer", "Time already up. Setting text and returning.")
            setResult(Activity.RESULT_OK) // Or a custom result code
            finish()
            return
        }

        binding.profileTimerWarning.visibility = View.GONE // Hide initially if time > 10s

        warningTimer = object : CountDownTimer(initialTimeLeft, 100) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isActive()) {
                    Log.d("UserProfileDetailTimer", "onTick: Activity no longer active, cancelling.")
                    cancel()
                    return
                }


                val secondsLeft = (millisUntilFinished + 999) / 1000
                Log.d("UserProfileDetailTimer", "onTick: Seconds Left: $secondsLeft")

                if (secondsLeft <= 10) { // Show warning when 10 seconds or less
                    binding.profileTimerWarning.text = "Warning: $secondsLeft seconds left in game!"
                    binding.profileTimerWarning.visibility = View.VISIBLE
                    if (secondsLeft <= 5) {
                        binding.profileTimerWarning.setTextColor(ContextCompat.getColor(this@UserProfileDetailActivity, R.color.timer_critical))
                    } else {
                        binding.profileTimerWarning.setTextColor(ContextCompat.getColor(this@UserProfileDetailActivity, R.color.timer_warning))
                    }
                } else {
                    binding.profileTimerWarning.visibility = View.GONE
                }
            }

            override fun onFinish() {
                if (!isActive()) {
                    Log.d("UserProfileDetailTimer", "onFinish: Activity no longer active.")
                    return
                }
                Log.d("UserProfileDetailTimer", "onFinish: Timer finished.")
                binding.profileTimerWarning.text = "Time's Up!"
                binding.profileTimerWarning.setTextColor(ContextCompat.getColor(this@UserProfileDetailActivity, R.color.timer_critical))
                binding.profileTimerWarning.visibility = View.VISIBLE
                setResult(Activity.RESULT_OK) // Indicate a normal closure due to timer.
                // MainActivity's onActivityResult will be called.
                finish() // Go back to MainActivity
            }
        }.start()
        Log.d("UserProfileDetailTimer", "WarningTimer started with initialTimeLeft: $initialTimeLeft")
    }

    private fun fetchUserProfile() {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getUserProfile(userId)
                if (response.isSuccessful && response.body() != null) {
                    val user = response.body()!!

                    // Populate Identity
                    Glide.with(this@UserProfileDetailActivity)
                        .load(user.profilePicUrl)
                        .placeholder(R.drawable.avatar_dummy)
                        .error(R.drawable.avatar_dummy)
                        .into(binding.profileDetailImage)

                    binding.profileDetailUsername.text = user.username ?: "Player"

                    user.country?.let {
                        val locale = Locale("", it)
                        binding.profileDetailCountry.text = "${getCountryFlagEmoji(it)} ${locale.displayCountry}"
                    } ?: run {
                        binding.profileDetailCountry.visibility = View.GONE
                    }

                    // Populate Member Since
                    try {
                        val zonedDateTime = ZonedDateTime.parse(user.createdAt, DateTimeFormatter.ISO_DATE_TIME)
                        val formattedDate = zonedDateTime.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                        binding.profileDetailMemberSince.text = "Member since $formattedDate"
                    } catch (e: Exception) {
                        binding.profileDetailMemberSince.visibility = View.GONE
                    }

                    // Populate Stats
                    binding.statLevel.text = user.level.toString()
                    binding.statWords.text = "%,d".format(user.wordsCount)

                    // TODO: Replace with real XP calculation from backend
                    val xpForNextLevel = user.level * 100 * (1.25.pow(user.level - 1)).toInt()
                    binding.statXpBar.max = xpForNextLevel
                    binding.statXpBar.progress = user.experience

                } else {
                    Toast.makeText(this@UserProfileDetailActivity, "Failed to load profile", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("UserProfileDetail", "Error fetching profile", e)
                Toast.makeText(this@UserProfileDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun fetchUserBestWords() {
        lifecycleScope.launch {
            try {
                // NOTE: We need a new backend endpoint for this too!
                // For now, we will simulate or use the "me/words" for demonstration.
                // You should create a GET "/api/v1/auth/users/{user_id}/words" endpoint.
                val response = ApiClient.instance.getMyWords() // Simulating with "my words"
                if (response.isSuccessful && response.body() != null) {
                    val bestWords = response.body()!!.take(3) // Take top 3
                    populateBestWords(bestWords)
                }
            } catch (e: Exception) {
                Log.e("UserProfileDetail", "Error fetching best words", e)
            }
        }
    }

    private fun populateBestWords(words: List<WordVaultEntry>) {
        binding.bestWordsContainer.removeAllViews()
        if (words.isEmpty()) {
            val noWordsView = TextView(this).apply {
                text = "No words submitted yet!"
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            }
            binding.bestWordsContainer.addView(noWordsView)
            return
        }

        words.forEach { wordEntry ->
            val wordView = layoutInflater.inflate(R.layout.item_best_word, binding.bestWordsContainer, false)
            val wordText: TextView = wordView.findViewById(R.id.bestWordText)
            val scoreText: TextView = wordView.findViewById(R.id.bestWordScore)

            wordText.text = wordEntry.submittedWord.replaceFirstChar { it.titlecase() }
            scoreText.text = "Score: ${wordEntry.creativityScore ?: "N/A"}"

            binding.bestWordsContainer.addView(wordView)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun isActive(): Boolean {
        return !isFinishing && !isDestroyed
    }

    override fun onDestroy() {
        super.onDestroy()
        warningTimer?.cancel() // Clean up timer
    }

}
// You'll need the `getCountryFlagEmoji` helper from the previous response.