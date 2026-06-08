package com.findmycar.shared

/**
 * Platform abstraction interfaces.
 *
 * These allow all app logic to live in the shared module (Kotlin Multiplatform),
 * with platform-specific implementations for Android and iOS.
 */

// --- Location ---

/**
 * Provides GPS location data.
 * Android: FusedLocationProviderClient
 * iOS: CLLocationManager
 */
interface LocationProvider {
    /** Last known GPS position, or null if no fix. */
    fun getLastLocation(): LatLng?

    /** Last known GPS speed in km/h, or null if no fix. */
    fun getSpeedKmh(): Float?

    /** GPS accuracy in meters, or null. */
    fun getAccuracy(): Float?

    /** Whether a recent GPS fix is available (within last 10 seconds). */
    fun isAvailable(): Boolean

    /** Start location updates at the given interval. */
    fun startUpdates(intervalMs: Long)

    /** Stop location updates. */
    fun stopUpdates()

    /** Change the update interval without stopping/starting. */
    fun setInterval(intervalMs: Long)

    /** Register listener for location changes. */
    fun onLocationChanged(listener: (location: LatLng, speedKmh: Float, accuracy: Float) -> Unit)
}

// --- Sensors ---

/**
 * Provides step detection.
 * Android: TYPE_STEP_DETECTOR
 * iOS: CMPedometer
 */
interface StepProvider {
    /** Whether the user is currently walking (step detected in last 3 seconds). */
    fun isWalking(): Boolean

    /** Steps counted since last reset. */
    fun stepsSinceReset(): Int

    /** Reset step counter. */
    fun resetSteps()

    /** Register listener for step events. */
    fun onStep(listener: () -> Unit)

    /** Start/stop step detection. */
    fun start()
    fun stop()
}

/**
 * Provides barometer (pressure) data.
 * Android: TYPE_PRESSURE
 * iOS: CMAltimeter
 */
interface BarometerProvider {
    /** Current smoothed pressure in hPa, or null if unavailable. */
    fun getPressureHpa(): Float?

    /** Take a one-shot reading (registers sensor briefly). */
    fun readOnce(callback: (Float) -> Unit)

    /** Start continuous readings. */
    fun startContinuous()

    /** Stop continuous readings. */
    fun stopContinuous()
}

/**
 * Provides IMU (accelerometer + gyroscope) data.
 * Android: TYPE_ACCELEROMETER + TYPE_GYROSCOPE
 * iOS: CMMotionManager
 */
interface ImuProvider {
    /** Start IMU sensor sampling. */
    fun start()

    /** Stop IMU sensor sampling. */
    fun stop()

    /** Whether IMU is currently active. */
    fun isActive(): Boolean

    /** Register listener for IMU samples. */
    fun onSample(listener: (accX: Float, accY: Float, accZ: Float,
                            gyroX: Float, gyroY: Float, gyroZ: Float) -> Unit)
}

/**
 * Provides device compass heading.
 * Android: TYPE_ROTATION_VECTOR
 * iOS: CLLocationManager heading
 */
interface CompassProvider {
    /** Current device heading in degrees (0=North, 90=East). */
    fun getHeadingDeg(): Float

    /** Start heading updates. */
    fun start()

    /** Stop heading updates. */
    fun stop()
}

// --- Persistence ---

/**
 * Key-value persistence.
 * Android: SharedPreferences
 * iOS: UserDefaults
 */
interface PersistenceProvider {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String)
    fun getFloat(key: String, default: Float = 0f): Float
    fun putFloat(key: String, value: Float)
    fun getInt(key: String, default: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

// --- Bluetooth ---

/**
 * Monitors car Bluetooth connection.
 * Android: BroadcastReceiver for ACL_CONNECTED/DISCONNECTED
 * iOS: CBCentralManager
 */
interface BluetoothProvider {
    /** Whether the configured car device is currently connected. */
    fun isCarConnected(): Boolean

    /** Start monitoring (with configured device MAC/UUID). */
    fun startMonitoring(deviceId: String?)

    /** Stop monitoring. */
    fun stopMonitoring()

    /** Register listeners. */
    fun onCarConnected(listener: () -> Unit)
    fun onCarDisconnected(listener: () -> Unit)
}

// --- Notifications ---

/**
 * Shows persistent notifications.
 * Android: NotificationManager + Foreground Service
 * iOS: UNUserNotificationCenter (limited background)
 */
interface NotificationProvider {
    fun show(text: String)
    fun update(text: String)
    fun dismiss()
}
