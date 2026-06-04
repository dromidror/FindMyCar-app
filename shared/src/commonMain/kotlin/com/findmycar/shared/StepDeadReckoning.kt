package com.findmycar.shared

import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple dead reckoning using step count + compass heading.
 *
 * Fallback when RoNIN model is not available.
 * Estimates displacement: each step = STEP_LENGTH_M in the current heading direction.
 */
class StepDeadReckoning {

    companion object {
        /** Average step length in meters */
        const val STEP_LENGTH_M = 0.7f
    }

    private var lastStepCount = -1
    private var headingRad = 0f  // current compass heading in radians (0=north, π/2=east)

    /**
     * Update the current compass heading.
     * @param headingDeg Device heading in degrees (0=North, 90=East)
     */
    fun updateHeading(headingDeg: Float) {
        headingRad = (headingDeg * kotlin.math.PI / 180f).toFloat()
    }

    /**
     * Update step count and compute displacement since last call.
     *
     * @param currentStepCount Current total step count from hardware sensor
     * @return Displacement since last call, or ZERO if no new steps
     */
    fun onStepCount(currentStepCount: Int): DisplacementVector {
        if (lastStepCount < 0) {
            lastStepCount = currentStepCount
            return DisplacementVector.ZERO
        }

        val newSteps = currentStepCount - lastStepCount
        lastStepCount = currentStepCount

        if (newSteps <= 0) return DisplacementVector.ZERO

        // Each step: move STEP_LENGTH_M in the heading direction
        val totalDistance = newSteps * STEP_LENGTH_M
        val dNorth = totalDistance * cos(headingRad)
        val dEast = totalDistance * sin(headingRad)

        return DisplacementVector(dNorth, dEast)
    }

    /**
     * Reset state (new session).
     */
    fun reset() {
        lastStepCount = -1
        headingRad = 0f
    }
}
