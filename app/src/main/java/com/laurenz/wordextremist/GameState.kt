package com.laurenz.wordextremist

import org.json.JSONObject // Import for JSON handling

enum class PlayerTurn {
    PLAYER_1, PLAYER_2
}

data class GameState(
    var player1: PlayerState,
    var player2: PlayerState,
    var currentPlayerTurn: PlayerTurn = PlayerTurn.PLAYER_1,
    var currentRound: Int = 1,
    val maxRounds: Int = 3, // e.g., best of 3 rounds
    var currentSentence: String = "Waiting for sentence...",
    var currentPrompt: String = "Waiting for prompt...",
    var wordToReplace: String = "",
    var wordsPlayedThisRoundByPlayer1: MutableList<String> = mutableListOf(),
    var wordsPlayedThisRoundByPlayer2: MutableList<String> = mutableListOf(),
    var allWordsPlayedThisRoundSet: MutableSet<String> = mutableSetOf(),
    var isWaitingForOpponent: Boolean = false
) {
    fun getCurrentPlayer(): PlayerState {
        return if (currentPlayerTurn == PlayerTurn.PLAYER_1) player1 else player2
    }

    fun getOpponentPlayer(): PlayerState {
        return if (currentPlayerTurn == PlayerTurn.PLAYER_1) player2 else player1
    }

    fun switchToOpponentTurnAndAwait() { // New helper
        switchTurn() // This already switches currentPlayerTurn
        isWaitingForOpponent = true
    }

    fun opponentHasPlayed() { // New helper
        isWaitingForOpponent = false
        // The actual switchTurn to local player will happen when their turn starts
    }

    fun switchTurn() {
        currentPlayerTurn = if (currentPlayerTurn == PlayerTurn.PLAYER_1) PlayerTurn.PLAYER_2 else PlayerTurn.PLAYER_1
        // Reset timer for the new player's turn
        // This will be handled in MainActivity
    }

    fun recordMistake() {
        getCurrentPlayer().mistakesInCurrentRound++
    }

    fun recordValidWord(word: String) { // New function
        allWordsPlayedThisRoundSet.add(word.lowercase())
        if (currentPlayerTurn == PlayerTurn.PLAYER_1) {
            wordsPlayedThisRoundByPlayer1.add(word)
        } else {
            wordsPlayedThisRoundByPlayer2.add(word)
        }
    }

    fun resetRoundMistakesAndWords() { // Renamed and updated
        player1.mistakesInCurrentRound = 0
        player2.mistakesInCurrentRound = 0
        wordsPlayedThisRoundByPlayer1.clear()
        wordsPlayedThisRoundByPlayer2.clear()
        allWordsPlayedThisRoundSet.clear()
    }

    fun rebuildAllWordsSet() {
        allWordsPlayedThisRoundSet.clear()
        wordsPlayedThisRoundByPlayer1.forEach { allWordsPlayedThisRoundSet.add(it.lowercase()) }
        wordsPlayedThisRoundByPlayer2.forEach { allWordsPlayedThisRoundSet.add(it.lowercase()) }
    }

    fun isRoundOver(): Boolean {
        return player1.mistakesInCurrentRound >= 3 || player2.mistakesInCurrentRound >= 3
        // Potentially add other conditions like a max word count per round
    }

    fun isGameOver(): Boolean {
        // Game over if a player has won the majority of rounds
        val roundsNeededToWin = (maxRounds / 2) + 1
        return player1.score >= roundsNeededToWin || player2.score >= roundsNeededToWin || currentRound > maxRounds
    }

    fun getRoundWinner(): PlayerState? {
        if (player1.mistakesInCurrentRound >= 3) return player2
        if (player2.mistakesInCurrentRound >= 3) return player1
        return null // Round not over due to mistakes yet, or another win condition
    }

    fun updateFromJson(payload: JSONObject) {
        currentSentence = payload.optString("current_sentence", currentSentence)
        currentPrompt = payload.optString("prompt", currentPrompt)
        wordToReplace = payload.optString("word_to_replace", wordToReplace)
        currentRound = payload.optInt("round", currentRound)

        val p1State = payload.optJSONObject("player1_state")
        if (p1State != null) {
            player1.score = p1State.optInt("score", player1.score)
            player1.mistakesInCurrentRound = p1State.optInt("mistakes", player1.mistakesInCurrentRound)
            // player1.id = p1State.optString("id", player1.id) // If sending IDs
        }

        val p2State = payload.optJSONObject("player2_state")
        if (p2State != null) {
            player2.score = p2State.optInt("score", player2.score)
            player2.mistakesInCurrentRound = p2State.optInt("mistakes", player2.mistakesInCurrentRound)
            // player2.id = p2State.optString("id", player2.id)
        }

        // Update whose turn it is - IMPORTANT: map player ID from server to local enum
        // This mapping logic might need to be more robust if IDs aren't fixed
        val whoseTurnId = payload.optString("current_player_id")
        if (whoseTurnId.isNotEmpty()) {
            currentPlayerTurn = if (whoseTurnId == player1.id) PlayerTurn.PLAYER_1 else PlayerTurn.PLAYER_2
        }


        // Determine if waiting based on whose turn it is (client-side perspective)
        isWaitingForOpponent = (currentPlayerTurn == PlayerTurn.PLAYER_2)

        // Update played words lists (handle potential null or missing arrays)
        val p1WordsArray = payload.optJSONArray("player1_words")
        if (p1WordsArray != null) {
            wordsPlayedThisRoundByPlayer1.clear()
            for (i in 0 until p1WordsArray.length()) {
                wordsPlayedThisRoundByPlayer1.add(p1WordsArray.optString(i, ""))
            }
        }

        val p2WordsArray = payload.optJSONArray("player2_words")
        if (p2WordsArray != null) {
            wordsPlayedThisRoundByPlayer2.clear()
            for (i in 0 until p2WordsArray.length()) {
                wordsPlayedThisRoundByPlayer2.add(p2WordsArray.optString(i, ""))
            }
        }

        // Rebuild the set after updating the lists
        rebuildAllWordsSet()
    }
}
