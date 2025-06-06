package com.laurenz.wordextremist

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.laurenz.wordextremist.auth.AuthManager
import com.laurenz.wordextremist.databinding.ActivityWordVaultBinding
import com.laurenz.wordextremist.model.WordVaultEntry
import com.laurenz.wordextremist.network.ApiClient
import com.laurenz.wordextremist.util.TokenManager
import com.laurenz.wordextremist.views.WordWheelView
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException


class WordVaultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWordVaultBinding
    private var allWordEntries: List<WordVaultEntry> = emptyList()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarWordVault.setNavigationOnClickListener {
            finish()
        }

        // Set up listeners for both wheel and pedestal
        setupSelectionListeners()

        // Hide main content until loaded
        binding.pedestalArea.visibility = View.INVISIBLE
        binding.wordWheelView.visibility = View.INVISIBLE
        binding.emptyStateView.visibility = View.GONE

        loadVault()
    }

    private fun loadVault() {
        // Show initial loading state
        binding.detailsDisplayArea.visibility = View.GONE
        binding.wordWheelView.visibility = View.INVISIBLE
        binding.textViewVaultStatus.visibility = View.VISIBLE
        binding.textViewVaultStatus.text = "Authenticating..."

        lifecycleScope.launch {
            // First, ensure the token is valid (this might do a silent re-login)
            val isAuthValid = AuthManager.ensureValidToken(this@WordVaultActivity)

            if (isAuthValid) {
                // If auth is good, proceed to fetch the data
                binding.textViewVaultStatus.text = "Loading Your Words..."
                fetchWordVaultData()
            } else {
                // If silent login failed, show a persistent error.
                binding.textViewVaultStatus.text = "Authentication failed.\nPlease check your connection and try again."
                Toast.makeText(this@WordVaultActivity, "Authentication failed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchWordVaultData() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getMyWords()

                if (response.isSuccessful) {
                    allWordEntries = response.body() ?: emptyList()
                    if (allWordEntries != null && allWordEntries.isNotEmpty()) {
                        binding.textViewVaultStatus.visibility = View.GONE
                        binding.emptyStateView.visibility = View.GONE

                        // Separate data for pedestal and wheel
                        val pedestalWords = allWordEntries.take(3)
                        val wheelWords = if (allWordEntries.size > 3) allWordEntries.subList(3, allWordEntries.size) else emptyList()

                        // Populate the UI components
                        populatePedestal(pedestalWords)
                        populateWordWheel(wheelWords)
                        // Make the content visible
                        binding.pedestalArea.visibility = View.VISIBLE
                        binding.wordWheelView.visibility = if (wheelWords.isNotEmpty()) View.VISIBLE else View.GONE
                    } else {
                        // Handle the case where the user has no words yet
                        binding.textViewVaultStatus.visibility = View.GONE
                        binding.pedestalArea.visibility = View.GONE
                        binding.wordWheelView.visibility = View.GONE
                        binding.emptyStateView.visibility = View.VISIBLE
                    }
                } else {
                    binding.textViewVaultStatus.text = "Failed to load words.\nServer Error: ${response.code()}"
                    binding.emptyStateView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("WordVaultActivity", "Error fetching word vault", e)
                binding.textViewVaultStatus.text = "An unexpected error occurred."
                binding.emptyStateView.visibility = View.VISIBLE
            }
        }
    }

    private fun populatePedestal(pedestalEntries: List<WordVaultEntry>) {
        val pedestalData = listOf(
            Triple(binding.pedestalCard1, binding.textViewPedestalWord1, binding.textViewPedestalRank1),
            Triple(binding.pedestalCard2, binding.textViewPedestalWord2, binding.textViewPedestalRank2),
            Triple(binding.pedestalCard3, binding.textViewPedestalWord3, binding.textViewPedestalRank3)
        )

        pedestalEntries.forEachIndexed { index, entry ->
            if (index < pedestalData.size) {
                val (card, wordView, rankView) = pedestalData[index]
                wordView.text = entry.submittedWord
                rankView.text = "#${index + 1}"
                card.visibility = View.VISIBLE
            }
        }

        for (i in pedestalEntries.size until pedestalData.size) {
            pedestalData[i].first.visibility = View.INVISIBLE
        }
    }

    private fun populateWordWheel(wheelEntries: List<WordVaultEntry>) {
        val wheelData = wheelEntries.map { entry ->
            Triple(
                entry.submittedWord,
                entry.creativityScore ?: 0,
                Pair(entry.sentenceText, entry.promptText)
            )
        }
        binding.wordWheelView.setWords(wheelData)
    }

    private fun handlePedestalClick(index: Int) {
        if (index < allWordEntries.size) {
            val entry = allWordEntries[index]
            showDetailsForWord(entry.submittedWord, entry.sentenceText, entry.promptText)
        }
    }

    private fun showDetailsForWord(word: String, sentence: String?, prompt: String?) {
        Log.i("WordVaultActivity", "Showing details for word: $word")
        binding.detailsDisplayArea.visibility = View.VISIBLE
        binding.textViewDetailWord.text = word
        binding.textViewDetailPrompt.text = prompt ?: "N/A"
        binding.textViewDetailSentence.text = sentence ?: "N/A"
        // Optional: Animate the details view in
    }


    private fun setupSelectionListeners() {
        // Word Wheel Listener
        binding.wordWheelView.wordSelectedListener = object : WordWheelView.OnWordSelectedListener {
            override fun onWordSelected(word: String, sentence: String?, prompt: String?) {
                showDetailsForWord(word, sentence, prompt)
            }
        }

        // Attach listeners to the new CardViews
        binding.pedestalCard1.setOnClickListener { handlePedestalClick(0) }
        binding.pedestalCard2.setOnClickListener { handlePedestalClick(1) }
        binding.pedestalCard3.setOnClickListener { handlePedestalClick(2) }
    }
}