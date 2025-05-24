// LauncherActivity.kt
package com.laurenz.wordextremist

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayGamesSdk
//import com.laurenz.wordextremist.auth.AuthPgsStatus
//import com.laurenz.wordextremist.auth.AuthViewModel
import com.laurenz.wordextremist.databinding.ActivityLauncherBinding
import kotlinx.coroutines.launch
import java.util.UUID

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding



    // --- DEBUG FLAG ---
    private val USE_DEBUG_AUTH = BuildConfig.DEBUG
    private val DEBUG_SERVER_AUTH_CODE_FOR_BACKEND = "DEBUG_FAKE_SERVER_AUTH_CODE_FOR_TESTING"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize AuthViewModel and CredentialManager/PlayGamesSd
        if (!USE_DEBUG_AUTH) {
            PlayGamesSdk.initialize(this) // Initialize Play Games SDK if not in debug auth mode
        }

        setupClickListeners()
        //observeViewModel()

    }

    private fun setupClickListeners() {
        binding.buttonStartMatch.setOnClickListener {
            //if (authViewModel.authStatus.value == AuthPgsStatus.AUTHENTICATED) {
            startActivity(Intent(this, MatchmakingActivity::class.java))

        }

        binding.buttonEditProfile.setOnClickListener {

            Toast.makeText(this, "Edit Profile: Not yet implemented.", Toast.LENGTH_SHORT).show()
            // Intent(this, ProfileActivity::class.java).also { startActivity(it) }

        }

        binding.buttonViewLeaderboard.setOnClickListener {

            Toast.makeText(this, "View Leaderboard: Not yet implemented.", Toast.LENGTH_SHORT).show()
            // Intent(this, LeaderboardActivity::class.java).also { startActivity(it) }

        }

    }

//    private fun observeViewModel() {
//        authViewModel.authStatus.observe(this) { status ->
//            Log.d("LauncherAuth", "Auth Status Changed: $status")
//            binding.progressBarAuth.visibility = if (status == AuthPgsStatus.BACKEND_AUTH_LOADING || status == AuthPgsStatus.PGS_SIGN_IN_ATTEMPTED) View.VISIBLE else View.GONE
//            when (status) {
//                AuthPgsStatus.AUTHENTICATED -> {
//                    binding.layoutMenuOptions.visibility = View.VISIBLE
//                    binding.buttonSignOut.visibility = View.VISIBLE
//                }
//                AuthPgsStatus.ERROR -> {
//                    binding.textViewWelcome.text = authViewModel.errorMessage.value ?: "Sign-in failed."
//                    binding.layoutMenuOptions.visibility = View.GONE // Or show limited options
//                    binding.buttonSignOut.visibility = View.GONE
//                    // Offer a retry sign-in button if appropriate
//                }
//                AuthPgsStatus.IDLE -> {
//                    binding.textViewWelcome.text = "Welcome! Please sign in."
//                    binding.layoutMenuOptions.visibility = View.GONE
//                    binding.buttonSignOut.visibility = View.GONE
//                    // If truly idle, might trigger sign-in attempt again or show sign-in button
//                }
//                AuthPgsStatus.BACKEND_AUTH_LOADING -> {
//                    binding.textViewWelcome.text = "Authenticating with server..."
//                }
//                AuthPgsStatus.PGS_SIGN_IN_ATTEMPTED -> {
//                    binding.textViewWelcome.text = "Finalizing sign-in..."
//                }
//                null -> {
//                    binding.textViewWelcome.text = "Initializing..."
//                    binding.layoutMenuOptions.visibility = View.GONE
//                    binding.buttonSignOut.visibility = View.GONE
//                }
//            }
//        }
//
//        authViewModel.currentUser.observe(this) { user ->
//            if (user != null && authViewModel.authStatus.value == AuthPgsStatus.AUTHENTICATED) {
//                binding.textViewWelcome.text = "Welcome, ${user.username ?: "Player"}!"
//            } else if (authViewModel.authStatus.value != AuthPgsStatus.BACKEND_AUTH_LOADING &&
//                authViewModel.authStatus.value != AuthPgsStatus.PGS_SIGN_IN_ATTEMPTED &&
//                !USE_DEBUG_AUTH) {
//                binding.textViewWelcome.text = "Sign in to play!"
//            }
//        }
//
//        authViewModel.errorMessage.observe(this) { error ->
//            error?.let {
//                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
//                authViewModel.clearErrorMessage()
//            }
//        }
//    }



}