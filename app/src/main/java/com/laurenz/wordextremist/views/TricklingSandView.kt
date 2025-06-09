// TricklingSandView.kt
package com.laurenz.wordextremist.views // Or your preferred package

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.laurenz.wordextremist.R // Make sure to import your R class
import com.laurenz.wordextremist.ui.animation.SandParticle
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sign

class TricklingSandView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val activeParticles = ArrayList<SandParticle>() // Particles currently falling/visible
    private val particlePool = ArrayList<SandParticle>()  // Pool of inactive/reusable particles
    private val random = Random()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG) // For particles
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)    // For the solid fill

    private var targetFillLevelNormalized: Float = 0f   // Set by MainActivity
    private var currentRenderedFillLevelNormalized: Float = 0f // Interpolated for drawing
    private var initialFillLevelForResume: Float = 0f
    private val fillInterpolationSpeed: Float = 0.8f // How quickly current catches up to target (0 to 1)

    // This will represent the speed at which the fill rises, aiming to complete in `gameTimerDurationMs`
    private var fillRisePerSecond: Float = 0f
    private var gameTimerDurationSeconds: Float = 30f // Default, will be set by MainActivity

    private var lastFrameTime: Long = 0
    private var isAnimating: Boolean = false

    // Sand colors from your theme
    private val sandColors = listOf(
        ContextCompat.getColor(context, R.color.sand_timer_top),
        ContextCompat.getColor(context, R.color.sand_timer_middle),
        ContextCompat.getColor(context, R.color.sand_timer_bottom)
    )
    private var sandFillShader: Shader? = null

    // Tunable parameters
    private val gravity = 300f // Pixels per second^2
    private val particlesPerSecond = 350
    private val maxTotalParticles  = 1500 // To avoid too many objects
    private val particleBaseRadius = 2.5f
    private val particleRadiusVariance = 1.0f
    private val horizontalSwayFactor = 15f

    init {
        Log.d("TricklingSandView", "View Initialized")
        // Pre-populate the pool a bit if desired, up to a certain portion of maxTotalParticles
        for (i in 0 until maxTotalParticles / 2) {
            particlePool.add(SandParticle(0f,0f, color = sandColors.first())) // Dummy initial values
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Create shader when size is known
        if (w > 0 && h > 0) {
            sandFillShader = LinearGradient(
                0f, h.toFloat(), 0f, 0f, // Fills from bottom to top
                intArrayOf(
                    ContextCompat.getColor(context, R.color.sand_timer_bottom), // Solid part bottom
                    ContextCompat.getColor(context, R.color.sand_timer_middle),
                    ContextCompat.getColor(context, R.color.sand_timer_top)  // Lighter towards top of fill
                ),
                floatArrayOf(0f, 0.6f, 1f), // Adjust stops for gradient appearance
                Shader.TileMode.CLAMP
            )
        } else {
            sandFillShader = null
        }
    }

    /**
     * Called by MainActivity to set the overall duration of the timer.
     * This helps the view calculate its own smooth fill rate.
     */
    fun setGameTimerDuration(durationSeconds: Float) {
        if (durationSeconds > 0) {
            this.gameTimerDurationSeconds = durationSeconds
            this.fillRisePerSecond = 1.0f / durationSeconds // Normalized rise per second
            Log.d("TricklingSandView", "Game Timer Duration set to: $durationSeconds s, Fill Rise/Sec: $fillRisePerSecond")
        } else {
            this.fillRisePerSecond = 1.0f / 30f; // Fallback if duration is invalid
        }
    }

    /**
     * MainActivity calls this on each of its timer ticks.
     * This value now primarily acts as a CAP for the internally smoothed fill level.
     * The internal fill will rise smoothly towards 1.0, but won't exceed this target.
     */
    fun setTargetFillLevelCap(normalizedLevelCap: Float) {
        this.targetFillLevelNormalized = normalizedLevelCap.coerceIn(0f, 1f)
        // If animation isn't running but should be, kick it off.
        // This is mostly for cases where the timer starts or if the view was reset.
        if (!isAnimating && targetFillLevelNormalized > 0f && isLaidOut) {
            Log.d("TricklingSandView", "setTargetFillLevelCap - Forcing animation start. Target: $targetFillLevelNormalized")
            startAnimationLoop()
        }
    }


    private fun spawnParticle() {
        val particle: SandParticle
        if (particlePool.isNotEmpty()) {
            particle = particlePool.removeAt(particlePool.size - 1) // Get from pool
            particle.isActive = true
        } else if (activeParticles.size < maxTotalParticles) { // Only create new if total is less than max
            particle = SandParticle(0f, 0f, color = sandColors.first()) // Create new
            particle.isActive = true
        } else {
            return // Max particles reached, cannot spawn more
        }

        // Initialize/Reset particle properties
        particle.x = random.nextFloat() * width.toFloat()
        particle.y = -10f - random.nextFloat() * 30f // Start above screen
        particle.yVelocity = 90f + random.nextFloat() * 90f // Slightly faster initial fall
        particle.xOffset = (random.nextFloat() - 0.5f) * horizontalSwayFactor * 2
        particle.radius = particleBaseRadius + (random.nextFloat() * particleRadiusVariance * 2) - particleRadiusVariance
        particle.color = sandColors[random.nextInt(sandColors.size)]

        activeParticles.add(particle)
    }


    private fun addNewParticles(deltaTimeSeconds: Float) {
        if (!isAnimating || currentRenderedFillLevelNormalized >= 0.995f) return // Stop adding if fill is almost complete

        val numNewParticlesToAttempt = (particlesPerSecond * deltaTimeSeconds).toInt()
        for (i in 0 until numNewParticlesToAttempt) {
            spawnParticle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Condition to stop drawing and the animation loop
        val shouldStopLoop = !isAnimating &&
                abs(targetFillLevelNormalized - currentRenderedFillLevelNormalized) < 0.001f &&
                activeParticles.isEmpty()

        if (shouldStopLoop) {
            lastFrameTime = 0L
            Log.d("TricklingSandView", "onDraw - Loop ending: animation stopped, fill reached, no particles.")
            return
        }

        if (width == 0 || height == 0) {
            if (isAnimating || activeParticles.isNotEmpty()) postInvalidateOnAnimation() // Keep trying if supposed to be active
            return
        }

        val now = System.currentTimeMillis()
        val deltaTimeSeconds = if (lastFrameTime == 0L) 0.016f else (now - lastFrameTime).toFloat() / 1000.0f
        lastFrameTime = now
        val cappedDeltaTime = min(deltaTimeSeconds, 0.033f) // Cap at ~30fps for physics step

        // 1. Smoothly increment currentRenderedFillLevelNormalized based on gameTimerDuration
        if (isAnimating && currentRenderedFillLevelNormalized < 1.0f) {
            currentRenderedFillLevelNormalized += fillRisePerSecond * cappedDeltaTime
        }
        // Ensure currentRendered does not exceed the target set by MainActivity (which reflects actual timer progress)
        // and also doesn't exceed 1.0
        currentRenderedFillLevelNormalized = min(currentRenderedFillLevelNormalized, targetFillLevelNormalized)
        currentRenderedFillLevelNormalized = currentRenderedFillLevelNormalized.coerceIn(0f, 1f)


        val currentRenderedFillLineY = height * (1f - currentRenderedFillLevelNormalized)

        // 2. Draw the solid "bulk" sand fill
        if (currentRenderedFillLevelNormalized > 0.001f) {
            fillPaint.shader = sandFillShader
            canvas.drawRect(0f, currentRenderedFillLineY, width.toFloat(), height.toFloat(), fillPaint)
        }

        // 3. Add new particles
        if (isAnimating) { // Add particles as long as the timer effect is supposed to be running
            addNewParticles(cappedDeltaTime)
        }

        // 4. Update and draw active particles
        val iterator = activeParticles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            // isActive check removed here, assuming all in activeParticles are active
            // Will be re-added if particle state becomes more complex

            p.yVelocity += gravity * cappedDeltaTime
            p.y += p.yVelocity * cappedDeltaTime
            p.x += p.xOffset * cappedDeltaTime

            if (p.x < -p.radius - 20 || p.x > width + p.radius + 20) { // Increased boundary for removal
                p.isActive = false; iterator.remove(); if (particlePool.size < maxTotalParticles) particlePool.add(p); continue
            }

            if (p.y + p.radius > currentRenderedFillLineY) {
                p.isActive = false; iterator.remove(); if (particlePool.size < maxTotalParticles) particlePool.add(p); continue
            }

            particlePaint.color = p.color
            canvas.drawCircle(p.x, p.y, p.radius, particlePaint)

            if (p.y > height + p.radius + 30f) { // Increased boundary for removal
                p.isActive = false; iterator.remove(); if (particlePool.size < maxTotalParticles) particlePool.add(p);
            }
        }
        //Log.d("TricklingSandView", "onDraw - Particles: ${activeParticles.size}, RenderFill: $currentRenderedFillLevelNormalized, TargetFill: $targetFillLevelNormalized")

        // 5. Continue animation loop
        if (isAnimating || abs(targetFillLevelNormalized - currentRenderedFillLevelNormalized) > 0.001f || activeParticles.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            Log.d("TricklingSandView", "onDraw - Animation loop naturally ending (final check).")
            lastFrameTime = 0L
        }
    }

    private fun startAnimationLoop() {
        if (!isAnimating) {
            isAnimating = true
            lastFrameTime = 0L // Critical to reset for accurate first deltaTime
            Log.d("TricklingSandView", "startAnimationLoop - Kicking off onDraw loop.")
            postInvalidateOnAnimation()
        }
    }

    fun startTimerEffect(totalDurationForFullTurnSeconds: Float, actualTimeLeftSeconds: Float) { // Accept duration
        Log.d("TricklingSandView", "startTimerEffect called. Duration: $totalDurationForFullTurnSeconds W:$width, H:$height")
        setGameTimerDuration(totalDurationForFullTurnSeconds) // Set the duration for internal calculation
        visibility = View.VISIBLE
        val timeElapsedSeconds = totalDurationForFullTurnSeconds - actualTimeLeftSeconds
        initialFillLevelForResume = if (totalDurationForFullTurnSeconds > 0) {
            (timeElapsedSeconds / totalDurationForFullTurnSeconds).coerceIn(0f, 1f)
        } else {
            0f
        }
        currentRenderedFillLevelNormalized = initialFillLevelForResume
        targetFillLevelNormalized = initialFillLevelForResume // Start target where current fill is

        Log.d("TricklingSandView", "InitialFillForResume: $initialFillLevelForResume, CurrentRenderedFill: $currentRenderedFillLevelNormalized")
        activeParticles.forEach { it.isActive = false }
        particlePool.addAll(activeParticles)
        activeParticles.clear()

        if (width == 0 || height == 0) {
            viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (width > 0 && height > 0) {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                        Log.d("TricklingSandView", "onGlobalLayout - Actually starting timer effect. W:$width, H:$height")
                        startAnimationLoop()
                    }
                }
            })
        } else {
            startAnimationLoop()
        }
    }

    fun stopTimerEffectAndFill() { // Called when timer finishes successfully
        Log.d("TricklingSandView", "stopTimerEffectAndFill called (timer finished)")
        targetFillLevelNormalized = 1.0f // Ensure target is full
        // isAnimating = false; // Let animation continue to fill up to target smoothly
        // The loop in onDraw will stop when currentRendered meets target and particles clear
        // No need to explicitly stop particle generation here, as currentRendered will reach 1.0
    }

    fun cancelTimerEffect() { // Called when timer is cancelled prematurely
        Log.d("TricklingSandView", "cancelTimerEffect called (timer cancelled)")
        isAnimating = false // Stop new particle generation immediately
        targetFillLevelNormalized = currentRenderedFillLevelNormalized // Stop fill where it is
        // Let existing particles fall and clear, or clear them abruptly:
        // activeParticles.clear(); particlePool.clear() // If abrupt clear is desired
        // invalidate()
    }


    fun resetAndHide() {
        Log.d("TricklingSandView", "resetAndHide called")
        isAnimating = false
        visibility = View.GONE
        targetFillLevelNormalized = 0f
        currentRenderedFillLevelNormalized = 0f
        initialFillLevelForResume = 0f
        activeParticles.forEach { it.isActive = false }
        particlePool.addAll(activeParticles)
        activeParticles.clear()
        lastFrameTime = 0L
        invalidate()
    }
}