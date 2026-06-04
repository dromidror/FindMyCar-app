package com.findmycar.app

import com.findmycar.shared.DisplacementVector
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RoNIN pedestrian dead reckoning using TFLite model.
 *
 * Feeds raw IMU data (acc + gyro) to the RoNIN ResNet model and returns
 * displacement vectors (north/east meters per inference window).
 *
 * Model input:  [1, 6, 200] — 6 IMU channels × 200 timesteps (1 second at 200Hz)
 * Model output: [1, 2] — velocity in m/s (v_north, v_east)
 *
 * Usage:
 *   val ronin = RoninDeadReckoning()
 *   // Feed IMU samples at ~200Hz:
 *   ronin.addSample(accX, accY, accZ, gyroX, gyroY, gyroZ)
 *   // Run inference when buffer is full:
 *   val displacement = ronin.infer(interpreter)
 */
class RoninDeadReckoning {

    companion object {
        /** Number of IMU channels (acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z) */
        const val NUM_CHANNELS = 6

        /** Number of timesteps per inference window (~1 second at 200Hz) */
        const val WINDOW_SIZE = 200

        /** Time duration of one window in seconds */
        const val WINDOW_DURATION_SEC = 1.0f

        /** How often to run inference (ms) */
        const val INFERENCE_INTERVAL_MS = 1000L

        /** Minimum samples needed before inference (allow slightly less than full window) */
        const val MIN_SAMPLES = 150
    }

    // Circular buffer: [WINDOW_SIZE][NUM_CHANNELS]
    private val buffer = Array(WINDOW_SIZE) { FloatArray(NUM_CHANNELS) }
    private var writeIndex = 0
    private var sampleCount = 0

    /**
     * Add a raw IMU sample. Call at sensor rate (~200Hz for best accuracy, but works at lower rates).
     *
     * @param accX Accelerometer X (m/s²)
     * @param accY Accelerometer Y (m/s²)
     * @param accZ Accelerometer Z (m/s²)
     * @param gyroX Gyroscope X (rad/s)
     * @param gyroY Gyroscope Y (rad/s)
     * @param gyroZ Gyroscope Z (rad/s)
     */
    fun addSample(accX: Float, accY: Float, accZ: Float,
                  gyroX: Float, gyroY: Float, gyroZ: Float) {
        buffer[writeIndex][0] = accX
        buffer[writeIndex][1] = accY
        buffer[writeIndex][2] = accZ
        buffer[writeIndex][3] = gyroX
        buffer[writeIndex][4] = gyroY
        buffer[writeIndex][5] = gyroZ
        writeIndex = (writeIndex + 1) % WINDOW_SIZE
        if (sampleCount < WINDOW_SIZE) sampleCount++
    }

    /**
     * Check if enough samples have been collected for inference.
     */
    fun isReady(): Boolean = sampleCount >= MIN_SAMPLES

    /**
     * Run inference on the current buffer.
     *
     * Returns the displacement (north/east meters) for this window,
     * or null if not enough data or inference fails.
     *
     * After inference, resets the sample count so next call waits for new data.
     *
     * @param interpreter TFLite interpreter loaded with ronin_model.tflite
     */
    fun infer(interpreter: Interpreter): DisplacementVector? {
        if (!isReady()) return null

        try {
            // Build input tensor: [1, 6, WINDOW_SIZE]
            // RoNIN expects channels-first: [batch, channels, timesteps]
            val inputTensor = interpreter.getInputTensor(0)
            val inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes())
                .order(ByteOrder.nativeOrder())

            // Read from circular buffer in order (oldest first)
            val startIdx = if (sampleCount >= WINDOW_SIZE) writeIndex else 0
            for (ch in 0 until NUM_CHANNELS) {
                for (t in 0 until WINDOW_SIZE) {
                    val bufIdx = (startIdx + t) % WINDOW_SIZE
                    inputBuffer.putFloat(buffer[bufIdx][ch])
                }
            }
            inputBuffer.rewind()

            // Run inference
            val outputTensor = interpreter.getOutputTensor(0)
            val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
                .order(ByteOrder.nativeOrder())

            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            // Output: [1, 2] — velocity (north m/s, east m/s)
            val vNorth = outputBuffer.float
            val vEast = outputBuffer.float

            // Convert velocity to displacement: d = v × t
            // Time is the actual duration of collected samples
            val timeSec = if (sampleCount >= WINDOW_SIZE) WINDOW_DURATION_SEC
                         else sampleCount / 200f  // approximate based on sample rate

            val dNorth = vNorth * timeSec
            val dEast = vEast * timeSec

            // Reset for next window
            sampleCount = 0

            return DisplacementVector(dNorth, dEast)

        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Reset buffer and state.
     */
    fun reset() {
        writeIndex = 0
        sampleCount = 0
        for (i in 0 until WINDOW_SIZE) {
            buffer[i].fill(0f)
        }
    }
}
