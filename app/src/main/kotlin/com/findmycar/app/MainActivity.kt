package com.findmycar.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.findmycar.shared.ParkingSpot
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Main screen of FindMyCar app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var parkInfoText: TextView
    private lateinit var debugIndicators: TextView
    private lateinit var findButton: MaterialButton
    private lateinit var testLogButton: MaterialButton
    private lateinit var carExitButton: MaterialButton
    private var logging = false
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ExitDetectionService.start(this)
    }

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Step counter will work if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateText = findViewById(R.id.stateText)
        parkInfoText = findViewById(R.id.parkInfoText)
        debugIndicators = findViewById(R.id.debugIndicators)
        findButton = findViewById(R.id.findButton)
        testLogButton = findViewById(R.id.testLogButton)
        carExitButton = findViewById(R.id.carExitButton)

        findButton.setOnClickListener {
            startActivity(Intent(this, FindMyCarActivity::class.java))
        }

        testLogButton.setOnClickListener { toggleLogging() }
        carExitButton.setOnClickListener { markCarExit() }
        findViewById<MaterialButton>(R.id.clearLogsButton).setOnClickListener { clearLogs() }

        // Debug toggle
        val debugToggle = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.debugToggle)
        val debugSection = findViewById<View>(R.id.debugSection)
        debugToggle.setOnCheckedChangeListener { _, checked ->
            debugSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked && logging) {
                // Turn off logging when debug is disabled
                toggleLogging()
            }
        }

        // Request location permission, then start service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            ExitDetectionService.start(this)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Request activity recognition (step counter needs this on Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        // Request battery optimization exemption (critical for background survival)
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // Schedule heartbeat to restart service if killed
        ServiceHeartbeat.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        updateDisplay()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            updateDebugIndicators()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    private fun updateDisplay() {
        val statePrefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val state = statePrefs.getString("presence_state", "UNKNOWN") ?: "UNKNOWN"
        val parkingPrefs = getSharedPreferences("parking_spot", MODE_PRIVATE)
        val parkingTimestamp = parkingPrefs.getLong("timestamp", 0L)

        when (state) {
            "IN_CAR" -> {
                stateText.text = "🚗 Driving"
                stateText.setTextColor(Color.parseColor("#4CAF50"))
                parkInfoText.visibility = View.GONE
                findButton.visibility = View.GONE
            }
            "EXITED" -> {
                stateText.text = "🅿️ Car Parked"
                stateText.setTextColor(Color.parseColor("#2196F3"))
                if (parkingTimestamp > 0) {
                    val elapsed = System.currentTimeMillis() - parkingTimestamp
                    val minutes = elapsed / 60_000
                    val timeStr = if (minutes < 60) {
                        "Parked ${minutes} min ago"
                    } else {
                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(parkingTimestamp))
                        "Parked at $time"
                    }
                    val hasFloor = parkingPrefs.getBoolean("has_floor", false)
                    val parkingPressure = parkingPrefs.getFloat("parking_pressure", 0f)
                    val currentPressure = statePrefs.getFloat("debug_pressure", 0f)

                    val floorStr = if (hasFloor && parkingPressure > 0f && currentPressure > 0f) {
                        val delta = parkingPressure - currentPressure
                        val floorsBelow = (delta / 0.4f).roundToInt()
                        com.findmycar.shared.formatFloorRelative(floorsBelow)
                    } else {
                        val floor = if (hasFloor) parkingPrefs.getInt("floor", 0) else null
                        com.findmycar.shared.formatFloor(floor)
                    }
                    parkInfoText.text = if (floorStr != null) "$timeStr\n$floorStr" else timeStr
                    parkInfoText.visibility = View.VISIBLE
                }
                findButton.visibility = View.VISIBLE
            }
            else -> {
                stateText.text = "⏳ Waiting for drive..."
                stateText.setTextColor(Color.GRAY)
                parkInfoText.visibility = View.GONE
                findButton.visibility = View.GONE
            }
        }
    }

    private fun updateDebugIndicators() {
        val statePrefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val motionState = statePrefs.getString("debug_motion_state", "?") ?: "?"
        val gpsSpeed = statePrefs.getFloat("debug_gps_speed", 0f)
        val gpsAvailable = statePrefs.getBoolean("debug_gps_available", false)
        val charging = statePrefs.getBoolean("debug_charging", false)
        val btConnected = statePrefs.getBoolean("debug_bt_connected", false)
        val steps = statePrefs.getInt("debug_steps_since_stop", 0)
        val presenceState = statePrefs.getString("presence_state", "UNKNOWN") ?: "UNKNOWN"

        val gpsIcon = if (gpsAvailable) "🟢" else "🔴"
        val motionIcon = if (motionState == "CAR_MOVING") "🟢" else "🔴"
        val chargeIcon = if (charging) "🟢" else "⚪"
        val btIcon = if (btConnected) "🟢" else "⚪"
        val stepsIcon = if (steps > 0) "🟢 $steps" else "⚪ 0"

        debugIndicators.text = buildString {
            append("GPS:     $gpsIcon ${if (gpsAvailable) "${"%.1f".format(gpsSpeed)} km/h" else "no fix"}\n")
            append("Motion:  $motionIcon $motionState\n")
            append("Charge:  $chargeIcon ${if (charging) "car charger" else "off"}\n")
            append("Car BT:  $btIcon ${if (btConnected) "connected" else "off"}\n")
            append("Steps:   $stepsIcon\n")
            append("State:   $presenceState")
        }
    }

    private fun toggleLogging() {
        logging = !logging
        val intent = Intent(ExitDetectionService.ACTION_TOGGLE_LOGGING).apply {
            setPackage(packageName)
            putExtra("enabled", logging)
        }
        sendBroadcast(intent)
        testLogButton.text = if (logging) "Stop Logging" else "Start Logging"
        testLogButton.setBackgroundColor(
            if (logging) Color.parseColor("#F44336") else Color.parseColor("#FF9800")
        )
    }

    private fun markCarExit() {
        val intent = Intent(ExitDetectionService.ACTION_USER_CAR_EXIT).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        android.widget.Toast.makeText(this, "Car Exit marked ✓", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        val logDir = java.io.File(getExternalFilesDir(null) ?: filesDir, "test_logs")
        var count = 0
        logDir.listFiles()?.forEach { it.delete(); count++ }
        android.widget.Toast.makeText(this, "Cleared $count log file(s)", android.widget.Toast.LENGTH_SHORT).show()
    }
}
