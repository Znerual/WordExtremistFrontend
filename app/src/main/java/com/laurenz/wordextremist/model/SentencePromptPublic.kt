package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class SentencePromptPublic(
    val id: Int,
    @SerializedName("sentence_text")
    val sentenceText: String,
    @SerializedName("target_word")
    val targetWord: String,
    @SerializedName("prompt_text")
    val promptText: String
    // Add other fields like difficulty if present in backend model
)
