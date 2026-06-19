package com.findmycar.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Test suite for CarPresenceStateMachine.
 *
 * Tests feed synthetic StateMachineInput sequences directly — no sensors involved.
 * Each test simulates a real-world scenario and verifies the state transitions.
 */
class CarPresenceStateMachineTest {

    private fun createSm() = CarPresenceStateMachine()

    /** Helper to build input with defaults for common cases */
    private fun input(
        motionState: String = "CAR_STOPPED",
        motionStateDurationMs: Long = 0L,
        cumulativeMovingMs: Long = 0L,
        stepsSinceStop: Int = 0,
        stepsDuringSlowMotion: Int = 0,
        timeSinceStopMs: Long = 0L,
        pickupDetected: Boolean = false,
        stepsSincePickup: Int = 0,
        carBluetoothConnected: Boolean = false
    ) = StateMachineInput(
        motionState = motionState,
        motionStateDurationMs = motionStateDurationMs,
        cumulativeMovingMs = cumulativeMovingMs,
        stepsSinceStop = stepsSinceStop,
        stepsDuringSlowMotion = stepsDuringSlowMotion,
        timeSinceStopMs = timeSinceStopMs,
        pickupDetected = pickupDetected,
        stepsSincePickup = stepsSincePickup,
        carBluetoothConnected = carBluetoothConnected
    )

    // ─── INIT state tests ───

