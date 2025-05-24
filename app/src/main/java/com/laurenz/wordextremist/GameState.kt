package com.laurenz.wordextremist

import android.util.Log
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
    var isWaitingForOpponent: Boolean = true
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

    fun updateFromJson(payload: JSONObject, localPlayerActualServerId: String) {
        Log.d("GameStateUpdate", "Updating from JSON. LocalPlayerActualServerId: $localPlayerActualServerId")
        Log.d("GameStateUpdate", "Payload: ${payload.toString(2)}")

        currentSentence = payload.optString("current_sentence", currentSentence)
        currentPrompt = payload.optString("prompt", currentPrompt)
        wordToReplace = payload.optString("word_to_replace", wordToReplace)
        currentRound = payload.optInt("round", currentRound)

        val serverP1Id = payload.optString("player1_server_id")
        val serverP2Id = payload.optString("player2_server_id")
        val serverP1StateJson = payload.optJSONObject("player1_state")
        val serverP2StateJson = payload.optJSONObject("player2_state")

        if (serverP1Id.isEmpty() || serverP2Id.isEmpty()) {
            Log.e("GameStateUpdate", "Server did not provide player1_server_id or player2_server_id. Cannot map players correctly.")
            // Potentially keep isWaitingForOpponent = true if IDs are missing
        }

        // Assign server player data to local player1 (self) and player2 (opponent)
        if (serverP1Id == localPlayerActualServerId) {
            updatePlayerStateFromPayload(this.player1, serverP1Id, serverP1StateJson, "player1_state (local)")
            updatePlayerStateFromPayload(this.player2, serverP2Id, serverP2StateJson, "player2_state (opponent)")
            assignWords(payload.optJSONArray("player1_words"), wordsPlayedThisRoundByPlayer1)
            assignWords(payload.optJSONArray("player2_words"), wordsPlayedThisRoundByPlayer2)
        } else if (serverP2Id == localPlayerActualServerId) {
            updatePlayerStateFromPayload(this.player1, serverP2Id, serverP2StateJson, "player2_state (local)")
            updatePlayerStateFromPayload(this.player2, serverP1Id, serverP1StateJson, "player1_state (opponent)")
            assignWords(payload.optJSONArray("player2_words"), wordsPlayedThisRoundByPlayer1)
            assignWords(payload.optJSONArray("player1_words"), wordsPlayedThisRoundByPlayer2)
        } else {
            Log.e("GameStateUpdate", "LocalPlayerActualServerId ($localPlayerActualServerId) matched neither serverP1Id ($serverP1Id) nor serverP2Id ($serverP2Id). Player data might be incorrect.")
            // Fallback: try to update based on existing non-empty serverIds if previously set,
            // or assume serverP1State is for player1 and serverP2State is for player2 if serverIds are not yet set.
            // This part needs careful consideration based on how consistently server sends IDs.
            // For now, we will log an error and proceed hoping for the best, but this is a weak point if IDs mismatch.
            if (this.player1.serverId.isEmpty() && this.player2.serverId.isEmpty()) { // First time setup without clear mapping
                updatePlayerStateFromPayload(this.player1, serverP1Id, serverP1StateJson, "player1_state (assumed local)")
                updatePlayerStateFromPayload(this.player2, serverP2Id, serverP2StateJson, "player2_state (assumed opponent)")
                assignWords(payload.optJSONArray("player1_words"), wordsPlayedThisRoundByPlayer1)
                assignWords(payload.optJSONArray("player2_words"), wordsPlayedThisRoundByPlayer2)
            }
        }


        // Update whose turn it is
        val serverWhoseTurnId = payload.optString("current_player_id")
        if (serverWhoseTurnId.isNotEmpty()) {
            if (this.player1.serverId == serverWhoseTurnId && this.player1.serverId.isNotEmpty()) {
                currentPlayerTurn = PlayerTurn.PLAYER_1
            } else if (this.player2.serverId == serverWhoseTurnId && this.player2.serverId.isNotEmpty()) {
                currentPlayerTurn = PlayerTurn.PLAYER_2
            } else {
                Log.w("GameStateUpdate", "Could not determine current turn from server_id: $serverWhoseTurnId. P1_SID: ${this.player1.serverId}, P2_SID: ${this.player2.serverId}")
            }
        } else {
            Log.w("GameStateUpdate", "current_player_id missing or empty in payload.")
        }

        // Determine if waiting for opponent
        val gameIsActive = payload.optBoolean("game_active", false) // Server MUST send this
        val bothPlayersHaveServerIds = this.player1.serverId.isNotEmpty() && this.player2.serverId.isNotEmpty()

        if (gameIsActive && bothPlayersHaveServerIds) {
            this.isWaitingForOpponent = false
        } else {
            // If game is not marked active OR not all player server IDs are known, we are likely still waiting or in an error state.
            this.isWaitingForOpponent = true
            if (!gameIsActive) Log.d("GameStateUpdate", "game_active is false or missing.")
            if (!bothPlayersHaveServerIds) Log.d("GameStateUpdate", "Not all player server IDs are populated. P1_SID: ${this.player1.serverId}, P2_SID: ${this.player2.serverId}")
        }

        rebuildAllWordsSet()
        Log.i("GameStateUpdate", "After update: isWaitingForOpponent=$isWaitingForOpponent, currentPlayerTurn=$currentPlayerTurn, P1_Name=${player1.name}(${player1.serverId}), P2_Name=${player2.name}(${player2.serverId})")

    }

    private fun updatePlayerStateFromPayload(player: PlayerState, serverId: String, stateJson: JSONObject?, logContext: String) {
        if (serverId.isNotEmpty()) { // Only assign serverId if provided
            player.serverId = serverId
        }
        if (stateJson != null) {
            // Keep "You" for local player if server name is generic, or update if server sends specific names.
            val serverName = stateJson.optString("name")
            if (player === this.player1 && (serverName.isEmpty() || serverName.startsWith("Player"))) {
                // Keep local player's name as "You" unless server provides a better one
            } else if (serverName.isNotEmpty()){
                player.name = serverName
            }
            player.score = stateJson.optInt("score", player.score)
            player.mistakesInCurrentRound = stateJson.optInt("mistakes", player.mistakesInCurrentRound)
            Log.d("GameStateUpdate", "Updated $logContext: Name=${player.name}, SID=${player.serverId}, Score=${player.score}, Mistakes=${player.mistakesInCurrentRound}")
        } else {
            Log.w("GameStateUpdate", "$logContext: stateJson is null for serverId $serverId")
        }
    }

    private fun assignWords(wordsArray: org.json.JSONArray?, targetList: MutableList<String>) {
        targetList.clear()
        if (wordsArray != null) {
            for (i in 0 until wordsArray.length()) {
                targetList.add(wordsArray.optString(i, ""))
            }
        }
    }
}
