package com.laurenz.wordextremist

// Keep necessary imports, remove unused ones (PlayGames, AuthViewModel etc.)
// Removed ViewModelProvider
// Removed PlayGames imports
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.laurenz.wordextremist.databinding.ActivityMainBinding
import com.laurenz.wordextremist.network.GameWebSocketClient
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

// Implement the WebSocket listener interface
class MainActivity : AppCompatActivity(), GameWebSocketClient.GameWebSocketListenerCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameState: GameState // Keep game state logic

    // Timer constants and variables remain the same
    private val TURN_DURATION_MS = 30000L
    private val COUNTDOWN_INTERVAL_MS = 1000L
    private var countDownTimer: CountDownTimer? = null
    private var timeLeftInMillis: Long = TURN_DURATION_MS

    // Removed authViewModel
    private var gameWebSocketClient: GameWebSocketClient? = null
    private var currentGameId: String? = null
    private var ownUserId: Int = -1
    private var currentGameLanguage: String = "en"
    // Removed currentBackendToken
    // Removed DEBUG constants

    private enum class RoundOutcome { WIN, LOSS, DRAW }
    private enum class MistakeType { LOCAL, OPPONENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Removed authViewModel initialization

        // --- Get Game Info from Intent ---
        ownUserId = intent.getIntExtra(MatchmakingActivity.EXTRA_OWN_USER_ID, -1)
        currentGameId = intent.getStringExtra(MatchmakingActivity.EXTRA_GAME_ID)
        currentGameLanguage = intent.getStringExtra(MatchmakingActivity.EXTRA_GAME_LANGUAGE_FOR_MAIN) ?: "en"
        // Removed backend token retrieval

        if (currentGameId == null) {
            Log.e("MainActivity", "Game ID is missing! Cannot start game.")
            Toast.makeText(this, "Error: Game ID not found.", Toast.LENGTH_LONG).show()
            // Go back to Matchmaking or show an error state

            // Or navigate back to an appropriate starting activity
            startActivity(Intent(this, LauncherActivity::class.java))
            return // Stop further execution in onCreate
        }

        if (ownUserId == -1) {
            Log.e("MainActivity", "Own User ID is missing! Cannot start game.")
            Toast.makeText(this, "Error: Own User ID not found.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LauncherActivity::class.java))
            return
        }

        Log.i("MainActivity", "Received Game ID: $currentGameId")

        // --- Remove All Play Games SDK Initialization and Auth Code Request ---
        // The entire block from PlayGamesSdk.initialize down to the end of its callbacks is removed.
        // Also remove the standalone requestServerSideAccess() call.

        // --- Initialize WebSocket Client ---
        Log.d("MainActivity", "Initializing WebSocket client for game: $currentGameId")
        gameWebSocketClient = GameWebSocketClient(currentGameId!!, this, this) // Pass gameId and listener (this)
        gameWebSocketClient?.connect() // Attempt to connect

        // Initialize Game State (basic placeholder)
        // The actual initial state should ideally come from the server via WebSocket upon connection
        initializePlaceholderGame() // Initialize with placeholders
        updateUI() // Update UI with initial placeholders

        // Setup UI Listeners (mostly unchanged)
        setupEmojiButtonListeners()
        setupInputListeners()

        // Initialize other UI elements (can be updated later by WS messages)
        updatePlaceholdersUI() // Setup default text

        // Timer might be started by a "game_start" message from the server,
        // but let's start it for player 1 initially for testing.
        // If your backend sends whose turn it is upon connection, adjust this.
        if (gameState.currentPlayerTurn == PlayerTurn.PLAYER_1) {
            // startNewTurnTimer() // Let's wait for onOpen or a start message
        }
    }


    // Use this for initial setup before server state arrives
    private fun initializePlaceholderGame() {
        val p1 = PlayerState("", "You", 0, 0) // Local player
        val p2 = PlayerState("", "Opponent", 0, 0) // Opponent

        gameState = GameState(
            player1 = p1,
            player2 = p2,
            language = currentGameLanguage,
            PlayerTurn.PLAYER_1,
            1,
            3,
            currentSentence = "Waiting for server...",
            currentPrompt = "...",
            wordToReplace = "",
            mutableListOf(),
            mutableListOf(),
            mutableSetOf(),
            true
        )
        gameState.resetRoundMistakesAndWords()
        // Assume Player 1 starts initially, server message should confirm/correct this
        gameState.currentPlayerTurn = PlayerTurn.PLAYER_1
        gameState.isWaitingForOpponent = true // Initially wait for connection/start signal
    }

    private fun updatePlaceholdersUI() {
        binding.textViewPlayer1Name.text = gameState.player1.name
        binding.textViewPlayer2Name.text = gameState.player2.name
        binding.textViewPlayer1Mistakes.text = "Mistakes: 0/3"
        binding.textViewPlayer2Mistakes.text = "Mistakes: 0/3"
        binding.textViewRoundScore.text = "0 - 0"
        binding.textViewTimer.text = "Time: --s"
        binding.textViewPrompt.text = gameState.currentPrompt
        binding.textViewSentence.text = gameState.currentSentence
        binding.editTextWordInput.hint = "Connecting..."
        binding.editTextWordInput.isEnabled = false
        binding.buttonSubmit.isEnabled = false
        setEmojiButtonsEnabled(false)
    }


    private fun setupInputListeners() {
        binding.buttonSubmit.setOnClickListener {
            handleSubmitWordAction() // Changed to reflect it's initiating an action
        }

        binding.editTextWordInput.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleSubmitWordAction() // Call your submit logic
                return@OnEditorActionListener true
            }
            false
        })
    }

    // --- WebSocket Listener Implementation ---

    override fun onOpen() {
        runOnUiThread { // Ensure UI updates are on the main thread
            Log.i("MainActivity_WS", "WebSocket Connected!")
            Toast.makeText(this, "Connected to game!", Toast.LENGTH_SHORT).show()
            // Server should send initial game state now.
            // If we assume Player 1 starts and server confirms:
            // gameState.isWaitingForOpponent = false // If server implies game starts now
            // updateUI()
            // startNewTurnTimer()
            // OR wait for a specific "game_start" message in onMessageReceived
            binding.editTextWordInput.hint = "Waiting for game start..." // Update hint
        }
    }

    override fun onMessageReceived(message: JSONObject) {
        runOnUiThread { // Ensure UI updates are on the main thread
            Log.d("MainActivity_WS", "Message received: $message")
            try {
                when (message.optString("type")) {
                    "status" -> handleStatusMessage(message.getJSONObject("payload"))
                    "game_state" -> handleGameStateUpdate(message.getJSONObject("payload"))
                    "game_started" -> handleGameStart(message.getJSONObject("payload"))
                    "new_round_started" -> handleNewRoundStarted(message.getJSONObject("payload"))
                    "emoji_broadcast" -> handleEmojiBroadcastAction(message.getJSONObject("payload"))
                    "validation_result" -> handleValidationResultFromServer(message.getJSONObject("payload"))
                    "opponent_mistake" -> handleOpponentMistake(message.getJSONObject("payload"))
                    "opponent_turn_ended" -> handleOpponentTurnEnded(message.getJSONObject("payload"))
                    "opponent_timeout" -> handleOpponentTimeOut(message.getJSONObject("payload"))
                    "round_over" -> handleRoundOverFromServer(message.getJSONObject("payload"))
                    "game_over" -> handleGameOverFromServer(message.getJSONObject("payload"))
                    "error" -> handleErrorFromServer(message.optString("message", "Unknown server error"))
                    "info_message" -> handleInfoMessage(message.getJSONObject("payload"))
                    "player_disconnected" -> handlePlayerDisconnected(message.getJSONObject("payload"))
                    // Add other message types as needed
                    else -> Log.w("MainActivity_WS", "Unknown message type received: ${message.optString("type")}")
                }
            } catch (e: JSONException) {
                Log.e("MainActivity_WS", "Error parsing JSON message payload", e)
            } catch (e: Exception) {
                Log.e("MainActivity_WS", "Error processing message: ${message}", e)
            }
        }
    }

    override fun onClosing(code: Int, reason: String) {
        runOnUiThread {
            Log.i("MainActivity_WS", "WebSocket Closing: $code / $reason")
            // Maybe show a "Disconnecting..." state
        }
    }

    override fun onFailure(t: Throwable, response: Response?) {
        runOnUiThread {
            Log.e("MainActivity_WS", "WebSocket Failure: ${t.message}", t)
            Toast.makeText(this, "Connection Error: ${t.message}", Toast.LENGTH_LONG).show()
            // Disable UI, show error message, potentially offer reconnect or exit
            binding.textViewPrompt.text = "CONNECTION LOST"
            binding.editTextWordInput.isEnabled = false
            binding.buttonSubmit.isEnabled = false
            cancelTimer() // Stop timer if running
        }
    }

    override fun onClosed(code: Int, reason: String) {
        runOnUiThread {
            Log.i("MainActivity_WS", "WebSocket Closed: $code / $reason")
            Toast.makeText(this, "Disconnected from game.", Toast.LENGTH_LONG).show()
            // Clean up, maybe navigate back
            if (!isFinishing) {
                //finish() // Or navigate somewhere else
                binding.textViewPrompt.text = "DISCONNECTED"
                binding.editTextWordInput.isEnabled = false
                binding.buttonSubmit.isEnabled = false
            }
        }
    }

    // --- Message Handlers (Implement based on your backend protocol) ---
    private fun handleOpponentMistake(payload: JSONObject) {
        val opponentMistakes = payload.optInt("mistakes", 0)
        gameState.player2.mistakesInCurrentRound = opponentMistakes

    }

    private fun handleStatusMessage(payload: JSONObject) { // Expect payload
        val statusText = payload.optString("message", "Server status...")
        Log.i("MainActivity_WS", "Status from server: $statusText")
        // You might want a dedicated TextView for general status messages,
        // or update the prompt/input hint temporarily.
        // For now, a Toast is simple:
        Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
        if (statusText.contains("Waiting for opponent")) {
            binding.editTextWordInput.hint = statusText
            // Potentially update a general status TextView:
            // binding.textViewGeneralStatus.text = statusText
            // binding.textViewGeneralStatus.visibility = View.VISIBLE
        }
    }

    private fun handleInfoMessage(payload: JSONObject) {
        val messageText = payload.optString("message", "Server information")
        Log.i("MainActivity_WS", "Info from server: $messageText")
        Toast.makeText(this, messageText, Toast.LENGTH_LONG).show()
        // You could also display this in a non-modal way, e.g., a temporary TextView
        // or update a status bar if the info is relevant to game flow (like "You timed out!").
    }

    private fun handlePlayerDisconnected(payload: JSONObject) {
        val disconnectedPlayerId = payload.optString("player_id")
        val reason = payload.optString("reason", "disconnected") // Optional reason
        Log.w("MainActivity_WS", "Player $disconnectedPlayerId $reason.")

        var disconnectedPlayerName = "Opponent"
        if (gameState.player1.serverId == disconnectedPlayerId) {
            disconnectedPlayerName = gameState.player1.name
        } else if (gameState.player2.serverId == disconnectedPlayerId) {
            disconnectedPlayerName = gameState.player2.name
        }

        val toastMessage = if (reason == "server_error_for_player") {
            "$disconnectedPlayerName encountered an error and was disconnected."
        } else {
            "$disconnectedPlayerName disconnected."
        }
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()

        // Game Logic implications:
        // The server might send a "game_over" message if a disconnect results in a forfeit.
        // If not, the game might be stuck or waiting. For now, we'll disable controls
        // and wait for further instructions from the server (like game_over).

        binding.editTextWordInput.isEnabled = false
        binding.buttonSubmit.isEnabled = false
        binding.editTextWordInput.hint = "$disconnectedPlayerName left the game."
        binding.textViewTimer.text = "Game Interrupted"
        setEmojiButtonsEnabled(false)
        cancelTimer()

        // Consider if you want a "Back to Menu" button to become visible here
        // if the game is effectively over due to disconnect.
        // binding.buttonBackToMenu.visibility = View.VISIBLE
        // binding.buttonBackToMenu.setOnClickListener { ... navigate to LauncherActivity ... }
    }

    // Ensure your handleNewRoundStarted matches what the backend sends ("new_round_started")
    private fun handleNewRoundStarted(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling new_round_started: ${payload.toString(2)}")
        cancelTimer() // Stop timer from the previous round

        val prevRoundWinnerId = payload.optString("round_winner_id", null)
        // The payload for "new_round_started" is a full game state update
        currentGameLanguage = payload.optString("language", currentGameLanguage)
        gameState.updateFromJson(payload, ownUserId.toString()) // Reuses the full state update logic

        val roundWinnerPlayerState = when(prevRoundWinnerId) {
            gameState.player1.serverId -> gameState.player1
            gameState.player2.serverId -> gameState.player2
            else -> null
        }
        val winnerName = roundWinnerPlayerState?.name ?: "No one"
        // gameState.currentRound is now the NEW round number due to updateFromJson



        val messageToast = if (gameState.currentRound > 1) { // Only show for rounds after the first
            "$winnerName wins Round ${gameState.currentRound - 1}!"
        } else {
            "Round ${gameState.currentRound} starting!"
        }
        Toast.makeText(this, messageToast, Toast.LENGTH_LONG).show()

        updateUI()

        binding.editTextWordInput.hint = "Round ${gameState.currentRound}!"
        // UI hints based on turn are handled by updateUI and handleTurnStart implicitly

        handleTurnStart()
        setEmojiButtonsEnabled(false)
    }

    private fun handleGameStateUpdate(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling game state update: $payload")
        // This is crucial: Parse the full state from the server and update local gameState
        try {
            currentGameLanguage = payload.optString("language", currentGameLanguage)
            gameState.updateFromJson(payload, ownUserId.toString())

            updateUI() // Refresh the entire UI based on the new state
            handleTurnStart() // Start timer etc. if it's now our turn

        } catch (e: JSONException) {
            Log.e("MainActivity_WS", "Error parsing game_state payload", e)
        }
    }

    private fun handleGameStart(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling game_start: $payload")
        // Payload might contain initial state or confirm player roles
        // Often combined with the first game_state message
        currentGameLanguage = payload.optString("language", currentGameLanguage)
        handleGameStateUpdate(payload) // Process initial state
        // Explicitly mark as not waiting if it's our turn
        if (gameState.isWaitingForOpponent) {
            Log.w("MainActivity_WS", "After game_start and state update, still waiting for opponent. Check payload and GameState.updateFromJson logic. Is 'game_active:true' and player IDs present in payload? Current turn: " + gameState.currentPlayerTurn);
        } else {
            Log.i("MainActivity_WS", "Game started successfully! isWaitingForOpponent is false.");
        }
    }


    private fun handleEmojiBroadcastAction(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling opponent action: $payload")

        val emojiTypeStr = payload.optString("emoji")
        try {
            val emojiType = EmojiType.valueOf(emojiTypeStr)
            showOpponentEmojiReaction(emojiType)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity_WS", "Invalid emoji type received: $emojiTypeStr")
        }

    }

    private fun handleValidationResultFromServer(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling validation result: $payload")
        val word = payload.getString("word") // The word we submitted
        val isValid = payload.getBoolean("is_valid")

        if (isValid) {
            cancelTimer() // Stop timer on successful submission
            Toast.makeText(this, "'$word' is a good word!", Toast.LENGTH_SHORT).show()
            gameState.recordValidWord(word, PlayerTurn.PLAYER_1) // Record for local player (player 1)
            animateWordToBox(word, binding.textViewPlayer1PlayedWords, true)
            // Server will send a game_state update to switch turn
            gameState.switchTurn()
            updateUI() // Update UI to show waiting state and disable input
        } else {
            Toast.makeText(this, "'$word' is not valid. Mistake!", Toast.LENGTH_LONG).show()
            gameState.recordMistake() // Record mistake for local player (player 1)
            updateUI() // Show mistake count increase

            playMistakeAnimation(MistakeType.LOCAL)

            // Server state update will follow if round ends. If not, it's still our turn.
            if (!gameState.isRoundOver()) { // Check based on local state (server might send round_over msg too)
                startNewTurnTimer() // Restart timer for another chance
            }
        }
    }

    private fun handleOpponentTurnEnded(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling opponent turn ended: $payload")
        try {
            val opponentPlayerServerId = payload.getString("opponent_player_id")
            val currentPlayerServerId = payload.getString("current_player_id")
            val opponentPlayedWord = payload.optString("opponent_played_word", null)

            // boolean opponentWordIsValid = payload.getBoolean("opponent_word_is_valid"); // Already implied true by this message type
            gameState.recordValidWord(opponentPlayedWord, PlayerTurn.PLAYER_2) // Record for opponent (player 2)
            gameState.switchTurn()


            // Animate opponent's word to their box
            // We need to know which player is the opponent for animation.
            // Since updateFromJson just ran, gameState.player2 is the opponent
            animateWordToBox(
                opponentPlayedWord,
                binding.textViewPlayer2PlayedWords,
                false
            )
            setEmojiButtonsEnabled(true)

            updateUI() // Refresh UI with the new state (now our turn)
            handleTurnStart() // This will start our timer, enable input, and importantly,

        } catch (e: JSONException) {
            Log.e("MainActivity_WS", "Error parsing opponent_turn_ended payload", e)
        } catch (e: java.lang.Exception) {
            Log.e(
                "MainActivity_WS",
                "Error processing opponent_turn_ended message: $payload", e
            )
        }
    }

    private fun handleOpponentTimeOut(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling opponent timeout: $payload")
        try {
            gameState.player2.mistakesInCurrentRound++
            gameState.switchTurn()

            updateUI()
            handleTurnStart()

            playMistakeAnimation(MistakeType.OPPONENT)
        } catch (e: JSONException) {
            Log.e("MainActivity_WS", "Error parsing opponent_timeout payload", e)
        } catch (e: java.lang.Exception) {
            Log.e(
                "MainActivity_WS",
                "Error processing opponent_timeout message: $payload", e
            )
        }


    }

    private fun handleRoundOverFromServer(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling round_over: $payload")
        cancelTimer()
        val winnerIdFromServer = payload.optString("winner_id", null) // ID of the round winner, or null for draw/other
        val p1Score = payload.getInt("player1_score")
        val p2Score = payload.getInt("player2_score")

        gameState.player1.score = p1Score
        gameState.player2.score = p2Score

        val localPlayerServerId = gameState.player1.serverId
        val outcome: RoundOutcome

        if (winnerIdFromServer == null || winnerIdFromServer.isEmpty() || winnerIdFromServer.equals("null", ignoreCase = true)) {
            outcome = RoundOutcome.DRAW
        } else if (localPlayerServerId != null && localPlayerServerId == winnerIdFromServer) {
            outcome = RoundOutcome.WIN
        } else {
            // If not a draw and local player didn't win, it's a loss for the local player
            outcome = RoundOutcome.LOSS
        }

        playRoundOutcomeAnimation(outcome)

        // Determine the name of the winner for the toast message
        val roundWinnerPlayerState = when(winnerIdFromServer) {
            gameState.player1.serverId -> gameState.player1
            gameState.player2.serverId -> gameState.player2
            else -> null
        }

        val messageToastText = when(outcome) {
            RoundOutcome.WIN -> "YOU WIN Round ${gameState.currentRound}!"
            RoundOutcome.LOSS -> if (roundWinnerPlayerState != null) {
                "${roundWinnerPlayerState.name} wins Round ${gameState.currentRound}!"
            } else {
                "Round ${gameState.currentRound} lost." // Fallback
            }
            RoundOutcome.DRAW -> "Round ${gameState.currentRound} ended. It's a draw!"
        }
        Toast.makeText(this, messageToastText, Toast.LENGTH_LONG).show()

        // Server should send a new 'game_state' or 'game_start' for the next round shortly
        // Update UI to show scores, maybe disable input temporarily
        gameState.currentRound = payload.getInt("next_round") // Assume server sends next round #
        gameState.resetRoundMistakesAndWords() // Reset local tracking for the new round
        updateUI()
        binding.editTextWordInput.hint = "Waiting for next round..."
        binding.editTextWordInput.isEnabled = false
        binding.buttonSubmit.isEnabled = false
        setEmojiButtonsEnabled(false)
        // Wait for the next game_state/game_start message for the new sentence/prompt/turn
    }


    private fun handleGameOverFromServer(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling game_over: $payload")
        cancelTimer()
        val winnerId = payload.optString("winner_id", null)
        val p1Score = payload.getInt("player1_score")
        val p2Score = payload.getInt("player2_score")

        gameState.player1.score = p1Score
        gameState.player2.score = p2Score

        val gameWinner = when(winnerId) {
            gameState.player1.serverId -> gameState.player1
            gameState.player2.serverId -> gameState.player2
            else -> null // Indicates a draw
        }

        val message = if (gameWinner != null) {
            "${gameWinner.name} wins the game!"
        } else {
            "It's a Draw!"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        updateUI() // Show final scores
        // Disable input permanently for this game instance
        binding.buttonSubmit.isEnabled = false
        binding.editTextWordInput.isEnabled = false
        binding.editTextWordInput.hint = "Game Over"
        setEmojiButtonsEnabled(false)
        // TODO: Add a "Play Again" or "Back to Menu" button visibility toggle here
    }

    private fun handleErrorFromServer(errorMessage: String) {
        Log.e("MainActivity_WS", "Received error message from server: $errorMessage")
        Toast.makeText(this, "Server Error: $errorMessage", Toast.LENGTH_LONG).show()
        // Decide how to handle server-side errors (e.g., show message, disconnect)
    }

    // --- Action Sending ---

    // Renamed from handleSubmitWord to clarify it sends an action
    private fun handleSubmitWordAction() {
        // Basic checks before sending
        if (!gameWebSocketClient?.isConnected()!!) {
            Toast.makeText(this, "Not connected to server.", Toast.LENGTH_SHORT).show()
            return
        }
        if (gameState.currentPlayerTurn != PlayerTurn.PLAYER_1 || gameState.isWaitingForOpponent) {
            Toast.makeText(this, "Not your turn.", Toast.LENGTH_SHORT).show()
            return
        }

        val enteredWord = binding.editTextWordInput.text.toString().trim().lowercase()

        if (enteredWord.isEmpty()) {
            Toast.makeText(this, "Please enter a word.", Toast.LENGTH_SHORT).show()
            showKeyboard()
            return
        }

        // Optional: Client-side check for already played words (can prevent unnecessary sends)
        if (gameState.allWordsPlayedThisRoundSet.contains(enteredWord)) {
            Toast.makeText(this, "Word already played this round.", Toast.LENGTH_LONG).show()
            binding.editTextWordInput.text.clear()
            showKeyboard()
            return
        }


        // Send the word to the server for validation
        Log.d("MainActivity_WS", "Sending word submission: $enteredWord")
        val payload = mapOf("word" to enteredWord)
        gameWebSocketClient?.sendPlayerAction("submit_word", payload)

        // UI feedback: Clear input, maybe show "Sending..."
        binding.editTextWordInput.text.clear()
        binding.editTextWordInput.isEnabled = false // Disable until server responds
        binding.buttonSubmit.isEnabled = false
        binding.editTextWordInput.hint = "Validating..." // Give feedback

        // --- Removed local simulation and direct state changes ---
        // The server's "validation_result" message will now handle the outcome.
    }

    private fun sendEmojiReaction(emojiType: EmojiType) {
        if (!gameWebSocketClient?.isConnected()!!) {
            Toast.makeText(this, "Not connected.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("MainActivity_WS", "Sending emoji reaction: ${emojiType.name}")
        val payload = mapOf("emoji" to emojiType.name) // Send enum name as string
        gameWebSocketClient?.sendPlayerAction("send_emoji", payload)

        // Disable buttons immediately after sending to prevent spam
        setEmojiButtonsEnabled(false)
    }

    // --- Turn Handling ---
    private fun handleTurnStart() {
        if (gameState.currentPlayerTurn == PlayerTurn.PLAYER_1 && !gameState.isWaitingForOpponent && !gameState.isGameOver()) {
            updateUI() // Ensure UI reflects it's our turn
            startNewTurnTimer()
            showKeyboard()

        } else {
            // It's opponent's turn or game is over
            cancelTimer() // Make sure our timer isn't running
            updateUI() // Ensure UI reflects opponent's turn / game over
            // Emoji buttons might be enabled if opponent just played (handled elsewhere)
        }
    }


    // --- UI Update Logic (Mostly Unchanged, but driven by state) ---

    private fun updateUI() {
        // Ensure we're on the main thread for UI updates
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateUI() }
            return
        }

        // Update Played Words Display
        binding.textViewPlayer1WordsLabel.text = "${gameState.player1.name}'s Words:"
        binding.textViewPlayer2WordsLabel.text = "${gameState.player2.name}'s Words:"
        binding.textViewPlayer1PlayedWords.text = gameState.wordsPlayedThisRoundByPlayer1.joinToString("\n")
        binding.textViewPlayer2PlayedWords.text = gameState.wordsPlayedThisRoundByPlayer2.joinToString("\n")
        binding.textViewPlayer1PlayedWords.movementMethod = ScrollingMovementMethod() // Ensure scrollable
        binding.textViewPlayer2PlayedWords.movementMethod = ScrollingMovementMethod()

        // Player Info
        binding.textViewPlayer1Name.text = gameState.player1.name
        binding.textViewPlayer1Mistakes.text = "Mistakes: ${gameState.player1.mistakesInCurrentRound}/3"
        binding.textViewPlayer2Name.text = gameState.player2.name
        binding.textViewPlayer2Mistakes.text = "Mistakes: ${gameState.player2.mistakesInCurrentRound}/3"

        // Round Score
        binding.textViewRoundScore.text = "${gameState.player1.score} - ${gameState.player2.score}"

        // Prompt and Sentence
        binding.textViewPrompt.text = gameState.currentPrompt
        if (gameState.currentSentence.isNotEmpty() && gameState.wordToReplace.isNotEmpty()) {
            binding.textViewSentence.text = highlightWord(gameState.currentSentence, gameState.wordToReplace)
        } else {
            binding.textViewSentence.text = gameState.currentSentence // Show sentence even if no word to replace
        }


        // Indicate current player & Input State
        val isLocalPlayerTurn = gameState.currentPlayerTurn == PlayerTurn.PLAYER_1
        binding.textViewPlayer1Name.setTypeface(null, if (isLocalPlayerTurn) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.textViewPlayer2Name.setTypeface(null, if (!isLocalPlayerTurn) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        // Input enabled ONLY if it's Player 1's turn, not waiting, and game not over
        val canInput = isLocalPlayerTurn && !gameState.isWaitingForOpponent && !gameState.isGameOver()

        binding.editTextWordInput.isEnabled = canInput
        binding.buttonSubmit.isEnabled = canInput

        if (canInput) {
            binding.editTextWordInput.hint = getString(R.string.word_input_hint)
            // Don't clear text if it's already focused and user might be typing
            // if (!binding.editTextWordInput.isFocused) {
            //     binding.editTextWordInput.text.clear() // Consider if needed, server response handles clearing now
            // }
        } else if (gameState.isGameOver()) {
            binding.editTextWordInput.hint = "Game Over"
            binding.editTextWordInput.text.clear()
        } else if (gameState.isWaitingForOpponent) {
            binding.editTextWordInput.hint = "Waiting for opponent..."
            binding.editTextWordInput.text.clear()
        } else { // Not our turn, not waiting, not game over => Opponent's turn
            binding.editTextWordInput.hint = "Opponent's turn"
            binding.editTextWordInput.text.clear()
        }

        // Hide keyboard if it's not the local player's turn to type
        if (!canInput) {
            hideKeyboard()
        }

        // Timer display is handled by the timer itself mostly
    }

    // --- Timer Logic (Mostly Unchanged) ---
    private fun startNewTurnTimer() {
        countDownTimer?.cancel()
        timeLeftInMillis = TURN_DURATION_MS
        binding.textViewTimer.setTextColor(ContextCompat.getColor(this, R.color.timer_default_color)) // Use this@MainActivity context

        countDownTimer = object : CountDownTimer(timeLeftInMillis, COUNTDOWN_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                if (!this@MainActivity.isActive) { // Check activity state
                    cancel()
                    return
                }
                timeLeftInMillis = millisUntilFinished
                val secondsLeft = (millisUntilFinished + 999) / 1000 // Round up for display
                binding.textViewTimer.text = "Time: ${secondsLeft}s"

                if (secondsLeft <= 5) {
                    binding.textViewTimer.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.timer_warning_color))
                } else {
                    binding.textViewTimer.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.timer_default_color))
                }
            }

            override fun onFinish() {
                if (!this@MainActivity.isActive) return // Check activity state

                binding.textViewTimer.text = "Time's up!"
                // Time's up is effectively a mistake, the *server* should handle this.
                // Client *could* send a "timeout" message, or server detects lack of input.
                // For simplicity, let client assume it's a mistake locally for immediate feedback,
                // but rely on server state update for the official outcome.
                Toast.makeText(this@MainActivity, "Time's up! Sending timeout.", Toast.LENGTH_SHORT).show()

                playMistakeAnimation(MistakeType.LOCAL)

                // OPTIONAL: Send a timeout message to server
                gameWebSocketClient?.sendPlayerAction("timeout", null)

                // Locally reflect as mistake for immediate UI feedback, server state will correct if needed
                gameState.recordMistake()
                updateUI()
                binding.editTextWordInput.isEnabled = false // Disable input after timeout
                binding.buttonSubmit.isEnabled = false

                // Wait for server's response (game_state, round_over, or game_over)
                // Do NOT automatically restart the timer here.
            }
        }.start()
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        binding.textViewTimer.text = "Time: --s" // Reset timer text
        binding.textViewTimer.setTextColor(ContextCompat.getColor(this, R.color.timer_default_color))
    }

    // --- Keyboard Utils ---
    private fun showKeyboard() {
        binding.editTextWordInput.requestFocus()
        // Post ensures the view is ready
        binding.editTextWordInput.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            imm?.showSoftInput(binding.editTextWordInput, InputMethodManager.SHOW_IMPLICIT)
        }, 100) // Small delay can sometimes help ensure focus is granted first
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        // Find the currently focused view, so we don't need to specify one
        var view = currentFocus
        // If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = View(this)
        }
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // Lifecycle Management
    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop called.")
        cancelTimer() // Stop timer when activity is not visible
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called. Closing WebSocket.")
        cancelTimer()
        gameWebSocketClient?.close() // Close WebSocket connection cleanly
        gameWebSocketClient = null
    }

    // Helper to check Activity state (needed for async operations/timers)
    val isActive: Boolean
        get() = !isFinishing && !isDestroyed


    // Helper function to highlight a specific word in a sentence
    private fun highlightWord(sentence: String, word: String): Spanned {
        val spannableString = SpannableString(sentence)
        val startIndex = sentence.indexOf(word, ignoreCase = true)
        if (startIndex != -1) {
            val endIndex = startIndex + word.length
            // You might want to define this color in colors.xml
            val highlightColor = ContextCompat.getColor(this, R.color.teal_200)
            spannableString.setSpan(
                ForegroundColorSpan(highlightColor),
                startIndex,
                endIndex,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // For HTML compatibility if needed, or just return spannableString
        // return Html.fromHtml(spannableString.toString(), Html.FROM_HTML_MODE_LEGACY) // if you need HTML
        return spannableString
    }
    private fun setupEmojiButtonListeners() {
        binding.buttonThumbUp.setOnClickListener { sendEmojiReaction(EmojiType.THUMBS_UP) }
        binding.buttonWowFace.setOnClickListener { sendEmojiReaction(EmojiType.WOW_FACE) }
        binding.buttonLaughing.setOnClickListener { sendEmojiReaction(EmojiType.LAUGHING) }
        binding.buttonHeart.setOnClickListener { sendEmojiReaction(EmojiType.HEART) }
    }

    private fun setEmojiButtonsEnabled(isEnabled: Boolean) {
        binding.buttonThumbUp.isEnabled = isEnabled
        binding.buttonWowFace.isEnabled = isEnabled
        binding.buttonLaughing.isEnabled = isEnabled
        binding.buttonHeart.isEnabled = isEnabled
        // You might want to change their alpha too to indicate disabled state
        val alphaValue = if (isEnabled) 1.0f else 0.5f
        binding.buttonThumbUp.alpha = alphaValue
        binding.buttonWowFace.alpha = alphaValue
        binding.buttonLaughing.alpha = alphaValue
        binding.buttonHeart.alpha = alphaValue
    }

    private fun animateWordToBox(word: String, targetBox: View, isPlayer1Word: Boolean) {
        val animatedWordView = binding.textViewAnimatedWord
        animatedWordView.text = word
        animatedWordView.visibility = View.VISIBLE
        animatedWordView.alpha = 1f // Ensure it's fully visible
        animatedWordView.scaleX = 1f
        animatedWordView.scaleY = 1f

        // Calculate target position (center of the targetBox)
        // We need to get the coordinates relative to the root layout
        val targetLocation = IntArray(2)
        targetBox.getLocationInWindow(targetLocation) // Gets top-left corner in window

        val targetX = targetLocation[0].toFloat() + targetBox.width / 2f - animatedWordView.width / 2f
        val targetY = targetLocation[1].toFloat() + targetBox.height / 2f - animatedWordView.height / 2f - getStatusBarHeight()
        // Adjust for status bar height if getLocationInWindow is used.
        // If targetBox.getGlobalVisibleRect() or similar is used, adjustment might differ.

        // Initial position (center of screen, where textViewAnimatedWord is defined by constraints)
        // We can get its current position as the starting point for translation relative to its parent
        val startX = animatedWordView.x
        val startY = animatedWordView.y

        // Create animators
        val scaleDownX = ObjectAnimator.ofFloat(animatedWordView, "scaleX", 1f, 0.5f)
        val scaleDownY = ObjectAnimator.ofFloat(animatedWordView, "scaleY", 1f, 0.5f)
        val fadeOut = ObjectAnimator.ofFloat(animatedWordView, "alpha", 1f, 0.3f)

        // Create translation animators.
        // We want to translate it from its current constrained position to the target.
        // The animatedWordView's x, y are relative to its parent (the ConstraintLayout).
        // targetX, targetY calculated above are absolute window coordinates.
        // We need to make sure we are translating correctly within the parent's coordinate system.
        // For simplicity, let's assume the root ConstraintLayout is the window.
        // A more robust way is to get targetBox coordinates relative to the animatedWordView's parent.

        // Get coordinates of targetBox relative to the parent of animatedWordView
        val parentLocation = IntArray(2)
        (animatedWordView.parent as View).getLocationInWindow(parentLocation)

        val relativeTargetX = targetLocation[0].toFloat() - parentLocation[0].toFloat() + targetBox.width / 2f - animatedWordView.width / 2f
        val relativeTargetY = targetLocation[1].toFloat() - parentLocation[1].toFloat() + targetBox.height / 2f - animatedWordView.height / 2f


        val translateX = ObjectAnimator.ofFloat(animatedWordView, "translationX", 0f, relativeTargetX - startX)
        val translateY = ObjectAnimator.ofFloat(animatedWordView, "translationY", 0f, relativeTargetY - startY)


        val animatorSet = AnimatorSet()
        animatorSet.playTogether(scaleDownX, scaleDownY, fadeOut, translateX, translateY)
        animatorSet.duration = 800 // milliseconds
        animatorSet.interpolator = AccelerateInterpolator() // Make it speed up as it moves

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animatedWordView.visibility = View.GONE
                // Reset translation for next use, as it's relative
                animatedWordView.translationX = 0f
                animatedWordView.translationY = 0f

                // Now, actually update the target box's text
                if (isPlayer1Word) {
                    binding.textViewPlayer1PlayedWords.text = gameState.wordsPlayedThisRoundByPlayer1.joinToString("\n")
                } else {
                    binding.textViewPlayer2PlayedWords.text = gameState.wordsPlayedThisRoundByPlayer2.joinToString("\n")
                }
                // Ensure the lists are scrollable after update
                binding.textViewPlayer1PlayedWords.movementMethod = ScrollingMovementMethod()
                binding.textViewPlayer2PlayedWords.movementMethod = ScrollingMovementMethod()
            }
        })
        animatorSet.start()
    }

    private fun playRoundOutcomeAnimation(outcome: RoundOutcome) {
        val animationView = binding.textViewRoundOutcomeAnimation

        val text: String
        val textColorRes: Int

        when (outcome) {
            RoundOutcome.WIN -> {
                text = "YOU WIN!"
                textColorRes = R.color.win_color // Define in colors.xml (e.g., green)
            }
            RoundOutcome.LOSS -> {
                text = "ROUND LOST!"
                textColorRes = R.color.lose_color // Define in colors.xml (e.g., red)
            }
            RoundOutcome.DRAW -> {
                text = "ROUND DRAW!"
                textColorRes = R.color.draw_color // Define in colors.xml (e.g., yellow/gray)
            }
        }

        animationView.text = text
        animationView.setTextColor(ContextCompat.getColor(this, textColorRes))
        animationView.visibility = View.VISIBLE

        // Reset initial state for animation
        animationView.alpha = 0f
        animationView.scaleX = 0.3f
        animationView.scaleY = 0.3f
        animationView.translationY = 100f // Start a bit lower
        animationView.translationX = 0f  // Ensure X is reset

        // Animation: Emerge (Fade in, Scale up, Translate Y up)
        val emergeDuration = 500L
        val emergeAlpha = ObjectAnimator.ofFloat(animationView, View.ALPHA, 0f, 1f).setDuration(emergeDuration)
        val emergeScaleX = ObjectAnimator.ofFloat(animationView, View.SCALE_X, 0.3f, 1.0f).setDuration(emergeDuration)
        val emergeScaleY = ObjectAnimator.ofFloat(animationView, View.SCALE_Y, 0.3f, 1.0f).setDuration(emergeDuration)
        val emergeTranslateY = ObjectAnimator.ofFloat(animationView, View.TRANSLATION_Y, 100f, 0f).setDuration(emergeDuration)
        emergeAlpha.interpolator = DecelerateInterpolator()
        emergeScaleX.interpolator = DecelerateInterpolator()
        emergeScaleY.interpolator = DecelerateInterpolator()
        emergeTranslateY.interpolator = DecelerateInterpolator()

        val emergeSet = AnimatorSet().apply {
            playTogether(emergeAlpha, emergeScaleX, emergeScaleY, emergeTranslateY)
        }

        // Hold Duration (before departing)
        var holdDuration = 1800L

        // Animation: Depart (Fade out, Scale down slightly, Translate Y up further to exit)
        val departDuration = 500L
        val departAlpha = ObjectAnimator.ofFloat(animationView, View.ALPHA, 1f, 0f).setDuration(departDuration)
        val departScaleX = ObjectAnimator.ofFloat(animationView, View.SCALE_X, 1.0f, 0.7f).setDuration(departDuration)
        val departScaleY = ObjectAnimator.ofFloat(animationView, View.SCALE_Y, 1.0f, 0.7f).setDuration(departDuration)
        val departTranslateY = ObjectAnimator.ofFloat(animationView, View.TRANSLATION_Y, 0f, -100f).setDuration(departDuration)
        departAlpha.interpolator = AccelerateInterpolator()
        departScaleX.interpolator = AccelerateInterpolator()
        departScaleY.interpolator = AccelerateInterpolator()
        departTranslateY.interpolator = AccelerateInterpolator()

        val departSet = AnimatorSet().apply {
            playTogether(departAlpha, departScaleX, departScaleY, departTranslateY)
        }

        // Optional Shake for Loss
        val shakeAnimator: ObjectAnimator? = if (outcome == RoundOutcome.LOSS) {
            ObjectAnimator.ofFloat(animationView, View.TRANSLATION_X, 0f, -25f, 25f, -25f, 25f, -15f, 15f, -5f, 5f, 0f).apply {
                duration = 600L
            }
        } else null

        val mainSequence = AnimatorSet()
        mainSequence.play(emergeSet)

        if (shakeAnimator != null) {
            mainSequence.play(shakeAnimator).after(emergeSet)
            // Adjust hold duration if shake is present, so depart starts after shake and some hold
            holdDuration -= shakeAnimator.duration
            if (holdDuration < 200) holdDuration = 200 // Minimum hold after shake
            departSet.startDelay = holdDuration
            mainSequence.play(departSet).after(shakeAnimator)
        } else {
            departSet.startDelay = holdDuration // Full hold if no shake
            mainSequence.play(departSet).after(emergeSet)
        }

        mainSequence.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                animationView.visibility = View.GONE
                // Reset properties for next time
                animationView.alpha = 1f
                animationView.scaleX = 1f
                animationView.scaleY = 1f
                animationView.translationX = 0f
                animationView.translationY = 0f
            }
        })
        mainSequence.start()
    }

    private fun playMistakeAnimation(mistakeType: MistakeType) {
        val animationView = binding.textViewMistakeAnimation // Use the new TextView
        val text: String
        val textColorRes: Int

        when (mistakeType) {
            MistakeType.LOCAL -> {
                text = "MISTAKE!"
                textColorRes = R.color.mistake_local_color
            }
            MistakeType.OPPONENT -> {
                text = "OPPONENT\nMISTAKE!" // Multi-line for opponent
                textColorRes = R.color.mistake_opponent_color
            }
        }

        animationView.text = text
        animationView.setTextColor(ContextCompat.getColor(this, textColorRes))
        animationView.visibility = View.VISIBLE

        // Reset initial state
        animationView.alpha = 0f
        animationView.scaleX = 0.5f
        animationView.scaleY = 0.5f
        animationView.translationX = 0f
        animationView.translationY = 0f // Let's not translate Y for this one initially

        // Animation: Pop In (Fade in, Scale up)
        val popInDuration = 300L
        val popInAlpha = ObjectAnimator.ofFloat(animationView, View.ALPHA, 0f, 1f).setDuration(popInDuration)
        val popInScaleX = ObjectAnimator.ofFloat(animationView, View.SCALE_X, 0.5f, 1.1f).setDuration(popInDuration) // Scale slightly larger
        val popInScaleY = ObjectAnimator.ofFloat(animationView, View.SCALE_Y, 0.5f, 1.1f).setDuration(popInDuration)
        popInAlpha.interpolator = DecelerateInterpolator()
        popInScaleX.interpolator = DecelerateInterpolator()
        popInScaleY.interpolator = DecelerateInterpolator()

        val popInSet = AnimatorSet().apply {
            playTogether(popInAlpha, popInScaleX, popInScaleY)
        }

        // Animation: Shake
        val shakeDuration = 500L
        val shakeTranslationX = ObjectAnimator.ofFloat(animationView, View.TRANSLATION_X, 0f, 25f, -25f, 25f, -25f, 15f, -15f, 6f, -6f, 0f)
        shakeTranslationX.duration = shakeDuration
        shakeTranslationX.interpolator = CycleInterpolator(3f) // Number of cycles for shake

        // Animation: Scale back to normal then Fade Out
        val scaleBackDuration = 150L
        val scaleBackX = ObjectAnimator.ofFloat(animationView, View.SCALE_X, 1.1f, 1.0f).setDuration(scaleBackDuration)
        val scaleBackY = ObjectAnimator.ofFloat(animationView, View.SCALE_Y, 1.1f, 1.0f).setDuration(scaleBackDuration)

        val fadeOutDuration = 400L
        val fadeOutAlpha = ObjectAnimator.ofFloat(animationView, View.ALPHA, 1f, 0f).setDuration(fadeOutDuration)
        fadeOutAlpha.startDelay = 100 // Hold a bit before fading

        val popOutSet = AnimatorSet().apply {
            playTogether(fadeOutAlpha, scaleBackX, scaleBackY) // Scale back while fading
        }


        val mainSequence = AnimatorSet()
        mainSequence.play(popInSet)
        mainSequence.play(shakeTranslationX).after(popInSet)
        mainSequence.play(popOutSet).after(shakeTranslationX)


        mainSequence.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                animationView.visibility = View.GONE
                // Reset properties
                animationView.alpha = 1f
                animationView.scaleX = 1f
                animationView.scaleY = 1f
                animationView.translationX = 0f
                animationView.translationY = 0f
            }
        })
        mainSequence.start()
    }

    private fun showMistakeReaction() {
        Log.d("MainActivity", "Showing emoji reaction.")
    }

    private fun showOpponentEmojiReaction(emojiType: EmojiType) {
        val emojiTextView = binding.textViewAnimatedEmoji

        // Set the correct Unicode emoji text
        val emojiString = when (emojiType) {
            EmojiType.THUMBS_UP -> "👍"
            EmojiType.WOW_FACE -> "😮"
            EmojiType.LAUGHING -> "😂"
            EmojiType.HEART -> "❤️"
        }
        emojiTextView.text = emojiString

        emojiTextView.visibility = View.VISIBLE
        emojiTextView.alpha = 0f // Start transparent
        emojiTextView.scaleX = 0.5f // Start small
        emojiTextView.scaleY = 0.5f

        // Animate: Pop up, scale up, then fade out and scale down slightly
        val fadeIn = ObjectAnimator.ofFloat(emojiTextView, "alpha", 0f, 1f)
        fadeIn.duration = 300
        fadeIn.interpolator = DecelerateInterpolator()

        val scaleUpX = ObjectAnimator.ofFloat(emojiTextView, "scaleX", 0.5f, 1.2f)
        val scaleUpY = ObjectAnimator.ofFloat(emojiTextView, "scaleY", 0.5f, 1.2f)
        scaleUpX.duration = 400
        scaleUpY.duration = 400
        scaleUpX.interpolator = DecelerateInterpolator()
        scaleUpY.interpolator = DecelerateInterpolator()

        val hold = ObjectAnimator.ofFloat(emojiTextView, "alpha", 1f, 1f)
        hold.duration = 800

        val fadeOut = ObjectAnimator.ofFloat(emojiTextView, "alpha", 1f, 0f)
        fadeOut.duration = 500
        fadeOut.interpolator = AccelerateInterpolator()

        val scaleDownSlightlyX = ObjectAnimator.ofFloat(emojiTextView, "scaleX", 1.2f, 1.0f)
        val scaleDownSlightlyY = ObjectAnimator.ofFloat(emojiTextView, "scaleY", 1.2f, 1.0f)
        scaleDownSlightlyX.duration = 500
        scaleDownSlightlyY.duration = 500

        val animatorSet = AnimatorSet()
        val popUpSet = AnimatorSet()
        popUpSet.playTogether(fadeIn, scaleUpX, scaleUpY)

        val popOutSet = AnimatorSet()
        popOutSet.playTogether(fadeOut, scaleDownSlightlyX, scaleDownSlightlyY)

        animatorSet.playSequentially(popUpSet, hold, popOutSet)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                emojiTextView.visibility = View.GONE
            }
        })
        animatorSet.start()
    }

    // Helper to get status bar height if needed for coordinate calculations
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

}