package com.findmycar.shared

/**
 * Holds model configuration loaded from model_config.json.
 *
 * This mirrors the JSON structure exported by findmycar-ml's export.py:
 * {
 *   "feature_cols": [...],
 *   "states": ["CAR_MOVING", "CAR_STOPPED"],
 *   "sequence_len": 50,
 *   "scaler_mean": [...],
 *   "scaler_scale": [...]
 * }
 */
data class ModelConfig(
    /** Ordered list of feature column names (should be 21) */
    val featureCols: List<String>,
    /** State labels: ["CAR_MOVING", "CAR_STOPPED"] */
    val states: List<String>,
    /** Number of timesteps per inference window */
    val sequenceLen: Int,
    /** Per-feature means for StandardScaler normalization */
    val scalerMean: List<Double>,
    /** Per-feature scales (std dev) for StandardScaler normalization */
    val scalerScale: List<Double>
) {
    /**
     * Create a [FeatureScaler] from this config's scaler parameters.
     */
    fun toFeatureScaler(): FeatureScaler = FeatureScaler.fromDoubles(scalerMean, scalerScale)

    /**
     * Create a fully configured [CarStateFeaturePipeline] from this config.
     */
    fun toPipeline(): CarStateFeaturePipeline = CarStateFeaturePipeline(
        scaler = toFeatureScaler(),
        sequenceLen = sequenceLen
    )

    /**
     * Validate that this config matches the expected 21-feature model.
     *
     * @return List of validation errors, empty if valid
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (featureCols.size != SensorFeatureComputer.FEATURE_COUNT) {
            errors.add("Expected ${SensorFeatureComputer.FEATURE_COUNT} features, got ${featureCols.size}")
        }
        if (scalerMean.size != featureCols.size) {
            errors.add("scaler_mean length (${scalerMean.size}) != feature_cols length (${featureCols.size})")
        }
        if (scalerScale.size != featureCols.size) {
            errors.add("scaler_scale length (${scalerScale.size}) != feature_cols length (${featureCols.size})")
        }
        if (states.isEmpty()) {
            errors.add("states list is empty")
        }
        if (sequenceLen <= 0) {
            errors.add("sequence_len must be positive, got $sequenceLen")
        }
        // Verify feature order matches expected
        val expected = SensorFeatureComputer.FEATURE_NAMES
        for (i in featureCols.indices) {
            if (i < expected.size && featureCols[i] != expected[i]) {
                errors.add("Feature[$i]: expected '${expected[i]}', got '${featureCols[i]}'")
            }
        }
        return errors
    }

    companion object {
        /** Default config matching the current trained model */
        val DEFAULT = ModelConfig(
            featureCols = SensorFeatureComputer.FEATURE_NAMES,
            states = listOf("CAR_MOVING", "CAR_STOPPED"),
            sequenceLen = SensorFeatureComputer.DEFAULT_SEQUENCE_LEN,
            scalerMean = listOf(
                0.17123881249107484, 2.801322902911251, 5.526615580674203,
                -0.0005820163425187371, 0.0009233914992376451, -0.0023566612178277705,
                149.3678076704466, -10.678802591931493, 5.974307272372261,
                9.908045364609968, 0.14691296665703274,
                -0.00039677593189813156, -0.0016077920099296242, -0.001563211502207538,
                0.0001680811546378608, 0.0005056239850443211, 5.1295789765182704e-05,
                9.90684309754915, 0.43819640177929825,
                0.14748465399265653, 0.07698160901687028
            ),
            scalerScale = listOf(
                3.2194915673115787, 4.8777899552481445, 5.281187961218265,
                0.3252314453253227, 0.44142441219132916, 0.3000421117210627,
                119.16806563965046, 29.624356168918034, 56.238193302775734,
                1.5164948905881774, 0.607519217031074,
                1.3350413400146128, 1.4569989849970855, 1.8361138513100617,
                0.43030356768170086, 0.6357042994810587, 0.3713553423888964,
                0.712029912033718, 1.3339657216783738,
                0.5399719089318802, 0.29183416249578875
            )
        )
    }
}
