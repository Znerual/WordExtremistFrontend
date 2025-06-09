package com.laurenz.wordextremist

// Keep necessary imports, remove unused ones (PlayGames, AuthViewModel etc.)
// Removed ViewModelProvider
// Removed PlayGames imports
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
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
import androidx.activity.OnBackPressedCallback // For newer back press handling
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.postDelayed
import com.bumptech.glide.Glide
import com.laurenz.wordextremist.databinding.ActivityMainBinding
import com.laurenz.wordextremist.model.GameState
import com.laurenz.wordextremist.model.PlayerTurn
import com.laurenz.wordextremist.network.GameWebSocketClient
import com.laurenz.wordextremist.model.RoundEndReason
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

// Implement the WebSocket listener interface
class MainActivity : AppCompatActivity(), GameWebSocketClient.GameWebSocketListenerCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameState: GameState // Keep game state logic
    private var gameHasActuallyStarted = false // New flag to control game logic start
    private var initialGamePayload: JSONObject? = null
    private var isEntryAnimationPlaying = true

    // Timer constants and variables remain the same
    private val COUNTDOWN_INTERVAL_MS = 1000L
    private var countDownTimer: CountDownTimer? = null
    private var timeLeftInMillis: Long = 0L
    private var turnEndTimeMillis: Long = 0L // Timestamp when the current turn should end
    private var isTimerPausedByActivity: Boolean = false

    private lateinit var userProfileLauncher: ActivityResultLauncher<Intent>

    // Removed authViewModel
    private var gameWebSocketClient: GameWebSocketClient? = null
    private var currentGameId: String? = null
    private var ownUserId: Int = -1
    private var ownUsername: String? = null
    private var ownLevelForDisplay: Int = 0
    private var opponentUsername: String? = null
    private var opponentLevelForDisplay: Int = 0
    private var currentGameLanguage: String = "en"
    private var ownProfilePicUrl: String? = null
    private var opponentProfilePicUrl: String? = null
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
        ownUsername = intent.getStringExtra(MatchmakingActivity.EXTRA_OWN_USER_NAME)
        ownLevelForDisplay = intent.getIntExtra(MatchmakingActivity.EXTRA_OWN_LEVEL, 0)
        opponentUsername = intent.getStringExtra(MatchmakingActivity.EXTRA_OPPONENT_USER_NAME)
        opponentLevelForDisplay = intent.getIntExtra(MatchmakingActivity.EXTRA_OPPONENT_LEVEL, 0)
        currentGameId = intent.getStringExtra(MatchmakingActivity.EXTRA_GAME_ID)
        currentGameLanguage = intent.getStringExtra(MatchmakingActivity.EXTRA_GAME_LANGUAGE_FOR_MAIN) ?: "en"
        ownProfilePicUrl = intent.getStringExtra(MatchmakingActivity.EXTRA_OWN_PROFILE_PIC_URL)
        opponentProfilePicUrl = intent.getStringExtra(MatchmakingActivity.EXTRA_OPPONENT_PROFILE_PIC_URL)

        // Removed backend token retrieval

        if (currentGameId == null) {
            Log.e("MainActivity", "Game ID is missing! Cannot start game.")
            Toast.makeText(this, "Error: Game ID not found.", Toast.LENGTH_LONG).show()
            // Go back to Matchmaking or show an error state

            // Or navigate back to an appropriate starting activity
            navigateToLauncherClearTask()
            return // Stop further execution in onCreate
        }

        if (ownUserId == -1) {
            Log.e("MainActivity", "Own User ID is missing! Cannot start game.")
            Toast.makeText(this, "Error: Own User ID not found.", Toast.LENGTH_LONG).show()
            navigateToLauncherClearTask()
            return
        }

        Log.i("MainActivity", "Received Game ID: $currentGameId")

        userProfileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // This lambda is called when UserProfileDetailActivity finishes and returns a result
            Log.d("MainActivity", "ActivityResult: Result code: ${result.resultCode}")
            if (result.resultCode == Activity.RESULT_OK) {
                // This means UserProfileDetailActivity finished due to its timer running out (as we set RESULT_OK there)
                Log.d("MainActivityTimer", "Returned from UserProfileDetailActivity with RESULT_OK (timer likely finished).")
            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                // This means the user pressed back or used the toolbar's up navigation
                Log.d("MainActivityTimer", "Returned from UserProfileDetailActivity with RESULT_CANCELED (user likely pressed back).")
            }
            // Regardless of the result code, onResume will be called next, and it handles
            // the timer state. So, the primary purpose here is logging or specific actions
            // based on *why* the other activity closed, if needed.
            // For your current timer logic, the crucial part happens in onResume.
        }

        // --- Remove All Play Games SDK Initialization and Auth Code Request ---
        // The entire block from PlayGamesSdk.initialize down to the end of its callbacks is removed.
        // Also remove the standalone requestServerSideAccess() call.
        // Initialize Game State (basic placeholder)
        // The actual initial state should ideally come from the server via WebSocket upon connection
        initializePlaceholderGame() // Initialize with placeholders
        updatePlaceholdersUI() // Setup default text
        setupBackButtonHandler()
        setupPlayerInfoClickListeners()




        gameWebSocketClient = GameWebSocketClient(currentGameId!!, this, this)
        gameWebSocketClient?.connect() // Connect immediately

        playEntryVSAnimation {
            Log.d("MainActivity", "VS Animation ended.")
            isEntryAnimationPlaying = false // Mark animation as finished
            gameHasActuallyStarted = true  // Allow game logic to proceed

            // Process any buffered initial payload
            initialGamePayload?.let {
                Log.d("MainActivity", "Processing buffered initial game payload after animation.")
                processBufferedInitialPayload(it)
                initialGamePayload = null // Clear buffer
            }

            setupGameUIAndListeners()

            sendClientReadyAction()

        }
    }

    private fun setupPlayerInfoClickListeners() {
        binding.player1InfoContainer.setOnClickListener {
            launchUserProfileDetail(gameState.player1.serverId.toInt())
        }

        binding.player2InfoContainer.setOnClickListener {
            launchUserProfileDetail(gameState.player2.serverId.toInt())
        }
    }

    private fun launchUserProfileDetail(userIdToView: Int) {
        if (userIdToView == -1) {
            Toast.makeText(this, "Player info not available yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, UserProfileDetailActivity::class.java).apply {
            putExtra(UserProfileDetailActivity.EXTRA_USER_ID, userIdToView)
            putExtra(UserProfileDetailActivity.EXTRA_TURN_END_TIME_MILLIS, turnEndTimeMillis)

        }
        // Use the launcher to start the activity
        userProfileLauncher.launch(intent)
        Log.d("MainActivity", "Launched UserProfileDetailActivity using new API.")
    }

    private fun setupGameUIAndListeners() {
        // This function is called AFTER the entry animation.
        Log.d("MainActivity", "Setting up game UI listeners and fading in UI.")
        setupEmojiButtonListeners()
        setupInputListeners()
        setupSentenceCardToggleListener()
        fadeInGameUI()


        if (gameWebSocketClient?.isConnected() == true && initialGamePayload == null) {
            // This state means we're connected, animation done, but no definitive game start message yet.
            // updateUI() will show "Waiting for game start..." based on current gameState
            updateUI() // Ensure UI reflects the current state (likely "Waiting for server...")

        }
    }


    private fun processBufferedInitialPayload(bufferedMessage: JSONObject) {
        val type = bufferedMessage.optString("type")
        val actualPayload = bufferedMessage.optJSONObject("payload")
        if (actualPayload == null) {
            Log.e("MainActivity", "Buffered message of type '$type' is missing the 'payload' object.")
            // Potentially show an error or try to proceed if some default state makes sense
            updateUI() // At least update UI to show waiting or error
            return
        }

        Log.i("MainActivity", "Processing buffered initial payload of type: $type")
        try {
            when (type) {
                "game_started" -> {
                    currentGameLanguage = actualPayload.optString("language", currentGameLanguage)
                    handleGameStateUpdate(actualPayload) // GameStart uses this for full state
                    if (gameState.isWaitingForOpponent && (gameState.currentPlayerTurn == PlayerTurn.PLAYER_1 || gameState.player1.serverId.isNotEmpty())) {
                        Log.w("MainActivity", "After buffered game_start, still waiting. SID: ${gameState.player1.serverId}")
                    } else {
                        Log.i("MainActivity", "Buffered Game started successfully!")
                    }
                }
                "game_state_reconnect" -> {
                    handleGameStateUpdate(actualPayload) // Reconnect also uses this
                    Log.i("MainActivity", "Buffered Reconnect state processed.")
                }
                "status" -> { // Could be an early "waiting for opponent"
                    handleStatusMessage(actualPayload)
                }
                // Add other critical initial messages if necessary
                else -> {
                    Log.w("MainActivity", "Buffered payload was of unhandled initial type: $type")
                    // Potentially just updateUI here if it's a general state.
                    updateUI()
                }
            }
        } catch (e: JSONException) {
            Log.e("MainActivity", "Error parsing buffered initial JSON payload", e)
        }
    }

    private fun playEntryVSAnimation(onAnimationEndAction: () -> Unit) {
        val vsOverlay = binding.vsAnimationOverlay
        val vsP1Card = binding.vsPlayer1Card
        val vsP2Card = binding.vsPlayer2Card
        val vsText = binding.textViewVS
        val vsHighlight1 = binding.vsHighlight1
        val vsHighlight2 = binding.vsHighlight2

        // Set player names for VS screen (using placeholder data for now)
        // In a real scenario, you might pass opponent's name from MatchmakingActivity
        // or wait for the first game_state message for actual names if VS anim needs it.
        // For now, using placeholders from initialized gameState.
        binding.vsPlayer1Name.text = ownUsername // gameState.player1.name // Should be "You"
        binding.vsPlayer1Level.text = "Lv. $ownLevelForDisplay" // Set level text
        binding.vsPlayer2Name.text = opponentUsername // gameState.player2.name // Should be "Opponent"
        binding.vsPlayer2Level.text = "Lv. $opponentLevelForDisplay" // Set level

        Glide.with(this)
            .load(ownProfilePicUrl)
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.vsPlayer1Avatar)

        Glide.with(this)
            .load(opponentProfilePicUrl)
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.vsPlayer2Avatar)

        // Make level TextViews visible if they were initially gone
        binding.vsPlayer1Level.visibility = View.VISIBLE
        binding.vsPlayer2Level.visibility = View.VISIBLE


        vsOverlay.visibility = View.VISIBLE
        vsOverlay.alpha = 0f

        // Initial states for animation
        vsP1Card.translationX = -vsP1Card.width.toFloat() - 200 // Start off-screen left
        vsP2Card.translationX = vsP2Card.width.toFloat() + 200   // Start off-screen right
        vsText.alpha = 0f
        vsText.scaleX = 0.5f
        vsText.scaleY = 0.5f
        vsHighlight1.alpha = 0f
        vsHighlight2.alpha = 0f

        val overallDuration = 2000L // Total animation duration before game starts
        val cardSlideInDuration = 600L
        val vsTextAppearDuration = 400L
        val highlightDuration = 800L
        val holdDuration = overallDuration - (cardSlideInDuration + vsTextAppearDuration / 2 + highlightDuration / 2) // Adjust for overlap

        // 1. Fade in VS Overlay
        val overlayFadeIn = ObjectAnimator.ofFloat(vsOverlay, View.ALPHA, 0f, 1f).apply {
            duration = 300
        }

        // 2. Player Cards Slide In
        val p1SlideIn = ObjectAnimator.ofFloat(vsP1Card, View.TRANSLATION_X, vsP1Card.translationX, 0f).apply {
            duration = cardSlideInDuration
            interpolator = DecelerateInterpolator(1.5f)
        }
        val p2SlideIn = ObjectAnimator.ofFloat(vsP2Card, View.TRANSLATION_X, vsP2Card.translationX, 0f).apply {
            duration = cardSlideInDuration
            interpolator = DecelerateInterpolator(1.5f)
        }
        val cardsSlideInSet = AnimatorSet().apply {
            playTogether(p1SlideIn, p2SlideIn)
            startDelay = 100 // Slight delay after overlay appears
        }

        // 3. "VS" Text Appears
        val vsTextAlpha = ObjectAnimator.ofFloat(vsText, View.ALPHA, 0f, 1f).apply { duration = vsTextAppearDuration }
        val vsTextScaleX = ObjectAnimator.ofFloat(vsText, View.SCALE_X, 0.5f, 1f).apply { duration = vsTextAppearDuration }
        val vsTextScaleY = ObjectAnimator.ofFloat(vsText, View.SCALE_Y, 0.5f, 1f).apply { duration = vsTextAppearDuration }
        val vsTextAppearSet = AnimatorSet().apply {
            playTogether(vsTextAlpha, vsTextScaleX, vsTextScaleY)
            interpolator = OvershootInterpolator()
            startDelay = cardSlideInDuration - 100 // Start as cards are finishing their slide
        }

        // 4. Moving Highlights (Optional but cool)
        vsHighlight1.translationX = -vsHighlight1.width.toFloat() // Start left of P1 card
        vsHighlight2.translationX = vsHighlight2.width.toFloat()  // Start right of P2 card

        val highlight1Alpha = ObjectAnimator.ofFloat(vsHighlight1, View.ALPHA, 0f, 0.7f, 0f).apply {
            duration = highlightDuration
            interpolator = AccelerateDecelerateInterpolator()
        }
        val highlight1Translate = ObjectAnimator.ofFloat(vsHighlight1, View.TRANSLATION_X, -vsHighlight1.width.toFloat(), binding.vsPlayer1Card.width.toFloat()).apply {
            duration = highlightDuration
            interpolator = AccelerateDecelerateInterpolator()
        }
        val highlight1Set = AnimatorSet().apply { playTogether(highlight1Alpha, highlight1Translate) }

        val highlight2Alpha = ObjectAnimator.ofFloat(vsHighlight2, View.ALPHA, 0f, 0.7f, 0f).apply {
            duration = highlightDuration
            interpolator = AccelerateDecelerateInterpolator()
        }
        val highlight2Translate = ObjectAnimator.ofFloat(vsHighlight2, View.TRANSLATION_X, binding.vsPlayer2Card.width.toFloat(), -vsHighlight2.width.toFloat()).apply {
            duration = highlightDuration
            interpolator = AccelerateDecelerateInterpolator()
        }
        val highlight2Set = AnimatorSet().apply { playTogether(highlight2Alpha, highlight2Translate) }

        val highlightsSet = AnimatorSet().apply {
            playTogether(highlight1Set, highlight2Set)
            startDelay = cardSlideInDuration + vsTextAppearDuration / 2 - 100 // Start after VS text is mostly visible
        }

        // 5. Animate Player Status Bar into Position (from VS card positions)
        //    This requires getting the final Y positions of the statusBar elements.
        //    For simplicity now, we'll just fade out the VS screen and fade in the game UI.
        //    A more complex animation would morph/move elements.

        // --- Sequence ---
        val entryAnimation = AnimatorSet()
        entryAnimation.play(overlayFadeIn)
        entryAnimation.play(cardsSlideInSet).after(overlayFadeIn)
        entryAnimation.play(vsTextAppearSet).with(cardsSlideInSet) // Play VS text concurrently with card slide end
        entryAnimation.play(highlightsSet).after(vsTextAppearSet)

        entryAnimation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Hold the VS screen for a bit
                Handler(Looper.getMainLooper()).postDelayed(holdDuration) {
                    // Fade out VS Overlay
                    ObjectAnimator.ofFloat(vsOverlay, View.ALPHA, 1f, 0f).apply {
                        duration = 400
                        interpolator = AccelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                vsOverlay.visibility = View.GONE
                                onAnimationEndAction() // Call the callback to start game logic
                            }
                        })
                        start()
                    }
                }
            }
        })
        entryAnimation.start()
    }

    private fun fadeInGameUI() {
        val gameUiElements = listOf(
            binding.statusBar,
            binding.promptCard,
            binding.sentenceCard,
            // binding.wordsPlayedCard, // This is initially GONE, handled by toggle
            binding.emojiReactionCard,
            binding.inputCard
        )
        val animators = gameUiElements.map { view ->
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
                duration = 500
                interpolator = DecelerateInterpolator()
            }
        }
        AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }

    private fun setupBackButtonHandler() {
        // For Android 13 and above, OnBackPressedDispatcher is preferred.
        // For simplicity and broader compatibility for now, we'll use the deprecated onBackPressed()
        // but you can refactor to OnBackPressedDispatcher if targeting API 33+ primarily.

        // This callback will only be enabled when the game is active (not over)
        val onBackPressedCallback = object : OnBackPressedCallback(true /* enabled by default */) {
            override fun handleOnBackPressed() {
                // Show confirmation dialog
                showLeaveGameConfirmationDialog()
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun showLeaveGameConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Leave Game?")
            .setMessage("Are you sure you want to leave the current game? This will count as a forfeit.")
            .setPositiveButton("Leave") { dialog, which ->
                // User confirmed, proceed to leave
                handleLeaveGame()
            }
            .setNegativeButton("Stay", null) // Null listener simply dismisses the dialog
            .setIcon(android.R.drawable.ic_dialog_alert) // Optional: add an icon
            .show()
    }

    private fun handleLeaveGame() {
        Log.i("MainActivity", "User chose to leave the game.")
        // 1. Inform the server (optional but good practice if you want to log forfeit specifically from client)
        //    The server's handle_player_disconnect will manage game state.
        //    If the WebSocket is closed, the server's disconnect logic will trigger.
        //    No explicit "leave_game" message is strictly necessary if disconnect is handled robustly.

        // 2. Close WebSocket
        gameWebSocketClient?.close() // This will trigger onClosed/onFailure which might show messages

        // 3. Navigate to LauncherActivity
        navigateToLauncherClearTask()
    }

    private fun navigateToLauncherClearTask() {
        val intent = Intent(this, LauncherActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish() // Finish MainActivity
    }


    private fun setupSentenceCardToggleListener() {
        binding.sentenceCard.setOnClickListener {
            if (binding.wordsPlayedCard.visibility == View.VISIBLE) {
                binding.wordsPlayedCard.visibility = View.GONE
                binding.imageViewToggleIndicator.setImageResource(R.drawable.ic_chevron_down)
            } else {
                binding.wordsPlayedCard.visibility = View.VISIBLE
                binding.imageViewToggleIndicator.setImageResource(R.drawable.ic_chevron_up)
            }
        }
    }

    // Use this for initial setup before server state arrives
    private fun initializePlaceholderGame() {
        val p1 = PlayerState("", ownUsername ?: "You", 0, 0) // Local player
        val p2 = PlayerState("", opponentUsername ?: "Opponent", 0, 0) // Opponent

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
            true,
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
        binding.tricklingSandView.resetAndHide()

        Glide.with(this)
            .load(ownProfilePicUrl)
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.imageViewPlayer1ProfilePic)

        Glide.with(this)
            .load(opponentProfilePicUrl)
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.imageViewPlayer2ProfilePic)

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
            if (!isEntryAnimationPlaying) { // If animation already finished, can update UI
                Toast.makeText(this, "Connected to game!", Toast.LENGTH_SHORT).show()
                binding.editTextWordInput.hint = "Waiting for game start..."
            } else {
                Log.d("MainActivity_WS", "WebSocket opened during entry animation.")
            }
        }
    }

    override fun onMessageReceived(message: JSONObject) {
        if (isEntryAnimationPlaying) {
            // Buffer critical initial messages like game_started or game_state_reconnect
            val type = message.optString("type")
            if (type == "game_started" || type == "game_state_reconnect" || type == "status") {
                Log.d("MainActivity_WS", "Buffering message of type '$type' received during VS animation.")
                initialGamePayload = message // Store the latest critical message
                return // Don't process further until animation ends
            }
            // For other messages during animation, decide if they should be ignored or queued.
            // For now, only buffering critical start messages.
            Log.d("MainActivity_WS", "Ignoring non-critical message type '$type' during VS animation.")
            return
        }

        runOnUiThread { // Ensure UI updates are on the main thread
            Log.d("MainActivity_WS", "Message received: $message")
            try {
                when (message.optString("type")) {
                    "game_setup_ready" -> handleGameSetupReady(message.getJSONObject("payload"))
                    "game_state_reconnect" -> handleReconnect(message.getJSONObject("payload"))
                    "round_started" -> handleRoundStarted(message.getJSONObject("payload"))
                    "new_round_started" -> handleNewRoundStarted(message.getJSONObject("payload"))
                    "emoji_broadcast" -> handleEmojiBroadcastAction(message.getJSONObject("payload"))
                    "validation_result" -> handleValidationResultFromServer(message.getJSONObject("payload"))
                    "opponent_mistake" -> handleOpponentMistake(message.getJSONObject("payload"))
                    "opponent_turn_ended" -> handleOpponentTurnEnded(message.getJSONObject("payload"))
                    "timeout" -> handleTimeOut(message.getJSONObject("payload"))
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

    private fun handleGameSetupReady(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling game_setup_ready. UI will wait for client_ready to be sent.")
        gameState.updateFromJson(payload, ownUserId.toString())
        fadeInGameUI()
        updateUI() // Update UI with initial data, input will be disabled.
    }

    private fun handleReconnect(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling reconnect.")
        gameState.updateFromJson(payload, ownUserId.toString())
        isEntryAnimationPlaying = false // Skip animation on reconnect
        fadeInGameUI()
        updateUI()
        // If the reconnected state shows it's our turn, start the timer
        if (!gameState.isWaitingForOpponent) {
            handleTurnStart()
        }
    }

    private fun handleRoundStarted(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling round_started. The timer is now live!")
        // Server confirms round start, gives authoritative timestamp
        gameState.lastActionTimestamp = (payload.optDouble("last_action_timestamp", 0.0) * 1000).toLong()
        gameState.isWaitingForOpponent = false // Game is officially active
        updateUI()
        handleTurnStart()
    }

    private fun handleNewRoundStarted(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling new_round_started.")
        cancelTimer()
        binding.tricklingSandView.resetAndHide()

        val prevRoundWinnerId = payload.optString("round_winner_id", null)
        val prevRoundEndReason = RoundEndReason.fromValue(payload.optString("previous_round_end_reason"))
        val winnerName = if (prevRoundWinnerId == gameState.player1.serverId) "You" else gameState.player2.name

        gameState.updateFromJson(payload, ownUserId.toString())

        val outcome = when {
            prevRoundWinnerId == gameState.player1.serverId -> RoundOutcome.WIN
            prevRoundWinnerId == gameState.player2.serverId -> RoundOutcome.LOSS
            else -> RoundOutcome.DRAW
        }

        playRoundOutcomeAnimation(outcome) {
            // This block executes after the round outcome animation is finished
            Log.d("MainActivity_WS", "Round outcome animation finished. Sending client_ready for new round.")
            sendClientReadyAction()
        }

        updateUI()
        binding.editTextWordInput.hint = "Round ${gameState.currentRound}! Get ready..."
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
            showReturnToMenuButton()
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
                setEmojiButtonsEnabled(false)
                showReturnToMenuButton()
            }
        }
    }

    // --- Message Handlers (Implement based on your backend protocol) ---
    private fun handleOpponentMistake(payload: JSONObject) {
        val opponentMistakes = payload.optInt("mistakes", 0)
        val reasonString = payload.optString("reason", "unknown_mistake")
        Log.d("MainActivity_WS", "Opponent mistake. Count: $opponentMistakes, Reason: $reasonString")

        gameState.player2.mistakesInCurrentRound = opponentMistakes
        updateUI()

        val mistakeReasonMessage = when (reasonString) {
            "repeated_word" -> "${gameState.player2.name} played a repeated word!"
            "invalid_word" -> "${gameState.player2.name} played an invalid word!"
            "timeout" -> "${gameState.player2.name} timed out!"
            else -> "${gameState.player2.name} made a mistake."
        }
        Toast.makeText(this, mistakeReasonMessage, Toast.LENGTH_SHORT).show()
        playMistakeAnimation(MistakeType.OPPONENT)

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
        val gameWinnerId = payload.optString("game_winner_id", null)
        val messageFromServer = payload.optString("message", "Opponent disconnected.")
        Log.w("MainActivity_WS", "Player $disconnectedPlayerId disconnected. Message: $messageFromServer")

        Toast.makeText(this, messageFromServer, Toast.LENGTH_LONG).show()

        binding.editTextWordInput.isEnabled = false
        binding.buttonSubmit.isEnabled = false
        binding.editTextWordInput.hint = "Opponent left."
        binding.textViewTimer.text = "Game Interrupted"
        setEmojiButtonsEnabled(false)
        cancelTimer()
        showReturnToMenuButton() // Show button to exit

        // If game_winner_id is present, it means the game is over due to forfeit
        if (gameWinnerId != null && gameWinnerId.isNotEmpty()) {
            // We can preemptively treat game as over, but server's game_over is authoritative
            Log.i("MainActivity_WS", "Game ended due to disconnect. Winner: $gameWinnerId")
            // The game_over message should follow from backend to update scores and confirm.
        }

        // Consider if you want a "Back to Menu" button to become visible here
        // if the game is effectively over due to disconnect.
        // binding.buttonBackToMenu.visibility = View.VISIBLE
        // binding.buttonBackToMenu.setOnClickListener { ... navigate to LauncherActivity ... }
    }


    private fun handleGameStateUpdate(payload: JSONObject) {
        Log.d("MainActivity_WS", "Handling game state update: $payload")
        if (!gameHasActuallyStarted) {
            Log.d("MainActivity_WS", "GameStateUpdate received, but entry animation not finished. Buffering.")
            return
        }
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
        if (!gameHasActuallyStarted) {
            Log.d("MainActivity_WS", "GameStart received, but entry animation not finished. Buffering.")
            // Optionally buffer this payload to be processed after animation.
            // For now, we assume server won't send critical start info before client is ready.
            return
        }
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
        val message = payload.optString("message", if (isValid) "Good word!" else "Not valid.")

        if (isValid) {
            cancelTimer() // Stop timer on successful submission
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            gameState.recordValidWord(word, PlayerTurn.PLAYER_1) // Record for local player (player 1)
            animateWordToBox(word, binding.textViewPlayer1PlayedWords, true)
            // Server will send a game_state update to switch turn
            gameState.switchTurn()

        } else {
            Toast.makeText(this, "'$word' is not valid. Mistake!", Toast.LENGTH_LONG).show()
            gameState.recordMistake() // Record mistake for local player (player 1)
            updateUI() // Show mistake count increase

            playMistakeAnimation(MistakeType.LOCAL)
        }
        updateUI()
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

    private fun handleTimeOut(payload: JSONObject) {
        Log.i("MainActivity_WS", "Handling opponent timeout: $payload")
        try {
            // --- MODIFIED: Read from "player_id" field ---
            val timedOutPlayerId = payload.getString("player_id")

            // Update the game state with the authoritative data from the server first.
            // This will correctly update mistake counts and scores for whoever timed out.
            gameState.updateFromJson(payload, ownUserId.toString())

            if (timedOutPlayerId == ownUserId.toString()) {
                // It was ME who timed out.
                Log.w("MainActivity_WS", "Confirmed: Local player timed out.")
                Toast.makeText(this, "You ran out of time!", Toast.LENGTH_SHORT).show()
                playMistakeAnimation(MistakeType.LOCAL)
            } else {
                // It was the OPPONENT who timed out.
                Log.i("MainActivity_WS", "Confirmed: Opponent timed out.")
                Toast.makeText(this, "${gameState.player2.name} timed out!", Toast.LENGTH_SHORT).show()
                playMistakeAnimation(MistakeType.OPPONENT)
            }

            updateUI() // Refresh UI based on the new state from updateFromJson

            // If the game is not over and it's now our turn, start the timer.
            if (!gameState.isGameOver() && gameState.currentPlayerTurn == PlayerTurn.PLAYER_1) {
                handleTurnStart()
            }

        } catch (e: JSONException) {
            Log.e("MainActivity_WS", "Error parsing turn_timeout payload", e)
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
        val p1Score = payload.getInt("player1_final_score")
        val p2Score = payload.getInt("player2_final_score")
        val reasonString = payload.optString("reason", RoundEndReason.UNKNOWN.value)
        val reason = RoundEndReason.fromValue(reasonString)

        gameState.player1.score = p1Score
        gameState.player2.score = p2Score
        gameState.isWaitingForOpponent = true

        val (winnerPlayerState, loserPlayerState) = when {
            winnerId == gameState.player1.serverId -> gameState.player1 to gameState.player2
            winnerId == gameState.player2.serverId -> gameState.player2 to gameState.player1
            else -> null to null // Draw or no specific winner
        }


        updateUI() // Show final scores
        playGameOverAnimation(winnerPlayerState, loserPlayerState, p1Score, p2Score, reason)


        // Disable input permanently for this game instance
        binding.buttonSubmit.isEnabled = false
        binding.editTextWordInput.isEnabled = false
        binding.editTextWordInput.hint = "Game Over"
        setEmojiButtonsEnabled(false)
        showReturnToMenuButton()
        // TODO: Add a "Play Again" or "Back to Menu" button visibility toggle here
    }

    private fun sendClientReadyAction() {
        Log.i("MainActivity_WS", "Client is ready. Sending 'client_ready' to server.")
        binding.editTextWordInput.hint = "Waiting for opponent..."
        gameWebSocketClient?.sendPlayerAction("client_ready", null)
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivityTimer", "onResume. Timer paused by activity: $isTimerPausedByActivity. Game started: $gameHasActuallyStarted")

        // If gameHasActuallyStarted is false, it means onCreate didn't finish its animation sequence.
        // The animation sequence itself will call connectWebSocketAndSetupGame.
        // If gameHasActuallyStarted is true, it implies game was running, and then paused.
        if (gameHasActuallyStarted && !isEntryAnimationPlaying) {
            Log.d("MainActivity", "onResume: Game had already started. Checking WebSocket.")
            // If WebSocket was connected, it might still be or needs re-establishing by user action
            // or automatic reconnect logic (not implemented here).
            // For now, just update UI. The game might be in a waiting state.
            if (isTimerPausedByActivity && gameState.currentPlayerTurn == PlayerTurn.PLAYER_1 && !gameState.isGameOver()) {
                // Timer was paused by onPause, and it's still our turn.
                Log.d("MainActivityTimer", "Resuming timer logic in onResume.")
                handleTurnStart() // This will recalculate remaining time and restart timer
            } else if (gameWebSocketClient?.isConnected() == true) {
                // If timer wasn't paused by us, or it's not our turn, just update UI
                updateUI()
                // if it became our turn while paused, server message should trigger handleTurnStart via onMessageReceived
            } else if (gameWebSocketClient != null) { // client exists but not connected
                Log.w("MainActivity", "onResume: WebSocket client exists but not connected. Attempting to reconnect.")
                // Consider adding a "Reconnecting..." UI state
                // gameWebSocketClient?.connect() // Or prompt user
                binding.textViewPrompt.text = "RECONNECTING..."
                binding.editTextWordInput.hint = "Reconnecting..."
                binding.editTextWordInput.isEnabled = false
                binding.buttonSubmit.isEnabled = false
                gameWebSocketClient?.connect()
            } else {
                // This case (game started but WS client is null) should ideally not happen
                // unless there was an error post-animation.
                Log.e("MainActivity", "onResume: Game started but WebSocket client is null. Critical error.")
                Toast.makeText(this, "Connection error. Please restart.", Toast.LENGTH_LONG).show()
                navigateToLauncherClearTask()
            }
        } else if (!isEntryAnimationPlaying && !gameHasActuallyStarted) {
            // This case means onCreate finished, animation callback was supposed to run
            // but didn't set gameHasActuallyStarted. This might indicate an issue
            // if the animation callback didn't fire or complete properly.
            // For safety, try to proceed if WS is already setup by onCreate.
            Log.w("MainActivity", "onResume: Entry animation seems finished, but game not marked as started. Attempting to setup UI/Listeners.")
            if (gameWebSocketClient != null) { // If WS client was initialized
                setupGameUIAndListeners() // Attempt to setup game UI.
                if (initialGamePayload != null) { // Process any buffered payload
                    processBufferedInitialPayload(initialGamePayload!!)
                    initialGamePayload = null
                } else if(gameWebSocketClient?.isConnected() == true){
                    updateUI() // If connected and no payload, just update UI (likely waiting for server msg)
                }
            } else {
                Log.e("MainActivity", "onResume: Game not started and WS client is null. Likely error in onCreate flow.")
                navigateToLauncherClearTask() // Critical error, go back
            }
        }
        else {
            Log.d("MainActivity", "onResume: Entry animation is likely pending or still in progress. No game logic started.")
        }
    }


    private fun playGameOverAnimation(
        winner: PlayerState?,
        loser: PlayerState?,
        p1FinalScore: Int,
        p2FinalScore: Int,
        reason: RoundEndReason
    ) {
        val viewsToFadeOut = listOf(
            binding.promptCard,
            binding.sentenceCard,
            binding.wordsPlayedCard,
            binding.emojiReactionCard,
            binding.inputCard,
            binding.statusBar // Fade out the entire status bar
            // binding.textViewTimer is part of promptCard, so it fades with it
        )

        val fadeOutAnimators = viewsToFadeOut.map { view ->
            ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
                duration = 600
                interpolator = AccelerateInterpolator()
            }
        }

        // Prepare Game Over Display
        val gameOverLayout = binding.gameOverDisplayLayout
        binding.textViewGameOverTitle.text = when {
            winner != null -> "GAME OVER"
            else -> "GAME DRAWN" // Or "GAME FINISHED"
        }

        // Player 1 (local player)
        binding.textViewFinalPlayer1Name.text = gameState.player1.name
        if (winner == gameState.player1) {
            binding.imageViewWinnerP1Crown.visibility = View.VISIBLE
            binding.textViewFinalPlayer1Name.setTypeface(null, android.graphics.Typeface.BOLD)
            binding.textViewFinalPlayer1Name.setTextColor(ContextCompat.getColor(this, R.color.win_color))
        } else {
            binding.imageViewWinnerP1Crown.visibility = View.GONE
            binding.textViewFinalPlayer1Name.setTypeface(null, android.graphics.Typeface.NORMAL)
            binding.textViewFinalPlayer1Name.setTextColor(ContextCompat.getColor(this, if (loser == gameState.player1) R.color.lose_color else R.color.text_primary))
        }

        // Player 2 (opponent)
        binding.textViewFinalPlayer2Name.text = gameState.player2.name
        if (winner == gameState.player2) {
            binding.imageViewWinnerP2Crown.visibility = View.VISIBLE
            binding.textViewFinalPlayer2Name.setTypeface(null, android.graphics.Typeface.BOLD)
            binding.textViewFinalPlayer2Name.setTextColor(ContextCompat.getColor(this, R.color.win_color))
        } else {
            binding.imageViewWinnerP2Crown.visibility = View.GONE
            binding.textViewFinalPlayer2Name.setTypeface(null, android.graphics.Typeface.NORMAL)
            binding.textViewFinalPlayer2Name.setTextColor(ContextCompat.getColor(this, if (loser == gameState.player2) R.color.lose_color else R.color.text_secondary))
        }

        binding.textViewFinalScoreDisplay.text = "$p1FinalScore - $p2FinalScore"

        val reasonMessage = when(reason) {
            RoundEndReason.DOUBLE_TIMEOUT -> "(Double Timeout)"
            RoundEndReason.OPPONENT_DISCONNECTED -> "(Opponent Disconnected)"
            RoundEndReason.TIMEOUT_MAX_MISTAKES -> "(Max Timeouts)"
            RoundEndReason.INVALID_WORD_MAX_MISTAKES -> "(Max Invalid Words)"
            RoundEndReason.REPEATED_WORD_MAX_MISTAKES -> "(Max Repeated Words)"
            RoundEndReason.MAX_ROUNDS_REACHED_OR_SCORE_LIMIT -> "" // No specific sub-reason needed usually
            RoundEndReason.UNKNOWN -> ""
        }
        if (reasonMessage.isNotEmpty()) {
            binding.textViewGameEndReason.text = reasonMessage
            binding.textViewGameEndReason.visibility = View.VISIBLE
        } else {
            binding.textViewGameEndReason.visibility = View.GONE
        }


        // Animation for Game Over Layout
        gameOverLayout.alpha = 0f
        gameOverLayout.scaleX = 0.7f
        gameOverLayout.scaleY = 0.7f
        gameOverLayout.translationY = 100f // Start slightly lower
        gameOverLayout.visibility = View.VISIBLE

        val fadeInGameOver = ObjectAnimator.ofFloat(gameOverLayout, View.ALPHA, 0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
        }
        val scaleXGameOver = ObjectAnimator.ofFloat(gameOverLayout, View.SCALE_X, 0.7f, 1f).apply {
            duration = 700
            interpolator = OvershootInterpolator(1.5f) // Add a slight overshoot
        }
        val scaleYGameOver = ObjectAnimator.ofFloat(gameOverLayout, View.SCALE_Y, 0.7f, 1f).apply {
            duration = 700
            interpolator = OvershootInterpolator(1.5f)
        }
        val translateYGameOver = ObjectAnimator.ofFloat(gameOverLayout, View.TRANSLATION_Y, 100f, 0f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
        }

        val gameOverAnimatorSet = AnimatorSet()
        gameOverAnimatorSet.playTogether(fadeInGameOver, scaleXGameOver, scaleYGameOver, translateYGameOver)
        gameOverAnimatorSet.startDelay = 300 // Start after game UI starts fading

        // Winner Name Animation (e.g., pulse or slightly larger scale)
        val winnerNameView = if (winner == gameState.player1) binding.textViewFinalPlayer1Name else if (winner == gameState.player2) binding.textViewFinalPlayer2Name else null
        var winnerPulseAnimatorSet: AnimatorSet? = null
        if (winnerNameView != null) {
            val pulseScaleX = ObjectAnimator.ofFloat(winnerNameView, View.SCALE_X, 1f, 1.15f, 1f).apply {
                duration = 800
                repeatCount = 1 // Play twice (original + 1 repeat)
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            val pulseScaleY = ObjectAnimator.ofFloat(winnerNameView, View.SCALE_Y, 1f, 1.15f, 1f).apply {
                duration = 800
                repeatCount = 1
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            winnerPulseAnimatorSet = AnimatorSet()
            winnerPulseAnimatorSet.playTogether(pulseScaleX, pulseScaleY)
            winnerPulseAnimatorSet.startDelay = gameOverAnimatorSet.duration + 100 // Start after main card appears
        }


        val fullAnimationSet = AnimatorSet()
        val allFadeOuts = AnimatorSet()
        allFadeOuts.playTogether(fadeOutAnimators)

        if (winnerPulseAnimatorSet != null) {
            fullAnimationSet.play(allFadeOuts).before(gameOverAnimatorSet)
            fullAnimationSet.play(winnerPulseAnimatorSet).after(gameOverAnimatorSet)
        } else {
            fullAnimationSet.play(allFadeOuts).before(gameOverAnimatorSet)
        }


        fullAnimationSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Make all faded out views GONE to prevent them from intercepting touches
                viewsToFadeOut.forEach { it.visibility = View.GONE }
                showReturnToMenuButton() // Show the button after animation
            }
        })
        fullAnimationSet.start()
    }

    private fun showReturnToMenuButton() {
        binding.buttonReturnToMenu.visibility = View.VISIBLE
        binding.buttonReturnToMenu.setOnClickListener {
            navigateToLauncherClearTask()
        }
    }

    private fun handleErrorFromServer(errorMessage: String) {
        Log.e("MainActivity_WS", "Received error message from server: $errorMessage")
        Toast.makeText(this, "Server Error: $errorMessage", Toast.LENGTH_LONG).show()
        // Potentially disable UI or show more prominent error
        binding.editTextWordInput.hint = "Server Error!"
        binding.editTextWordInput.isEnabled = false
        binding.buttonSubmit.isEnabled = false
        setEmojiButtonsEnabled(false)
        cancelTimer()
        showReturnToMenuButton()
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
        if (gameState.isGameOver() || gameState.isWaitingForOpponent || gameState.currentPlayerTurn != PlayerTurn.PLAYER_1) {
            cancelTimer() // Not our turn, ensure timer is off
            updateUI()
            return
        }
        if (gameState.currentPlayerTurn == PlayerTurn.PLAYER_1 && !gameState.isWaitingForOpponent && !gameState.isGameOver()) {
            updateUI() // Ensure UI reflects it's our turn

            val currentTurnDurationMs = gameState.turnDurationSeconds * 1000L // Use server value

            if (isTimerPausedByActivity && turnEndTimeMillis > 0) {
                val currentTime = System.currentTimeMillis()
                val newTimeLeft = turnEndTimeMillis - currentTime
                if (newTimeLeft > 0) {
                    timeLeftInMillis = newTimeLeft // Restore from where it left off
                    Log.d("MainActivityTimer", "Resuming timer. Time left: $timeLeftInMillis")
                    startTurnTimerInternal() // Use a new internal function
                } else {
                    timeLeftInMillis = 0 // Time has already passed
                    Log.d("MainActivityTimer", "Timer expired while paused. Time left: 0")
                    handleTimerFinish() // Trigger timeout logic immediately
                }
                isTimerPausedByActivity = false // Reset flag
            } else if (!isTimerActive()) { // Only start a new timer if one isn't already running (or paused)
                timeLeftInMillis = currentTurnDurationMs
                turnEndTimeMillis = System.currentTimeMillis() + currentTurnDurationMs
                Log.d("MainActivityTimer", "Starting new timer. Ends at: $turnEndTimeMillis")
                startTurnTimerInternal()
            }
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

        Glide.with(this)
            .load(ownProfilePicUrl) // Assuming these URLs are static for the game duration
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.imageViewPlayer1ProfilePic)

        Glide.with(this)
            .load(opponentProfilePicUrl)
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.imageViewPlayer2ProfilePic)


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
        binding.tricklingSandView.resetAndHide() // Ensure clean state
        val currentTurnDurationMs = gameState.turnDurationSeconds * 1000L
        timeLeftInMillis = currentTurnDurationMs
        turnEndTimeMillis = System.currentTimeMillis() + currentTurnDurationMs // Set the absolute end time
        Log.d("MainActivityTimer", "Starting NEW turn timer. Ends at: $turnEndTimeMillis")
        startTurnTimerInternal()
    }

    private fun startTurnTimerInternal() {
        countDownTimer?.cancel() // Cancel any existing one first
        if (timeLeftInMillis <= 0) { // If no time left, trigger finish immediately
            handleTimerFinish()
            return
        }

        binding.textViewTimer.setTextColor(ContextCompat.getColor(this, R.color.timer_default_color))
        val totalDurationForFullTurnSeconds = gameState.turnDurationSeconds.toFloat()
        val actualTimeLeftSeconds = timeLeftInMillis / 1000f
        binding.tricklingSandView.startTimerEffect(totalDurationForFullTurnSeconds, actualTimeLeftSeconds)

        countDownTimer = object : CountDownTimer(timeLeftInMillis, COUNTDOWN_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                if (!this@MainActivity.isActive) {
                    cancel()
                    return
                }
                timeLeftInMillis = millisUntilFinished // Update the remaining time
                val secondsLeft = (millisUntilFinished + 999) / 1000
                binding.textViewTimer.text = "Time: ${secondsLeft}s"
                if (secondsLeft <= 5) {
                    binding.textViewTimer.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.timer_critical))
                }

                val timeElapsedSinceTurnStart = (gameState.turnDurationSeconds * 1000L) - millisUntilFinished
                val progress = timeElapsedSinceTurnStart.toFloat() / (gameState.turnDurationSeconds * 1000L).toFloat()
                binding.tricklingSandView.setTargetFillLevelCap(progress.coerceIn(0f, 1f))
            }

            override fun onFinish() {
                if (!this@MainActivity.isActive) return
                handleTimerFinish()
            }
        }.start()
        Log.d("MainActivityTimer", "CountDownTimer started with timeLeft: $timeLeftInMillis")
    }

    private fun handleTimerFinish() {
        binding.textViewTimer.text = "Time's up!"
        binding.tricklingSandView.stopTimerEffectAndFill()
        Toast.makeText(this@MainActivity, "Time's up! Sending timeout.", Toast.LENGTH_SHORT).show()
        playMistakeAnimation(MistakeType.LOCAL)
        // gameWebSocketClient?.sendPlayerAction("timeout", null)
        gameState.recordMistake() // Local reflection
        updateUI()
        binding.editTextWordInput.isEnabled = false
        binding.buttonSubmit.isEnabled = false
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        binding.textViewTimer.text = "Time: --s"
        binding.textViewTimer.setTextColor(ContextCompat.getColor(this, R.color.timer_default_color))
        binding.tricklingSandView.cancelTimerEffect()
        // Do NOT reset turnEndTimeMillis here unless it's a new round or game over
        Log.d("MainActivityTimer", "Timer cancelled.")
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

    private fun isTimerActive(): Boolean {
        return countDownTimer != null
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

    override fun onPause() {
        super.onPause()
        Log.d("MainActivityTimer", "onPause. Timer active: ${isTimerActive()}")
        if (isTimerActive() && gameState.currentPlayerTurn == PlayerTurn.PLAYER_1 && !gameState.isGameOver()) {
            countDownTimer?.cancel() // Stop the ticking
            isTimerPausedByActivity = true
            Log.d("MainActivityTimer", "Timer paused by activity. Time left: $timeLeftInMillis. End time: $turnEndTimeMillis")
        }
    }

    // Lifecycle Management
    override fun onStop() {
        super.onStop()
        Log.d("MainActivityTimer", "onPause. Timer active: ${isTimerActive()}")
        if (isTimerActive() && gameState.currentPlayerTurn == PlayerTurn.PLAYER_1 && !gameState.isGameOver()) {
            countDownTimer?.cancel() // Stop the ticking
            isTimerPausedByActivity = true
            Log.d("MainActivityTimer", "Timer paused by activity. Time left: $timeLeftInMillis. End time: $turnEndTimeMillis")
        }
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

    private fun playRoundOutcomeAnimation(outcome: RoundOutcome, onAnimationEnd: () -> Unit = {}) {
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
                onAnimationEnd()
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