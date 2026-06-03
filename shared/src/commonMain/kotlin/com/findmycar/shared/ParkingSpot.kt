package com.findmycar.shared

/**
 * Represents a saved parking location.
 *
 * Can be:
 * - GPS-based: lat/lng are available (parked where GPS was working)
 * - Relative-only: only relativeNorth/East displacement from exit point (no GPS at parking)
 * - Resolved: was relative-only, then GPS became available and the position was computed
 */
data class ParkingSpot(
    /** Latitude of parking location (null if relative-only) */
    val latitude: Double? = null,
    /** Longitude of parking location (null if relative-only) */
    val longitude: Double? = null,
    /** GPS accuracy in meters (null if relative-only) */
    val accuracy: Float? = null,
    /** Timestamp when the user exited the car (epoch ms) */
    val timestamp: Long = 0L,
    /** Whether GPS coordinates are available */
    val hasGps: Boolean = false,
    /** Whether this spot is relative-only (no GPS at parking time) */
    val hasRelativeOnly: Boolean = false,
    /** Relative displacement north from exit point (meters, only if hasRelativeOnly) */
    val relativeNorth: Float? = null,
    /** Relative displacement east from exit point (meters, only if hasRelativeOnly) */
    val relativeEast: Float? = null
) {
    /**
     * Get GPS coordinates as LatLng, or null if not available.
     */
    fun toLatLng(): LatLng? {
        if (!hasGps || latitude == null || longitude == null) return null
        return LatLng(latitude, longitude)
    }

    /**
     * Get relative displacement as a vector, or null if not available.
     */
    fun toRelativeDisplacement(): DisplacementVector? {
        if (relativeNorth == null || relativeEast == null) return null
        return DisplacementVector(relativeNorth, relativeEast)
    }

    companion object {
        /**
         * Create a GPS-based parking spot.
         */
        fun fromGps(lat: Double, lng: Double, accuracy: Float, timestamp: Long) = ParkingSpot(
            latitude = lat,
            longitude = lng,
            accuracy = accuracy,
            timestamp = timestamp,
            hasGps = true,
            hasRelativeOnly = false
        )

        /**
         * Create a relative-only parking spot (no GPS was available).
         * The displacement represents accumulated walking from the exit point.
         */
        fun fromRelative(displacement: DisplacementVector, timestamp: Long) = ParkingSpot(
            timestamp = timestamp,
            hasGps = false,
            hasRelativeOnly = true,
            relativeNorth = displacement.dNorth,
            relativeEast = displacement.dEast
        )

        /**
         * Resolve a relative parking spot to GPS coordinates.
         * Used when GPS becomes available after a no-GPS parking event.
         *
         * @param currentGps Current GPS fix
         * @param accumulatedDisplacement Total displacement walked since parking
         */
        fun resolveWithGps(currentGps: LatLng, accumulatedDisplacement: DisplacementVector, timestamp: Long): ParkingSpot {
            val resolved = currentGps.minus(accumulatedDisplacement)
            return ParkingSpot(
                latitude = resolved.lat,
                longitude = resolved.lng,
                accuracy = null,  // no direct GPS accuracy for resolved spots
                timestamp = timestamp,
                hasGps = true,
                hasRelativeOnly = false
            )
        }
    }
}
