package com.findmycar.shared

import kotlin.math.sqrt

/**
 * End-to-end feature pipeline for car state inference.
 *
 * Combines [SensorFeatureComputer] (raw → 21 features) and [FeatureScaler] (normalization)
 * into a single pipeline that produces model-ready input from raw sensor readings.
 *
 * This replicates the preprocessing done in findmycar-ml's preprocess.py + train.py:
 *   raw sensor → compute_trend_features() → StandardScaler → model input
 *
 * Usage:
 *   val pipeline = CarStateFeaturePipeline(scaler)
 *   // Feed readings at ~10Hz:
 *   pipeline.addReading(accX, accY, accZ, gyroX, gyroY, gyroZ, yaw, pitch, roll)
 *   // When ready for inference:
 *   val modelInput: FloatArray = pipeline.getModelInput()  // flat array for TFLite
 */
class CarStateFeaturePipeline(
    private val scaler: FeatureScaler = FeatureScaler.DEFAULT,
    private val sequenceLen: Int = SensorFeatureComputer.DEFAULT_SEQUENCE_LEN,
    rollingWindowSize: Int = SensorFeatureComputer.ROLLING_WINDOW_SIZE
) {
    private val computer = SensorFeatureComputer(rollingWindowSize)

    init {
        // Keep enough history for the sequence + some buffer
        computer.setMaxHistory(sequenceLen + 50)
    }

    /**
     * Add a raw sensor reading. Call at ~10Hz.
     *
     * @return The 21-element normalized feature vector for this timestep
     */
    fun addReading(
        accX: Float, accY: Float, accZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        yawDeg: Float, pitchDeg: Float, rollDeg: Float
    ): FloatArray {
        val raw = computer.addRawReading(accX, accY, accZ, gyroX, gyroY, gyroZ, yawDeg, pitchDeg, rollDeg)
        return scaler.transform(raw)
    }

    /**
     * Get the normalized feature sequence as a flat FloatArray for TFLite.
     *
     * Shape: [1, sequenceLen, 21] flattened to [sequenceLen * 21]
     * Ready to be written into a ByteBuffer for model inference.
     */
    fun getModelInput(): FloatArray {
        val rawSequence = computer.getFeatureSequenceFlat(sequenceLen)
        return scaler.transformFlatInPlace(rawSequence, sequenceLen)
    }

    /**
     * Get the normalized feature sequence as a list of FloatArrays.
     *
     * @return List of [sequenceLen] normalized 21-element feature vectors
     */
    fun getModelInputSequence(): List<FloatArray> {
        val rawSequence = computer.getFeatureSequence(sequenceLen)
        return scaler.transformSequence(rawSequence)
    }

    /**
     * Whether enough readings have been collected for a full inference window.
     */
    fun isReady(): Boolean = computer.isReady(sequenceLen)

    /**
     * Number of readings collected so far.
     */
    fun availableReadings(): Int = computer.availableReadings()

    /**
     * Get the latest raw (un-normalized) feature vector, or null if no readings yet.
     */
    fun latestRawFeatures(): FloatArray? = computer.latestFeatures()

    /**
     * Get the latest normalized feature vector, or null if no readings yet.
     */
    fun latestNormalizedFeatures(): FloatArray? =
        computer.latestFeatures()?.let { scaler.transform(it) }

    /**
     * Reset the pipeline, clearing all history.
     */
    fun reset() = computer.reset()

    /**
     * Quick stationary check based on recent acceleration and gyroscope variability.
     *
     * When the device is completely still, there's no point running the model —
     * we can short-circuit to "STOPPED" state.
     *
     * @param accStdThreshold Sum of acc std across axes below which = stationary
     * @param gyroStdThreshold Sum of gyro std across axes below which = stationary
     * @param minSamples Minimum samples needed for a reliable check
     * @return true if the device appears stationary, false otherwise, null if not enough data
     */
    fun isStationary(
        accStdThreshold: Float = 0.3f,
        gyroStdThreshold: Float = 0.05f,
        minSamples: Int = 10
    ): Boolean? {
        val readings = computer.availableReadings()
        if (readings < minSamples) return null

        val sequence = computer.getFeatureSequence(minOf(readings, 20))

        // Compute std of raw acc and gyro across recent readings
        val accXValues = sequence.map { it[0] }
        val accYValues = sequence.map { it[1] }
        val accZValues = sequence.map { it[2] }
        val gyroXValues = sequence.map { it[3] }
        val gyroYValues = sequence.map { it[4] }
        val gyroZValues = sequence.map { it[5] }

        val accStdSum = stdOf(accXValues) + stdOf(accYValues) + stdOf(accZValues)
        val gyroStdSum = stdOf(gyroXValues) + stdOf(gyroYValues) + stdOf(gyroZValues)

        return accStdSum < accStdThreshold && gyroStdSum < gyroStdThreshold
    }

    /**
     * Get summary statistics for the current window (useful for debug display).
     */
    fun getWindowStats(): WindowStats? {
        val readings = computer.availableReadings()
        if (readings < 2) return null

        val count = minOf(readings, sequenceLen)
        val sequence = computer.getFeatureSequence(count)

        val accXValues = sequence.map { it[0] }
        val accYValues = sequence.map { it[1] }
        val accZValues = sequence.map { it[2] }
        val gyroXValues = sequence.map { it[3] }
        val gyroYValues = sequence.map { it[4] }
        val gyroZValues = sequence.map { it[5] }

        return WindowStats(
            sampleCount = count,
            accXMean = meanOf(accXValues), accYMean = meanOf(accYValues), accZMean = meanOf(accZValues),
            gyroXMean = meanOf(gyroXValues), gyroYMean = meanOf(gyroYValues), gyroZMean = meanOf(gyroZValues),
            accXStd = stdOf(accXValues), accYStd = stdOf(accYValues), accZStd = stdOf(accZValues),
            gyroXStd = stdOf(gyroXValues), gyroYStd = stdOf(gyroYValues), gyroZStd = stdOf(gyroZValues),
            accStdSum = stdOf(accXValues) + stdOf(accYValues) + stdOf(accZValues),
            gyroStdSum = stdOf(gyroXValues) + stdOf(gyroYValues) + stdOf(gyroZValues)
        )
    }

    private fun meanOf(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        var sum = 0f
        for (v in values) sum += v
        return sum / values.size
    }

    private fun stdOf(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val m = meanOf(values)
        var sumSq = 0f
        for (v in values) {
            val diff = v - m
            sumSq += diff * diff
        }
        return sqrt(sumSq / values.size)
    }
}

/**
 * Summary statistics for the current sensor window (for debug/display purposes).
 */
data class WindowStats(
    val sampleCount: Int,
    val accXMean: Float, val accYMean: Float, val accZMean: Float,
    val gyroXMean: Float, val gyroYMean: Float, val gyroZMean: Float,
    val accXStd: Float, val accYStd: Float, val accZStd: Float,
    val gyroXStd: Float, val gyroYStd: Float, val gyroZStd: Float,
    val accStdSum: Float,
    val gyroStdSum: Float
)
