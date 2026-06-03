package com.findmycar.shared

/**
 * Computes navigation bearing and distance from current position to parking spot.
 *
 * Handles all 4 GPS scenarios:
 *   1. GPS at parking + GPS now → direct GPS navigation
 *   2. No GPS at parking + GPS now → resolve parking via backward anchor
 *   3. GPS at parking + no GPS now → estimate current via forward RoNIN
 *   4. No GPS anywhere → use reversed RoNIN displacement
 */
class NavigationEngine {

    companion object {
        /** Distance threshold to consider "arrived" (meters) */
        const val ARRIVAL_THRESHOLD_M = 5f
    }

    /**
     * Compute navigation result.
     *
     * @param parkingGps GPS coordinates of parking (null if only relative displacement known)
     * @param currentGps Current GPS position (null if no GPS fix available)
     * @param accumulatedFromParking Total RoNIN displacement since parking exit (for scenario 2 & 4)
     * @param accumulatedFromLastGps RoNIN displacement since last GPS fix (for scenario 3)
     * @param lastGpsBeforeLoss Last known GPS before GPS was lost (for scenario 3)
     * @return Navigation result with bearing, distance, and scenario info
     */
    fun compute(
        parkingGps: LatLng?,
        currentGps: LatLng?,
        accumulatedFromParking: DisplacementVector?,
        accumulatedFromLastGps: DisplacementVector?,
        lastGpsBeforeLoss: LatLng? = null
    ): NavigationResult {

        // Scenario 1: Both GPS available
        if (parkingGps != null && currentGps != null) {
            val bearing = currentGps.bearingTo(parkingGps)
            val distance = currentGps.distanceTo(parkingGps)
            return NavigationResult(
                bearingToCarDeg = bearing,
                distanceMeters = distance,
                scenario = 1,
                isGpsBased = true,
                arrived = distance < ARRIVAL_THRESHOLD_M
            )
        }

        // Scenario 2: No parking GPS + current GPS available → resolve backward
        if (parkingGps == null && currentGps != null && accumulatedFromParking != null) {
            val resolvedParking = currentGps.minus(accumulatedFromParking)
            val bearing = currentGps.bearingTo(resolvedParking)
            val distance = currentGps.distanceTo(resolvedParking)
            return NavigationResult(
                bearingToCarDeg = bearing,
                distanceMeters = distance,
                scenario = 2,
                isGpsBased = true,
                arrived = distance < ARRIVAL_THRESHOLD_M
            )
        }

        // Scenario 3: Parking GPS known + no current GPS → estimate current position forward
        if (parkingGps != null && currentGps == null && accumulatedFromLastGps != null) {
            val anchor = lastGpsBeforeLoss ?: parkingGps
            val estimatedCurrent = anchor.plus(accumulatedFromLastGps)
            val bearing = estimatedCurrent.bearingTo(parkingGps)
            val distance = estimatedCurrent.distanceTo(parkingGps)
            return NavigationResult(
                bearingToCarDeg = bearing,
                distanceMeters = distance,
                scenario = 3,
                isGpsBased = false,
                arrived = distance < ARRIVAL_THRESHOLD_M
            )
        }

        // Scenario 4: No GPS anywhere → use reverse of accumulated displacement
        if (accumulatedFromParking != null) {
            val vectorToCar = -accumulatedFromParking
            return NavigationResult(
                bearingToCarDeg = vectorToCar.bearingDeg,
                distanceMeters = vectorToCar.magnitude,
                scenario = 4,
                isGpsBased = false,
                arrived = vectorToCar.magnitude < ARRIVAL_THRESHOLD_M
            )
        }

        // No data available — cannot navigate
        return NavigationResult(
            bearingToCarDeg = 0f,
            distanceMeters = 0f,
            scenario = 0,
            isGpsBased = false,
            arrived = false
        )
    }
}

/**
 * Result of a navigation computation.
 */
data class NavigationResult(
    /** Absolute bearing to the car (0=North, 90=East, 180=South, 270=West) */
    val bearingToCarDeg: Float,
    /** Distance to the car in meters */
    val distanceMeters: Float,
    /** Which GPS scenario is active (1–4, 0=no data) */
    val scenario: Int,
    /** Whether navigation is based on GPS coordinates (true) or dead reckoning (false) */
    val isGpsBased: Boolean,
    /** Whether the user has arrived (distance < 5m) */
    val arrived: Boolean
)
