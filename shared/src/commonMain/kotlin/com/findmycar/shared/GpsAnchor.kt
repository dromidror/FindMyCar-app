package com.findmycar.shared

/**
 * GPS Anchor utilities for converting between relative displacement and absolute GPS coordinates.
 *
 * Used when GPS becomes available after a no-GPS segment:
 * - Backward: compute parking location from current GPS + accumulated walk displacement
 * - Forward: estimate current position from last GPS + accumulated displacement since
 */
object GpsAnchor {

    /**
     * Compute parking GPS from current GPS by subtracting accumulated walking displacement.
     *
     * parkingLocation = currentGps - displacement
     *
     * @param currentGps The first GPS fix obtained after exiting the car
     * @param displacementSinceParking Total displacement walked since parking (from RoNIN)
     * @return Estimated GPS coordinates of the parking spot
     */
    fun resolveBackward(currentGps: LatLng, displacementSinceParking: DisplacementVector): LatLng {
        return currentGps.minus(displacementSinceParking)
    }

    /**
     * Estimate current position from last known GPS by adding displacement since GPS loss.
     *
     * estimatedCurrent = lastGps + displacement
     *
     * @param lastGps The last GPS fix before signal was lost
     * @param displacementSinceLoss Total displacement since GPS was lost (from RoNIN)
     * @return Estimated current GPS position
     */
    fun resolveForward(lastGps: LatLng, displacementSinceLoss: DisplacementVector): LatLng {
        return lastGps.plus(displacementSinceLoss)
    }
}
