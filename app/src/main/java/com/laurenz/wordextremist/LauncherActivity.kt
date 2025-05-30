// LauncherActivity.kt
package com.laurenz.wordextremist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayGamesSdk
//import com.laurenz.wordextremist.auth.AuthPgsStatus
//import com.laurenz.wordextremist.auth.AuthViewModel
import com.laurenz.wordextremist.databinding.ActivityLauncherBinding
import com.laurenz.wordextremist.ui.animation.AnimatedElement
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.widget.ImageView
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import java.util.UUID
import androidx.core.content.edit

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private val languageMap = mapOf("English" to "en", "Español" to "es", "Français" to "fr") // Name to Code
    private var selectedLanguageCode: String = "en" // Default

    private val PREFS_NAME = "WordExtremistPrefs"
    private val PREF_SELECTED_LANGUAGE_CODE = "selectedLanguageCode"

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
        var frameCount = 0 // For debugging
        override fun doFrame(frameTimeNanos: Long) {
            if (frameCount < 5 || frameCount % 60 == 0) { // Log first 5 frames, then every 60 frames
                Log.d("LauncherActivity_AnimLoop", "doFrame: Frame $frameCount. Updating elements. isDestroyed=$isDestroyed, isFinishing=$isFinishing")
            }
            frameCount++

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
        Log.d("LauncherActivity", "onCreate: Content view set") // ADDED LOG
        // Initialize AuthViewModel and CredentialManager/PlayGamesSd
        if (!USE_DEBUG_AUTH) {
            PlayGamesSdk.initialize(this) // Initialize Play Games SDK if not in debug auth mode
        }

        setupLanguageSpinner()
        loadLanguagePreference() // Load saved language and update spinner
        setupClickListeners()
        //observeViewModel()

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
            //if (authViewModel.authStatus.value == AuthPgsStatus.AUTHENTICATED) {
            startActivity(Intent(this, MatchmakingActivity::class.java))

        }

        binding.profileSection.setOnClickListener {

            Toast.makeText(this, "Edit Profile: Not yet implemented.", Toast.LENGTH_SHORT).show()
            // Intent(this, ProfileActivity::class.java).also { startActivity(it) }

        }

        binding.buttonViewLeaderboard.setOnClickListener {

            Toast.makeText(this, "View Leaderboard: Not yet implemented.", Toast.LENGTH_SHORT).show()
            // Intent(this, LeaderboardActivity::class.java).also { startActivity(it) }

        }

        binding.buttonTreasureChest.setOnClickListener {
            Toast.makeText(this, "Word Vault: Not yet implemented.", Toast.LENGTH_SHORT).show()
        }

        binding.buttonSignOut.setOnClickListener {
            // Handle sign out logic
            Toast.makeText(this, "Sign Out clicked.", Toast.LENGTH_SHORT).show();
        }

    }


}