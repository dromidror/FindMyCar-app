package com.findmycar.shared

import kotlin.math.sqrt

/**
 * Computes the 21 model input features from raw sensor readings.
 *
 * The ML model (findmycar-ml) expects a sequence of 50 timesteps, each with 21 features:
 *
 *  0. acc_x                  — raw accelerometer X (m/s²)
 *  1. acc_y                  — raw accelerometer Y (m/s²)
 *  2. acc_z                  — raw accelerometer Z (m/s²)
 *  3. gyro_x                 — raw gyroscope X (rad/s)
 *  4. gyro_y                 — raw gyroscope Y (rad/s)
 *  5. gyro_z                 — raw gyroscope Z (rad/s)
 *  6. yaw_deg                — device orientation yaw (degrees, 0–360)
 *  7. pitch_deg              — device orientation pitch (degrees)
 *  8. roll_deg               — device orientation roll (degrees)
 *  9. acc_magnitude          — sqrt(acc_x² + acc_y² + acc_z²)
 * 10. gyro_magnitude         — sqrt(gyro_x² + gyro_y² + gyro_z²)
 * 11. delta_acc_x            — acc_x change from previous reading (jerk)
 * 12. delta_acc_y            — acc_y change from previous reading
 * 13. delta_acc_z            — acc_z change from previous reading
 * 14. delta_gyro_x           — gyro_x change from previous reading
 * 15. delta_gyro_y           — gyro_y change from previous reading
 * 16. delta_gyro_z           — gyro_z change from previous reading
 * 17. rolling_acc_mag_mean   — rolling mean of acc_magnitude (window=10, ~1 sec)
 * 18. rolling_acc_mag_std    — rolling std of acc_magnitude (window=10)
 * 19. rolling_gyro_mag_mean  — rolling mean of gyro_magnitude (window=10)
 * 20. rolling_gyro_mag_std   — rolling std of gyro_magnitude (window=10)
 *
 * Usage:
 *   val computer = SensorFeatureComputer()
 *   // For each sensor reading at ~10Hz:
 *   computer.addRawReading(accX, accY, accZ, gyroX, gyroY, gyroZ, yawDeg, pitchDeg, rollDeg)
 *   // When ready for inference:
 *   val features: List<FloatArray> = computer.getFeatureSequence(sequenceLen = 50)
 */
