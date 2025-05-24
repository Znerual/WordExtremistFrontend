package com.laurenz.wordextremist

data class PlayerState(
    var serverId: String, // e.g., "player1" or a unique ID from backend
    var name: String,
    var score: Int = 0, // Overall match score (rounds won)
    var mistakesInCurrentRound: Int = 0
)
