package com.laurenz.wordextremist.ui.tutorial

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.IdRes
import com.laurenz.wordextremist.R
import com.laurenz.wordextremist.views.TutorialOverlayView
import com.laurenz.wordextremist.ui.tutorial.TutorialStep


class TutorialManager(
    private val activity: Activity,
    private val steps: List<TutorialStep>
) {
    private var currentStepIndex = -1
    private var overlayView: TutorialOverlayView? = null
    private var explanationTextView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    var onTutorialFinishedListener: (() -> Unit)? = null


    fun start() {
        // Add the overlay to the activity's root view
        val inflater = LayoutInflater.from(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val overlayLayout = inflater.inflate(R.layout.tutorial_overlay, root, false)
        root.addView(overlayLayout)

        overlayView = overlayLayout.findViewById(R.id.tutorialOverlayView)
        explanationTextView = overlayLayout.findViewById(R.id.textViewTutorial)

        // Set the listener for when the user taps the highlighted area
        overlayView?.onHighlightClickListener = {
            next()
        }
        overlayView?.onAnywhereClickListener = {
            next()
        }

        // Start with the first step
        currentStepIndex = 0
        showStep(currentStepIndex)
    }

    fun next() {
        handler.removeCallbacksAndMessages(null) // Cancel any pending view waits
        var shouldAdvance = true
        if (currentStepIndex in steps.indices) {
            // If the current step has a custom action, run it.
            // That action is now responsible for calling advance() if needed.
            shouldAdvance = steps[currentStepIndex].customAction?.invoke(this) ?: true
        }
        if (shouldAdvance) {
            advance()
        } else {
            // The custom action returned false, so it will handle calling advance() itself.
            Log.d("TutorialManager", "Action is handling next step asynchronously.")
        }
    }

    fun advance() {
        currentStepIndex++
        if (currentStepIndex < steps.size) {
            showStep(currentStepIndex)
        } else {
            end()
        }
    }


    private fun showStep(index: Int) {
        val step = steps[index]
        overlayView?.visibility = View.VISIBLE
        explanationTextView?.text = step.explanationText

        if (step.targetViewId != null) {
            // This is a step that highlights a view
            handleHighlightStep(step)
        } else {
            // This is a text-only page
            handleTextPageStep()
        }
    }

    fun showStepByIndex(index: Int) {
        if (index in steps.indices) {
            currentStepIndex = index
            showStep(currentStepIndex)
        } else {
            Log.e("TutorialManager", "Cannot show step, index $index is out of bounds.")
        }
    }

    private fun handleHighlightStep(step: TutorialStep) {
        val targetView = activity.findViewById<View>(step.targetViewId!!)
        // If view not found OR not visible yet, wait for it
        if (targetView == null || targetView.visibility != View.VISIBLE || targetView.width == 0) {
            waitForView(step)
            return
        }

        targetView.post {
            val location = IntArray(2)
            targetView.getLocationInWindow(location)

            // Get the status bar's height to correct the offset
            val statusBarHeight = getStatusBarHeight()

            val rect = Rect(
                location[0],
                location[1] - statusBarHeight, // The FIX for the Y-coordinate
                location[0] + targetView.width,
                location[1] - statusBarHeight + targetView.height // Also fix the bottom edge
            )

            overlayView?.setHighlightRect(rect)
            positionExplanationText(rect)
        }
    }

    private fun waitForView(step: TutorialStep, retryCount: Int = 0) {
        if (retryCount > 10) return // Prevent infinite loop
        Log.d("TutorialManager", "Waiting for view: ${step.targetViewId}, retry: $retryCount")
        handler.postDelayed({
            handleHighlightStep(step) // Retry
        }, 200) // check again in 200ms
    }

    private fun handleTextPageStep() {
        // For text-only pages, we don't highlight anything
        overlayView?.clearHighlight()

        // And we center the explanation text
        explanationTextView?.let {
            val params = it.layoutParams as FrameLayout.LayoutParams
            params.topMargin = 0
            params.bottomMargin = 0
            params.gravity = Gravity.CENTER
            it.layoutParams = params
        }
    }

    private fun positionExplanationText(highlightRect: Rect) {
        val textView = explanationTextView ?: return
        val parentView = overlayView as View
        val params = textView.layoutParams as FrameLayout.LayoutParams

        // Reset margins before calculating new ones
        params.topMargin = 0
        params.bottomMargin = 0

        val spaceAbove = highlightRect.top
        val spaceBelow = parentView.height - highlightRect.bottom

        if (spaceBelow > spaceAbove) {
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.topMargin = highlightRect.bottom + 50
        } else {
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.bottomMargin = parentView.height - highlightRect.top + 50
        }
        textView.layoutParams = params
    }

    fun end(runListener: Boolean = true) {
        handler.removeCallbacksAndMessages(null)
        overlayView?.let {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            // Check if view is still attached
            if(it.parent == root) {
                root.removeView(it)
            }
        }
        overlayView = null
        if(runListener) {
            onTutorialFinishedListener?.invoke()
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = activity.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }
}