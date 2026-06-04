package com.findmycar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.findmycar.shared.CarPresenceState
import com.findmycar.shared.CarPresenceStateMachine
import com.findmycar.shared.CarStateFeaturePipeline
import com.findmycar.shared.DisplacementVector
import com.findmycar.shared.FeatureScaler
import com.findmycar.shared.LatLng
import com.findmycar.shared.PathAccumulator
import com.findmycar.shared.ParkingSpot
import com.findmycar.shared.SensorFeatureComputer
import com.findmycar.shared.StateMachineInput
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground service for car exit detection.
 *
 * Motion state detection priority:
 *   1. GPS available → use GPS speed (speed < 1 km/h = STOPPED, >= 1 = MOVING)
 *   2. No GPS → fall back to ML model inference from sensors
 *
 * Combines motion state with step counter + pickup detection to determine
 * when the user exits their parked car.
 */
class ExitDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private lateinit var parkingPrefs: SharedPreferences

    private val stateMachine = CarPresenceStateMachine()
    private val handler = Handler(Looper.getMainLooper())
    private val pathAccumulator = PathAccumulator()
    private val stepDeadReckoning = com.findmycar.shared.StepDeadReckoning()

    // ML model (fallback when no GPS)
    private var featurePipeline: CarStateFeaturePipeline? = null
    private var interpreter: Interpreter? = null
    private var inputByteSize = 0
    private var outputByteSize = 0
    private var sequenceLen = SensorFeatureComputer.DEFAULT_SEQUENCE_LEN

    // Sensor state
    private val accelerometer = FloatArray(3)
    private val gyroscope = FloatArray(3)
    private var yawDeg = 0f
    private var pitchDeg = 0f
    private var rollDeg = 0f

    // Step counter
    private var stepCountBaseline = -1
    private var stepCountCurrent = 0
    private var stepCountAtStop = 0
    private var stepCountAtPickup = 0
    private var stepCountAtSlowStart = 0

    // GPS state
    private var lastGpsLocation: Location? = null
    private var lastGpsFixTimeMs = 0L
    private var gpsSpeedKmh = 0f
    private var gpsActive = false

    // Motion state tracking
    private var currentMotionState = "CAR_STOPPED"
    private var motionStateStartMs = System.currentTimeMillis()
    private var stopTimestampMs = 0L

    // Pickup detection
    private var pickupDetector: PickupDetector? = null
    private var pickupDetected = false

    // Bluetooth
    private var bluetoothMonitor: CarBluetoothMonitor? = null
    private var carBluetoothConnected = false

    // Test logger
    private var testLogger: TestLogger? = null
    private var roninDR: RoninDeadReckoning? = null
    private var roninInterpreter: Interpreter? = null

    // Power (USB charging as fallback for Bluetooth)
    // Only relevant if phone was charging while DRIVING (not wall charger)
    private var wasChargingWhileDriving = false
    private var powerDisconnectedWhileStopped = false
    private var powerReceiverRegistered = false

    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (wasChargingWhileDriving && currentMotionState == "CAR_STOPPED"
                        && stateMachine.state == CarPresenceState.IN_CAR) {
                        powerDisconnectedWhileStopped = true
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    // If charging starts while driving, remember it
                    if (currentMotionState == "CAR_MOVING" && stateMachine.state == CarPresenceState.IN_CAR) {
                        wasChargingWhileDriving = true
                    }
                    powerDisconnectedWhileStopped = false
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "exit_detection"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS_NAME = "exit_detection_state"
        private const val PARKING_PREFS_NAME = "parking_spot"
        private const val KEY_STATE = "presence_state"
        private const val MODEL_LOCAL_FILE = "car_state_model.tflite"
        private const val CONFIG_LOCAL_FILE = "model_config.json"

        private const val INFERENCE_INTERVAL_MS = 5000L
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val GPS_STALE_MS = 10_000L  // GPS considered unavailable after 10s without fix
        private const val SPEED_STOPPED_THRESHOLD_KMH = 1.0f

        const val ACTION_TOGGLE_LOGGING = "com.findmycar.app.TOGGLE_LOGGING"
        const val ACTION_USER_CAR_EXIT = "com.findmycar.app.USER_CAR_EXIT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ExitDetectionService::class.java)
            )
        }
    }

    private val loggingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE_LOGGING -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    if (enabled) testLogger?.start() else testLogger?.stop()
                }
                ACTION_USER_CAR_EXIT -> {
                    // Log a special marker row indicating user manually pressed "Car Exit"
                    val loc = lastGpsLocation
                    testLogger?.log(
                        lat = loc?.latitude,
                        lng = loc?.longitude,
                        gpsSpeedKmh = if (hasRecentGps()) gpsSpeedKmh else null,
                        gpsAccuracy = loc?.accuracy,
                        roninDNorth = 0f, roninDEast = 0f,
                        roninTotalNorth = pathAccumulator.totalNorth,
                        roninTotalEast = pathAccumulator.totalEast,
                        motionState = "USER_EXIT_PRESSED",
                        presenceState = stateMachine.state.name,
                        stepsSinceStop = stepCountCurrent - stepCountAtStop
                    )
                }
            }
        }
    }

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        parkingPrefs = getSharedPreferences(PARKING_PREFS_NAME, Context.MODE_PRIVATE)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))

        // Restore state
        val savedState = prefs.getString(KEY_STATE, "UNKNOWN") ?: "UNKNOWN"
        stateMachine.restoreState(
            when (savedState) {
                "IN_CAR" -> CarPresenceState.IN_CAR
                "EXITED" -> CarPresenceState.EXITED
                else -> CarPresenceState.UNKNOWN
            }
        )

        loadModel()
        startSensors()
        startGps()
        startBluetoothMonitor()
        registerPowerReceiver()

        // Test logging
        testLogger = TestLogger(this)
        loadRoninModel()

        // Register logging control receiver
        val logFilter = android.content.IntentFilter().apply {
            addAction(ACTION_TOGGLE_LOGGING)
            addAction(ACTION_USER_CAR_EXIT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(loggingReceiver, logFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(loggingReceiver, logFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        stopGps()
        bluetoothMonitor?.stop()
        unregisterPowerReceiver()
        unregisterReceiver(loggingReceiver)
        testLogger?.shutdown()
        roninInterpreter?.close()
        interpreter?.close()
    }

    // --- Model loading (fallback for no-GPS) ---

    private fun loadRoninModel() {
        val roninFile = File(filesDir, "ronin_model.tflite")
        if (!roninFile.exists()) return
        try {
            roninInterpreter = Interpreter(roninFile, Interpreter.Options())
            roninDR = RoninDeadReckoning()
        } catch (_: Exception) {}
    }

    private fun loadModel() {
        val modelFile = File(filesDir, MODEL_LOCAL_FILE)
        val configFile = File(filesDir, CONFIG_LOCAL_FILE)
        if (!modelFile.exists() || !configFile.exists()) return

        try {
            val config = JSONObject(configFile.readText())
            val scalerMeanArray = config.optJSONArray("scaler_mean")
            val scalerScaleArray = config.optJSONArray("scaler_scale")
            val seqLen = config.optInt("sequence_len", SensorFeatureComputer.DEFAULT_SEQUENCE_LEN)
            sequenceLen = seqLen

            if (scalerMeanArray != null && scalerScaleArray != null &&
                scalerMeanArray.length() == SensorFeatureComputer.FEATURE_COUNT &&
                scalerScaleArray.length() == SensorFeatureComputer.FEATURE_COUNT) {
                val mean = FloatArray(scalerMeanArray.length()) { scalerMeanArray.getDouble(it).toFloat() }
                val scale = FloatArray(scalerScaleArray.length()) { scalerScaleArray.getDouble(it).toFloat() }
                featurePipeline = CarStateFeaturePipeline(
                    scaler = FeatureScaler(mean, scale),
                    sequenceLen = seqLen
                )
            }

            val tflite = Interpreter(modelFile, Interpreter.Options())
            inputByteSize = tflite.getInputTensor(0).numBytes()
            outputByteSize = tflite.getOutputTensor(0).numBytes()
            interpreter = tflite
        } catch (_: Exception) {}
    }

    // --- GPS ---

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastGpsLocation = loc
            lastGpsFixTimeMs = System.currentTimeMillis()
            gpsSpeedKmh = loc.speed * 3.6f

            // If PathAccumulator was active and GPS returned → anchor
            if (pathAccumulator.isActive) {
                val displacement = pathAccumulator.stopAndEmit()
                // Resolve parking location backward
                val currentLatLng = LatLng(loc.latitude, loc.longitude)
                val parkingLatLng = currentLatLng.minus(displacement)
                saveParkingSpot(parkingLatLng, loc.accuracy)
            }
        }
    }

    private fun startGps() {
        if (gpsActive) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            gpsActive = true
        } catch (_: SecurityException) {}
    }

    private fun stopGps() {
        if (!gpsActive) return
        fusedClient.removeLocationUpdates(locationCallback)
        gpsActive = false
    }

    private fun hasRecentGps(): Boolean {
        return lastGpsFixTimeMs > 0 && (System.currentTimeMillis() - lastGpsFixTimeMs) < GPS_STALE_MS
    }

    // --- Sensors ---

    private fun startSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Pickup detector
        pickupDetector = PickupDetector { onPickupDetected() }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(pickupDetector, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        handler.post(sampleRunnable)
        handler.postDelayed(inferenceRunnable, INFERENCE_INTERVAL_MS)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometer[0] = event.values[0]
                accelerometer[1] = event.values[1]
                accelerometer[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroscope[0] = event.values[0]
                gyroscope[1] = event.values[1]
                gyroscope[2] = event.values[2]
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rm = FloatArray(9); val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rm, event.values)
                SensorManager.getOrientation(rm, o)
                yawDeg = ((Math.toDegrees(o[0].toDouble()) + 360.0) % 360.0).toFloat()
                pitchDeg = Math.toDegrees(o[1].toDouble()).toFloat()
                rollDeg = Math.toDegrees(o[2].toDouble()).toFloat()
                // Update step dead reckoning heading
                stepDeadReckoning.updateHeading(yawDeg)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0].toInt()
                if (stepCountBaseline < 0) stepCountBaseline = totalSteps
                stepCountCurrent = totalSteps
                // Accumulate displacement during GPS gaps
                if (pathAccumulator.isActive) {
                    val displacement = stepDeadReckoning.onStepCount(totalSteps)
                    if (displacement.magnitude > 0f) {
                        pathAccumulator.addDisplacement(displacement)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Sampling ---

    private val sampleRunnable = object : Runnable {
        override fun run() {
            featurePipeline?.addReading(
                accelerometer[0], accelerometer[1], accelerometer[2],
                gyroscope[0], gyroscope[1], gyroscope[2],
                yawDeg, pitchDeg, rollDeg
            )
            // Feed RoNIN
            roninDR?.addSample(
                accelerometer[0], accelerometer[1], accelerometer[2],
                gyroscope[0], gyroscope[1], gyroscope[2]
            )
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    // --- Motion state detection ---

    private val inferenceRunnable = object : Runnable {
        override fun run() {
            determineMotionState()
            evaluateStateMachine()

            // Run RoNIN inference and log
            var roninDN = 0f
            var roninDE = 0f
            val ri = roninInterpreter
            val rd = roninDR
            if (ri != null && rd != null && rd.isReady()) {
                val dv = rd.infer(ri)
                if (dv != null) {
                    roninDN = dv.dNorth
                    roninDE = dv.dEast
                    if (pathAccumulator.isActive) {
                        pathAccumulator.addDisplacement(dv)
                    }
                }
            }

            // Log to test file
            val loc = lastGpsLocation
            testLogger?.log(
                lat = loc?.latitude,
                lng = loc?.longitude,
                gpsSpeedKmh = if (hasRecentGps()) gpsSpeedKmh else null,
                gpsAccuracy = loc?.accuracy,
                roninDNorth = roninDN,
                roninDEast = roninDE,
                roninTotalNorth = pathAccumulator.totalNorth,
                roninTotalEast = pathAccumulator.totalEast,
                motionState = currentMotionState,
                presenceState = stateMachine.state.name,
                stepsSinceStop = stepCountCurrent - stepCountAtStop
            )

            handler.postDelayed(this, INFERENCE_INTERVAL_MS)
        }
    }

    /**
     * Determine current motion state.
     * Priority: GPS speed first, ML model fallback.
     */
    private fun determineMotionState() {
        val newMotionState: String

        if (hasRecentGps()) {
            // PRIMARY: Use GPS speed directly
            newMotionState = if (gpsSpeedKmh < SPEED_STOPPED_THRESHOLD_KMH) {
                "CAR_STOPPED"
            } else {
                "CAR_MOVING"
            }
        } else {
            // FALLBACK: Use ML model (no GPS available)
            newMotionState = runModelInference() ?: currentMotionState
        }

        if (newMotionState != currentMotionState) {
            onMotionStateChanged(currentMotionState, newMotionState)
            currentMotionState = newMotionState
            motionStateStartMs = System.currentTimeMillis()
        }
    }

    /**
     * Run ML model inference. Returns motion state string or null if model unavailable.
     */
    private fun runModelInference(): String? {
        val model = interpreter ?: return null
        val pipeline = featurePipeline ?: return null
        if (pipeline.availableReadings() < sequenceLen) return null

        val modelInput = pipeline.getModelInput()
        val inputBuffer = ByteBuffer.allocateDirect(inputByteSize).order(ByteOrder.nativeOrder())
        val expectedFloats = sequenceLen * SensorFeatureComputer.FEATURE_COUNT
        for (i in 0 until minOf(inputByteSize / 4, expectedFloats)) {
            inputBuffer.putFloat(modelInput[i])
        }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(outputByteSize).order(ByteOrder.nativeOrder())
        return try {
            model.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val numOutputs = outputByteSize / 4
            if (numOutputs == 1) {
                // Binary sigmoid: > 0.5 = STOPPED
                val score = outputBuffer.float
                if (score > 0.5f) "CAR_STOPPED" else "CAR_MOVING"
            } else {
                // Multi-class softmax: argmax
                val scores = FloatArray(numOutputs) { outputBuffer.float }
                var maxIdx = 0
                for (i in 1 until scores.size) {
                    if (scores[i] > scores[maxIdx]) maxIdx = i
                }
                if (maxIdx == 0) "CAR_MOVING" else "CAR_STOPPED"
            }
        } catch (_: Exception) { null }
    }

    private fun onMotionStateChanged(oldState: String, newState: String) {
        if (newState == "CAR_STOPPED" && oldState != "CAR_STOPPED") {
            stopTimestampMs = System.currentTimeMillis()
            stepCountAtStop = stepCountCurrent
            pickupDetected = false
            pickupDetector?.reset()
            powerDisconnectedWhileStopped = false
        }
        if (newState == "CAR_MOVING") {
            stepCountAtSlowStart = stepCountCurrent
            // If charging while driving → mark for exit detection
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            if (plugged > 0) wasChargingWhileDriving = true
        }
    }

    private fun onPickupDetected() {
        if (stateMachine.state == CarPresenceState.IN_CAR && currentMotionState == "CAR_STOPPED") {
            pickupDetected = true
            stepCountAtPickup = stepCountCurrent
        }
    }

    // --- State machine ---

    private fun evaluateStateMachine() {
        val now = System.currentTimeMillis()
        val stepsSinceStop = stepCountCurrent - stepCountAtStop
        val stepsSincePickup = if (pickupDetected) stepCountCurrent - stepCountAtPickup else 0
        val stepsDuringMotion = stepCountCurrent - stepCountAtSlowStart

        val input = StateMachineInput(
            motionState = currentMotionState,
            motionStateDurationMs = now - motionStateStartMs,
            stepsSinceStop = stepsSinceStop,
            stepsDuringSlowMotion = stepsDuringMotion,
            timeSinceStopMs = if (stopTimestampMs > 0) now - stopTimestampMs else 0,
            pickupDetected = pickupDetected,
            stepsSincePickup = stepsSincePickup,
            carBluetoothConnected = carBluetoothConnected
        )

        val oldState = stateMachine.state
        val newState = stateMachine.evaluate(input)

        if (newState != oldState) {
            onStateTransition(oldState, newState)
        }
    }

    private fun onStateTransition(from: CarPresenceState, to: CarPresenceState) {
        prefs.edit().putString(KEY_STATE, to.name).apply()

        when (to) {
            CarPresenceState.IN_CAR -> {
                wasChargingWhileDriving = false
                powerDisconnectedWhileStopped = false
                updateNotification("🚗 In car — tracking")
            }
            CarPresenceState.EXITED -> {
                // Save parking spot
                val loc = lastGpsLocation
                if (loc != null && hasRecentGps()) {
                    saveParkingSpot(LatLng(loc.latitude, loc.longitude), loc.accuracy)
                } else {
                    // No GPS — start path accumulation for backward resolution
                    pathAccumulator.start()
                    saveParkingNoGps()
                }
                updateNotification("🅿️ Car parked")
            }
            CarPresenceState.UNKNOWN -> {
                updateNotification("⏳ Waiting for drive...")
            }
        }
    }

    // --- Parking spot persistence ---

    private fun saveParkingSpot(latLng: LatLng, accuracy: Float?) {
        parkingPrefs.edit()
            .putFloat("latitude", latLng.lat.toFloat())
            .putFloat("longitude", latLng.lng.toFloat())
            .putFloat("accuracy", accuracy ?: 0f)
            .putLong("timestamp", System.currentTimeMillis())
            .putBoolean("has_gps", true)
            .putBoolean("has_relative_only", false)
            .apply()
    }

    private fun saveParkingNoGps() {
        parkingPrefs.edit()
            .putLong("timestamp", System.currentTimeMillis())
            .putBoolean("has_gps", false)
            .putBoolean("has_relative_only", true)
            .apply()
    }

    // --- Bluetooth ---

    private fun startBluetoothMonitor() {
        val mac = CarBluetoothMonitor.getCarMac(this)
        if (mac.isNullOrBlank()) return

        bluetoothMonitor = CarBluetoothMonitor(
            context = this,
            onConnected = {
                carBluetoothConnected = true
                handler.post { evaluateStateMachine() }
            },
            onDisconnected = { carBluetoothConnected = false }
        )
        bluetoothMonitor?.start(mac)
    }

    // --- Power monitoring ---

    private fun registerPowerReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
        powerReceiverRegistered = true

        // Check if currently charging (for initial state)
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isCharging = plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
        if (isCharging && stateMachine.state == CarPresenceState.IN_CAR) {
            wasChargingWhileDriving = true
        }
    }

    private fun unregisterPowerReceiver() {
        if (!powerReceiverRegistered) return
        unregisterReceiver(powerReceiver)
        powerReceiverRegistered = false
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FindMyCar",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Car presence monitoring" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindMyCar")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
