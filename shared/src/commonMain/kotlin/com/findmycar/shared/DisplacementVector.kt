package com.findmycar.shared

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 2D displacement in meters using NED (North-East-Down) frame.
 *
 * @param dNorth meters northward (positive = north)
 * @param dEast meters eastward (positive = east)
 */
data class DisplacementVector(
    val dNorth: Float,
    val dEast: Float
) {
    /** Total distance (magnitude) in meters */
    val magnitude: Float get() = sqrt(dNorth * dNorth + dEast * dEast)

    /** Bearing in degrees (0=North, 90=East, 180=South, 270=West) */
    val bearingDeg: Float get() {
        val rad = atan2(dEast.toDouble(), dNorth.toDouble())
        val deg = (rad * 180.0 / PI).toFloat()
        return (deg + 360f) % 360f
    }

    operator fun plus(other: DisplacementVector) = DisplacementVector(
        dNorth = dNorth + other.dNorth,
        dEast = dEast + other.dEast
    )

    operator fun unaryMinus() = DisplacementVector(
        dNorth = -dNorth,
        dEast = -dEast
    )

    companion object {
        val ZERO = DisplacementVector(0f, 0f)
    }
}

/**
 * GPS coordinate pair.
 */
data class LatLng(val lat: Double, val lng: Double) {

    companion object {
        /** Meters per degree of latitude (constant) */
        const val METERS_PER_DEG_LAT = 111_320.0

        /** Meters per degree of longitude at a given latitude */
        fun metersPerDegLng(lat: Double): Double =
            METERS_PER_DEG_LAT * cos(lat * PI / 180.0)
    }

    /**
     * Compute displacement from this point to another in meters.
     */
    fun displaceTo(other: LatLng): DisplacementVector {
        val dLat = other.lat - lat
        val dLng = other.lng - lng
        val dNorth = (dLat * METERS_PER_DEG_LAT).toFloat()
        val dEast = (dLng * metersPerDegLng(lat)).toFloat()
        return DisplacementVector(dNorth, dEast)
    }

    /**
     * Apply a displacement vector to get a new GPS position.
     */
    fun plus(dv: DisplacementVector): LatLng {
        val newLat = lat + dv.dNorth / METERS_PER_DEG_LAT
        val newLng = lng + dv.dEast / metersPerDegLng(lat)
        return LatLng(newLat, newLng)
    }

    /**
     * Subtract a displacement vector (reverse direction) to get a new GPS position.
     * Useful for GPS Anchor backward calculation: parkingGps = currentGps.minus(accumulated)
     */
    fun minus(dv: DisplacementVector): LatLng {
        val newLat = lat - dv.dNorth / METERS_PER_DEG_LAT
        val newLng = lng - dv.dEast / metersPerDegLng(lat)
        return LatLng(newLat, newLng)
    }

    /**
     * Distance to another point in meters (simple Euclidean approximation, good for < 10km).
     */
    fun distanceTo(other: LatLng): Float {
        val dv = displaceTo(other)
        return dv.magnitude
    }

    /**
     * Bearing to another point in degrees (0=North, 90=East).
     */
    fun bearingTo(other: LatLng): Float {
        val dv = displaceTo(other)
        return dv.bearingDeg
    }
}
