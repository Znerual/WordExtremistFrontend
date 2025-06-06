package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class WordVaultEntry(
    @SerializedName("submitted_word")
    val submittedWord: String,

    @SerializedName("creativity_score")
    val creativityScore: Int?,

    @SerializedName("sentence_text")
    val sentenceText: String,

    @SerializedName("prompt_text")
    val promptText: String
)
