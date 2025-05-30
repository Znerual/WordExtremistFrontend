package com.laurenz.wordextremist.ui.animation

import android.widget.ImageView

data class AnimatedElement(
    val view: ImageView,
    var currentTranslationX: Float,
    var currentTranslationY: Float,
    var dx: Float, // Velocity x
    var dy: Float, // Velocity y
    var rotationSpeed: Float,
    var currentRotation: Float,
    var currentAlpha: Float,
    var targetAlpha: Float,
    var alphaChangeSpeed: Float
)
