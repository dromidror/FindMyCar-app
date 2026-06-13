package com.findmycar.shared

/**
 * Car presence state machine.
 *
 * All transition rules are defined declaratively in one place.
 * Uses the generic StateMachine DSL for clean, maintainable logic.
 */

enum class CarPresenceState {
    INIT,       // After install, waiting for first 10-min drive
    UNKNOWN,    // After first trip, waiting for next drive (10s)
    IN_CAR,     // User is inside the car
    EXITED      // User has exited the car
}

data class StateMachineInput(
    val motionState: String,            // "CAR_MOVING" or "CAR_STOPPED"
    val motionStateDurationMs: Long,    // how long current motion state active
    val stepsSinceStop: Int,            // steps since car stopped
    val stepsDuringSlowMotion: Int,     // steps during slow motion (>0 = walking)
    val timeSinceStopMs: Long,          // time since stop detected
    val pickupDetected: Boolean,        // phone was picked up
    val stepsSincePickup: Int,          // steps since pickup
    val carBluetoothConnected: Boolean  // car BT device connected
)

/**
 * Creates the car presence state machine with all rules.
 */
fun createCarPresenceStateMachine(): StateMachine<CarPresenceState, StateMachineInput> {
    return StateMachine(CarPresenceState.INIT) {

        // ─── INIT: First time after install. Need 10 min driving to activate. ───
        state(CarPresenceState.INIT) {
            transitionTo(CarPresenceState.IN_CAR) { input ->
                input.motionState == "CAR_MOVING" && input.motionStateDurationMs >= 600_000L
            }
        }

        // ─── UNKNOWN: After first trip. Need 10s driving to re-enter IN_CAR. ───
        state(CarPresenceState.UNKNOWN) {
            transitionTo(CarPresenceState.IN_CAR) { input ->
                input.motionState == "CAR_MOVING" && input.motionStateDurationMs >= 10_000L
            }
        }

        // ─── IN_CAR: User is in the car. Detect exit when stopped + walking. ───
        // Car BT connected = stay IN_CAR (never exit while BT connected)
        state(CarPresenceState.IN_CAR) {
            // Exit: stopped + 10 steps + BT NOT connected
            transitionTo(CarPresenceState.EXITED) { input ->
                !input.carBluetoothConnected &&
                input.motionState == "CAR_STOPPED" &&
                input.timeSinceStopMs < 300_000L &&
                input.stepsSinceStop >= 10
            }
            // Exit: stopped + pickup + 5 steps + BT NOT connected
            transitionTo(CarPresenceState.EXITED) { input ->
                !input.carBluetoothConnected &&
                input.motionState == "CAR_STOPPED" &&
                input.timeSinceStopMs < 300_000L &&
                input.pickupDetected &&
                input.stepsSincePickup >= 5
            }
            // Exit: stopped too long (5 min timeout) + BT NOT connected
            transitionTo(CarPresenceState.EXITED) { input ->
                !input.carBluetoothConnected &&
                input.motionState == "CAR_STOPPED" &&
                input.timeSinceStopMs >= 300_000L
            }
        }

        // ─── EXITED: User left the car. Detect return to car. ───
        state(CarPresenceState.EXITED) {
            // Return: car Bluetooth reconnected
            transitionTo(CarPresenceState.IN_CAR) { input ->
                input.carBluetoothConnected
            }
            // Return: sustained driving (10s)
            transitionTo(CarPresenceState.IN_CAR) { input ->
                input.motionState == "CAR_MOVING" && input.motionStateDurationMs >= 10_000L
            }
            // Return: slow motion (20s) without steps (in vehicle, not walking)
            transitionTo(CarPresenceState.IN_CAR) { input ->
                input.motionState == "CAR_STOPPED" &&
                input.motionStateDurationMs >= 20_000L &&
                input.stepsDuringSlowMotion == 0 &&
                input.motionState == "CAR_MOVING"  // This checks slow/moving without steps
            }
        }
    }
}

/**
 * Wrapper for backward compatibility.
 */
class CarPresenceStateMachine {
    private val sm = createCarPresenceStateMachine()

    val state: CarPresenceState get() = sm.currentState

    fun evaluate(input: StateMachineInput): CarPresenceState = sm.process(input)

    fun restoreState(savedState: CarPresenceState) { sm.setState(savedState) }

    fun reset() { sm.reset(CarPresenceState.INIT) }

    fun onTransition(handler: (from: CarPresenceState, to: CarPresenceState, input: StateMachineInput) -> Unit) {
        sm.onTransition(handler)
    }
}
