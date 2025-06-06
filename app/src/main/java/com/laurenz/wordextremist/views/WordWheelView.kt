// WordWheelView.kt
package com.laurenz.wordextremist.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import android.view.ScaleGestureDetector
import com.laurenz.wordextremist.R
import kotlin.math.*

class WordWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnWordSelectedListener {
        fun onWordSelected(word: String, sentence: String?, prompt: String?)
    }

    data class WordInfo(
        val text: String,
        val originalSentence: String?,
        val originalPrompt: String?,
        val creativityScore: Int = 0,

        // Current animated properties for drawing
        var currentRadialOffset: Float = 0f,
        var currentAngularOffset: Float = 0f,

        // Target properties calculated by layout algorithm
        var targetRadialOffset: Float = 0f,
        var targetAngularOffset: Float = 0f,

        var angleOnWheel: Float = 0f, // Fixed base angle on the wheel (0-360)
        var textWidth: Float = 0f,
        var textHeight: Float = 0f,

        // For hit detection and internal calcs
        var screenX: Float = 0f,     // Final X on canvas (after adjustments and transformations)
        var screenY: Float = 0f,     // Final Y on canvas
        val visualRect: RectF = RectF() // Bounding box on screen for hit detection
    )

    private val wordsList = mutableListOf<WordInfo>()

    private lateinit var backgroundPaint: Paint
    private lateinit var textPaint: Paint // Will be our base paint
    private lateinit var textMeasurePaint: Paint
    private lateinit var highlightPaint: Paint // For the selected word


    private var viewCenterX = 0f // Renamed from centerX for clarity
    private var viewCenterY = 0f // Renamed from centerY for clarity
    private var baseRadius = 0f  // The radius at scaleFactor = 1.0f
    private var currentEffectiveRadius = 0f // baseRadius * scaleFactor

    private val maxRadialScatterRatio = 0.45f // Max 8% of baseRadius inwards or outwards
    private val maxAngularScatterDeg = 1.0f   // Max 3 degrees angular offset

    private val random = java.util.Random() // For generating scatter

    private var currentRotationAngle = 0f
    private var manualRotationAngle = 0f
    private val autoRotationSpeed = 5f
    private var selectedWordIndex: Int = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingRotation = false // More specific name for rotational drag
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var flingAnimator: ValueAnimator? = null
    var wordSelectedListener: OnWordSelectedListener? = null
    private var isAutoRotating = false

    // --- Pinch Zoom Properties ---
    private var scaleFactor = 1.0f
    private val minScaleFactor = 0.5f
    private val maxScaleFactor = 2.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isScaling = false
    private var scaleFocusX = 0f // Focus point of the scale gesture
    private var scaleFocusY = 0f

    // --- Pan Properties (when zoomed in) ---
    private var panOffsetX = 0f
    private var panOffsetY = 0f
    private var isPanned = false // To indicate if any panning has occurred

    // --- Animation of the force graph creation
    private var layoutAnimator: ValueAnimator? = null
    private val layoutAnimationDuration = 1500L // Duration for words to settle

    init {
        isClickable = true
        isFocusable = true
        initPaints()
        setupScaleGestureDetector()
    }

    private fun initPaints() {
        val poppinsTypeface = try {
            ResourcesCompat.getFont(context, R.font.poppins) ?: Typeface.DEFAULT
        } catch (e: Exception) {
            Log.e("WordWheelView", "Poppins font not found, using default.", e)
            Typeface.DEFAULT
        }

        backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.word_wheel_background)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL) // Soft edge for background
        }

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = poppinsTypeface
            textAlign = Paint.Align.CENTER
            // Text size will be set dynamically in onSizeChanged
        }

        textMeasurePaint = Paint(textPaint)

        highlightPaint = Paint(textPaint).apply {
            color = ContextCompat.getColor(context, R.color.word_wheel_text_highlight)
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2f
            // Text size and other properties will be set in onDraw
        }
    }

    private fun setupScaleGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                stopAutoRotation() // Stop auto rotation during scale
                stopFling()
                scaleFocusX = detector.focusX
                scaleFocusY = detector.focusY
                Log.d("WordWheelView", "Scale Begin. Focus: ($scaleFocusX, $scaleFocusY)")
                parent.requestDisallowInterceptTouchEvent(true)
                return true // We're handling this gesture
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previousScaleFactor = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minScaleFactor, maxScaleFactor)

                if (abs(scaleFactor - previousScaleFactor) > 0.001f) {

                    val focusRelativeToCenterX = scaleFocusX - viewCenterX
                    val focusRelativeToCenterY = scaleFocusY - viewCenterY

                    // Pan adjustment to keep focus point fixed:
                    val worldFocusXBefore = (focusRelativeToCenterX - panOffsetX) / previousScaleFactor
                    val worldFocusYBefore = (focusRelativeToCenterY - panOffsetY) / previousScaleFactor
                    panOffsetX = focusRelativeToCenterX - (worldFocusXBefore * scaleFactor)
                    panOffsetY = focusRelativeToCenterY - (worldFocusYBefore * scaleFactor)

                    currentEffectiveRadius = baseRadius * scaleFactor // This is important for drawing
                    constrainPanOffsets()

                    // Option 1: Just invalidate and let onDraw handle the new scale.
                    // The existing targetRadialOffsets will be applied to the new baseRadius * scaleFactor.
                    // This is simpler and often sufficient if the overlap logic is robust enough
                    // or if minor temporary overlaps during scaling are acceptable.
                    // The text will scale with the canvas.

                    // Option 2: If text size is NOT scaled by canvas but set dynamically based on scaleFactor
                    // to keep apparent size constant, then text metrics change, and overlap re-eval is needed.
                    // if (isTextSizeDynamicallyAdjustedForConstantVisualSize) {
                    //     wordsList.forEach { wordInfo ->
                    //         wordInfo.textWidth = textMeasurePaint.measureText(wordInfo.text) // (re-measure with new text size)
                    //         val metrics = textMeasurePaint.fontMetrics
                    //         wordInfo.textHeight = metrics.descent - metrics.ascent
                    //     }
                    //     // Re-run the overlap adjustment because word dimensions changed relative to each other
                    //     adjustWordTargetPositionsForOverlap()
                    //     // Animate to these new target offsets (might be jittery during active pinch)
                    //     animateWordsToTargetOffsets()
                    // }


                    // For the current implementation where text scales with the canvas:
                    // The relative angular positions (angleOnWheel) don't change.
                    // The targetRadialOffset (which is a ratio applied to baseRadius) also doesn't need to change inherently *because* of scale alone.
                    // The *visual* overlaps will change, but the layout *targets* can remain.
                    // So, we don't need to call calculateInitialWordAngles() or adjustWordTargetPositionsForOverlap() here.
                    // The onDraw method will use the new scaleFactor with existing offsets.

                    updateSelectedWord() // Selection might change due to altered visual layout
                    invalidate()
                    Log.d("WordWheelView", "Scaling: Factor=$scaleFactor, Focus: (${detector.focusX}, ${detector.focusY}), Pan: ($panOffsetX, $panOffsetY)")
                }
                return true
            }


            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                Log.d("WordWheelView", "Scale End. Factor=$scaleFactor")
                if (!isDraggingRotation) { // Only restart if not also in a rotational drag
                    startAutoRotation()
                }
                // parent.requestDisallowInterceptTouchEvent(false) // Let parent intercept again if not dragging
            }
        })
    }




    fun setWords(words: List<Triple<String, Int, Pair<String?, String?>>>) {
        wordsList.clear()
        if (width == 0 || height == 0) {
            // Handle deferral if needed, or rely on onSizeChanged to call performLayoutCalculations
        }

        words.forEach { item ->
            val wordText = item.first
            val creativity = item.second
            val sentencePromptPair = item.third

            wordsList.add(
                WordInfo(
                    text = wordText,
                    originalSentence = sentencePromptPair.first,
                    originalPrompt = sentencePromptPair.second,
                    creativityScore = creativity, // Store creativity score
                )
            )
        }

        // Reset view state
        currentRotationAngle = 0f
        manualRotationAngle = 0f
        scaleFactor = 1.0f
        // baseRadius and currentEffectiveRadius will be set/updated in onSizeChanged
        panOffsetX = 0f
        panOffsetY = 0f
        isPanned = false
        selectedWordIndex = -1

        stopAllMotionAndAnimations()

        if (isAttachedToWindow && width > 0 && height > 0) {
            currentEffectiveRadius = baseRadius * scaleFactor

            updateTextMetrics()
            performLayoutCalculationsAndAnimate() // New combined function
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewCenterX = w / 2f
        viewCenterY = h / 2f
        baseRadius = min(w, h) * 0.35f
        currentEffectiveRadius = baseRadius * scaleFactor // Apply current scale


        val gradientRadius = baseRadius * 1.2f // Make the gradient slightly larger than the word area
        if (gradientRadius > 0) {
            backgroundPaint.shader = RadialGradient(
                viewCenterX,
                viewCenterY,
                gradientRadius,
                intArrayOf(
                    ContextCompat.getColor(context, R.color.word_wheel_background), // Opaque center color
                    ContextCompat.getColor(context, R.color.word_wheel_background), // Opaque for most of the radius
                    Color.TRANSPARENT                                               // Fade to transparent at the edge
                ),
                floatArrayOf(0f, 0.8f, 1.0f), // Defines the color stops for the gradient
                Shader.TileMode.CLAMP
            )
        }



        updateTextMetrics()


        if (wordsList.isNotEmpty()) {
            performLayoutCalculationsAndAnimate() // Re-layout and animate if size changes
        }
        constrainPanOffsets()
    }

    fun triggerWordReshuffleAnimation() {
        Log.d("WordWheelView", "Triggering word reshuffle animation.")
        if (isAttachedToWindow && width > 0 && height > 0 && wordsList.isNotEmpty()) {
            stopAllMotionAndAnimations()
            performLayoutCalculationsAndAnimate()
        }
    }

    private fun updateTextMetrics() {
        if (baseRadius == 0f) return // Cannot calculate without a radius

        // 1. Determine a base text size. Make it generous but cap it.
        var newTextSize = (baseRadius * 0.15f).coerceAtMost(60f)

        // 2. If there are many words, reduce the text size to avoid clutter.
        val wordCountThreshold = 15
        if (wordsList.size > wordCountThreshold) {
            // Create a scaling factor that shrinks the text as word count increases.
            // CoerceAtLeast ensures the text never becomes unreadably small.
            val scaleDownFactor = (wordCountThreshold.toFloat() / wordsList.size.toFloat()).coerceAtLeast(0.7f)
            newTextSize *= scaleDownFactor
        }

        Log.d("WordWheelView", "Updating text metrics. Word count: ${wordsList.size}, New text size: $newTextSize")

        // 3. Apply the new size to our paint objects.
        textPaint.textSize = newTextSize
        textMeasurePaint.textSize = newTextSize
        // highlightPaint's size is set dynamically in onDraw, so it will adapt automatically.

        // 4. IMPORTANT: Recalculate width/height for every word, as these are now stale.
        wordsList.forEach { wordInfo ->
            wordInfo.textWidth = textMeasurePaint.measureText(wordInfo.text)
            val metrics = textMeasurePaint.fontMetrics
            wordInfo.textHeight = metrics.descent - metrics.ascent
        }
    }

    private fun performLayoutCalculationsAndAnimate() {
        if (wordsList.isEmpty() || baseRadius == 0f) return

        calculateInitialWordAngles() // Sets base angleOnWheel and resets targetRadialOffset
        // Calculate target positions through iterative adjustment
        adjustWordTargetPositionsForOverlap()

        // Animate words from their current offsets to their new target offsets
        animateWordsToTargetOffsets()

        updateSelectedWord() // Update based on initial target for immediate highlight
        // Invalidate will be called by the animator
    }

    // Renamed and modified to update TARGETS
    private fun adjustWordTargetPositionsForOverlap(iterations: Int = 12, pushBase: Float = 4f) {
        if (wordsList.size < 2) return

        val tempRect1 = RectF()
        val tempRect2 = RectF()

        // Start with targets at a slight initial random offset for more dynamic settling
        wordsList.forEach {
            it.targetRadialOffset = (random.nextFloat() * 0.2f - 0.1f) * maxRadialScatterRatio
        }


        for (iter in 0 until iterations) {
            var adjustmentsMade = false
            // Create a list of indices and shuffle it to process words in a somewhat random order
            // This can help break out of simple oscillation patterns.
            val processingOrder = wordsList.indices.shuffled(random)

            for (i in processingOrder) {
                val word1 = wordsList[i]
                // Calculate visual rect based on its CURRENT TARGET position for overlap check
                calculateWordVisualRect(word1, word1.targetRadialOffset, 0f, tempRect1) // totalWheelRotation is 0 for this static layout phase

                for (k in processingOrder) {
                    if (i == k) continue
                    val word2 = wordsList[k]
                    calculateWordVisualRect(word2, word2.targetRadialOffset, 0f, tempRect2)

                    if (RectF.intersects(tempRect1, tempRect2)) {
                        adjustmentsMade = true
                        // More robust push: move both away from their midpoint along the radial line
                        val overlapMidX = (tempRect1.centerX() + tempRect2.centerX()) / 2f
                        val overlapMidY = (tempRect1.centerY() + tempRect2.centerY()) / 2f

                        // Vector from view center to word1's current target center
                        val w1Rad = Math.toRadians(word1.angleOnWheel.toDouble())
                        val w1TargetX = viewCenterX + baseRadius * (1f + word1.targetRadialOffset) * cos(w1Rad).toFloat()
                        val w1TargetY = viewCenterY + baseRadius * (1f + word1.targetRadialOffset) * sin(w1Rad).toFloat()

                        // Vector from view center to word2's current target center
                        val w2Rad = Math.toRadians(word2.angleOnWheel.toDouble())
                        val w2TargetX = viewCenterX + baseRadius * (1f + word2.targetRadialOffset) * cos(w2Rad).toFloat()
                        val w2TargetY = viewCenterY + baseRadius * (1f + word2.targetRadialOffset) * sin(w2Rad).toFloat()

                        // Push word1 radially
                        val dx1 = w1TargetX - viewCenterX
                        val dy1 = w1TargetY - viewCenterY
                        val dist1 = sqrt(dx1*dx1 + dy1*dy1)
                        if (dist1 > 0) { // Avoid division by zero
                            word1.targetRadialOffset += (pushBase / baseRadius) * (if (word1.targetRadialOffset > word2.targetRadialOffset) 1f else -0.8f) * (iter + 1) / iterations.toFloat() // Stronger push initially
                        }

                        // Push word2 radially
                        val dx2 = w2TargetX - viewCenterX
                        val dy2 = w2TargetY - viewCenterY
                        val dist2 = sqrt(dx2*dx2 + dy2*dy2)
                        if (dist2 > 0) {
                            word2.targetRadialOffset += (pushBase / baseRadius) * (if (word2.targetRadialOffset > word1.targetRadialOffset) 1f else -0.8f) * (iter + 1) / iterations.toFloat()
                        }

                        word1.targetRadialOffset = word1.targetRadialOffset.coerceIn(-maxRadialScatterRatio, maxRadialScatterRatio)
                        word2.targetRadialOffset = word2.targetRadialOffset.coerceIn(-maxRadialScatterRatio, maxRadialScatterRatio)

                        // Recalculate rect1 for next comparison in inner loop
                        calculateWordVisualRect(word1, word1.targetRadialOffset, 0f, tempRect1)
                    }
                }
            }
            if (!adjustmentsMade && iter > 1) break // Give it at least one full pass
        }
    }

    private fun animateWordsToTargetOffsets() {
        layoutAnimator?.cancel() // Cancel previous layout animation

        // Create a single animator that animates a fraction from 0 to 1
        layoutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = layoutAnimationDuration
            interpolator = DecelerateInterpolator(1.5f)

            // Store starting offsets at the beginning of the animation
            val startOffsets = wordsList.map { it.currentRadialOffset to it.currentAngularOffset }

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                wordsList.forEachIndexed { index, wordInfo ->
                    val (startRadial, startAngular) = startOffsets[index]
                    wordInfo.currentRadialOffset = startRadial + (wordInfo.targetRadialOffset - startRadial) * fraction
                    // wordInfo.currentAngularOffset = startAngular + (wordInfo.targetAngularOffset - startAngular) * fraction // If animating angular
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Ensure current offsets are exactly the target offsets
                    wordsList.forEach {
                        it.currentRadialOffset = it.targetRadialOffset
                        it.currentAngularOffset = it.targetAngularOffset
                    }
                    invalidate()
                    Log.d("WordWheelView", "Layout animation finished. Starting auto-rotation.")
                    startAutoRotation() // Start auto-rotation after words settle
                    layoutAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    Log.d("WordWheelView", "Layout animation cancelled.")
                    // Potentially snap to target or leave as is
                    wordsList.forEach {
                        it.currentRadialOffset = it.targetRadialOffset
                        it.currentAngularOffset = it.targetAngularOffset
                    }
                    invalidate()
                    startAutoRotation()
                    layoutAnimator = null
                }
            })
        }
        layoutAnimator?.start()
    }


    // Helper to calculate visual rect for overlap check (uses TARGET offsets, in wheel's local system)
    private fun calculateWordVisualRect(
        wordInfo: WordInfo,
        radialOffsetToUse: Float, // Pass the offset to use (current or target)
        totalWheelRotation: Float, // Pass 0 if checking static layout
        outRect: RectF
    ) {
        val displayAngle = (wordInfo.angleOnWheel + totalWheelRotation + 360f) % 360f
        val effectiveRadius = baseRadius * (1f + radialOffsetToUse)

        val rad = Math.toRadians(displayAngle.toDouble())
        val wordCanvasCenterX = viewCenterX + (effectiveRadius * cos(rad)).toFloat()
        val wordCanvasCenterY = viewCenterY + (effectiveRadius * sin(rad)).toFloat()

        val textHalfWidth = wordInfo.textWidth / 2f
        val textHalfHeight = wordInfo.textHeight / 2f

        val localCorners = floatArrayOf(
            -textHalfWidth, -textHalfHeight, textHalfWidth, -textHalfHeight,
            textHalfWidth, textHalfHeight, -textHalfWidth, textHalfHeight
        )
        val matrix = Matrix()
        matrix.setRotate(displayAngle + 90f) // Text's own rotation
        matrix.postTranslate(wordCanvasCenterX, wordCanvasCenterY)

        val transformedCorners = FloatArray(8)
        matrix.mapPoints(transformedCorners, localCorners)

        outRect.set(
            transformedCorners.filterIndexed { i, _ -> i % 2 == 0 }.minOrNull() ?: 0f,
            transformedCorners.filterIndexed { i, _ -> i % 2 != 0 }.minOrNull() ?: 0f,
            transformedCorners.filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull() ?: 0f,
            transformedCorners.filterIndexed { i, _ -> i % 2 != 0 }.maxOrNull() ?: 0f
        )
    }

    private fun calculateInitialWordAngles() {
        if (wordsList.isEmpty()) return // Nothing to calculate

        val angleStep = 360f / wordsList.size
        wordsList.forEachIndexed { index, wordInfo ->
            wordInfo.angleOnWheel = index * angleStep
            // Reset target radial offset. The overlap adjustment will then modify this.
            // The currentRadialOffset (used for animation start) retains its value
            // from before this calculation, allowing animation from previous state.
            wordInfo.targetRadialOffset = 0f
            // If you were also animating angular scatter, you'd reset targetAngularOffset here:
            // wordInfo.targetAngularOffset = 0f
        }
    }

    private fun calculateWordVisualRectOnScreen(
        wordInfo: WordInfo,
        finalDisplayAngle: Float, // The angle the word is drawn at relative to wheel center (includes totalRotation + word's own angular offsets)
        finalEffectiveRadius: Float, // The radius the word is drawn at (includes word's own radial offsets)
        outRect: RectF,
        viewCanvasMatrix: Matrix, // The canvas matrix from onDraw (includes pan & scale)
        wheelViewCenterX: Float, // Original center of the wheel view (before pan/scale)
        wheelViewCenterY: Float,
        wheelBaseRadius: Float // Original base radius of the wheel
    ) {
        val textHalfWidth = wordInfo.textWidth / 2f
        val textHalfHeight = wordInfo.textHeight / 2f

        val localCorners = floatArrayOf(
            -textHalfWidth, -textHalfHeight, textHalfWidth, -textHalfHeight,
            textHalfWidth, textHalfHeight, -textHalfWidth, textHalfHeight
        )

        // Matrix for this word's transformation relative to the wheel's center (unpanned, unscaled)
        val wordLocalMatrix = Matrix()
        // 1. Rotate the text shape itself
        wordLocalMatrix.setRotate(finalDisplayAngle + 90f) // Text orientation
        // 2. Translate the rotated shape to its position on the wheel
        val rad = Math.toRadians(finalDisplayAngle.toDouble())
        wordLocalMatrix.postTranslate(
            wheelViewCenterX + (finalEffectiveRadius * cos(rad)).toFloat(),
            wheelViewCenterY + (finalEffectiveRadius * sin(rad)).toFloat()
        )

        // 3. Now apply the overall view's canvas matrix (which includes pan and scale)
        val finalMatrix = Matrix(viewCanvasMatrix) // Start with a copy of the canvas's matrix
        finalMatrix.preConcat(wordLocalMatrix) // Apply word's local transform *before* view's transform
        // Order matters: local -> then view's pan/scale
        // Or, simpler: transform local corners by wordLocalMatrix, then transform those by viewCanvasMatrix

        // Simpler to map:
        // Transform local corners by word's rotation FIRST
        val rotatedLocalCorners = FloatArray(8)
        Matrix().apply { setRotate(finalDisplayAngle + 90f) }.mapPoints(rotatedLocalCorners, localCorners)

        // Translate these rotated corners to the wheel position (still in wheel's unscaled space)
        val wheelPosRad = Math.toRadians(finalDisplayAngle.toDouble()) // Angle of word on wheel
        val wheelPosX = wheelViewCenterX + (finalEffectiveRadius * cos(wheelPosRad)).toFloat()
        val wheelPosY = wheelViewCenterY + (finalEffectiveRadius * sin(wheelPosRad)).toFloat()

        val cornersOnWheel = FloatArray(8)
        for(i in rotatedLocalCorners.indices step 2) {
            cornersOnWheel[i] = rotatedLocalCorners[i] + wheelPosX
            cornersOnWheel[i+1] = rotatedLocalCorners[i+1] + wheelPosY
        }

        // Now transform these wheel-space corners by the main canvas matrix (pan+scale)
        val screenCorners = FloatArray(8)
        viewCanvasMatrix.mapPoints(screenCorners, cornersOnWheel)


        outRect.set(
            screenCorners.filterIndexed { i, _ -> i % 2 == 0 }.minOrNull() ?: 0f,
            screenCorners.filterIndexed { i, _ -> i % 2 != 0 }.minOrNull() ?: 0f,
            screenCorners.filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull() ?: 0f,
            screenCorners.filterIndexed { i, _ -> i % 2 != 0 }.maxOrNull() ?: 0f
        )
    }


    private fun startAutoRotation() {
        if (isAutoRotating || flingAnimator?.isRunning == true || isScaling || isDraggingRotation) return
        Log.d("WordWheelView", "Starting Auto Rotation")
        isAutoRotating = true
        postInvalidateOnAnimation()
    }

    private fun stopAutoRotation() {
        if (!isAutoRotating) return
        Log.d("WordWheelView", "Stopping Auto Rotation")
        isAutoRotating = false
    }
    private fun stopFling() {
        if (flingAnimator?.isRunning == true) {
            Log.d("WordWheelView", "Stopping Fling Animation")
            flingAnimator?.cancel()
        }
        flingAnimator = null
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (wordsList.isEmpty() || width == 0 || height == 0) return

        // Auto-rotation logic (ensure it doesn't run during layout animation)
        if (isAutoRotating && !isDraggingRotation && !isScaling && flingAnimator?.isRunning != true && layoutAnimator?.isRunning != true) {
            val deltaTime = 16L // Approximate frame time
            currentRotationAngle = (currentRotationAngle + (autoRotationSpeed * deltaTime / 1000f) + 360f) % 360f
            if (isAttachedToWindow) {
                postInvalidateOnAnimation()
            }
        }

        val totalRotation = (currentRotationAngle + manualRotationAngle + 360f) % 360f

        // --- Apply Pan and Scale to Canvas ---
        canvas.save()
        canvas.translate(panOffsetX, panOffsetY)
        canvas.scale(scaleFactor, scaleFactor, viewCenterX, viewCenterY)

        val gradientRadius = baseRadius * 1.2f
        canvas.drawCircle(viewCenterX, viewCenterY, gradientRadius, backgroundPaint)

        // This matrix now contains the pan and scale transformations
        val wheelWorldMatrix = Matrix(canvas.matrix)

        wordsList.forEachIndexed { index, wordInfo ->
            val displayAngle = (wordInfo.angleOnWheel + wordInfo.currentAngularOffset + totalRotation + 360f) % 360f
            val effectiveRadius = baseRadius * (1f + wordInfo.currentRadialOffset) // Use current animated offset

            val rad = Math.toRadians(displayAngle.toDouble())
            // Position of the word's center on the (unscaled, unpanned) wheel conceptual circle
            val wheelLocalWordCenterX = viewCenterX + (effectiveRadius * cos(rad)).toFloat()
            val wheelLocalWordCenterY = viewCenterY + (effectiveRadius * sin(rad)).toFloat()

            canvas.save()
            // Transform for this specific word: translate to its position on the wheel, then rotate the text
            canvas.translate(wheelLocalWordCenterX, wheelLocalWordCenterY)
            canvas.rotate(displayAngle + 90f) // Rotate canvas for text orientation

            val currentPaint: Paint
            if (index == selectedWordIndex) {
                // For selected word, use the special highlight paint
                highlightPaint.textSize = textPaint.textSize * 1.15f // Make it bigger
                currentPaint = highlightPaint
            } else {
                // For unselected words, set color based on creativity
                textPaint.color = getCreativityColor(wordInfo.creativityScore)
                textPaint.textSize = textMeasurePaint.textSize // Reset to base size
                currentPaint = textPaint
            }


            val textMetrics = currentPaint.fontMetrics
            val textYOffset = -(textMetrics.ascent + textMetrics.descent) / 2f // More accurate vertical centering
            canvas.drawText(wordInfo.text, 0f, textYOffset, currentPaint)
            canvas.restore() // Restore from this word's translate/rotate

            // --- Calculate visualRect for hit detection ---
            // 1. Define the local bounding box of the text (unrotated, around 0,0)
            val textHalfWidth = wordInfo.textWidth / 2f
            val textHalfHeight = wordInfo.textHeight / 2f
            val localTextRect = RectF(-textHalfWidth, -textHalfHeight, textHalfWidth, textHalfHeight)

            val wordMatrixOnWheel = Matrix()
            wordMatrixOnWheel.setRotate(displayAngle + 90f) // Text's orientation
            wordMatrixOnWheel.postTranslate(wheelLocalWordCenterX, wheelLocalWordCenterY) // Position on wheel

            // 3. Now, apply the overall wheel's world transformation (pan & scale)
            //    to this wheel-local matrix.
            val finalScreenMatrix = Matrix(wheelWorldMatrix) // Start with wheel's pan & scale matrix
            finalScreenMatrix.preConcat(wordMatrixOnWheel) // Apply word's transform *within* the wheel's world
            finalScreenMatrix.mapRect(wordInfo.visualRect, localTextRect)
            // Order: Word's local -> Word's on-wheel -> Wheel's world (pan/scale)

            // Actually, it's simpler: transform the local rect by wordMatrixOnWheel to get its bounds
            // in the wheel's unscaled/unpanned coordinate system, then transform *that rect*
            // by the wheelWorldMatrix. OR, transform the local rect by the combined matrix.

//            val combinedMatrixForWord = Matrix()
//            // Start with the word's orientation and position relative to the wheel's center
//            combinedMatrixForWord.setRotate(displayAngle + 90f) // Rotate text
//            combinedMatrixForWord.postTranslate(wheelLocalWordCenterX, wheelLocalWordCenterY) // Position on wheel
//
//            // Then, apply the overall canvas transformations (pan and scale) that were applied to the wheel
//            combinedMatrixForWord.postConcat(wheelWorldMatrix) // This maps from text local (0,0) to screen
//
//            // Transform the localTextRect to get the screenRect
//            combinedMatrixForWord.mapRect(wordInfo.visualRect, localTextRect)

            // Store the center of this screen rectangle for simple proximity fallback if needed
            wordInfo.screenX = wordInfo.visualRect.centerX()
            wordInfo.screenY = wordInfo.visualRect.centerY()
        }
        canvas.restore() // Restore from global pan and scale
    }

    private fun getCreativityColor(score: Int): Int {
        return ContextCompat.getColor(
            context, when {
                score >= 5 -> R.color.word_creativity_5
                score == 4 -> R.color.word_creativity_4
                score == 3 -> R.color.word_creativity_3
                score == 2 -> R.color.word_creativity_2
                else -> R.color.word_wheel_text_default // Default color for scores 0-1
            }
        )
    }

    private fun getHighlightCreativityColor(score: Int, baseColor: Int): Int {
        // For simplicity, let's use a fixed highlight color, or a brighter version of base.
        // Example: if base is dark, make highlight lighter. If base is light, make highlight darker/more vibrant.
        // For now, using a generic bright highlight for selected, but you can customize this.
        return when {
            score >= 4 -> ContextCompat.getColor(context, R.color.word_wheel_text_highlight_creative_5) // e.g. Bright Yellow
            score >= 2 -> ContextCompat.getColor(context, R.color.word_wheel_text_highlight) // Default highlight
            else -> ContextCompat.getColor(context, R.color.word_wheel_text_highlight_creative_0) // e.g. Light Blue
        }
        // A more advanced approach would be to calculate a lighter/brighter shade of the baseColor.
        // return highlightPaint.color // Or keep the standard highlight color for all selected words
    }

    private fun stopAllMotionAndAnimations() {
        Log.d("WordWheelView", "Stopping all motion and animations.")
        stopAutoRotation()
        stopFling()
        layoutAnimator?.cancel() // Cancel ongoing layout animation
        layoutAnimator = null
        // isScaling and isDraggingRotation will be reset by touch events
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (wordsList.isEmpty()) return super.onTouchEvent(event)

        // Allow taps even if layoutAnimator is running, but not drag/fling
        if (layoutAnimator?.isRunning == true && event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_DOWN) {
            Log.d("WordWheelView", "Touch event (move/etc.) ignored during layout animation.")
            return true // Consume drags during layout anim
        }

        var handled = scaleGestureDetector.onTouchEvent(event) // Pass to scale detector first

        // If scaling, don't process other touch events like drag/tap for rotation
        if (isScaling) {
            isDraggingRotation = false // Ensure rotation drag is reset if scaling starts
            velocityTracker?.clear() // Clear velocity if scaling overrides drag
            return true // Scale detector consumed it
        }

        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (layoutAnimator?.isRunning == true) {
                    Log.d("WordWheelView", "Tap down during layout animation. Will cancel and restart if tap selects.")
                    // Don't call stopAllMotionAndAnimations() yet, let performTap decide
                } else {
                    stopAllMotionAndAnimations()
                }
                isDraggingRotation = false
                isPanned = false
                lastTouchX = event.x
                lastTouchY = event.y
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                else velocityTracker?.clear()
                velocityTracker?.addMovement(event)
                parent.requestDisallowInterceptTouchEvent(true)
                handled = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                Log.d("WordWheelView", "Pointer Down")
            }
            MotionEvent.ACTION_MOVE -> {
                if (layoutAnimator?.isRunning == true) return true
                val currentX = event.x
                val currentY = event.y
                val dx = currentX - lastTouchX
                val dy = currentY - lastTouchY

                if (event.pointerCount > 1 && scaleFactor > minScaleFactor + 0.01f) { // PAN
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop || isPanned) {
                        isPanned = true
                        isDraggingRotation = false
                        stopAutoRotation(); stopFling()
                        panOffsetX += dx
                        panOffsetY += dy
                        constrainPanOffsets()
                        invalidate()
                        handled = true
                    }
                } else if (event.pointerCount == 1 && !isPanned) { // ROTATIONAL DRAG
                    if (!isDraggingRotation && (abs(dx) > touchSlop || abs(dy) > touchSlop/2)) { // More sensitive to start rotational drag
                        isDraggingRotation = true
                    }
                    if (isDraggingRotation) {
                        stopAutoRotation(); stopFling()
                        // Use atan2 for more robust angle calculation from drag
                        val angleStart = atan2(lastTouchY - viewCenterY - panOffsetY, lastTouchX - viewCenterX - panOffsetX)
                        val angleEnd = atan2(currentY - viewCenterY - panOffsetY, currentX - viewCenterX - panOffsetX)
                        var angleDelta = Math.toDegrees((angleEnd - angleStart).toDouble()).toFloat()

                        // Normalize delta to avoid huge jumps when crossing atan2 discontinuity
                        if (angleDelta > 180) angleDelta -= 360
                        if (angleDelta < -180) angleDelta += 360

                        manualRotationAngle = (manualRotationAngle + angleDelta + 360f) % 360f
                        invalidate()
                        handled = true
                    }
                }
                lastTouchX = currentX
                lastTouchY = currentY
            }
            MotionEvent.ACTION_POINTER_UP -> {
                Log.d("WordWheelView", "Pointer Up")
                if (event.pointerCount == 2) {
                    val remainingPointerIndex = if (event.actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(remainingPointerIndex)
                    lastTouchY = event.getY(remainingPointerIndex)
                    isPanned = false
                    isDraggingRotation = false
                    velocityTracker?.clear()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                val wasLayoutAnimating = layoutAnimator?.isRunning == true
                if (wasLayoutAnimating) {
                    // If layout was animating and user lifts finger (likely from a tap that started it)
                    // The tap in performTap would have already called triggerWordReshuffleAnimation.
                    // We don't want to start auto-rotation here if a new layout anim is about to start.
                    Log.d("WordWheelView", "UP/CANCEL during/after a tap that might have triggered reshuffle.")
                }
                if (isDraggingRotation) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    // For fling, we need tangential velocity.
                    // This is tricky. A simpler approach is to use the change in angle over time.
                    // Or, use the dominant screen velocity component (e.g., X if mostly horizontal drag around top/bottom)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    // Determine dominant velocity for fling direction or use a more complex tangential velocity calculation
                    val flingVelocity = if(abs(velocityX) > abs(velocityY)) velocityX else -velocityY // Heuristic
                    if (abs(flingVelocity) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        fling(flingVelocity * 0.5f) // Reduce fling sensitivity
                    } else { if (!isScaling && !wasLayoutAnimating) startAutoRotation() }
                } else if (isPanned) {
                    if (!isScaling && !wasLayoutAnimating) startAutoRotation()
                }
                else if (!isScaling) { // TAP
                    performTap(event.x, event.y)
                    if (layoutAnimator?.isRunning != true && !wasLayoutAnimating) { // Check if tap *didn't* start a new layout anim
                        startAutoRotation()
                    }
                }
                isDraggingRotation = false
                isPanned = false
                velocityTracker?.recycle(); velocityTracker = null
                handled = true
            }
        }
        return handled || super.onTouchEvent(event)
    }

    private fun constrainPanOffsets() {
        // Calculate max allowable pan based on current scale and view size vs content size
        // Content "width/height" is roughly diameter (2 * baseRadius)
        val contentWidthScaled = 2 * baseRadius * scaleFactor
        val contentHeightScaled = 2 * baseRadius * scaleFactor

        val maxPanX = max(0f, (contentWidthScaled - width) / 2f)
        val maxPanY = max(0f, (contentHeightScaled - height) / 2f)

        panOffsetX = panOffsetX.coerceIn(-maxPanX, maxPanX)
        panOffsetY = panOffsetY.coerceIn(-maxPanY, maxPanY)
    }


    private fun performTap(tapX: Float, tapY: Float) {
        Log.d("WordWheelView", "Tap at ($tapX, $tapY). Selected Index: $selectedWordIndex. Scale: $scaleFactor, Pan: ($panOffsetX, $panOffsetY). Checking ${wordsList.size} words.")
        var tappedWordInfo: WordInfo? = null
        for (i in wordsList.indices.reversed()) {
            val info = wordsList[i]
            if (info.visualRect.width() <= 0 || info.visualRect.height() <= 0) { // Skip invalid rects
                // Log.w("WordWheelView", "Skipping tap check for '${info.text}', visualRect is invalid: ${info.visualRect}")
                continue
            }
            // Log.d("WordWheelView", "Checking word '${info.text}': rect=${info.visualRect}")
            if (info.visualRect.contains(tapX, tapY)) {
                tappedWordInfo = info
                break
            }
        }

        if (tappedWordInfo != null) {
            Log.i("WordWheelView", "Word TAPPED and SELECTED: ${tappedWordInfo.text}")
            selectedWordIndex = wordsList.indexOf(tappedWordInfo)
            invalidate() // Show highlight immediately
            wordSelectedListener?.onWordSelected(
                tappedWordInfo.text,
                tappedWordInfo.originalSentence,
                tappedWordInfo.originalPrompt
            )
            // --- TRIGGER RE-LAYOUT ANIMATION ON TAP ---
            triggerWordReshuffleAnimation() // Public method to initiate re-layout
        } else {
            Log.d(
                "WordWheelView",
                "No specific word tapped by coordinate check. Resuming auto-rotation."
            )
            // If no word was tapped, just resume auto-rotation if nothing else is happening
            if (!isDraggingRotation && !isScaling && flingAnimator?.isRunning != true && layoutAnimator?.isRunning != true) {
                startAutoRotation()
            }
        }
    }

    private fun fling(velocityX: Float) {
        stopAllMotionAndAnimations()


        val angularVelocity = (velocityX / (currentEffectiveRadius.takeIf { it > 0 } ?: baseRadius)) * (180f / PI.toFloat()) * 0.2f
        val startFlingAngle = manualRotationAngle
        val targetFlingAngle = manualRotationAngle + angularVelocity * 0.7f

        Log.d("WordWheelView", "Flinging: velX=$velocityX, angVel=$angularVelocity, startAngle=$startFlingAngle, targetAngle=$targetFlingAngle")

        flingAnimator = ValueAnimator.ofFloat(startFlingAngle, targetFlingAngle).apply {
            duration = 1000L
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener {
                manualRotationAngle = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Log.d("WordWheelView", "Fling Animation Ended naturally.")
                    if (isAttachedToWindow && !isDraggingRotation && !isScaling) {
                        startAutoRotation()
                    }
                    flingAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    Log.d("WordWheelView", "Fling Animation Cancelled.")
                    if (isAttachedToWindow && !isDraggingRotation && !isScaling) { // Check if appropriate to restart
                        startAutoRotation()
                    }
                    flingAnimator = null
                }
            })
        }
        flingAnimator?.start()
    }

    private fun updateSelectedWord() {
        if (wordsList.isEmpty() || width == 0 || height == 0) {
            selectedWordIndex = -1
            return
        }
        val selectionAngleScreen = 270f
        var minAngleDiff = Float.MAX_VALUE
        var newSelectedIndex = 0
        val totalRotation = (currentRotationAngle + manualRotationAngle + 360f) % 360f

        wordsList.forEachIndexed { index, wordInfo ->
            // Use the scattered angle for determining visual selection
            val wordDisplayAngle = (wordInfo.angleOnWheel + wordInfo.currentAngularOffset + totalRotation + 360f) % 360f

            var diff = abs(wordDisplayAngle - selectionAngleScreen)
            if (diff > 180f) {
                diff = 360f - diff
            }

            if (diff < minAngleDiff) {
                minAngleDiff = diff
                newSelectedIndex = index
            }
        }
        if (selectedWordIndex != newSelectedIndex) {
            selectedWordIndex = newSelectedIndex
        }
    }

    fun release() {
        Log.d("WordWheelView", "Releasing resources")
        stopAllMotionAndAnimations()
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d("WordWheelView", "onAttachedToWindow")
        if (visibility == VISIBLE && wordsList.isNotEmpty() && !isAutoRotating && flingAnimator?.isRunning != true) {
            startAutoRotation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d("WordWheelView", "onDetachedFromWindow")
        release()
    }
}