package com.findmycar.shared

/**
 * Manages known parking places.
 *
 * A place becomes "known" when the user parks there and drives away
 * WITHOUT pressing "Find My Car". This means the user remembers
 * where the car is → no need to track next time.
 *
 * At known places: GPS 1/minute, no sensors (battery saving).
 */
class KnownParkingPlaces {

    companion object {
        /** Radius to consider "same place" (meters) */
        const val MATCH_RADIUS_M = 100f
    }

    private val places = mutableListOf<KnownPlace>()

    /**
     * Check if a GPS position is at a known parking place.
     */
    fun isKnownPlace(lat: Double, lng: Double): KnownPlace? {
        val pos = LatLng(lat, lng)
        return places.firstOrNull { pos.distanceTo(LatLng(it.lat, it.lng)) < MATCH_RADIUS_M }
    }

    /**
     * Mark current parking as "known" — user drove away without using Find.
     * Called when EXITED → IN_CAR transition happens and Find was never pressed.
     */
    fun learnPlace(lat: Double, lng: Double) {
        if (isKnownPlace(lat, lng) != null) return  // already known
        places.add(KnownPlace(lat, lng, parkCount = 1))
    }

    /**
     * Remove a place from known list.
     * Called when user presses "Find My Car" at a previously known place
     * (they forgot this time → no longer reliable as "known").
     */
    fun forgetPlace(lat: Double, lng: Double) {
        places.removeAll { LatLng(lat, lng).distanceTo(LatLng(it.lat, it.lng)) < MATCH_RADIUS_M }
    }

    /**
     * Get all known places.
     */
    fun getAll(): List<KnownPlace> = places.toList()

    /**
     * Serialize for persistence.
     */
    fun toList(): List<Map<String, Any>> = places.map {
        mapOf("lat" to it.lat, "lng" to it.lng, "parkCount" to it.parkCount)
    }

    /**
     * Restore from persistence.
     */
    fun fromList(data: List<Map<String, Any>>) {
        places.clear()
        data.forEach { map ->
            places.add(KnownPlace(
                lat = (map["lat"] as Number).toDouble(),
                lng = (map["lng"] as Number).toDouble(),
                parkCount = (map["parkCount"] as? Number)?.toInt() ?: 1
            ))
        }
    }

    fun clear() {
        places.clear()
    }
}

data class KnownPlace(
    val lat: Double,
    val lng: Double,
    var parkCount: Int = 1
)
