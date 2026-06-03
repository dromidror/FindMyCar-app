package com.findmycar.shared

/**
 * Applies StandardScaler normalization to feature vectors.
 *
 * Replicates sklearn's StandardScaler: scaled = (value - mean) / scale
 *
 * The scaler parameters are loaded from the model's config.json (scaler_mean and scaler_scale),
 * which are saved during training in findmycar-ml.
 *
 * Usage:
 *   val scaler = FeatureScaler(meanArray, scaleArray)
 *   val normalized = scaler.transform(rawFeatures)
 *   // or for a full sequence:
 *   val normalizedSeq = scaler.transformSequence(featureSequence)
 */
class FeatureScaler(
    /** Per-feature means (length = 21) */
    private val mean: FloatArray,
    /** Per-feature scales / standard deviations (length = 21) */
    private val scale: FloatArray
) {
    init {
        require(mean.size == scale.size) {
            "Mean and scale arrays must have the same length. Got mean=${mean.size}, scale=${scale.size}"
        }
        require(mean.size == SensorFeatureComputer.FEATURE_COUNT) {
            "Expected ${SensorFeatureComputer.FEATURE_COUNT} features, got ${mean.size}"
        }
    }

    /**
     * Number of features this scaler handles.
     */
    val featureCount: Int get() = mean.size

    /**
     * Normalize a single feature vector in-place.
     *
     * @param features A 21-element FloatArray of raw feature values
     * @return The same array, now containing normalized values
     */
    fun transformInPlace(features: FloatArray): FloatArray {
        for (i in features.indices) {
            val s = scale[i]
            features[i] = if (s != 0f) (features[i] - mean[i]) / s else 0f
        }
        return features
    }

    /**
     * Normalize a single feature vector, returning a new array.
     *
     * @param features A 21-element FloatArray of raw feature values
     * @return A new FloatArray with normalized values
     */
    fun transform(features: FloatArray): FloatArray {
        val result = FloatArray(features.size)
        for (i in features.indices) {
            val s = scale[i]
            result[i] = if (s != 0f) (features[i] - mean[i]) / s else 0f
        }
        return result
    }

    /**
     * Normalize a sequence of feature vectors.
     *
     * @param sequence List of 21-element FloatArrays
     * @return New list of normalized FloatArrays
     */
    fun transformSequence(sequence: List<FloatArray>): List<FloatArray> {
        return sequence.map { transform(it) }
    }

    /**
     * Normalize a flat feature array (sequenceLen * 21 floats) in-place.
     *
     * @param flat A flat array of size sequenceLen * featureCount
     * @param sequenceLen Number of timesteps
     * @return The same array, now normalized
     */
    fun transformFlatInPlace(flat: FloatArray, sequenceLen: Int): FloatArray {
        val fc = featureCount
        for (t in 0 until sequenceLen) {
            val offset = t * fc
            for (i in 0 until fc) {
                val s = scale[i]
                flat[offset + i] = if (s != 0f) (flat[offset + i] - mean[i]) / s else 0f
            }
        }
        return flat
    }

    /**
     * Inverse transform: convert normalized values back to original scale.
     *
     * @param features A 21-element FloatArray of normalized values
     * @return A new FloatArray with original-scale values
     */
    fun inverseTransform(features: FloatArray): FloatArray {
        val result = FloatArray(features.size)
        for (i in features.indices) {
            result[i] = features[i] * scale[i] + mean[i]
        }
        return result
    }

    companion object {
        /**
         * Create a FeatureScaler from Double arrays (as stored in JSON config).
         */
        fun fromDoubles(mean: List<Double>, scale: List<Double>): FeatureScaler {
            return FeatureScaler(
                mean = FloatArray(mean.size) { mean[it].toFloat() },
                scale = FloatArray(scale.size) { scale[it].toFloat() }
            )
        }

        /**
         * Default scaler parameters from the trained model (findmycar-ml models/config.json).
         *
         * These are baked in as fallback values. The app should prefer loading from
         * the downloaded model_config.json, but these serve as a safety net.
         */
        val DEFAULT = FeatureScaler(
            mean = floatArrayOf(
                0.17123881f, 2.80132290f, 5.52661558f,       // acc_x, acc_y, acc_z
                -0.00058202f, 0.00092339f, -0.00235666f,     // gyro_x, gyro_y, gyro_z
                149.36780767f, -10.67880259f, 5.97430727f,   // yaw, pitch, roll
                9.90804536f, 0.14691297f,                     // acc_mag, gyro_mag
                -0.00039678f, -0.00160779f, -0.00156321f,   // delta_acc_x/y/z
                0.00016808f, 0.00050562f, 0.00005130f,       // delta_gyro_x/y/z
                9.90684310f, 0.43819640f,                     // rolling_acc_mag_mean/std
                0.14748465f, 0.07698161f                      // rolling_gyro_mag_mean/std
            ),
            scale = floatArrayOf(
                3.21949157f, 4.87778996f, 5.28118796f,       // acc_x, acc_y, acc_z
                0.32523145f, 0.44142441f, 0.30004211f,       // gyro_x, gyro_y, gyro_z
                119.16806564f, 29.62435617f, 56.23819330f,   // yaw, pitch, roll
                1.51649489f, 0.60751922f,                     // acc_mag, gyro_mag
                1.33504134f, 1.45699898f, 1.83611385f,       // delta_acc_x/y/z
                0.43030357f, 0.63570430f, 0.37135534f,       // delta_gyro_x/y/z
                0.71202991f, 1.33396572f,                     // rolling_acc_mag_mean/std
                0.53997191f, 0.29183416f                      // rolling_gyro_mag_mean/std
            )
        )
    }
}