    @Test
    fun initState_staysInit_whenNotEnoughDriving() {
        val sm = createSm()
        // 1 minute of driving — not enough (need 2 min)
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 60_000, cumulativeMovingMs = 60_000))
        assertEquals(CarPresenceState.INIT, sm.state)
    }

    @Test
    fun initState_transitionsToInCar_after2MinDriving() {
        val sm = createSm()
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 120_000, cumulativeMovingMs = 120_000))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun initState_staysInit_whenStopped() {
        val sm = createSm()
        // Even with lots of cumulative time, must be currently moving
        sm.evaluate(input(motionState = "CAR_STOPPED", cumulativeMovingMs = 600_000))
        assertEquals(CarPresenceState.INIT, sm.state)
    }

    @Test
    fun initState_doesNotTransition_onWalkingSpeed() {
        val sm = createSm()
        // Simulate walking classified as CAR_MOVING at low speed for 5 min
        // But cumulativeMovingMs shouldn't accumulate (engine handles this)
        // If it does reach threshold with motionState=CAR_MOVING, SM will transition
        // This test verifies the SM boundary condition
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 119_000, cumulativeMovingMs = 119_000))
        assertEquals(CarPresenceState.INIT, sm.state)
    }

    // ─── UNKNOWN state tests ───

    @Test
    fun unknownState_transitionsToInCar_after10sDriving() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.UNKNOWN)
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 10_000))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun unknownState_staysUnknown_withShortDriving() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.UNKNOWN)
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 9_000))
        assertEquals(CarPresenceState.UNKNOWN, sm.state)
    }

    // ─── IN_CAR → EXITED tests ───

    @Test
    fun inCar_transitionsToExited_with10Steps() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 30_000,
            stepsSinceStop = 10
        ))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    @Test
    fun inCar_staysInCar_withFewerThan10Steps() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 30_000,
            stepsSinceStop = 9
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun inCar_transitionsToExited_withPickupAnd5Steps() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 30_000,
            pickupDetected = true,
            stepsSincePickup = 5
        ))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    @Test
    fun inCar_staysInCar_withPickupButOnly4Steps() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 30_000,
            pickupDetected = true,
            stepsSincePickup = 4
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun inCar_transitionsToExited_after5MinTimeout() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 300_000  // 5 min
        ))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    @Test
    fun inCar_staysInCar_justBefore5MinTimeout() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 299_999
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun inCar_neverExits_whenBluetoothConnected() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        // Even with 10 steps + stopped, BT overrides
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 30_000,
            stepsSinceStop = 50,
            carBluetoothConnected = true
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun inCar_neverExits_whenBluetoothConnected_evenAfterTimeout() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 600_000,  // 10 min stopped
            stepsSinceStop = 100,
            carBluetoothConnected = true
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun inCar_doesNotExit_whenStopTooOldFor10Steps() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)
        // timeSinceStop >= 300_000 triggers the timeout rule, not the steps rule
        // But steps rule requires timeSinceStop < 300_000
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 300_001,
            stepsSinceStop = 10
        ))
        // Should still transition via timeout rule
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    // ─── EXITED → IN_CAR tests ───

    @Test
    fun exited_transitionsToInCar_whenBluetoothReconnects() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.EXITED)
        sm.evaluate(input(carBluetoothConnected = true))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun exited_transitionsToInCar_after30sSustainedDriving() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.EXITED)
        sm.evaluate(input(
            motionState = "CAR_MOVING",
            motionStateDurationMs = 30_000,
            stepsDuringSlowMotion = 0
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun exited_staysExited_withShortDriving() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.EXITED)
        sm.evaluate(input(
            motionState = "CAR_MOVING",
            motionStateDurationMs = 29_000,
            stepsDuringSlowMotion = 0
        ))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    @Test
    fun exited_staysExited_whenWalkingDuringSlowMotion() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.EXITED)
        // Even 30s of "moving" but with walking steps = not real driving
        sm.evaluate(input(
            motionState = "CAR_MOVING",
            motionStateDurationMs = 60_000,
            stepsDuringSlowMotion = 5
        ))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    @Test
    fun exited_transitionsToInCar_atHighSpeed_despitePhantomSteps() {
        // Reproduces bug from sm_log_20260616_172035.csv:
        // Car driving at 60-100 km/h but a single phantom step (vibration)
        // was blocking the EXITED → IN_CAR transition.
        // The fix: stepsDuringSlowMotion should be 0 at driving speeds
        // because steps at high speed are vibration, not walking.
        val sm = createSm()
        sm.restoreState(CarPresenceState.EXITED)
        sm.evaluate(input(
            motionState = "CAR_MOVING",
            motionStateDurationMs = 30_000,
            stepsDuringSlowMotion = 0  // engine now sets 0 when gpsSpeed >= 10
        ))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    // ─── Full scenario tests ───

    @Test
    fun fullScenario_driveAndPark() {
        val sm = createSm()
        assertEquals(CarPresenceState.INIT, sm.state)

        // Drive for 2+ minutes
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 130_000, cumulativeMovingMs = 130_000))
        assertEquals(CarPresenceState.IN_CAR, sm.state)

        // Stop
        sm.evaluate(input(motionState = "CAR_STOPPED", timeSinceStopMs = 5_000, stepsSinceStop = 0))
        assertEquals(CarPresenceState.IN_CAR, sm.state)

        // Walk away (10 steps)
        sm.evaluate(input(motionState = "CAR_STOPPED", timeSinceStopMs = 30_000, stepsSinceStop = 10))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }

    @Test
    fun fullScenario_driveAndPark_withBluetooth() {
        val sm = createSm()

        // Drive with BT
        sm.evaluate(input(motionState = "CAR_MOVING", motionStateDurationMs = 130_000, cumulativeMovingMs = 130_000, carBluetoothConnected = true))
        assertEquals(CarPresenceState.IN_CAR, sm.state)

        // Stop + walk but BT still connected — no exit
        sm.evaluate(input(motionState = "CAR_STOPPED", timeSinceStopMs = 30_000, stepsSinceStop = 50, carBluetoothConnected = true))
        assertEquals(CarPresenceState.IN_CAR, sm.state)

        // BT disconnects + walking
        sm.evaluate(input(motionState = "CAR_STOPPED", timeSinceStopMs = 60_000, stepsSinceStop = 50, carBluetoothConnected = false))
        assertEquals(CarPresenceState.EXITED, sm.state)

        // BT reconnects (got back in car)
        sm.evaluate(input(carBluetoothConnected = true))
        assertEquals(CarPresenceState.IN_CAR, sm.state)
    }

    @Test
    fun fullScenario_pickupTriggersEarlyExit() {
        val sm = createSm()
        sm.restoreState(CarPresenceState.IN_CAR)

        // Car stops
        sm.evaluate(input(motionState = "CAR_STOPPED", timeSinceStopMs = 10_000, stepsSinceStop = 0))
        assertEquals(CarPresenceState.IN_CAR, sm.state)

        // Pickup phone + 5 steps
        sm.evaluate(input(
            motionState = "CAR_STOPPED",
            timeSinceStopMs = 20_000,
            pickupDetected = true,
            stepsSincePickup = 5
        ))
        assertEquals(CarPresenceState.EXITED, sm.state)
    }
}
