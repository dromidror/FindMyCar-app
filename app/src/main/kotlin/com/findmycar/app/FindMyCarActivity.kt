package com.findmycar.app

import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.findmycar.shared.DisplacementVector
import com.findmycar.shared.LatLng
import com.findmycar.shared.NavigationEngine
import com.findmycar.shared.PathAccumulator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.roundToInt

/**
 * Navigation screen: shows arrow + distance to the parked car.
 *
 * Works in 4 GPS scenarios:
 * 1. GPS at parking + GPS now → direct bearing
 * 2. No GPS at parking + GPS now → backward anchor
 * 3. GPS at parking + no GPS now → forward dead reckoning
 * 4. No GPS anywhere → reversed accumulated path
 */
class FindMyCarActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var arrowView: ImageView
    private lateinit var distanceText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var statusText: TextView
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedClient: FusedLocationProviderClient

    private val navEngine = NavigationEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val stepDR = com.findmycar.shared.StepDeadReckoning()

    private var deviceHeadingDeg = 0f
    private var currentGps: LatLng? = null
    private var lastGpsLatLng: LatLng? = null
    private var parkingGps: LatLng? = null
    private var pathAccumulator = PathAccumulator()
    private var lastGpsTimeMs = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            currentGps = LatLng(loc.latitude, loc.longitude)
            lastGpsLatLng = currentGps
            lastGpsTimeMs = System.currentTimeMillis()

            // If path accumulator was running (no GPS), stop it — GPS is back
            if (pathAccumulator.isActive) {
                pathAccumulator.stopAndEmit() // displacement no longer needed for navigation since GPS is here
            }
        }
    }

    private val navUpdateRunnable = object : Runnable {
        override fun run() {
            updateNavigation()
            handler.postDelayed(this, 1000L) // 1Hz update
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_find_my_car)

        arrowView = findViewById(R.id.navArrow)
        distanceText = findViewById(R.id.navDistance)
        accuracyText = findViewById(R.id.navAccuracy)
        statusText = findViewById(R.id.navStatus)

        sensorManager = getSystemService(SensorManager::class.java)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        findViewById<com.google.android.material.button.MaterialButton>(R.id.foundItButton).setOnClickListener {
            finish()
        }

        loadParkingSpot()
        loadParkingHistory()
        startNavigation()
    }

    private fun loadParkingHistory() {
        val prefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val historyStr = prefs.getString("parking_history", null) ?: return

        try {
            val entries = mutableListOf<String>()
            val positions = mutableListOf<LatLng>()

            historyStr.split(";").forEach { entry ->
                val parts = entry.split(",")
                if (parts.size >= 5) {
                    val lat = parts[0].toDouble()
                    val lng = parts[1].toDouble()
                    val ts = parts[2].toLong()
                    val floor = parts[3].toIntOrNull()

                    positions.add(LatLng(lat, lng))
                    val elapsed = (System.currentTimeMillis() - ts) / 60_000
                    val timeStr = if (elapsed < 60) "${elapsed}min ago"
                        else "${elapsed / 60}h ago"
                    val floorStr = com.findmycar.shared.formatFloorShort(floor) ?: ""
                    entries.add("$timeStr ${if (floorStr.isNotEmpty()) "($floorStr)" else ""}")
                }
            }

            if (entries.size > 1) {
                val listView = findViewById<android.widget.ListView>(R.id.parkingListView)
                val titleView = findViewById<android.widget.TextView>(R.id.navListTitle)
                titleView.visibility = View.VISIBLE
                listView.visibility = View.VISIBLE
                listView.adapter = android.widget.ArrayAdapter(
                    this, android.R.layout.simple_list_item_single_choice, entries
                )
                listView.setItemChecked(0, true)
                listView.setOnItemClickListener { _, _, pos, _ ->
                    parkingGps = positions[pos]
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadParkingSpot() {
        val prefs = getSharedPreferences("parking_spot", MODE_PRIVATE)
        val hasGps = prefs.getBoolean("has_gps", false)
        if (hasGps) {
            val lat = prefs.getFloat("latitude", 0f).toDouble()
            val lng = prefs.getFloat("longitude", 0f).toDouble()
            parkingGps = LatLng(lat, lng)
        }
    }

    private fun startNavigation() {
        // Compass
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Step counter (for dead reckoning when no GPS)
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // GPS
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {}

        // Start nav updates
        handler.post(navUpdateRunnable)
    }

    private fun updateNavigation() {
        val accFromParking = if (pathAccumulator.isActive) pathAccumulator.currentTotal() else null
        val accFromLastGps = if (pathAccumulator.isActive && lastGpsLatLng != null) pathAccumulator.currentTotal() else null

        val result = navEngine.compute(
            parkingGps = parkingGps,
            currentGps = currentGps,
            accumulatedFromParking = accFromParking,
            accumulatedFromLastGps = accFromLastGps,
            lastGpsBeforeLoss = lastGpsLatLng
        )

        if (result.arrived) {
            statusText.text = "🎉 You're here!"
            statusText.visibility = View.VISIBLE
            distanceText.text = "0m"
            return
        }

        // Rotate arrow: bearing to car minus device heading
        val rotation = result.bearingToCarDeg - deviceHeadingDeg
        arrowView.rotation = rotation

        distanceText.text = "${result.distanceMeters.roundToInt()}m"

        accuracyText.text = if (result.isGpsBased) {
            "GPS navigation"
        } else {
            "⚠️ Low accuracy — estimated"
        }
    }

    private fun updateArrowRotation() {
        val accFromParking = if (pathAccumulator.isActive) pathAccumulator.currentTotal() else null
        val accFromLastGps = if (pathAccumulator.isActive && lastGpsLatLng != null) pathAccumulator.currentTotal() else null

        val result = navEngine.compute(
            parkingGps = parkingGps,
            currentGps = currentGps,
            accumulatedFromParking = accFromParking,
            accumulatedFromLastGps = accFromLastGps,
            lastGpsBeforeLoss = lastGpsLatLng
        )
        if (!result.arrived) {
            arrowView.rotation = result.bearingToCarDeg - deviceHeadingDeg
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                deviceHeadingDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                stepDR.updateHeading(deviceHeadingDeg)

                // Smooth arrow rotation at sensor rate
                updateArrowRotation()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val steps = event.values[0].toInt()
                // If no GPS, accumulate displacement from steps
                val gpsStale = (System.currentTimeMillis() - lastGpsTimeMs) > 10_000L
                if (gpsStale) {
                    if (!pathAccumulator.isActive) pathAccumulator.start()
                    val displacement = stepDR.onStepCount(steps)
                    if (displacement.magnitude > 0f) {
                        pathAccumulator.addDisplacement(displacement)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(navUpdateRunnable)
        sensorManager.unregisterListener(this)
        fusedClient.removeLocationUpdates(locationCallback)
    }
}
