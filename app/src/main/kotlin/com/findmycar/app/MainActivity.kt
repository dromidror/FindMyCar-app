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
        if (granted) {
            ExitDetectionService.start(this)
            // Now request background location (needed for always-on GPS)
            requestBackgroundLocation()
        }
    }

    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Background location granted or denied — service works either way */ }

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Step counter will work if granted */ }

    private fun requestBackgroundLocation() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TTL check: app expires 2 months after build
        val expiryDate = 1785877200000L  // August 5, 2026 (2 months from June 5, 2026)
        if (System.currentTimeMillis() > expiryDate) {
            setContentView(android.widget.TextView(this).apply {
                text = "This beta version has expired.\nPlease update to the latest version."
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
            })
            return
        }

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
        findViewById<MaterialButton>(R.id.resetStateButton).setOnClickListener { resetAllState() }

        // Debug toggle
        val debugToggle = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.debugToggle)
        val debugSection = findViewById<View>(R.id.debugSection)
        debugToggle.setOnCheckedChangeListener { _, checked ->
            debugMode = checked
            debugSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked && logging) {
                toggleLogging()
            }
            updateDisplay()
        }

        // Request location permission, then start service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            ExitDetectionService.start(this)
            requestBackgroundLocation()
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

    private var debugMode = false

    private fun updateDisplay() {
        val statePrefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val state = statePrefs.getString("presence_state", "UNKNOWN") ?: "UNKNOWN"
        val parkingPrefs = getSharedPreferences("parking_spot", MODE_PRIVATE)
        val parkingTimestamp = parkingPrefs.getLong("timestamp", 0L)

        when (state) {
            "IN_CAR" -> {
                if (debugMode) {
                    stateText.text = "🚗 Driving"
                    stateText.setTextColor(Color.parseColor("#4CAF50"))
                    stateText.visibility = View.VISIBLE
                } else {
                    stateText.visibility = View.GONE
                }
                parkInfoText.visibility = View.GONE
                findButton.visibility = View.VISIBLE
                findButton.isEnabled = false
                findButton.text = "NOT\nPARKED"
            }
            "EXITED" -> {
                if (debugMode) {
                    stateText.text = "🅿️ Car Parked"
                    stateText.setTextColor(Color.parseColor("#2196F3"))
                    stateText.visibility = View.VISIBLE
                } else {
                    stateText.visibility = View.GONE
                }
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

                // Check distance to car — only enable Find if > 10m away
                val parkLat = parkingPrefs.getFloat("latitude", 0f).toDouble()
                val parkLng = parkingPrefs.getFloat("longitude", 0f).toDouble()
                val hasGps = parkingPrefs.getBoolean("has_gps", false)
                val gpsAvailable = statePrefs.getBoolean("debug_gps_available", false)
                val currentLat = statePrefs.getFloat("debug_gps_lat", 0f).toDouble()
                val currentLng = statePrefs.getFloat("debug_gps_lng", 0f).toDouble()

                val distanceToCar = if (hasGps && gpsAvailable && parkLat != 0.0 && currentLat != 0.0) {
                    com.findmycar.shared.LatLng(currentLat, currentLng)
                        .distanceTo(com.findmycar.shared.LatLng(parkLat, parkLng))
                } else {
                    Float.MAX_VALUE // Unknown distance — allow Find
                }

                findButton.visibility = View.VISIBLE
                if (distanceToCar > 10f) {
                    findButton.isEnabled = true
                    findButton.text = "FIND\nMY CAR"
                } else {
                    findButton.isEnabled = false
                    findButton.text = "CAR IS\nNEARBY"
                }
            }
            else -> {
                if (debugMode) {
                    stateText.text = "⏳ Waiting for drive..."
                    stateText.setTextColor(Color.GRAY)
                    stateText.visibility = View.VISIBLE
                } else {
                    stateText.visibility = View.GONE
                }
                parkInfoText.visibility = View.GONE
                findButton.visibility = View.VISIBLE
                findButton.isEnabled = false
                findButton.text = "NOT\nPARKED"
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

    private fun resetAllState() {
        getSharedPreferences("exit_detection_state", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("parking_spot", MODE_PRIVATE).edit().clear().apply()
        android.widget.Toast.makeText(this, "State & parking data cleared", android.widget.Toast.LENGTH_SHORT).show()
        updateDisplay()
    }
}
