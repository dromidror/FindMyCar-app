package com.findmycar.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Detects when the phone is picked up from a resting position.
 *
 * A "pickup" is defined as a sharp orientation change (pitch or roll changing
 * by more than 30 degrees within 1 second). This distinguishes a deliberate
 * phone grab from gradual drift or vibration.
 *
 * Used during the exit window to enable faster exit detection (fewer steps needed).
 */
class PickupDetector(
    private val onPickup: () -> Unit
) : SensorEventListener {

    companion object {
        /** Minimum orientation change to count as a pickup (degrees) */
        const val THRESHOLD_DEGREES = 30f

        /** Time window for the change to occur (ms) */
        const val TIME_WINDOW_MS = 1000L

        /** Cooldown after a pickup is detected to avoid repeated triggers (ms) */
        const val COOLDOWN_MS = 5000L
    }

    private var lastPitch = 0f
    private var lastRoll = 0f
    private var lastUpdateMs = 0L
    private var referencesPitch = 0f
    private var referencesRoll = 0f
    private var referenceTimeMs = 0L
    private var lastPickupMs = 0L
    private var initialized = false

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
        val now = System.currentTimeMillis()

        if (!initialized) {
            referencesPitch = pitchDeg
            referencesRoll = rollDeg
            referenceTimeMs = now
            lastPitch = pitchDeg
            lastRoll = rollDeg
            lastUpdateMs = now
            initialized = true
            return
        }

        // Update reference point if older than the time window
        if (now - referenceTimeMs > TIME_WINDOW_MS) {
            referencesPitch = lastPitch
            referencesRoll = lastRoll
            referenceTimeMs = lastUpdateMs
        }

        // Check if orientation changed sharply from the reference
        val pitchDelta = abs(pitchDeg - referencesPitch)
        val rollDelta = abs(rollDeg - referencesRoll)

        if ((pitchDelta > THRESHOLD_DEGREES || rollDelta > THRESHOLD_DEGREES) &&
            (now - lastPickupMs > COOLDOWN_MS)) {
            lastPickupMs = now
            onPickup()
        }

        lastPitch = pitchDeg
        lastRoll = rollDeg
        lastUpdateMs = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Reset the detector state (call when starting a new exit window).
     */
    fun reset() {
        initialized = false
        lastPickupMs = 0L
    }
}
