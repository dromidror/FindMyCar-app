package com.findmycar.shared

/**
 * Platform-independent exit detection engine.
 *
 * Contains all the logic that was previously in ExitDetectionService (Android),
 * but uses provider interfaces instead of direct platform calls.
 * This enables the same logic to run on Android and iOS.
 */
class ExitDetectionEngine(
    private val location: LocationProvider,
    private val steps: StepProvider,
    private val barometer: BarometerProvider,
    private val imu: ImuProvider,
    private val compass: CompassProvider,
    private val bluetooth: BluetoothProvider,
    private val persistence: PersistenceProvider,
    private val notification: NotificationProvider
) {
    /** Set to true to enable debug logging (StateMachineLog + transition log). */
    var debugMode = false

    val stateMachine = CarPresenceStateMachine()
    val floorDetector = FloorDetector()
    val pathAccumulator = PathAccumulator()
    val parkingHistory = ParkingHistory()

    // Motion state
    private var currentMotionState = "CAR_STOPPED"
    private var motionStateStartMs = currentTimeMs()
    private var stopTimestampMs = 0L
    private var locationAtStop: LatLng? = null
    private val speedBuffer = ArrayDeque<Float>(5)  // last 5 GPS speeds for smoothing
    private var cumulativeMovingMs = 0L             // total time in CAR_MOVING (for INIT→IN_CAR)
    private var lastTickMs = currentTimeMs()

    // Steps
    private var stepsSinceStop = 0
    private var stepsSincePickup = 0
    private var isWalking = false
    private var lastStepTimeMs = 0L

    // Pickup
    private var pickupDetected = false

    // Power/BT
    private var wasChargingWhileDriving = false
    private var carBluetoothConnected = false

    // GPS rate
    private var currentGpsIntervalMs = 1000L

    companion object {
        private const val GPS_STALE_MS = 15_000L
        private const val SPEED_STOPPED_THRESHOLD_KMH = 1.0f
        private const val SPEED_DRIVING_THRESHOLD_KMH = 15.0f  // minimum speed to count as driving (filters GPS drift)
        private const val INFERENCE_INTERVAL_MS = 5000L
        private const val GPS_INTERVAL_EXITED_MS = 10_000L  // 10s — must detect driving to re-enter car
    }

    /**
     * Initialize: restore state, register listeners, start providers.
     */
    fun start() {
        // Restore state
        val savedState = persistence.getString("presence_state", "INIT") ?: "INIT"
        stateMachine.restoreState(
            when (savedState) {
                "IN_CAR" -> CarPresenceState.IN_CAR
                "EXITED" -> CarPresenceState.EXITED
                "UNKNOWN" -> CarPresenceState.UNKNOWN
                else -> CarPresenceState.INIT
            }
        )

        // Register step listener
        steps.onStep {
            lastStepTimeMs = currentTimeMs()
            isWalking = true
            stepsSinceStop++
            stepsSincePickup++
        }

        // Register location listener
        location.onLocationChanged { pos, speedKmh, accuracy ->
            // Smooth GPS speed to filter drift spikes
            speedBuffer.addLast(speedKmh)
            if (speedBuffer.size > 5) speedBuffer.removeFirst()
            onLocationUpdate(pos, speedKmh)
        }

        // Register BT
        bluetooth.onCarConnected {
            carBluetoothConnected = true
            evaluate()
        }
        bluetooth.onCarDisconnected {
            carBluetoothConnected = false
        }

        // Start GPS
        location.startUpdates(currentGpsIntervalMs)
        steps.start()

        notification.show("Initializing...")
    }

    /**
     * Stop all providers.
     */
    fun stop() {
        location.stopUpdates()
        steps.stop()
        imu.stop()
        barometer.stopContinuous()
        bluetooth.stopMonitoring()
    }

    /**
     * Called periodically (every 5 seconds) by the platform timer.
     */
    fun tick() {
        val now = currentTimeMs()

        // Accumulate driving time for INIT→IN_CAR transition — only at real driving speed + good accuracy
        if (currentMotionState == "CAR_MOVING") {
            val speed = location.getSpeedKmh() ?: 0f
            val accuracy = location.getAccuracy() ?: 999f
            if (speed >= SPEED_DRIVING_THRESHOLD_KMH && accuracy < 30f) {
                cumulativeMovingMs += (now - lastTickMs)
            }
        }
        lastTickMs = now

        determineMotionState()
        evaluate()

        // Update debug/UI values in persistence
        persistence.putString("debug_motion_state", currentMotionState)
        persistence.putBoolean("debug_gps_available", location.isAvailable())
        persistence.putFloat("debug_gps_speed", location.getSpeedKmh() ?: 0f)
        persistence.putInt("debug_steps_since_stop", stepsSinceStop)
        persistence.putBoolean("debug_bt_connected", carBluetoothConnected)
        persistence.putFloat("debug_pressure", barometer.getPressureHpa() ?: 0f)
        val loc = location.getLastLocation()
        persistence.putFloat("debug_gps_lat", loc?.lat?.toFloat() ?: 0f)
        persistence.putFloat("debug_gps_lng", loc?.lng?.toFloat() ?: 0f)
    }

    // --- Core logic ---

    private fun determineMotionState() {
        val newMotionState: String

        // In EXITED state, don't actively detect motion — wait for GPS to show real driving
        if (stateMachine.state == CarPresenceState.EXITED) {
            // Only transition out of EXITED via GPS showing sustained high speed
            if (location.isAvailable()) {
                val speed = location.getSpeedKmh() ?: 0f
                if (speed >= 15f) {
                    newMotionState = "CAR_MOVING"
                } else {
                    // Low speed in EXITED — ensure state is STOPPED so duration resets
                    if (currentMotionState != "CAR_STOPPED") {
                        onMotionStateChanged(currentMotionState, "CAR_STOPPED")
                        currentMotionState = "CAR_STOPPED"
                        motionStateStartMs = currentTimeMs()
                    }
                    return
                }
            } else {
                // No GPS in EXITED — ensure state is STOPPED
                if (currentMotionState != "CAR_STOPPED") {
                    onMotionStateChanged(currentMotionState, "CAR_STOPPED")
                    currentMotionState = "CAR_STOPPED"
                    motionStateStartMs = currentTimeMs()
                }
                return
            }
            if (newMotionState != currentMotionState) {
                onMotionStateChanged(currentMotionState, newMotionState)
                currentMotionState = newMotionState
                motionStateStartMs = currentTimeMs()
            }
            return
        }

        // Walking check — only relevant at low GPS speed.
        // At driving speed, step sensor fires from vehicle vibration (bus/car bumps).
        val gpsSpeed = if (location.isAvailable()) {
            if (speedBuffer.isNotEmpty()) {
                var sum = 0f; for (s in speedBuffer) sum += s; sum / speedBuffer.size
            } else location.getSpeedKmh() ?: 0f
        } else 0f

        val stepsActive = stepsSinceStop > 0 && (currentTimeMs() - lastStepTimeMs) < 3000L
        if (!stepsActive) isWalking = false

        if (stepsActive && gpsSpeed < SPEED_DRIVING_THRESHOLD_KMH) {
            // Steps below driving speed = walking (not vehicle vibration)
            newMotionState = "CAR_STOPPED"
        } else if (location.isAvailable()) {
            val rawSpeed = location.getSpeedKmh() ?: 0f
            // Use smoothed speed (average of last 5 readings) to filter GPS drift spikes
            val speed = if (speedBuffer.isNotEmpty()) {
                var sum = 0f; for (s in speedBuffer) sum += s; sum / speedBuffer.size
            } else rawSpeed
            // Adaptive GPS rate
            if (speed > 100f && currentGpsIntervalMs != 3000L) {
                currentGpsIntervalMs = 3000L
                location.setInterval(3000L)
            } else if (speed <= 100f && currentGpsIntervalMs != 1000L &&
                       stateMachine.state != CarPresenceState.EXITED) {
                currentGpsIntervalMs = 1000L
                location.setInterval(1000L)
            }

            if (speed >= 10f) {
                // Fast driving — stop IMU, reset step counters (vibration steps don't count)
                imu.stop()
                steps.stop()
                stepsSinceStop = 0
                stepsSincePickup = 0
                isWalking = false
            } else {
                steps.start()
            }

            newMotionState = if (speed < SPEED_STOPPED_THRESHOLD_KMH) "CAR_STOPPED" else "CAR_MOVING"
        } else {
            // No GPS — start IMU for ML model (regardless of BT state)
            imu.start()
            steps.start()
            if (!floorDetector.isCalibrated && currentMotionState == "CAR_MOVING") {
                floorDetector.calibrate()
            }
            newMotionState = currentMotionState // Keep current until platform provides model result
        }

        if (newMotionState != currentMotionState) {
            onMotionStateChanged(currentMotionState, newMotionState)
            currentMotionState = newMotionState
            motionStateStartMs = currentTimeMs()
        }
    }

    private fun onMotionStateChanged(oldState: String, newState: String) {
        if (newState == "CAR_STOPPED" && oldState != "CAR_STOPPED") {
            stopTimestampMs = currentTimeMs()
            stepsSinceStop = 0
            stepsSincePickup = 0
            pickupDetected = false
            locationAtStop = location.getLastLocation()
        }
        if (newState == "CAR_MOVING") {
            stepsSinceStop = 0
        }
    }

    private fun evaluate() {
        val now = currentTimeMs()
        // Steps at driving speed are vibration, not walking.
        // When GPS is unavailable, we can't confirm walking — don't block transitions.
        val gpsSpeed = location.getSpeedKmh()  // null if GPS stale
        val reallyWalking = isWalking && gpsSpeed != null && gpsSpeed < SPEED_DRIVING_THRESHOLD_KMH

        val input = StateMachineInput(
            motionState = currentMotionState,
            motionStateDurationMs = now - motionStateStartMs,
            cumulativeMovingMs = cumulativeMovingMs,
            stepsSinceStop = stepsSinceStop,
            stepsDuringSlowMotion = if (reallyWalking) 1 else 0,
            timeSinceStopMs = if (stopTimestampMs > 0) now - stopTimestampMs else 0,
            pickupDetected = pickupDetected,
            stepsSincePickup = stepsSincePickup,
            carBluetoothConnected = carBluetoothConnected
        )

        val oldState = stateMachine.state
        val newState = stateMachine.evaluate(input)

        // Log to state machine debug console (DEV only)
        if (debugMode) {
            val source = determineEventSource()
            val counter = determineCounter(source)
            val timeInState = formatDuration(now - motionStateStartMs)
            val transition = if (newState != oldState) newState.name else null

            StateMachineLog.add(StateMachineLog.Entry(
                state = oldState.name,
                source = source,
                counter = counter,
                motionState = currentMotionState,
                steps = stepsSinceStop,
                pickup = if (oldState == CarPresenceState.IN_CAR && currentMotionState == "CAR_STOPPED")
                    pickupDetected else null,
                timeInState = timeInState,
                transition = transition
            ))
        }

        if (newState != oldState) {
            // Always-on transition log for debugging phantom state changes (DEV only)
            if (debugMode) {
                logTransition(oldState, newState, input, now)
            }
            onStateTransition(StateTransition(oldState, newState))
        }
    }

    /**
     * Logs every state transition with full context to persistence.
     * Runs always (independent of debug console toggle).
     * Appends to a semicolon-delimited log string in SharedPreferences.
     */
    private fun logTransition(
        from: CarPresenceState,
        to: CarPresenceState,
        input: StateMachineInput,
        timestampMs: Long
    ) {
        val gpsLat = location.getLastLocation()?.lat ?: 0.0
        val gpsLng = location.getLastLocation()?.lng ?: 0.0
        val gpsSpeed = location.getSpeedKmh() ?: -1f
        val gpsAvailable = location.isAvailable()

        val entry = buildString {
            append(timestampMs)
            append(",").append(from.name)
            append(",").append(to.name)
            append(",").append(input.motionState)
            append(",").append(input.motionStateDurationMs)
            append(",").append(input.cumulativeMovingMs)
            append(",").append(input.stepsSinceStop)
            append(",").append(input.stepsDuringSlowMotion)
            append(",").append(input.timeSinceStopMs)
            append(",").append(input.pickupDetected)
            append(",").append(input.stepsSincePickup)
            append(",").append(input.carBluetoothConnected)
            append(",").append(gpsAvailable)
            append(",").append(gpsSpeed)
            append(",").append(gpsLat)
            append(",").append(gpsLng)
        }

        // Append to existing log (keep last 50 transitions)
        val existing = persistence.getString("transition_log", "") ?: ""
        val entries = existing.split(";").filter { it.isNotBlank() }.toMutableList()
        entries.add(entry)
        if (entries.size > 50) {
            entries.removeAt(0)
        }
        persistence.putString("transition_log", entries.joinToString(";"))
    }

    /**
     * Determine what triggered this evaluation for the debug log.
     */
    private fun determineEventSource(): String {
        return when {
            carBluetoothConnected -> "BT"
            location.isAvailable() -> "GPS"
            else -> "tick"
        }
    }

    /**
     * Get the relevant counter value for the event source.
     */
    private fun determineCounter(source: String): String {
        return when (source) {
            "GPS" -> {
                val speed = location.getSpeedKmh() ?: 0f
                val intPart = speed.toInt()
                val decPart = ((speed - intPart) * 10).toInt()
                "$intPart.$decPart"
            }
            "BT" -> if (carBluetoothConnected) "conn" else "disc"
            else -> "-"
        }
    }

    /**
     * Format milliseconds to mm:ss for the debug log.
     */
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    }

    private fun onStateTransition(transition: StateTransition) {
        persistence.putString("presence_state", transition.to.name)

        when (transition.to) {
            CarPresenceState.INIT -> {
                notification.update("⏳ Initializing...")
            }
            CarPresenceState.UNKNOWN -> {
                notification.update("⏳ Waiting for drive...")
            }
            CarPresenceState.IN_CAR -> {
                wasChargingWhileDriving = false
                floorDetector.reset()
                currentGpsIntervalMs = 1000L
                location.setInterval(1000L)
                notification.update("🚗 In car — tracking")
            }
            CarPresenceState.EXITED -> {
                // Read barometer for floor
                barometer.startContinuous()  // Keep reading for live floor difference display
                barometer.readOnce { pressure ->
                    floorDetector.addReading(pressure)
                }
                val parkingFloor = floorDetector.computeFloor()

                // Save parking at stop location
                val loc = locationAtStop ?: location.getLastLocation()
                if (loc != null && location.isAvailable()) {
                    saveParkingGps(loc, location.getAccuracy())
                } else {
                    pathAccumulator.start()
                    saveParkingNoGps()
                }

                // Save floor
                persistence.putInt("floor", parkingFloor ?: 0)
                persistence.putBoolean("has_floor", parkingFloor != null)
                persistence.putFloat("parking_pressure", floorDetector.currentPressure)

                // Enter low-power mode
                imu.stop()
                steps.start()  // Keep for "return to car" detection
                currentGpsIntervalMs = GPS_INTERVAL_EXITED_MS
                location.setInterval(GPS_INTERVAL_EXITED_MS)

                val floorLabel = formatFloorShort(parkingFloor) ?: ""
                notification.update("🅿️ Car parked${if (floorLabel.isNotEmpty()) " ($floorLabel)" else ""}")
            }
        }
    }

    private fun onLocationUpdate(pos: LatLng, speedKmh: Float) {
        // GPS anchor: if accumulating and GPS returns
        if (pathAccumulator.isActive) {
            barometer.readOnce { floorDetector.addReading(it) }
            val displacement = pathAccumulator.stopAndEmit()
            val parkingPos = pos.minus(displacement)
            saveParkingGps(parkingPos, null)
        }
    }

    /**
     * Called by platform when phone pickup is detected.
     */
    fun onPickupDetected() {
        if (stateMachine.state == CarPresenceState.IN_CAR && currentMotionState == "CAR_STOPPED") {
            pickupDetected = true
            stepsSincePickup = 0
        }
    }

    /**
     * Update motion state from external ML model inference (platform-specific).
     */
    fun setMotionStateFromModel(state: String) {
        if (!location.isAvailable()) {
            if (state != currentMotionState) {
                onMotionStateChanged(currentMotionState, state)
                currentMotionState = state
                motionStateStartMs = currentTimeMs()
            }
        }
    }

    // --- Persistence helpers ---

    private fun saveParkingGps(pos: LatLng, accuracy: Float?) {
        persistence.putFloat("latitude", pos.lat.toFloat())
        persistence.putFloat("longitude", pos.lng.toFloat())
        persistence.putFloat("accuracy", accuracy ?: 0f)
        persistence.putLong("timestamp", currentTimeMs())
        persistence.putBoolean("has_gps", true)
        // Add to history
        parkingHistory.add(pos.lat, pos.lng, currentTimeMs(), floorDetector.computeFloor(), hasGps = true)
        // Persist history as simple delimited string
        val historyStr = parkingHistory.getAll().joinToString(";") { e ->
            "${e.lat},${e.lng},${e.timestamp},${e.floor ?: ""},${e.hasGps}"
        }
        persistence.putString("parking_history", historyStr)
    }

    private fun saveParkingNoGps() {
        persistence.putLong("timestamp", currentTimeMs())
        persistence.putBoolean("has_gps", false)
    }

    // --- Utility ---

    private fun currentTimeMs(): Long = platformTimeMs()
}

/**
 * Platform-specific time function.
 * Must be provided by expect/actual or passed in.
 */
expect fun platformTimeMs(): Long

/**
 * Simple data class for state transition info.
 */
data class StateTransition(
    val from: CarPresenceState,
    val to: CarPresenceState
)
