package com.laurenz.wordextremist.ui.animation

data class SandParticle(
    var x: Float,
    var y: Float,
    var yVelocity: Float = 0f,
    var xOffset: Float = 0f, // For subtle horizontal sway
    var radius: Float = 3f, // Size of the particle
    var color: Int, // Color of the particle
    var isActive: Boolean = false // New flag
)
