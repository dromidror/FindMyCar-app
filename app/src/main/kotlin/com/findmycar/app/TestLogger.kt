package com.findmycar.app

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dumps GPS + RoNIN + motion state to a CSV file for testing/analysis.
 *
 * CSV columns:
 *   timestamp_iso, timestamp_ms, lat, lng, gps_speed_kmh, gps_accuracy,
 *   ronin_dNorth, ronin_dEast, ronin_total_north, ronin_total_east,
 *   motion_state, presence_state, steps_since_stop
 */
class TestLogger(context: Context) {

    private val logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "test_logs")
    private var logFile: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var started = false

    fun start() {
        if (started) return
        logDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(logDir, "test_log_$timestamp.csv")
        executor.submit {
            FileWriter(logFile, true).use { w ->
                w.append("timestamp_iso,timestamp_ms,")
                w.append("lat,lng,gps_speed_kmh,gps_accuracy,")
                w.append("ronin_dNorth,ronin_dEast,ronin_total_north,ronin_total_east,")
                w.append("motion_state,presence_state,steps_since_stop\n")
            }
        }
        started = true
    }

    fun log(
        lat: Double?, lng: Double?, gpsSpeedKmh: Float?, gpsAccuracy: Float?,
        roninDNorth: Float, roninDEast: Float,
        roninTotalNorth: Float, roninTotalEast: Float,
        motionState: String, presenceState: String,
        stepsSinceStop: Int
    ) {
        if (!started) return
        val file = logFile ?: return
        val now = System.currentTimeMillis()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(now))

        executor.submit {
            runCatching {
                FileWriter(file, true).use { w ->
                    w.append("$iso,$now,")
                    w.append("${lat ?: ""},${lng ?: ""},${gpsSpeedKmh ?: ""},${gpsAccuracy ?: ""},")
                    w.append("${"%.4f".format(roninDNorth)},${"%.4f".format(roninDEast)},")
                    w.append("${"%.4f".format(roninTotalNorth)},${"%.4f".format(roninTotalEast)},")
                    w.append("$motionState,$presenceState,$stepsSinceStop\n")
                }
            }
        }
    }

    fun stop() {
        started = false
    }

    fun getLogFile(): File? = logFile

    fun shutdown() {
        stop()
        executor.shutdown()
    }
}
