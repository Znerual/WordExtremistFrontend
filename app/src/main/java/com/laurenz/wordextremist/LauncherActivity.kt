// LauncherActivity.kt
package com.laurenz.wordextremist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import com.laurenz.wordextremist.auth.AuthManager
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope
import com.google.android.gms.games.PlayGamesSdk
//import com.laurenz.wordextremist.auth.AuthPgsStatus
//import com.laurenz.wordextremist.auth.AuthViewModel
import com.laurenz.wordextremist.databinding.ActivityLauncherBinding
import com.laurenz.wordextremist.model.DeviceLoginRequestData
import com.laurenz.wordextremist.ui.animation.AnimatedElement
import com.laurenz.wordextremist.util.KeystoreHelper
import com.laurenz.wordextremist.util.TokenManager
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.edit
import com.bumptech.glide.Glide
import com.laurenz.wordextremist.model.UserPublic
import com.laurenz.wordextremist.network.ApiClient
import com.laurenz.wordextremist.ui.tutorial.TutorialManager
import com.laurenz.wordextremist.ui.tutorial.TutorialStep

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private val languageMap = mapOf("English" to "en", "Español" to "es", "Français" to "fr") // Name to Code
    private var selectedLanguageCode: String = "en" // Default

    private val PREFS_NAME = "WordExtremistPrefs"
    private val PREF_SELECTED_LANGUAGE_CODE = "selectedLanguageCode"
    private val PREF_USER_NAME = "userName"
    private val PREF_USER_LEVEL = "userLevel"
    private val PREF_USER_EXPERIENCE = "userExperience"
    private val PREF_USER_WORDS_COUNT = "userWordsCount"
    private val PREF_USER_PROFILE_PIC_URL = "userProfilePicUrl"
    private val PREF_USER_DB_ID = "userDbId" // To store user's database ID
    private val PREF_TUTORIAL_COMPLETED = "tutorialCompleted"

    private lateinit var tutorialManager: TutorialManager
    private lateinit var tutorialGameLauncher: ActivityResultLauncher<Intent>
    private lateinit var tutorialProfileLauncher: ActivityResultLauncher<Intent>
    private lateinit var tutorialVaultLauncher: ActivityResultLauncher<Intent>
    companion object {
        var isTutorialInProgress = false
    }

    private lateinit var editProfileResultLauncher: ActivityResultLauncher<Intent>

    private var localClientIdentifier: String? = null // For device login
    private var currentUserDbId: Int? = null // Store current user's DB ID

    // --- DEBUG FLAG ---
    private val USE_DEBUG_AUTH = BuildConfig.DEBUG
    private val DEBUG_SERVER_AUTH_CODE_FOR_BACKEND = "DEBUG_FAKE_SERVER_AUTH_CODE_FOR_TESTING"

    // Properties for animation
    private val animatedElementsList = mutableListOf<AnimatedElement>()
    private val random = Random() // java.util.Random
    private var parentViewWidth = 0
    private var parentViewHeight = 0
    private var choreographer: Choreographer? = null
    private var isActivityResumed = false

    private val animationFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {

            // Ensure dimensions are set and activity is active before updating
            if (parentViewWidth > 0 && parentViewHeight > 0 && !isDestroyed && !isFinishing) {
                updateAnimatedElements()
            }
            // Continue the loop
            choreographer?.postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LauncherActivity", "onCreate: Started") // ADDED LOG
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localClientIdentifier = getLocalClientIdentifier() // Get device ID
        if (localClientIdentifier == null) {
            Toast.makeText(this, "Critical Error: Cannot identify device.", Toast.LENGTH_LONG).show()
            finishAffinity() // Exit if no device ID
            return
        }

        // Initialize AuthViewModel and CredentialManager/PlayGamesSd
        if (!USE_DEBUG_AUTH) {
            PlayGamesSdk.initialize(this) // Initialize Play Games SDK if not in debug auth mode
        }

        setupLanguageSpinner()
        loadLanguagePreference() // Load saved language and update spinner
        setupClickListeners()
        loadCachedProfileInfo() // Load cached info first


        // Setup for animations: Get parent dimensions once layout is complete
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (binding.root.width > 0 && binding.root.height > 0) {
                    val previousWidth = parentViewWidth // For logging
                    parentViewWidth = binding.root.width
                    parentViewHeight = binding.root.height
                    Log.d("LauncherActivity", "onGlobalLayout: Parent dimensions ACQUIRED. " +
                            "New: $parentViewWidth x $parentViewHeight. Previous: $previousWidth x ...")
                    // Initialize elements only if they haven't been, e.g. on first layout
                    if (animatedElementsList.isEmpty()) {
                        Log.d("LauncherActivity", "onGlobalLayout: Initializing animated elements...")
                        initializeAnimatedElements()

                        if (animatedElementsList.isNotEmpty() && isActivityResumed) {
                            Log.d("LauncherActivity", "onGlobalLayout: Elements initialized and activity is resumed. Starting animations NOW.")
                            startOrResumeAnimations()
                        } else if (animatedElementsList.isEmpty()) {
                            Log.w("LauncherActivity", "onGlobalLayout: Initialization attempted but list is still empty.")
                        } else if (!isActivityResumed) {
                            Log.d("LauncherActivity", "onGlobalLayout: Elements initialized, but activity not resumed yet. Animations will start in onResume.")
                        }

                    } else {
                        Log.d("LauncherActivity", "onGlobalLayout: Animated elements already initialized, count: ${animatedElementsList.size}")
                        if (isActivityResumed) { // Only try to start if resumed
                            Log.d("LauncherActivity", "onGlobalLayout: Elements existed, activity resumed, ensuring animations are running if needed.")
                            startOrResumeAnimations() // This will re-post if necessary, or do nothing if already running fine.
                        }
                    }

                    // Animations will be started/resumed in onResume()
                    // We can remove the listener now as we have the dimensions
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    Log.d("LauncherActivity", "onGlobalLayout: Listener removed.")
                } else {
                    Log.d("LauncherActivity", "onGlobalLayout: Called but root dimensions are still zero (width=${binding.root.width}, height=${binding.root.height}). Waiting for valid dimensions.")
                }
            }
        })
        Log.d("LauncherActivity", "onCreate: OnGlobalLayoutListener attached.")

        // Tutorial
        tutorialGameLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // This is called when the scripted game (MainActivity) finishes.
            // We can now proceed to the next tutorial step.
            showTutorialOverlay()
            tutorialManager.advance()
        }

        // Register launchers for other activities in the tutorial
        tutorialProfileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            showTutorialOverlay()
            tutorialManager.advance()
        }
        tutorialVaultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            showTutorialOverlay()
            tutorialManager.advance()
        }

        checkAndStartTutorial()

    }

    private fun hideTutorialOverlay() {
        findViewById<View?>(R.id.tutorialOverlayView)?.visibility = View.GONE
    }
    private fun showTutorialOverlay() {
        findViewById<View?>(R.id.tutorialOverlayView)?.visibility = View.VISIBLE
    }


    private fun checkAndStartTutorial() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tutorialCompleted = prefs.getBoolean(PREF_TUTORIAL_COMPLETED, false)

        if (!tutorialCompleted && !isTutorialInProgress || true) {
            binding.root.post {
                startTutorial()
            }
        }
    }

    private fun startTutorial() {
        isTutorialInProgress = true

        val steps = listOf(
            // Step 0: Profile Highlight. No async work, so return true.
            TutorialStep(R.id.profileSection, "Welcome! This is your profile. Tap here to edit your name and see stats.") { true },

            // Step 1: Language Highlight. No async work, so return true.
            TutorialStep(R.id.languageSection, "You can choose the language for your word battles here.") { true },

            // Step 2: Start Button. No async work, so return true.
            TutorialStep(R.id.buttonStartMatch, "Ready? Let's play a quick practice round. Tap here to begin!") { true },

            // Step 3: Text Page to launch game. This IS async, so return false.
            TutorialStep(null, "The goal is simple:\n\n1. Find a word that fits the prompt...\n\nTap anywhere to start.") { manager ->
                hideTutorialOverlay()
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_IS_TUTORIAL_MODE, true)
                }
                tutorialGameLauncher.launch(intent)
                false // Manager must WAIT for the activity to return.
            },

            // --- NEW TEXT PAGE 2: EXPLAIN THE PROFILE SCREEN ---
            TutorialStep(
                targetViewId = null,
                explanationText = "Great job! Let's take a closer look at your profile, where you can change your name and see your stats.\n\nTap anywhere to continue.",
            ) {
                manager ->
                hideTutorialOverlay() // Hide before launching
                val intent = Intent(this, EditProfileActivity::class.java)
                tutorialProfileLauncher.launch(intent)
                false // <- IMPORTANT: Action handles flow
            },
            TutorialStep(
                targetViewId = R.id.buttonTreasureChest,
                explanationText = "The Word Vault saves your best and most unique words. Tap to explore it!",
            ) {
                hideTutorialOverlay()
                val intent = Intent(this, WordVaultActivity::class.java).apply {
                    putExtra(WordVaultActivity.EXTRA_IS_TUTORIAL_MODE, true)
                }

                tutorialVaultLauncher.launch(intent)
                false // <- IMPORTANT: Action handles flow
            },
            TutorialStep(
                targetViewId = null, // Final message is also a text page now
                explanationText = "You're all set! Enjoy the game.\n\nTap anywhere to finish the tutorial."
            ) { true }
        )

        tutorialManager = TutorialManager(this, steps)
        tutorialManager.onTutorialFinishedListener = {
            // This code now runs when the tutorial ends
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean(PREF_TUTORIAL_COMPLETED, true)
                apply()
            }
            isTutorialInProgress = false
            Log.d("LauncherActivity", "Tutorial finished and state reset.")
        }

        tutorialManager.start()
    }

    @SuppressLint("HardwareIds")
    private fun getLocalClientIdentifier(): String? {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun checkAuthAndLoadProfile() {
        binding.progressBarAuth.visibility = View.VISIBLE
        binding.buttonStartMatch.isEnabled = false
        showDefaultProfileState("Loading...")

        lifecycleScope.launch {
            // Use the AuthManager to ensure we have a valid token.
            // This will perform a silent login if needed.
            val isAuthValid = AuthManager.ensureValidToken(this@LauncherActivity)

            if (isAuthValid) {
                // Now that we're sure the token is valid, fetch the profile.
                Log.d("LauncherActivity", "Auth is valid. Fetching profile.")
                fetchUserProfile() // This can be a simplified function now
            } else {
                // If even the silent login fails, show an error.
                // This indicates a persistent problem (e.g., no network, server down).
                Log.e("LauncherActivity", "AuthManager failed to ensure a valid token.")
                showDefaultProfileState("Login failed. Check connection.")
                Toast.makeText(this@LauncherActivity, "Login failed. Please check your network and restart.", Toast.LENGTH_LONG).show()
                binding.progressBarAuth.visibility = View.GONE
            }
        }
    }

    // A simplified profile fetcher, as it assumes the token is valid.
    private fun fetchUserProfile() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getMyProfile()
                if (response.isSuccessful) {
                    response.body()?.let { user ->
                        Log.i("LauncherActivity", "Profile fetched: ${user.username}")
                        updateProfileUI(user)
                        cacheProfileInfo(user)
                        currentUserDbId = user.id
                        binding.buttonStartMatch.isEnabled = true
                    }
                } else {
                    // This case is now less likely but good to have as a fallback.
                    Log.e("LauncherActivity", "Profile fetch failed even after auth check: ${response.code()}")
                    showDefaultProfileState("Could not load profile.")
                    binding.buttonStartMatch.isEnabled = false
                }
            } catch (e: Exception) {
                Log.e("LauncherActivity", "Exception fetching profile", e)
                showDefaultProfileState("Error connecting to server.")
                binding.buttonStartMatch.isEnabled = false
            } finally {
                binding.progressBarAuth.visibility = View.GONE
            }
        }
    }

    private fun initiateDeviceLogin() {
        val currentLocalClientId = localClientIdentifier ?: return // Should be available
        var clientPassword = KeystoreHelper.getStoredPassword(this)
        if (clientPassword == null) {
            clientPassword = KeystoreHelper.generateAndStorePassword(this)
            Log.i("LauncherActivity", "New client password generated and stored.")
        }

        binding.progressBarAuth.visibility = View.VISIBLE
        binding.buttonStartMatch.isEnabled = false // Disable while authenticating
        showDefaultProfileState("Authenticating...")

        lifecycleScope.launch {
            try {
                val loginRequest = DeviceLoginRequestData(
                    clientProvidedId = currentLocalClientId,
                    clientGeneratedPassword = clientPassword
                )
                Log.d("LauncherActivity", "Calling /device-login with client ID: $currentLocalClientId")
                val response = ApiClient.instance.deviceLogin(loginRequest)

                if (response.isSuccessful && response.body() != null) {
                    val backendTokenResponse = response.body()!!
                    val expiresIn = backendTokenResponse.expiresIn ?: 3600
                    TokenManager.saveToken(this@LauncherActivity, backendTokenResponse.accessToken, expiresIn)
                    Log.i("LauncherActivity", "Device login successful. JWT received.")

                    backendTokenResponse.user?.let { user ->
                        Log.i("LauncherActivity", "User data from login: ${user.username}, Level: ${user.level}")
                        updateProfileUI(user)
                        cacheProfileInfo(user)
                        currentUserDbId = user.id // Store user's DB ID
                    } ?: run {
                        // If user object not in login response, fetch it immediately
                        Log.w("LauncherActivity", "User object not in login response, fetching via /users/me.")
                        fetchUserProfile()
                    }
                    binding.buttonStartMatch.isEnabled = true
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Device login failed"
                    Log.e("LauncherActivity", "Error from /device-login: ${response.code()} - $errorBody")
                    if (response.code() == 401) { // Unauthorized
                        Log.w("LauncherActivity", "Device login 401. Clearing stored password and retrying registration.")
                        KeystoreHelper.clearStoredPassword(this@LauncherActivity) // Clear potentially bad password
                        // Optionally retry login once, which will re-register with new password
                        initiateDeviceLogin() // Be careful of infinite loops here; add a retry counter if needed
                    } else {
                        showDefaultProfileState("Authentication failed.")
                    }
                }
            } catch (e: Exception) {
                Log.e("LauncherActivity", "Exception during /device-login", e)
                showDefaultProfileState("Connection error during login.")
            } finally {
                binding.progressBarAuth.visibility = View.GONE
            }
        }
    }


    private fun loadCachedProfileInfo() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(PREF_USER_NAME, null)
        val level = prefs.getInt(PREF_USER_LEVEL, -1)
        // val experience = prefs.getInt(PREF_USER_EXPERIENCE, -1) // Not directly used in display string if wordsCount present
        val wordsCount = prefs.getInt(PREF_USER_WORDS_COUNT, -1)
        currentUserDbId = prefs.getInt(PREF_USER_DB_ID, -1).takeIf { it != -1 }
        val profilePicUrl = prefs.getString(PREF_USER_PROFILE_PIC_URL, null)

        if (username != null && level != -1) {
            binding.textViewPlayerName.text = username
            val levelText = if (wordsCount > 0) {
                "Level $level • $wordsCount words"
            } else {
                "Level $level" // Fallback if no words or XP to show initially
            }
            binding.textViewPlayerLevel.text = levelText

            Glide.with(this)
                .load(profilePicUrl) // Glide handles null URLs gracefully
                .placeholder(R.drawable.avatar_dummy) // Show while loading
                .error(R.drawable.avatar_dummy)       // Show if URL is bad or load fails
                .into(binding.imageViewProfilePicture)

            binding.profileSection.visibility = View.VISIBLE
            binding.buttonStartMatch.isEnabled = true // Enable if cached info exists
        } else {
            showDefaultProfileState("Loading profile...") // Initial state before auth attempt
            binding.buttonStartMatch.isEnabled = false
        }
    }

    private fun updateProfileUI(user: UserPublic) {
        binding.textViewPlayerName.text = user.username ?: "Player"
        val levelText = if (user.wordsCount > 0) {
            "Level ${user.level} • ${user.wordsCount} words"
        } else {
            "Level ${user.level} • ${user.experience} XP"
        }
        binding.textViewPlayerLevel.text = levelText

        Glide.with(this)
            .load(user.profilePicUrl) // Load the URL from the user object
            .placeholder(R.drawable.avatar_dummy)
            .error(R.drawable.avatar_dummy)
            .into(binding.imageViewProfilePicture)

        binding.profileSection.visibility = View.VISIBLE
        Log.d("LauncherActivity", "Profile UI Updated: ${user.username}, Level ${user.level}, Words ${user.wordsCount}")
    }

    private fun showDefaultProfileState(message: String? = "Sign in to play!") {
        binding.textViewPlayerName.text = "Guest Player"
        binding.textViewPlayerLevel.text = message
        binding.imageViewProfilePicture.setImageResource(R.drawable.avatar_dummy)

        binding.profileSection.visibility = View.VISIBLE // Or GONE based on design
        // currentUserDbId = null // Clear if we are in a default state
    }

    private fun cacheProfileInfo(user: UserPublic) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(PREF_USER_NAME, user.username)
            putInt(PREF_USER_LEVEL, user.level)
            putInt(PREF_USER_EXPERIENCE, user.experience)
            putInt(PREF_USER_WORDS_COUNT, user.wordsCount)
            putInt(PREF_USER_DB_ID, user.id)
            putString(PREF_USER_PROFILE_PIC_URL, user.profilePicUrl)
            apply()
        }
        Log.d("LauncherActivity", "Cached profile info: ${user.username}, ID: ${user.id}")
    }

    private fun clearCachedProfileInfo() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(PREF_USER_NAME)
            remove(PREF_USER_LEVEL)
            remove(PREF_USER_EXPERIENCE)
            remove(PREF_USER_WORDS_COUNT)
            remove(PREF_USER_DB_ID)
            remove(PREF_USER_PROFILE_PIC_URL)
            apply()
        }
        currentUserDbId = null
        Log.d("LauncherActivity", "Cleared cached profile info.")
    }



    private fun initializeAnimatedElements() {
        Log.d("LauncherActivity", "initializeAnimatedElements: Attempting to initialize...") // ADDED LOG
        // This should be called after parentViewWidth and parentViewHeight are set
        if (parentViewWidth == 0 || parentViewHeight == 0) {
            Log.e("LauncherActivity", "Cannot initialize animations: Parent dimensions not available.")
            return
        }

        // Check if activity is in a valid state to interact with views
        if (isDestroyed || isFinishing) {
            Log.w("LauncherActivity", "Attempted to initialize animations on a destroyed/finishing activity.")
            return
        }

        val baseDecorativeViews : MutableList<ImageView?> = mutableListOf(
            binding.decorativeElement1, binding.decorativeElement2, binding.decorativeElement3,
            binding.decorativeElement4, binding.decorativeElement5, binding.decorativeElement6,
            binding.decorativeElement7, binding.decorativeElement8, binding.decorativeElement9,
            binding.decorativeElement10, binding.decorativeElement11, binding.decorativeElement12
        )

        val decorativeViews = baseDecorativeViews.filterNotNull() // Get only non-null

        animatedElementsList.clear() // Clear previous elements if any (e.g., on re-initialization)

        decorativeViews.forEach { view ->
            // Reset translations, positions are initially from XML constraints
            view.translationX = 0f
            view.translationY = 0f

            val speed = random.nextFloat() * 1.5f + 0.5f // Speed: 1.5 to 4.5 units/frame
            val angle = random.nextDouble() * 2.0 * PI // Direction in radians

            val dx = (cos(angle) * speed).toFloat()
            val dy = (sin(angle) * speed).toFloat()

            val rotationSpeed = random.nextFloat() * 1.5f - 0.75f // Rotation: -0.75 to 0.75 degrees/frame
            val initialAlpha = view.alpha // Alpha from XML (e.g., 0.18)
            // Target alpha will be between 0.1f and 0.5f
            val targetAlpha = random.nextFloat() * 0.4f + 0.1f

            animatedElementsList.add(
                AnimatedElement(
                    view = view,
                    currentTranslationX = 0f, // Start with no translation
                    currentTranslationY = 0f,
                    dx = dx,
                    dy = dy,
                    rotationSpeed = rotationSpeed,
                    currentRotation = view.rotation, // Initial rotation from XML
                    currentAlpha = initialAlpha,
                    targetAlpha = targetAlpha,
                    // Alpha changes gradually, speed: 0.003 to 0.013
                    alphaChangeSpeed = 0.003f + random.nextFloat() * 0.01f
                )
            )
        }

        if (animatedElementsList.isNotEmpty()) {
            Log.d("LauncherActivity", "initializeAnimatedElements: SUCCESS - Initialized ${animatedElementsList.size} elements.")
        } else {
            Log.w("LauncherActivity", "initializeAnimatedElements: COMPLETED but list is EMPTY.")
        }

        Log.d("LauncherActivity", "Initialized ${animatedElementsList.size} animated elements.")
    }

    private fun updateAnimatedElements() {
        animatedElementsList.forEach { element ->
            // 1. Update Translation (Movement)
            element.currentTranslationX += element.dx
            element.currentTranslationY += element.dy

            // 2. Update Rotation
            element.currentRotation = (element.currentRotation + element.rotationSpeed).let {
                // Normalize rotation to 0-360 to prevent excessively large numbers
                if (it >= 360f) it - 360f else if (it < 0f) it + 360f else it
            }
            element.view.rotation = element.currentRotation

            // 3. Update Alpha
            // If current alpha is close to target, pick a new random target alpha
//            if (abs(element.currentAlpha - element.targetAlpha) < element.alphaChangeSpeed * 2) { // Using alphaChangeSpeed*2 to ensure it triggers even if overshot
//                var newTarget: Float
//                do {
//                    newTarget = random.nextFloat() * 0.4f + 0.1f // New target: 0.1f to 0.5f
//                } while (abs(element.currentAlpha - newTarget) < 0.05f && animatedElementsList.size > 1) // Ensure some difference for next target
//                element.targetAlpha = newTarget
//            }
//
//            // Gradually move current alpha towards target alpha
//            if (element.currentAlpha < element.targetAlpha) {
//                element.currentAlpha = (element.currentAlpha + element.alphaChangeSpeed).coerceAtMost(element.targetAlpha)
//            } else if (element.currentAlpha > element.targetAlpha) {
//                element.currentAlpha = (element.currentAlpha - element.alphaChangeSpeed).coerceAtLeast(element.targetAlpha)
//            }
//            element.view.alpha = element.currentAlpha

            // 4. Bouncing Logic (Collision with parent edges)
            // These are the view's current visual coordinates within the parent
            val currentViewLeft = element.view.left + element.currentTranslationX
            val currentViewTop = element.view.top + element.currentTranslationY
            val currentViewRight = currentViewLeft + element.view.width
            val currentViewBottom = currentViewTop + element.view.height

            // Bounce off left edge
            if (currentViewLeft < 0) {
                element.currentTranslationX = -element.view.left.toFloat() // Correct position to be at the edge
                element.dx *= -1 // Reverse horizontal direction
            }
            // Bounce off right edge
            else if (currentViewRight > parentViewWidth) {
                element.currentTranslationX = (parentViewWidth - element.view.left - element.view.width).toFloat()
                element.dx *= -1
            }

            // Bounce off top edge
            if (currentViewTop < 0) {
                element.currentTranslationY = -element.view.top.toFloat()
                element.dy *= -1 // Reverse vertical direction
            }
            // Bounce off bottom edge
            else if (currentViewBottom > parentViewHeight) {
                element.currentTranslationY = (parentViewHeight - element.view.top - element.view.height).toFloat()
                element.dy *= -1
            }

            // 5. Apply new translations to the view
            element.view.translationX = element.currentTranslationX
            element.view.translationY = element.currentTranslationY
        }
    }

    private fun startOrResumeAnimations() {
        if (choreographer == null) {
            choreographer = Choreographer.getInstance()
        }
        // Remove any existing callback to prevent multiple loops, then post a new one
        choreographer?.removeFrameCallback(animationFrameCallback)
        choreographer?.postFrameCallback(animationFrameCallback)
        Log.d("LauncherActivity", "Animations started/resumed.")
    }

    private fun pauseAnimations() {
        choreographer?.removeFrameCallback(animationFrameCallback)
        Log.d("LauncherActivity", "Animations paused.")
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true // SET FLAG
        Log.d("LauncherActivity", "onResume: Called. parentWidth=$parentViewWidth, listEmpty=${animatedElementsList.isEmpty()}, isActivityDestroyed=$isDestroyed") // MODIFIED LOG

        if (isDestroyed || isFinishing) {
            Log.w("LauncherActivity", "onResume: Activity is destroyed or finishing. Bailing out of animation start.")
            return
        }

        if (isTutorialInProgress) {
            Log.d("LauncherActivity", "Tutorial is in progress, showing overlay and skipping auth check.")
            // If we are returning from another activity, the overlay needs to be shown again.
            showTutorialOverlay()
            return // Don't proceed with normal onResume logic
        }

        checkAuthAndLoadProfile()

        // If dimensions are ready
        if (parentViewWidth > 0 && parentViewHeight > 0 && animatedElementsList.isNotEmpty()) {
            Log.d("LauncherActivity", "onResume: Dimensions and elements ready. ATTEMPTING to start/resume animations.")
            startOrResumeAnimations()
        } else if (parentViewWidth == 0 || parentViewHeight == 0) {
            // This case is typically for the *first* onResume, before onGlobalLayout has fired.
            Log.d("LauncherActivity", "onResume: Parent dimensions not yet available. Waiting for onGlobalLayout to initialize and potentially start animations.")
        } else if (animatedElementsList.isEmpty() && parentViewWidth > 0 && parentViewHeight > 0) {
            // Dimensions are ready, but list is empty. This means initializeAnimatedElements in onGlobalLayout might have failed or hasn't run yet.
            // Or if it ran but resulted in an empty list.
            Log.d("LauncherActivity", "onResume: Dimensions ready, but list is empty. onGlobalLayout should initialize them. If it already ran and failed, check initializeAnimatedElements logs.")
            // Potentially try to initialize again as a safeguard, though onGlobalLayout is the primary init point.
            // initializeAnimatedElements() // Consider if this is needed or could cause issues. For now, rely on onGlobalLayout.
        }
    }

    override fun onPause() {
        super.onPause()
        pauseAnimations()
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseAnimations() // Ensure callbacks are removed
        choreographer = null // Release choreographer instance
        animatedElementsList.clear()
    }


    private fun setupLanguageSpinner() {
        val languageNames = languageMap.keys.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedName = languageNames[position]
                selectedLanguageCode = languageMap[selectedName] ?: "en" // Fallback to "en"
                saveLanguagePreference(selectedLanguageCode)
                Log.d("LauncherActivity", "Language selected: $selectedName ($selectedLanguageCode)")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Optional: handle case where nothing is selected
            }
        }
    }

    private fun saveLanguagePreference(languageCode: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_SELECTED_LANGUAGE_CODE, languageCode) }
    }

    private fun loadLanguagePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLangCode = prefs.getString(PREF_SELECTED_LANGUAGE_CODE, "en") ?: "en"
        selectedLanguageCode = savedLangCode

        // Set the spinner to the saved language
        val languageNames = languageMap.keys.toList()
        val position = languageMap.values.indexOf(savedLangCode)
        if (position != -1 && position < binding.spinnerLanguage.adapter.count) {
            binding.spinnerLanguage.setSelection(position)
        } else {
            binding.spinnerLanguage.setSelection(0) // Default to first item (English) if not found
        }
    }

    private fun setupClickListeners() {
        binding.buttonStartMatch.setOnClickListener {
            if (TokenManager.getToken(this) != null && currentUserDbId != null) {
                val intent = Intent(this, MatchmakingActivity::class.java).apply {
                    putExtra(MatchmakingActivity.EXTRA_SELECTED_LANGUAGE, selectedLanguageCode)
                    // MatchmakingActivity will use the globally available token
                    // It also needs to know the user's DB ID if it's used for any direct identification
                    // before its own /users/me call, but typically it would rely on the token.
                    // For this refactor, MatchmakingActivity will fetch its own /users/me if it needs user details.
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Authenticating... Please wait.", Toast.LENGTH_SHORT).show()
                // Auth process should be running or will be triggered by onResume if token is missing
                checkAuthAndLoadProfile() // Re-check/trigger auth
            }

        }

        binding.profileSection.setOnClickListener {
            Toast.makeText(this, "Profile section clicked.", Toast.LENGTH_SHORT).show()
            // Optionally, re-fetch profile on click if you want to ensure it's super fresh
            val token = TokenManager.getToken(this)
            if (token == null) initiateDeviceLogin()

            val intent = Intent(this, EditProfileActivity::class.java)
            editProfileResultLauncher.launch(intent)
        }

        binding.buttonViewLeaderboard.setOnClickListener {

            Toast.makeText(this, "View Leaderboard: Not yet implemented.", Toast.LENGTH_SHORT).show()
            // Intent(this, LeaderboardActivity::class.java).also { startActivity(it) }

        }

        binding.buttonTreasureChest.setOnClickListener {
            Log.d("LauncherActivity", "Treasure Chest button clicked.") // Log click
            // --- MODIFIED SECTION ---
            if (TokenManager.getToken(this) != null && currentUserDbId != null) {
                val intent = Intent(this, WordVaultActivity::class.java)
                // You can pass data to WordVaultActivity if needed, e.g., user ID
                // intent.putExtra("USER_ID", currentUserDbId)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please sign in to view your Word Vault.", Toast.LENGTH_LONG).show()
                // Optionally, trigger sign-in
                checkAuthAndLoadProfile()
            }
        }

        binding.buttonSignOut.setOnClickListener {
            TokenManager.clearToken(this)
            clearCachedProfileInfo()
            showDefaultProfileState("Signed out. Log in to play!") // Updated message
            binding.buttonStartMatch.isEnabled = false // Disable until re-auth
            Toast.makeText(this, "Signed Out.", Toast.LENGTH_SHORT).show()
        }

    }


}