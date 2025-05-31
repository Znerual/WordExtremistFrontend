// MorphingSymbolView.kt
package com.laurenz.wordextremist.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.laurenz.wordextremist.R
import kotlinx.coroutines.*

class MorphingSymbolView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL // Changed to FILL for solid text
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.primary)
        // textSize will be set in onSizeChanged or when needed
    }

    private var currentText = "W"
    private val symbols = listOf("W", "WO", "WOR", "WORD", "WORD?", "!", "...") // Added more symbols
    private var symbolIndex = 0

    private var textAlpha = 255 // For fade animations
    private var textScale = 1.0f // For pulse/scale animations

    private var morphAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var isMorphingActive = false

    // Calculated in onSizeChanged
    private var centerX = 0f
    private var baselineY = 0f
    private var baseTextSize = 0f

    init {
        // Set clickable to true if you want to trigger animations on touch (not used here)
        // isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        baseTextSize = h * 0.5f // Make text about 50% of view height
        textPaint.textSize = baseTextSize
        // Calculate baseline for centered text
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds("W", 0, 1, textBounds) // Get bounds for a typical char
        baselineY = (h / 2f) - textBounds.exactCenterY()
    }


    private fun createMorphAnimation(): ValueAnimator {
        // This animator will drive the sequence of symbol changes and fades
        val animator = ValueAnimator.ofInt(0, 100) // Dummy values, we use it for timing
        animator.duration = 2000L // Duration for one full cycle (fade out, change, fade in, hold)
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = AccelerateDecelerateInterpolator() // Smooth start/end

        var currentStage = 0 // 0: fade_out, 1: change_symbol_and_fade_in, 2: hold

        animator.addUpdateListener { animation ->
            // We don't use animation.animatedValue directly for symbol change logic
            // Instead, we manage stages within the listener based on time or animator state
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) {
                super.onAnimationRepeat(animation)
                if (!isMorphingActive) {
                    animation.cancel()
                    return
                }
                // Cycle through symbols
                symbolIndex = (symbolIndex + 1) % symbols.size
                currentText = symbols[symbolIndex]
                // Trigger a fade-in for the new symbol (or handle within a single animator)
                // For simplicity, we'll handle fade in/out using ObjectAnimators triggered by this
                // For a single ValueAnimator approach:
                // You'd need to manage alpha based on animator.animatedFraction within stages
                // This is simpler with chained ObjectAnimators, but let's try with one ValueAnimator cycle
            }
        })
        // A more robust way with a single ValueAnimator for the whole cycle:
        // This requires more complex state management within the listener.
        // Let's simplify: use chained ObjectAnimators or separate ValueAnimators for fade in/out
        // triggered in sequence.

        // Simplified approach: A ValueAnimator that just ticks, and we manage fades separately.
        // This is less ideal than property animators.
        // Let's use property animators for alpha and scale.

        // Better: Stop using this complicated ValueAnimator and use ObjectAnimator chains
        return animator // Placeholder, will replace
    }

    fun startMorphing() {
        if (morphAnimator?.isRunning == true || !isAttachedToWindow) return
        isMorphingActive = true
        visibility = View.VISIBLE
        animateNextSymbol()
    }

    private fun animateNextSymbol() {
        if (!isMorphingActive || !isAttachedToWindow) return

        // 1. Fade Out current symbol
        val fadeOut = ValueAnimator.ofInt(255, 0)
        fadeOut.duration = 400
        fadeOut.interpolator = DecelerateInterpolator()
        fadeOut.addUpdateListener {
            textAlpha = it.animatedValue as Int
            invalidate()
        }
        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!isMorphingActive) return
                // 2. Change symbol
                symbolIndex = (symbolIndex + 1) % symbols.size
                currentText = symbols[symbolIndex]

                // 3. Fade In new symbol
                val fadeIn = ValueAnimator.ofInt(0, 255)
                fadeIn.duration = 400
                fadeIn.interpolator = DecelerateInterpolator()
                fadeIn.addUpdateListener {
                    textAlpha = it.animatedValue as Int
                    invalidate()
                }
                fadeIn.addListener(object: AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isMorphingActive) return
                        // 4. Hold
                        morphAnimator = ValueAnimator.ofInt(0,1) // Dummy animator for delay
                        morphAnimator?.duration = 1000 // Hold duration
                        morphAnimator?.addListener(object: AnimatorListenerAdapter(){
                            override fun onAnimationEnd(animation: Animator) {
                                if (isMorphingActive) animateNextSymbol() // Loop
                            }
                        })
                        morphAnimator?.start()
                    }
                })
                fadeIn.start()
                morphAnimator = fadeIn // Track current main animator
            }
        })
        fadeOut.start()
        morphAnimator = fadeOut // Track current main animator
    }


    fun stopMorphing() {
        isMorphingActive = false
        morphAnimator?.cancel()
        morphAnimator = null
        pulseAnimator?.cancel() // Also cancel pulse if it was running
        pulseAnimator = null
        // Optionally fade out the current symbol smoothly
        if (textAlpha > 0) {
            ValueAnimator.ofInt(textAlpha, 0).apply {
                duration = 200
                addUpdateListener {
                    textAlpha = it.animatedValue as Int
                    invalidate()
                }
                addListener(object: AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.INVISIBLE // Hide when fully faded out
                    }
                })
                start()
            }
        } else {
            visibility = View.INVISIBLE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        textPaint.alpha = textAlpha
        // Apply scale if a pulse animation is running or if you want a general scale
        canvas.save()
        canvas.scale(textScale, textScale, centerX, baselineY + textPaint.fontMetrics.ascent / 2) // Scale around text center
        canvas.drawText(currentText, centerX, baselineY, textPaint)
        canvas.restore()
    }

    fun playMatchFoundPulse() {
        // Ensure previous animators are fully stopped and listeners cleared if necessary
        // to prevent interference.
        morphAnimator?.cancel() // Stop regular morphing
        morphAnimator = null
        pulseAnimator?.cancel() // Cancel any existing pulse
        pulseAnimator = null

        visibility = View.VISIBLE
        isMorphingActive = false

        // currentText = "VS"
        textAlpha = 255
        textScale = 1.0f // Start at normal scale

        val originalColor = ContextCompat.getColor(context, R.color.primary)
        val highlightColor = ContextCompat.getColor(context, R.color.secondary_variant) // Using obvious debug color
        // val highlightColor = ContextCompat.getColor(context, R.color.accent_color)

        val singleLegDuration = 130L  // Duration for 0 -> 1 (or 1 -> 0)

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f)
        pulseAnimator?.duration = singleLegDuration
        pulseAnimator?.repeatCount = 3
        pulseAnimator?.repeatMode = ValueAnimator.REVERSE
        pulseAnimator?.interpolator = AccelerateDecelerateInterpolator()

        Log.d("MorphingPulse", "Setup Pulse: Duration for 0->1 is ${singleLegDuration}ms. Total expected: ${singleLegDuration * 2}ms")

        val startScale = 1.0f
        val peakScale = 2.0f

        pulseAnimator?.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float // Goes 0->1, then 1->0

            textScale = startScale + (fraction * (peakScale - startScale))
            textPaint.color = ArgbEvaluator().evaluate(fraction, originalColor, highlightColor) as Int

            // Log more info about the animator's state
            Log.d("MorphingPulse", "Update: Fraction: $fraction, Scale: $textScale, Color: ${String.format("#%06X", (0xFFFFFF and textPaint.color))}, CurrentPlayTime: ${animation.currentPlayTime}, IsRunning: ${animation.isRunning}")
            invalidate()
        }

        pulseAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                Log.d("MorphingPulse", "Pulse Animation START. Animator: $animation")
            }

            override fun onAnimationCancel(animation: Animator) {
                super.onAnimationCancel(animation)
                Log.w("MorphingPulse", "Pulse Animation CANCELLED. Animator: $animation")
                // Reset to a known good state if cancelled
                textScale = 1.0f
                textPaint.color = originalColor
                textPaint.alpha = 255
                invalidate()
            }

            override fun onAnimationEnd(animation: Animator) {
                // This 'endedNaturally' check is important.
                // If currentPlayTime is less than total expected duration, it was likely cancelled.
                val totalExpectedDuration = singleLegDuration * ( (animation as ValueAnimator).repeatCount + 1)
                val endedNaturally = animation.currentPlayTime >= totalExpectedDuration - 16 // Allow small margin for timing

                Log.d("MorphingPulse", "Pulse Animation END. CurrentPlayTime: ${animation.currentPlayTime}, TotalExpected: $totalExpectedDuration, EndedNaturally: $endedNaturally. Animator: $animation")

                // Always reset to base state on end, regardless of natural finish or cancellation (covered by onAnimationCancel too)
                textScale = 1.0f
                textPaint.color = originalColor
                textPaint.alpha = 255
                invalidate()
            }
        })
        pulseAnimator?.start()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this) {
            if (visibility == VISIBLE && !isMorphingActive && morphAnimator?.isRunning != true) {
                // If view becomes visible and morphing should be active but isn't
                // (e.g. after being GONE then VISIBLE again), restart it.
                // This is more relevant if MatchmakingActivity hides/shows it.
                // For now, startMorphing is called from onAttachedToWindow or explicitly.
                // if (shouldAutoStartMorphing) startMorphing()
            } else if (visibility != VISIBLE) {
                stopMorphing() // Stop animation if view is not visible
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Automatically start morphing if it's supposed to be active
        // Consider a flag if you don't always want it to auto-start
        if (visibility == VISIBLE) { // Only start if initially visible
            startMorphing()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMorphing()
    }
}