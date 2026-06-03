package com.findmycar.shared

/**
 * Tracks whether the user is in or out of their car.
 *
 * State transitions:
 *   UNKNOWN → IN_CAR: sustained vehicle motion detected (CAR_MOVING for 10s)
 *   IN_CAR → EXITED: car stopped + walking steps detected within 5 minutes
 *   EXITED → IN_CAR: sustained vehicle motion again, or car Bluetooth reconnects
 *
 * This class is pure logic with no platform dependencies — testable in isolation.
 */
class CarPresenceStateMachine {

    companion object {
        /** Duration of CAR_MOVING needed to confirm user is in a car */
        const val MOVING_CONFIRM_MS = 10_000L

        /** Duration of CAR_SLOW (with no steps) needed to confirm user is in a car */
        const val SLOW_CONFIRM_MS = 20_000L

        /** Steps needed after stop to confirm exit */
        const val EXIT_STEPS_THRESHOLD = 15

        /** Steps needed after pickup event to confirm exit (faster path) */
        const val EXIT_STEPS_AFTER_PICKUP = 5

        /** Maximum time after stop to detect exit (5 minutes) */
        const val EXIT_WINDOW_MS = 300_000L
    }

    var state: CarPresenceState = CarPresenceState.UNKNOWN
        private set

    /**
     * Evaluate the current inputs and potentially transition state.
     *
     * Call this periodically (every few seconds) with the latest sensor data.
     *
     * @return The new state after evaluation (may be unchanged)
     */
    fun evaluate(input: StateMachineInput): CarPresenceState {
        val newState = when (state) {
            CarPresenceState.UNKNOWN -> evaluateUnknown(input)
            CarPresenceState.IN_CAR -> evaluateInCar(input)
            CarPresenceState.EXITED -> evaluateExited(input)
        }
        state = newState
        return newState
    }

    /**
     * Force the state to a specific value (for restoring persisted state).
     */
    fun restoreState(savedState: CarPresenceState) {
        state = savedState
    }

    /**
     * Reset to UNKNOWN state.
     */
    fun reset() {
        state = CarPresenceState.UNKNOWN
    }

    // --- UNKNOWN state ---

    private fun evaluateUnknown(input: StateMachineInput): CarPresenceState {
        // Transition to IN_CAR when sustained vehicle motion is detected
        if (input.motionState == "CAR_MOVING" && input.motionStateDurationMs >= MOVING_CONFIRM_MS) {
            return CarPresenceState.IN_CAR
        }
        return CarPresenceState.UNKNOWN
    }

    // --- IN_CAR state ---

    private fun evaluateInCar(input: StateMachineInput): CarPresenceState {
        // If car is moving or slow, stay IN_CAR (cancel any exit window)
        if (input.motionState == "CAR_MOVING" || input.motionState == "CAR_SLOW") {
            return CarPresenceState.IN_CAR
        }

        // Car is stopped — check for exit signals
        if (input.motionState == "CAR_STOPPED") {
            // Exit window timeout — assume user exited and we missed it
            if (input.timeSinceStopMs >= EXIT_WINDOW_MS) {
                return CarPresenceState.EXITED
            }

            // Primary exit: enough steps since stop
            if (input.stepsSinceStop >= EXIT_STEPS_THRESHOLD) {
                return CarPresenceState.EXITED
            }

            // Fast exit: pickup detected + fewer steps needed
            if (input.pickupDetected && input.stepsSincePickup >= EXIT_STEPS_AFTER_PICKUP) {
                return CarPresenceState.EXITED
            }
        }

        return CarPresenceState.IN_CAR
    }

    // --- EXITED state ---

    private fun evaluateExited(input: StateMachineInput): CarPresenceState {
        // Car Bluetooth reconnected — immediate transition
        if (input.carBluetoothConnected) {
            return CarPresenceState.IN_CAR
        }

        // Sustained CAR_MOVING — user is driving again
        if (input.motionState == "CAR_MOVING" && input.motionStateDurationMs >= MOVING_CONFIRM_MS) {
            return CarPresenceState.IN_CAR
        }

        // Sustained CAR_SLOW with no steps — in a slow-moving vehicle, not walking
        if (input.motionState == "CAR_SLOW" &&
            input.motionStateDurationMs >= SLOW_CONFIRM_MS &&
            input.stepsDuringSlowMotion == 0) {
            return CarPresenceState.IN_CAR
        }

        return CarPresenceState.EXITED
    }
}

/**
 * The three possible states of user-car presence.
 */
enum class CarPresenceState {
    /** Initial state — no vehicle motion detected yet */
    UNKNOWN,
    /** User is inside the car (driving or recently stopped) */
    IN_CAR,
    /** User has exited the car — parking spot saved */
    EXITED
}

/**
 * Input data for the state machine evaluation.
 *
 * All fields are computed by the service from sensor data.
 */
data class StateMachineInput(
    /** Current motion model output: "CAR_MOVING", "CAR_SLOW", or "CAR_STOPPED" */
    val motionState: String,
    /** How long the current motionState has been continuously active (ms) */
    val motionStateDurationMs: Long,
    /** Steps counted since the last CAR_STOPPED event */
    val stepsSinceStop: Int,
    /** Steps counted during the current CAR_SLOW period (for EXITED→IN_CAR check) */
    val stepsDuringSlowMotion: Int,
    /** Time elapsed since CAR_STOPPED was first detected (ms) */
    val timeSinceStopMs: Long,
    /** Whether a phone pickup event was detected during the exit window */
    val pickupDetected: Boolean,
    /** Steps counted since the pickup event */
    val stepsSincePickup: Int,
    /** Whether the configured car Bluetooth device is currently connected */
    val carBluetoothConnected: Boolean
)
