package com.findmycar.app

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.findmycar.shared.ParkingSpot
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main screen of FindMyCar app.
 *
 * Shows:
 * - Current state (driving / parked)
 * - "Find My Car" button when parked
 * - Parking time info
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var parkInfoText: TextView
    private lateinit var findButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateText = findViewById(R.id.stateText)
        parkInfoText = findViewById(R.id.parkInfoText)
        findButton = findViewById(R.id.findButton)

        findButton.setOnClickListener {
            startActivity(Intent(this, FindMyCarActivity::class.java))
        }

        // Start the exit detection service
        ExitDetectionService.start(this)
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
}
