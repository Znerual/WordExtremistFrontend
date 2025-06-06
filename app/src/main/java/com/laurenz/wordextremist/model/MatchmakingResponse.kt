package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class MatchmakingResponse(
    @SerializedName("status")
    val status: String, // e.g., "matched", "waiting", "error"

    @SerializedName("game_id")
    val game_id: String?, // Can be null if status is "waiting" or "error"

    @SerializedName("language") // Ensure this matches backend key
    val language: String?, // Add this field

    @SerializedName("opponent_name")
    val opponent_name: String?, // Can be null if status is "waiting" or "error"

    @SerializedName("opponent_level")
    val opponent_level: Int?, // Can be null if status is "waiting" or "error"

    @SerializedName("opponent_profile_pic_url")
    val opponent_profile_pic_url: String?, // Can be null if status is "waiting" or "error"

    @SerializedName("player1_id")
    val player1_id: Int?, // Database ID of player 1 in the game, null if not matched

    @SerializedName("player2_id")
    val player2_id: Int?, // Database ID of player 2 in the game, null if not matched

    @SerializedName("your_player_id_in_game")
    val your_player_id_in_game: Int? // The database ID of the player who made this request, null if not applicable yet
)
