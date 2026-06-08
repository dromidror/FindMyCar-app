package com.findmycar.shared

/**
 * Event emitted when the car presence state changes.
 */
data class StateTransitionEvent(
    val from: CarPresenceState,
    val to: CarPresenceState
)

/**
 * Simple event emitter for state transitions.
 * Listeners can subscribe to be notified when the state changes.
 */
class StateEventEmitter {

    private val listeners = mutableListOf<(StateTransitionEvent) -> Unit>()

    /**
     * Subscribe to state transition events.
     */
    fun on(listener: (StateTransitionEvent) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a listener.
     */
    fun off(listener: (StateTransitionEvent) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Emit a state transition event to all listeners.
     */
    fun emit(event: StateTransitionEvent) {
        listeners.forEach { it(event) }
    }

    /**
     * Remove all listeners.
     */
    fun clear() {
        listeners.clear()
    }
}
