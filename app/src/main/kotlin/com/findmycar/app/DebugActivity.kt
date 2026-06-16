package com.findmycar.app

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.findmycar.shared.StateMachineLog
import com.google.android.material.button.MaterialButton
import java.io.File

class DebugActivity : AppCompatActivity() {

    private lateinit var debugIndicators: TextView
    private lateinit var smLogText: TextView
    private lateinit var smLogScrollView: ScrollView
    private lateinit var smToggleButton: MaterialButton
    private lateinit var testLogButton: MaterialButton
    private var logging = false
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastLogSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        debugIndicators = findViewById(R.id.debugIndicators)
        smLogText = findViewById(R.id.smLogText)
        smLogScrollView = findViewById(R.id.smLogScrollView)
        smToggleButton = findViewById(R.id.smToggleButton)
        testLogButton = findViewById(R.id.testLogButton)

        smToggleButton.setOnClickListener { toggleSmLog() }
        testLogButton.setOnClickListener { toggleLogging() }
        findViewById<MaterialButton>(R.id.carExitButton).setOnClickListener { markCarExit() }
        findViewById<MaterialButton>(R.id.clearLogsButton).setOnClickListener { clearLogs() }
        findViewById<MaterialButton>(R.id.resetStateButton).setOnClickListener { resetAllState() }
        findViewById<MaterialButton>(R.id.smSaveButton).setOnClickListener { saveSmLog() }
        findViewById<MaterialButton>(R.id.smCleanButton).setOnClickListener { cleanSmConsole() }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDebugIndicators()
            updateStateMachineLog()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    private fun updateDebugIndicators() {
        val statePrefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val motionState = statePrefs.getString("debug_motion_state", "?") ?: "?"
        val gpsSpeed = statePrefs.getFloat("debug_gps_speed", 0f)
        val gpsAvailable = statePrefs.getBoolean("debug_gps_available", false)
        val btConnected = statePrefs.getBoolean("debug_bt_connected", false)
        val steps = statePrefs.getInt("debug_steps_since_stop", 0)
        val presenceState = statePrefs.getString("presence_state", "INIT") ?: "INIT"
        val pressure = statePrefs.getFloat("debug_pressure", 0f)

        val gpsIcon = if (gpsAvailable) "🟢" else "🔴"
        val motionIcon = if (motionState == "CAR_MOVING") "🟢" else "🔴"
        val btIcon = if (btConnected) "🟢" else "⚪"
        val stepsIcon = if (steps > 0) "🟢 $steps" else "⚪ 0"

        debugIndicators.text = buildString {
            append("GPS:      $gpsIcon ${if (gpsAvailable) "${"%.1f".format(gpsSpeed)} km/h" else "no fix"}\n")
            append("Motion:   $motionIcon $motionState\n")
            append("Car BT:   $btIcon ${if (btConnected) "connected" else "off"}\n")
            append("Steps:    $stepsIcon\n")
            append("Pressure: ${"%.1f".format(pressure)} hPa\n")
            append("State:    $presenceState")
        }
    }

    private fun updateStateMachineLog() {
        val currentSize = StateMachineLog.size()
        if (currentSize == lastLogSize) return  // No new entries
        lastLogSize = currentSize

        val entries = StateMachineLog.getAll()
        val sb = StringBuilder()
        for (entry in entries) {
            val state = entry.state.padEnd(8)
            val src = entry.source.padEnd(6)
            val cnt = entry.counter.padEnd(6)
            val motion = entry.motionState.padEnd(13)
            val pickup = when (entry.pickup) {
                true -> "true "
                false -> "false"
                null -> "  -  "
            }
            val time = entry.timeInState.padEnd(6)
            val trans = entry.transition ?: "-"
            sb.appendLine("$state $src $cnt $motion $pickup $time $trans")
        }
        smLogText.text = sb.toString()

        // Auto-scroll to bottom
        smLogScrollView.post { smLogScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toggleLogging() {
        logging = !logging
        val intent = Intent(ExitDetectionService.ACTION_TOGGLE_LOGGING).apply {
            setPackage(packageName)
            putExtra("enabled", logging)
        }
        sendBroadcast(intent)
        testLogButton.text = if (logging) "Stop Log" else "Start Log"
    }

    private fun toggleSmLog() {
        StateMachineLog.enabled = !StateMachineLog.enabled
        if (StateMachineLog.enabled) {
            smToggleButton.text = "⏹ Stop"
            smToggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
        } else {
            smToggleButton.text = "▶ Start"
            smToggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        }
    }

    private fun markCarExit() {
        val intent = Intent(ExitDetectionService.ACTION_USER_CAR_EXIT).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Car Exit marked ✓", Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        val logDir = File(getExternalFilesDir(null) ?: filesDir, "test_logs")
        var count = 0
        logDir.listFiles()?.forEach { it.delete(); count++ }
        StateMachineLog.clear()
        lastLogSize = 0
        smLogText.text = ""
        Toast.makeText(this, "Cleared $count log file(s) + SM log", Toast.LENGTH_SHORT).show()
    }

    private fun saveSmLog() {
        val entries = StateMachineLog.getAll()
        if (entries.isEmpty()) {
            Toast.makeText(this, "No log entries to save", Toast.LENGTH_SHORT).show()
            return
        }

        val logDir = File(getExternalFilesDir(null) ?: filesDir, "sm_logs")
        logDir.mkdirs()

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(logDir, "sm_log_$timestamp.csv")

        file.bufferedWriter().use { writer ->
            writer.appendLine("state,source,counter,motion_state,pickup,time_in_state,transition")
            for (entry in entries) {
                val pickup = when (entry.pickup) {
                    true -> "true"
                    false -> "false"
                    null -> ""
                }
                val transition = entry.transition ?: ""
                writer.appendLine("${entry.state},${entry.source},${entry.counter},${entry.motionState},$pickup,${entry.timeInState},$transition")
            }
        }

        Toast.makeText(this, "Saved ${entries.size} entries → ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun cleanSmConsole() {
        StateMachineLog.clear()
        lastLogSize = 0
        smLogText.text = ""
        Toast.makeText(this, "Console cleared", Toast.LENGTH_SHORT).show()
    }

    private fun resetAllState() {
        getSharedPreferences("exit_detection_state", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("parking_spot", MODE_PRIVATE).edit().clear().apply()
        Toast.makeText(this, "State & parking data cleared", Toast.LENGTH_SHORT).show()
    }
}
