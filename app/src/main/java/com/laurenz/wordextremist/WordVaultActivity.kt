package com.laurenz.wordextremist

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.laurenz.wordextremist.databinding.ActivityWordVaultBinding
import com.laurenz.wordextremist.views.WordWheelView

class WordVaultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWordVaultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dummyWords = listOf(
            // Triple: (WordText, CreativityScore, (Sentence, Prompt))
            Triple("Ephemeral", 5, Pair("The beauty was ephemeral.", "Find a synonym.")),
            Triple("Serendipity", 4, Pair("It was pure serendipity that they met.", "Explain it.")),
            Triple("Mellifluous", 3, Pair("Her voice was mellifluous.", "Use in a sentence about nature.")),
            Triple("Brilliant", 5, Pair("The idea was brilliant.", "Make it more EXTREME!")),
            Triple("Clever", 2, Pair("A clever fox outsmarted the hunter.", "Find a synonym.")),
            Triple("Astute", 4, Pair("His astute observations impressed everyone.", "Use in a sentence.")),
            Triple("Quixotic", 3, Pair("His quixotic quest was inspiring.", "What does it mean?")),
            Triple("Ubiquitous", 2, Pair("Smartphones are ubiquitous.", "Give an example.")),
            Triple("Luminous", 4, Pair("The moon was luminous.", "Find an antonym.")),
            Triple("Resilience", 5, Pair("She showed great resilience.", "What's its power?")),
            Triple("Panacea", 1, Pair("It wasn't a panacea for all ills.", "Define it.")),
            Triple("Zany", 3, Pair("His zany antics made everyone laugh.", "Describe it.")),
            Triple("Ephemeral", 5, Pair("The beauty was ephemeral.", "Find a synonym.")),
            Triple("Serendipity", 4, Pair("It was pure serendipity that they met.", "Explain it.")),
            Triple("Mellifluous", 3, Pair("Her voice was mellifluous.", "Use in a sentence about nature.")),
            Triple("Brilliant", 5, Pair("The idea was brilliant.", "Make it more EXTREME!")),
            Triple("Clever", 2, Pair("A clever fox outsmarted the hunter.", "Find a synonym.")),
            Triple("Astute", 4, Pair("His astute observations impressed everyone.", "Use in a sentence.")),
            Triple("Quixotic", 3, Pair("His quixotic quest was inspiring.", "What does it mean?")),
            Triple("Ubiquitous", 2, Pair("Smartphones are ubiquitous.", "Give an example.")),
            Triple("Luminous", 4, Pair("The moon was luminous.", "Find an antonym.")),
            Triple("Resilience", 5, Pair("She showed great resilience.", "What's its power?")),
            Triple("Panacea", 1, Pair("It wasn't a panacea for all ills.", "Define it.")),
            Triple("Zany", 3, Pair("His zany antics made everyone laugh.", "Describe it.")),
            Triple("Ephemeral", 5, Pair("The beauty was ephemeral.", "Find a synonym.")),
            Triple("Serendipity", 4, Pair("It was pure serendipity that they met.", "Explain it.")),
            Triple("Mellifluous", 3, Pair("Her voice was mellifluous.", "Use in a sentence about nature.")),
            Triple("Brilliant", 5, Pair("The idea was brilliant.", "Make it more EXTREME!")),
            Triple("Clever", 2, Pair("A clever fox outsmarted the hunter.", "Find a synonym.")),
            Triple("Astute", 4, Pair("His astute observations impressed everyone.", "Use in a sentence.")),
            Triple("Quixotic", 3, Pair("His quixotic quest was inspiring.", "What does it mean?")),
            Triple("Ubiquitous", 2, Pair("Smartphones are ubiquitous.", "Give an example.")),
            Triple("Luminous", 4, Pair("The moon was luminous.", "Find an antonym.")),
            Triple("Resilience", 5, Pair("She showed great resilience.", "What's its power?")),
            Triple("Panacea", 1, Pair("It wasn't a panacea for all ills.", "Define it.")),
            Triple("Zany", 3, Pair("His zany antics made everyone laugh.", "Describe it.")),
        )

        // For now, take words after the top 3 for the wheel
        val wheelWordsData = if (dummyWords.size > 3) dummyWords.subList(3, dummyWords.size) else emptyList()
        binding.wordWheelView.setWords(wheelWordsData)

        binding.wordWheelView.wordSelectedListener = object : WordWheelView.OnWordSelectedListener {
            override fun onWordSelected(word: String, sentence: String?, prompt: String?) {
                Log.i("WordVaultActivity", "Word selected from wheel: $word")
                binding.detailsDisplayArea.visibility = View.VISIBLE
                binding.textViewDetailWord.text = word
                binding.textViewDetailPrompt.text = prompt ?: "N/A"
                binding.textViewDetailSentence.text = sentence ?: "N/A"
                // You might want to animate the detailsDisplayArea in
            }
        }

    }
}