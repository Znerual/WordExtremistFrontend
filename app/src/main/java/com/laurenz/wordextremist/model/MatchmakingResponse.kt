package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class MatchmakingResponse(
    @SerializedName("game_id")
    val game_id: String,
    val status: String, // e.g., "matched", "waiting", "error"
    @SerializedName("opponent_name") // Assuming backend sends this
    val opponent_name: String? = null,
    // Add other fields your backend matchmaking might return
    // val player1_id: String?,
    // val player2_id: String?
)
