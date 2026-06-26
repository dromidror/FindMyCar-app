package com.findmycar.app

import java.util.Locale

/**
 * Locale-aware formatting for distance and time.
 */
object LocaleHelper {

    /**
     * Returns true if the locale uses imperial units (US, UK, Myanmar, Liberia).
     */
    fun usesImperial(): Boolean {
        val country = Locale.getDefault().country.uppercase()
        return country in listOf("US", "GB", "MM", "LR")
    }

    /**
     * Format distance in meters to a locale-appropriate string.
     * Metric: meters / km
     * Imperial: feet / miles
     */
    fun formatDistance(meters: Float): String {
        return if (usesImperial()) {
            val feet = meters * 3.28084f
            if (feet >= 5280f) {
                val miles = feet / 5280f
                "${"%.1f".format(miles)} mi"
            } else {
                "${feet.toInt()} ft"
            }
        } else {
            if (meters >= 1000f) {
                val km = meters / 1000f
                "${"%.1f".format(km)} km"
            } else {
                "${meters.toInt()} m"
            }
        }
    }

    /**
     * Format a timestamp to locale-aware time string (e.g. "14:30" or "2:30 PM").
     */
    fun formatTime(timestampMs: Long): String {
        val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault())
        return format.format(java.util.Date(timestampMs))
    }
}