class SensorFeatureComputer(
    /** Rolling window size for acc/gyro magnitude stats (~1 sec at 10Hz) */
    private val rollingWindowSize: Int = ROLLING_WINDOW_SIZE
) {

    companion object {
        /** Number of features per timestep expected by the model */
        const val FEATURE_COUNT = 21

        /** Default rolling window for magnitude stats (10 readings ≈ 1 second at 10Hz) */
        const val ROLLING_WINDOW_SIZE = 10

        /** Default sequence length expected by the model */
        const val DEFAULT_SEQUENCE_LEN = 50

        /** Feature names in order, matching findmycar-ml models/config.json */
        val FEATURE_NAMES = listOf(
            "acc_x", "acc_y", "acc_z",
            "gyro_x", "gyro_y", "gyro_z",
            "yaw_deg", "pitch_deg", "roll_deg",
            "acc_magnitude", "gyro_magnitude",
            "delta_acc_x", "delta_acc_y", "delta_acc_z",
            "delta_gyro_x", "delta_gyro_y", "delta_gyro_z",
            "rolling_acc_mag_mean", "rolling_acc_mag_std",
            "rolling_gyro_mag_mean", "rolling_gyro_mag_std"
        )
    }

    /** Internal storage for computed feature vectors */
    private val featureHistory = ArrayDeque<FloatArray>()

    /** Previous raw reading for delta computation */
    private var prevAccX = 0f
    private var prevAccY = 0f
    private var prevAccZ = 0f
    private var prevGyroX = 0f
    private var prevGyroY = 0f
    private var prevGyroZ = 0f
    private var hasPrevious = false

    /** Rolling buffer for magnitude values (for rolling mean/std) */
    private val accMagBuffer = ArrayDeque<Float>()
    private val gyroMagBuffer = ArrayDeque<Float>()

    /** Maximum history to retain (prevents unbounded memory growth) */
    private var maxHistory = 200

    /**
     * Set the maximum number of feature vectors to retain in history.
     * Older entries are discarded. Default is 200.
     */
    fun setMaxHistory(max: Int) {
        maxHistory = max
        trimHistory()
    }

    /**
     * Add a raw sensor reading and compute the full 21-feature vector.
     *
     * Call this at ~10Hz (every ~100ms) with the latest sensor values.
     *
     * @param accX Accelerometer X axis (m/s²)
     * @param accY Accelerometer Y axis (m/s²)
     * @param accZ Accelerometer Z axis (m/s²)
     * @param gyroX Gyroscope X axis (rad/s)
     * @param gyroY Gyroscope Y axis (rad/s)
     * @param gyroZ Gyroscope Z axis (rad/s)
     * @param yawDeg Device yaw in degrees (0–360)
     * @param pitchDeg Device pitch in degrees
     * @param rollDeg Device roll in degrees
     * @return The computed 21-element feature vector for this timestep
     */
    fun addRawReading(
        accX: Float, accY: Float, accZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        yawDeg: Float, pitchDeg: Float, rollDeg: Float
    ): FloatArray {
        // 1. Magnitudes
        val accMag = sqrt(accX * accX + accY * accY + accZ * accZ)
        val gyroMag = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)

        // 2. Deltas (rate of change / jerk) — 0 for the first reading
        val deltaAccX: Float
        val deltaAccY: Float
        val deltaAccZ: Float
        val deltaGyroX: Float
        val deltaGyroY: Float
        val deltaGyroZ: Float

        if (hasPrevious) {
            deltaAccX = accX - prevAccX
            deltaAccY = accY - prevAccY
            deltaAccZ = accZ - prevAccZ
            deltaGyroX = gyroX - prevGyroX
            deltaGyroY = gyroY - prevGyroY
            deltaGyroZ = gyroZ - prevGyroZ
        } else {
            deltaAccX = 0f
            deltaAccY = 0f
            deltaAccZ = 0f
            deltaGyroX = 0f
            deltaGyroY = 0f
            deltaGyroZ = 0f
        }

        prevAccX = accX
        prevAccY = accY
        prevAccZ = accZ
        prevGyroX = gyroX
        prevGyroY = gyroY
        prevGyroZ = gyroZ
        hasPrevious = true

        // 3. Update rolling buffers
        accMagBuffer.addLast(accMag)
        gyroMagBuffer.addLast(gyroMag)
        while (accMagBuffer.size > rollingWindowSize) accMagBuffer.removeFirst()
        while (gyroMagBuffer.size > rollingWindowSize) gyroMagBuffer.removeFirst()

        // 4. Rolling statistics
        val rollingAccMagMean = mean(accMagBuffer)
        val rollingAccMagStd = std(accMagBuffer, rollingAccMagMean)
        val rollingGyroMagMean = mean(gyroMagBuffer)
        val rollingGyroMagStd = std(gyroMagBuffer, rollingGyroMagMean)

        // 5. Assemble the 21-feature vector
        val features = FloatArray(FEATURE_COUNT)
        features[0] = accX
        features[1] = accY
        features[2] = accZ
        features[3] = gyroX
        features[4] = gyroY
        features[5] = gyroZ
        features[6] = yawDeg
        features[7] = pitchDeg
        features[8] = rollDeg
        features[9] = accMag
        features[10] = gyroMag
        features[11] = deltaAccX
        features[12] = deltaAccY
        features[13] = deltaAccZ
        features[14] = deltaGyroX
        features[15] = deltaGyroY
        features[16] = deltaGyroZ
        features[17] = rollingAccMagMean
        features[18] = rollingAccMagStd
        features[19] = rollingGyroMagMean
        features[20] = rollingGyroMagStd

        featureHistory.addLast(features)
        trimHistory()

        return features
    }

    /**
     * Get the feature sequence for model inference.
     *
     * Returns a list of [sequenceLen] feature vectors (each 21 floats).
     * If fewer readings are available, pads the beginning with zeros.
     *
     * @param sequenceLen Number of timesteps the model expects (default: 50)
     * @return List of FloatArray, each of size 21. List size = sequenceLen.
     */
    fun getFeatureSequence(sequenceLen: Int = DEFAULT_SEQUENCE_LEN): List<FloatArray> {
        val available = featureHistory.size
        return if (available >= sequenceLen) {
            featureHistory.toList().takeLast(sequenceLen)
        } else {
            val padding = List(sequenceLen - available) { FloatArray(FEATURE_COUNT) }
            padding + featureHistory.toList()
        }
    }

    /**
     * Get the feature sequence as a flat FloatArray suitable for TFLite input buffer.
     *
     * Layout: [timestep0_feat0, timestep0_feat1, ..., timestep0_feat20,
     *          timestep1_feat0, ..., timestepN_feat20]
     *
     * @param sequenceLen Number of timesteps the model expects (default: 50)
     * @return FloatArray of size sequenceLen * 21
     */
    fun getFeatureSequenceFlat(sequenceLen: Int = DEFAULT_SEQUENCE_LEN): FloatArray {
        val sequence = getFeatureSequence(sequenceLen)
        val flat = FloatArray(sequenceLen * FEATURE_COUNT)
        for (t in sequence.indices) {
            sequence[t].copyInto(flat, t * FEATURE_COUNT)
        }
        return flat
    }

    /**
     * Get the number of feature vectors currently stored.
     */
    fun availableReadings(): Int = featureHistory.size

    /**
     * Check if enough readings have been collected for a full inference window.
     */
    fun isReady(sequenceLen: Int = DEFAULT_SEQUENCE_LEN): Boolean =
        featureHistory.size >= sequenceLen

    /**
     * Clear all stored readings and reset state.
     */
    fun reset() {
        featureHistory.clear()
        accMagBuffer.clear()
        gyroMagBuffer.clear()
        hasPrevious = false
        prevAccX = 0f; prevAccY = 0f; prevAccZ = 0f
        prevGyroX = 0f; prevGyroY = 0f; prevGyroZ = 0f
    }

    /**
     * Get the latest computed feature vector, or null if no readings yet.
     */
    fun latestFeatures(): FloatArray? =
        if (featureHistory.isEmpty()) null else featureHistory.last()

    private fun trimHistory() {
        while (featureHistory.size > maxHistory) {
            featureHistory.removeFirst()
        }
    }

    private fun mean(buffer: ArrayDeque<Float>): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0f
        for (v in buffer) sum += v
        return sum / buffer.size
    }

    private fun std(buffer: ArrayDeque<Float>, mean: Float): Float {
        if (buffer.size < 2) return 0f
        var sumSq = 0f
        for (v in buffer) {
            val diff = v - mean
            sumSq += diff * diff
        }
        return sqrt(sumSq / buffer.size)
    }
}
