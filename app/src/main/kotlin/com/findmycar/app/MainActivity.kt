package com.findmycar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var parkInfoText: TextView
    private lateinit var findButton: MaterialButton
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ExitDetectionService.start(this)
            requestBackgroundLocation()
        }
    }

    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

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

        // TTL check
        val expiryDate = 1785877200000L
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

        parkInfoText = findViewById(R.id.parkInfoText)
        findButton = findViewById(R.id.findButton)

        findButton.setOnClickListener {
            startActivity(Intent(this, FindMyCarActivity::class.java))
        }

        // Bottom navigation
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.nav_home
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true  // already here
                R.id.nav_find -> {
                    startActivity(Intent(this, FindMyCarActivity::class.java))
                    false
                }
                R.id.nav_debug -> {
                    startActivity(Intent(this, DebugActivity::class.java))
                    false
                }
                else -> false
            }
        }

        // Permissions + service start
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            ExitDetectionService.start(this)
            requestBackgroundLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        // Battery optimization exemption
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }
        ServiceHeartbeat.schedule(this)
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
            updateDisplay()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    private fun updateDisplay() {
        val statePrefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val state = statePrefs.getString("presence_state", "INIT") ?: "INIT"
        val parkingPrefs = getSharedPreferences("parking_spot", MODE_PRIVATE)
        val parkingTimestamp = parkingPrefs.getLong("timestamp", 0L)

        when (state) {
            "EXITED" -> {
                if (parkingTimestamp > 0) {
                    val elapsed = System.currentTimeMillis() - parkingTimestamp
                    val minutes = elapsed / 60_000
                    val timeStr = if (minutes < 60) "Parked ${minutes} min ago"
                    else {
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
                    } else null

                    parkInfoText.text = if (floorStr != null) "$timeStr\n$floorStr" else timeStr
                    parkInfoText.visibility = View.VISIBLE
                } else {
                    parkInfoText.visibility = View.GONE
                }

                // Distance check
                val parkLat = parkingPrefs.getFloat("latitude", 0f).toDouble()
                val parkLng = parkingPrefs.getFloat("longitude", 0f).toDouble()
                val hasGps = parkingPrefs.getBoolean("has_gps", false)
                val gpsAvailable = statePrefs.getBoolean("debug_gps_available", false)
                val currentLat = statePrefs.getFloat("debug_gps_lat", 0f).toDouble()
                val currentLng = statePrefs.getFloat("debug_gps_lng", 0f).toDouble()

                val distanceToCar = if (hasGps && gpsAvailable && parkLat != 0.0 && currentLat != 0.0) {
                    com.findmycar.shared.LatLng(currentLat, currentLng)
                        .distanceTo(com.findmycar.shared.LatLng(parkLat, parkLng))
                } else Float.MAX_VALUE

                if (distanceToCar > 10f) {
                    findButton.isEnabled = true
                    findButton.text = "FIND\nMY CAR"
                } else {
                    findButton.isEnabled = false
                    findButton.text = "CAR IS\nNEARBY"
                }
            }
            else -> {
                parkInfoText.visibility = View.GONE
                findButton.isEnabled = false
                findButton.text = "NOT\nPARKED"
            }
        }
    }
}
