package com.laurenz.wordextremist.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.laurenz.wordextremist.R

class TutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint()
    private val transparentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var highlightRect: RectF? = null
    private var cornerRadius = 30f

    private var isHighlightActive = false

    var onAnywhereClickListener: (() -> Unit)? = null
    var onHighlightClickListener: (() -> Unit)? = null

    init {
        // This is crucial for onDraw to be called for a ViewGroup
        setWillNotDraw(false)

        // PorterDuffXfermode can behave unexpectedly with hardware acceleration.
        // Forcing this view to a software layer ensures it works correctly.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        // Setup the paint for the semi-transparent background
        backgroundPaint.color = ContextCompat.getColor(context, R.color.tutorial_overlay_background)
        backgroundPaint.style = Paint.Style.FILL

        // Setup the paint that will "clear" a section of the canvas
        transparentPaint.color = Color.TRANSPARENT
        transparentPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = ContextCompat.getColor(context, R.color.primary) // Use your app's primary color
        strokePaint.strokeWidth = 8f // A nice, thick border
    }

    /**
     * Sets the rectangle to be highlighted (the "hole").
     * @param rect The rectangle defining the position and size of the view to highlight.
     */
    fun setHighlightRect(rect: Rect) {
        // Add some padding around the view for a nicer look
        val padding = 6f
        this.highlightRect = RectF(
            rect.left - padding,
            rect.top - padding,
            rect.right + padding,
            rect.bottom + padding
        )

        isHighlightActive = true
        invalidate()
    }

    fun clearHighlight() {
        this.highlightRect = null
        isHighlightActive = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundPaint.color)

        highlightRect?.let {
            // 1. "Cut out" the hole
            canvas.drawRoundRect(it, cornerRadius, cornerRadius, transparentPaint)
            // 2. Draw the colored border around the hole
            canvas.drawRoundRect(it, cornerRadius, cornerRadius, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (isHighlightActive) {
                // --- HIGHLIGHT MODE ---
                // Only trigger the listener if the click is inside the highlight
                highlightRect?.let { rect ->
                    if (rect.contains(event.x, event.y)) {
                        onHighlightClickListener?.invoke()
                        return true // Consume the event
                    }
                }
                // If the click was outside the highlight, we do nothing, but still consume the touch
                // to prevent accidental clicks on the underlying UI.

            } else {
                // --- TEXT-ONLY MODE ---
                // Any click on the screen will trigger the listener
                onAnywhereClickListener?.invoke()
                return true // Consume the event
            }
        }
        // Consume all touch events to block the UI beneath the overlay
        return true
    }
}