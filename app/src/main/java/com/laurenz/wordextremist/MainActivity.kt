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
    private var currentUserId: Int = -1
    private var ownUserId: Int = -1
    // Removed currentBackendToken
    // Removed DEBUG constants

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Removed authViewModel initialization

        // --- Get Game Info from Intent ---
        ownUserId = intent.getIntExtra(MatchmakingActivity.EXTRA_OWN_USER_ID, -1)
        currentGameId = intent.getStringExtra(MatchmakingActivity.EXTRA_GAME_ID)
        currentUserId = intent.getIntExtra(MatchmakingActivity.EXTRA_USER_ID, -1)
        // Removed backend token retrieval

        if (currentGameId == null) {
            Log.e("MainActivity", "Game ID is missing! Cannot start game.")
            Toast.makeText(this, "Error: Game ID not found.", Toast.LENGTH_LONG).show()
            // Go back to Matchmaking or show an error state

            // Or navigate back to an appropriate starting activity
            startActivity(Intent(this, LauncherActivity::class.java))
            return // Stop further execution in onCreate
        }

        if (currentUserId == -1) {
            Log.e("MainActivity", "User ID is missing! Cannot start game.")
            Toast.makeText(this, "Error: User ID not found.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LauncherActivity::class.java))
            return
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
        gameWebSocketClient = GameWebSocketClient(currentGameId!!, ownUserId, this) // Pass gameId and listener (this)
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
                    "game_state" -> handleGameStateUpdate(message.getJSONObject("payload"))
                    "game_start" -> handleGameStart(message.getJSONObject("payload"))
                    "opponent_action" -> handleOpponentAction(message.getJSONObject("payload"))
                    "validation_result" -> handleValidationResultFromServer(message.getJSONObject("payload"))
                    "opponent_turn_ended" -> handleOpponentTurnEnded(message.getJSONObject("payload"))
                    "round_over" -> handleRoundOverFromServer(message.getJSONObject("payload"))
                    "game_over" -> handleGameOverFromServer(message.getJSONObject("payload"))
                    "error" -> handleErrorFromServer(message.optString("message", "Unknown server error"))
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

    private fun handleGameStateUpdate(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling game state update: $payload")
        // This is crucial: Parse the full state from the server and update local gameState
        try {
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
        handleGameStateUpdate(payload) // Process initial state
        // Explicitly mark as not waiting if it's our turn
        if (gameState.isWaitingForOpponent) {
            Log.w("MainActivity_WS", "After game_start and state update, still waiting for opponent. Check payload and GameState.updateFromJson logic. Is 'game_active:true' and player IDs present in payload? Current turn: " + gameState.currentPlayerTurn);
        } else {
            Log.i("MainActivity_WS", "Game started successfully! isWaitingForOpponent is false.");
        }
    }


    private fun handleOpponentAction(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling opponent action: $payload")
        val actionType = payload.optString("action")
        when (actionType) {
            "submit_word" -> {
                val word = payload.getString("word")
                val isValid = payload.getBoolean("is_valid") // Assuming server validates and tells us
                Log.i("MainActivity_WS", "Opponent played '$word', Valid: $isValid")
                if (isValid) {
                    gameState.recordValidWord(word) // Record for opponent (player 2)
                    animateWordToBox(word, binding.textViewPlayer2PlayedWords, false)
                    setEmojiButtonsEnabled(true) // Enable reactions after opponent plays
                    // The server should send a game_state update to switch turns formally
                } else {
                    gameState.recordMistake() // Record mistake for opponent (player 2)
                    Toast.makeText(this, "Opponent made a mistake!", Toast.LENGTH_SHORT).show()
                    updateUI() // Show mistake count update
                    // Server state update will follow if round ends
                }
                // Server should send a GameState update to reflect the new state (scores, words, turn)
                // updateUI() // Update UI based on intermediate action
            }
            "send_emoji" -> {
                val emojiTypeStr = payload.optString("emoji")
                try {
                    val emojiType = EmojiType.valueOf(emojiTypeStr)
                    showOpponentEmojiReaction(emojiType)
                } catch (e: IllegalArgumentException) {
                    Log.w("MainActivity_WS", "Invalid emoji type received: $emojiTypeStr")
                }
            }
            // Add other opponent actions
        }
        // Often, after an opponent action, the server sends a full game_state update.
        // If so, you might not need to manually update parts of the state here.
        // However, animations might be triggered directly.
    }

    private fun handleValidationResultFromServer(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling validation result: $payload")
        val word = payload.getString("word") // The word we submitted
        val isValid = payload.getBoolean("is_valid")
        val message = payload.optString("message", "") // Optional feedback message

        if (isValid) {
            cancelTimer() // Stop timer on successful submission
            Toast.makeText(this, "'$word' is a good word!", Toast.LENGTH_SHORT).show()
            gameState.recordValidWord(word) // Record for local player (player 1)
            animateWordToBox(word, binding.textViewPlayer1PlayedWords, true)
            // Server will send a game_state update to switch turn
            gameState.isWaitingForOpponent = true // Assume we now wait for opponent
            updateUI() // Update UI to show waiting state and disable input
        } else {
            Toast.makeText(this, message.ifEmpty { "'$word' is not valid. Mistake!" }, Toast.LENGTH_LONG).show()
            gameState.recordMistake() // Record mistake for local player (player 1)
            updateUI() // Show mistake count increase

            // Server state update will follow if round ends. If not, it's still our turn.
            if (!gameState.isRoundOver()) { // Check based on local state (server might send round_over msg too)
                startNewTurnTimer() // Restart timer for another chance
            }
        }
    }

    private fun handleOpponentTurnEnded(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling opponent turn ended: $payload")
        try {
            val opponentWord = payload.getString("opponent_played_word")

            // boolean opponentWordIsValid = payload.getBoolean("opponent_word_is_valid"); // Already implied true by this message type

            // The payload for "opponent_turn_ended" IS the new full game state
            // from the perspective of the player whose turn it now is.
            gameState.updateFromJson(
                payload,
                ownUserId.toString()
            ) // CRITICAL: Update with the full state

            // Animate opponent's word to their box
            // We need to know which player is the opponent for animation.
            // Since updateFromJson just ran, gameState.player2 is the opponent.
            if (gameState.player2.serverId.length > 0) { // Check if opponent exists
                animateWordToBox(
                    opponentWord,
                    binding.textViewPlayer2PlayedWords,
                    false
                ) // false = not Player 1's word

            } else {
                Log.w(
                    "MainActivity_WS",
                    "Cannot animate opponent word, gameState.player2 is null or has no serverId."
                )
            }


            // Now that the opponent has played THEIR turn and it's successfully ended,
            // enable emojis for THIS player to react.
            setEmojiButtonsEnabled(true)

            updateUI() // Refresh UI with the new state (now our turn)
            handleTurnStart() // This will start our timer, enable input, and importantly,

            // *disable* emojis again because it's our turn to type.
            // The window to use emojis is brief, right after this handler.
        } catch (e: JSONException) {
            Log.e("MainActivity_WS", "Error parsing opponent_turn_ended payload", e)
        } catch (e: java.lang.Exception) {
            Log.e(
                "MainActivity_WS",
                "Error processing opponent_turn_ended message: $payload", e
            )
        }
    }

    private fun handleRoundOverFromServer(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling round_over: $payload")
        cancelTimer()
        val winnerId = payload.optString("winner_id", null) // ID of the round winner, or null for draw/other
        val p1Score = payload.getInt("player1_score")
        val p2Score = payload.getInt("player2_score")

        gameState.player1.score = p1Score
        gameState.player2.score = p2Score

        val roundWinner = when(winnerId) {
            gameState.player1.serverId -> gameState.player1
            gameState.player2.serverId -> gameState.player2
            else -> null
        }

        val message = roundWinner?.let { "${it.name} wins Round ${gameState.currentRound}!" } ?: "Round ${gameState.currentRound} ended."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

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