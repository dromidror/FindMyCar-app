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

/**
 * Main screen of FindMyCar app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var parkInfoText: TextView
    private lateinit var findButton: MaterialButton
    private lateinit var testLogButton: MaterialButton
    private lateinit var carExitButton: MaterialButton
    private var logging = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ExitDetectionService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateText = findViewById(R.id.stateText)
        parkInfoText = findViewById(R.id.parkInfoText)
        findButton = findViewById(R.id.findButton)
        testLogButton = findViewById(R.id.testLogButton)
        carExitButton = findViewById(R.id.carExitButton)

        findButton.setOnClickListener {
            startActivity(Intent(this, FindMyCarActivity::class.java))
        }

        testLogButton.setOnClickListener { toggleLogging() }
        carExitButton.setOnClickListener { markCarExit() }

        // Request location permission, then start service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            ExitDetectionService.start(this)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplay()
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
                    parkInfoText.text = if (minutes < 60) {
                        "Parked ${minutes} min ago"
                    } else {
                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(parkingTimestamp))
                        "Parked at $time"
                    }
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
}
