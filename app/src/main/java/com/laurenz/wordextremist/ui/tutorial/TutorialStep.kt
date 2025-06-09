package com.laurenz.wordextremist.ui.tutorial

import androidx.annotation.IdRes

// Data class to define a single step in our tutorial
data class TutorialStep(
    @IdRes val targetViewId: Int?,
    val explanationText: String,
    val customAction: ((TutorialManager) -> Boolean)? = null // For special steps like starting a game
)
