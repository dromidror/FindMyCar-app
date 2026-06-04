package com.findmycar.shared

import kotlin.math.roundToInt

/**
 * Detects parking floor using barometer pressure readings.
 *
 * Calibrates at parking entry (GPS lost while driving) = Floor 0.
 * Computes floor from pressure delta at parking time.
 *
 * Higher pressure = lower altitude (underground).
 * ~0.4 hPa per floor (~3.5-4 meters).
 */
class FloorDetector {

    companion object {
        /** Pressure change per floor in hPa */
        const val HPA_PER_FLOOR = 0.4f

        /** Moving average window size (number of readings at 1 Hz = seconds) */
        const val SMOOTHING_WINDOW = 5
    }

    private val buffer = ArrayDeque<Float>()

    /** Current smoothed pressure (hPa) */
    var currentPressure: Float = 0f
        private set

    /** Entry-level pressure calibration (null = not calibrated) */
    var entryPressure: Float? = null
        private set

    /** Whether entry calibration has been done */
    val isCalibrated: Boolean get() = entryPressure != null

    /**
     * Feed a barometer reading. Call at ~1 Hz.
     */
    fun addReading(pressureHpa: Float) {
        buffer.addLast(pressureHpa)
        while (buffer.size > SMOOTHING_WINDOW) buffer.removeFirst()
        currentPressure = buffer.average().toFloat()
    }

    /**
     * Calibrate: set current smoothed pressure as entry level (Floor 0).
     * Call when GPS is lost while car is still moving.
     */
    fun calibrate() {
        if (currentPressure > 0f) {
            entryPressure = currentPressure
        }
    }

    /**
     * Compute parking floor relative to entry.
     *
     * @return Negative = below entry (B1=-1, B2=-2), Zero = same level, Positive = above.
     *         Null if not calibrated.
     */
    fun computeFloor(): Int? {
        val entry = entryPressure ?: return null
        if (currentPressure <= 0f) return null
        val delta = currentPressure - entry
        // Higher pressure = lower altitude → negative floor (underground)
        return -(delta / HPA_PER_FLOOR).roundToInt()
    }

    /**
     * Compute how many floors the car is relative to the user's CURRENT position.
     *
     * Call this during "Find" session with live barometer readings.
     * Positive = car is below you, Negative = car is above you.
     *
     * @param parkingPressure The barometer reading saved when the car was parked
     * @return floors difference: +2 = car is 2 floors below you, -1 = car is 1 floor above you
     */
    fun floorsFromCurrentToParking(parkingPressure: Float): Int? {
        if (currentPressure <= 0f || parkingPressure <= 0f) return null
        val delta = parkingPressure - currentPressure
        // parkingPressure > currentPressure means car is at higher pressure = lower altitude = below you
        return (delta / HPA_PER_FLOOR).roundToInt()
    }

    /**
     * Raw pressure delta from entry (hPa). Positive = went underground.
     */
    fun pressureDelta(): Float? {
        val entry = entryPressure ?: return null
        return currentPressure - entry
    }

    /**
     * Reset for new trip.
     */
    fun reset() {
        buffer.clear()
        currentPressure = 0f
        entryPressure = null
    }

    /**
     * Persist state to a map.
     */
    fun saveState(): Map<String, Any> = buildMap {
        put("currentPressure", currentPressure)
        entryPressure?.let { put("entryPressure", it) }
    }

    /**
     * Restore state from a map.
     */
    fun restoreState(state: Map<String, Any>) {
        currentPressure = (state["currentPressure"] as? Number)?.toFloat() ?: 0f
        entryPressure = (state["entryPressure"] as? Number)?.toFloat()
        if (currentPressure > 0f) buffer.addLast(currentPressure)
    }

    private fun ArrayDeque<Float>.average(): Double {
        if (isEmpty()) return 0.0
        var sum = 0.0
        for (v in this) sum += v
        return sum / size
    }
}

/**
 * Format a floor number for display — relative to entry point.
 *
 * Shows how many floors above/below the parking entry the car is.
 * This works regardless of whether entry was at ground, B1, or F3.
 *
 * @return e.g., "2 floors below entry", "Same level as entry", "1 floor above entry"
 */
fun formatFloor(floor: Int?): String? = when {
    floor == null -> null
    floor == 0 -> "Same level as entry"
    floor == -1 -> "1 floor below entry"
    floor < -1 -> "${-floor} floors below entry"
    floor == 1 -> "1 floor above entry"
    else -> "$floor floors above entry"
}

/**
 * Short format for compact display.
 * e.g., "↓2" (2 below), "↑1" (1 above), "E" (entry level)
 */
fun formatFloorShort(floor: Int?): String? = when {
    floor == null -> null
    floor == 0 -> "E"
    floor < 0 -> "↓${-floor}"
    else -> "↑$floor"
}

/**
 * Format relative floor for live "Find" display.
 * Shows where the car is relative to the user's current position.
 *
 * @param floorsBelow Positive = car is below you, Negative = car is above you, 0 = same floor
 */
fun formatFloorRelative(floorsBelow: Int?): String? = when {
    floorsBelow == null -> null
    floorsBelow == 0 -> "Same floor as your car"
    floorsBelow == 1 -> "Car is 1 floor below you"
    floorsBelow > 1 -> "Car is $floorsBelow floors below you"
    floorsBelow == -1 -> "Car is 1 floor above you"
    else -> "Car is ${-floorsBelow} floors above you"
}
