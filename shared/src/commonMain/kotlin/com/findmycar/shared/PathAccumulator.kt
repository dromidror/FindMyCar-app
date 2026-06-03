package com.findmycar.shared

/**
 * Accumulates displacement vectors during GPS gaps.
 *
 * Tracks total movement (north/east meters) from a starting point.
 * Only active when GPS is unavailable. Emits total and resets when GPS returns.
 *
 * State is persistable so it survives process kills.
 */
class PathAccumulator {

    var totalNorth: Float = 0f
        private set
    var totalEast: Float = 0f
        private set
    var isActive: Boolean = false
        private set

    /**
     * Start accumulating (GPS lost). Resets any previous accumulation.
     */
    fun start() {
        totalNorth = 0f
        totalEast = 0f
        isActive = true
    }

    /**
     * Add a displacement from RoNIN inference or step estimation.
     */
    fun addDisplacement(dv: DisplacementVector) {
        if (!isActive) return
        totalNorth += dv.dNorth
        totalEast += dv.dEast
    }

    /**
     * Stop accumulation and return total displacement (GPS returned).
     * Resets the accumulator to inactive.
     */
    fun stopAndEmit(): DisplacementVector {
        val result = DisplacementVector(totalNorth, totalEast)
        totalNorth = 0f
        totalEast = 0f
        isActive = false
        return result
    }

    /**
     * Get current accumulated displacement without stopping.
     */
    fun currentTotal(): DisplacementVector =
        DisplacementVector(totalNorth, totalEast)

    /**
     * Total distance walked from start point.
     */
    fun totalDistance(): Float =
        currentTotal().magnitude

    /**
     * Reset all state.
     */
    fun reset() {
        totalNorth = 0f
        totalEast = 0f
        isActive = false
    }

    /**
     * Serialize state to a map (for SharedPreferences persistence).
     */
    fun saveState(): Map<String, Any> = mapOf(
        "totalNorth" to totalNorth,
        "totalEast" to totalEast,
        "isActive" to isActive
    )

    /**
     * Restore state from a map.
     */
    fun restoreState(state: Map<String, Any>) {
        totalNorth = (state["totalNorth"] as? Number)?.toFloat() ?: 0f
        totalEast = (state["totalEast"] as? Number)?.toFloat() ?: 0f
        isActive = (state["isActive"] as? Boolean) ?: false
    }
}
