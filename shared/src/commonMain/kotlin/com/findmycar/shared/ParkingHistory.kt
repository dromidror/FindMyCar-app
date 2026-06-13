package com.findmycar.shared

/**
 * Maintains a history of recent parking locations.
 * Stores the last N parking events so the user can select
 * the correct one if the automatic detection picked the wrong spot.
 */
class ParkingHistory(private val maxEntries: Int = 10) {

    private val entries = mutableListOf<ParkingEntry>()

    data class ParkingEntry(
        val lat: Double,
        val lng: Double,
        val timestamp: Long,
        val floor: Int?,
        val hasGps: Boolean
    )

    /**
     * Add a new parking entry. Most recent first.
     */
    fun add(lat: Double, lng: Double, timestamp: Long, floor: Int? = null, hasGps: Boolean = true) {
        entries.add(0, ParkingEntry(lat, lng, timestamp, floor, hasGps))
        if (entries.size > maxEntries) entries.removeLast()
    }

    /**
     * Get all entries (most recent first).
     */
    fun getAll(): List<ParkingEntry> = entries.toList()

    /**
     * Get the most recent entry.
     */
    fun latest(): ParkingEntry? = entries.firstOrNull()

    /**
     * Number of entries.
     */
    fun count(): Int = entries.size

    /**
     * Clear all history.
     */
    fun clear() { entries.clear() }

    /**
     * Serialize to list of maps (for persistence).
     */
    fun toList(): List<Map<String, Any>> = entries.map { e ->
        buildMap {
            put("lat", e.lat)
            put("lng", e.lng)
            put("timestamp", e.timestamp)
            put("hasGps", e.hasGps)
            e.floor?.let { put("floor", it) }
        }
    }

    /**
     * Restore from list of maps.
     */
    fun fromList(data: List<Map<String, Any>>) {
        entries.clear()
        data.forEach { map ->
            entries.add(ParkingEntry(
                lat = (map["lat"] as Number).toDouble(),
                lng = (map["lng"] as Number).toDouble(),
                timestamp = (map["timestamp"] as Number).toLong(),
                floor = (map["floor"] as? Number)?.toInt(),
                hasGps = (map["hasGps"] as? Boolean) ?: true
            ))
        }
    }
}
