package com.findmycar.shared

/**
 * Captures state machine evaluation entries for the debug console.
 *
 * Each entry records: current state, event source, counter value,
 * motion state, time in state, and resulting transition (if any).
 */
object StateMachineLog {

    data class Entry(
        val state: String,          // current state (e.g. "INIT", "IN_CAR")
        val source: String,         // event source (e.g. "GPS", "steps", "BT")
        val counter: String,        // relevant counter (e.g. speed "60.0", steps "10")
        val motionState: String,    // "CAR_MOVING" / "CAR_STOPPED"
        val pickup: Boolean?,       // pickup detected (null if not relevant)
        val timeInState: String,    // formatted duration (mm:ss)
        val transition: String?     // new state or null ("-")
    )

    private val entries = mutableListOf<Entry>()
    private const val MAX_ENTRIES = 200

    var enabled = false

    fun add(entry: Entry) {
        if (!enabled) return
        entries.add(entry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    fun getAll(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size
}
