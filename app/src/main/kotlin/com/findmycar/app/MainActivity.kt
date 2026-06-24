package com.findmycar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.findmycar.shared.LatLng
import com.findmycar.shared.NavigationEngine
import com.findmycar.shared.PathAccumulator
import com.findmycar.shared.StepDeadReckoning
import com.google.android.gms.location.*
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single-screen app:
 * - Shows "Not Parked" when driving / INIT / UNKNOWN
 * - Shows navigation arrow + distance + floor when car is parked (EXITED state)
 *
 * In DEV mode: bottom nav with Debug tab is visible
 * In PROD mode: single screen, no navigation
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI - common
    private lateinit var notParkedView: View
    private lateinit var navView: View
    private lateinit var bottomNav: BottomNavigationView

    // UI - not parked
    private lateinit var notParkedEmoji: TextView
    private lateinit var notParkedText: TextView
    private lateinit var stateInfoText: TextView

    // UI - navigation
    private lateinit var arrowView: ImageView
    private lateinit var distanceText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var statusText: TextView
    private lateinit var floorText: TextView
    private lateinit var parkInfoText: TextView

    // Navigation engine
    private val navEngine = NavigationEngine()
    private val stepDR = StepDeadReckoning()
    private val pathAccumulator = PathAccumulator()
    private var deviceHeadingDeg = 0f
    private var currentGps: LatLng? = null
    private var lastGpsLatLng: LatLng? = null
    private var parkingGps: LatLng? = null
    private var lastGpsTimeMs = 0L

    // Sensors
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedClient: FusedLocationProviderClient

    // Missing permissions UI
    private lateinit var missingPermissionsView: View
    private lateinit var permLocationFine: TextView
    private lateinit var permSteps: TextView
    private lateinit var permLocationBg: TextView
    private lateinit var permBattery: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Permission re-request launchers
    private val reqLocationFine = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startServiceAndGps()
        checkMissingPermissions()
    }

    private val reqSteps = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> checkMissingPermissions() }

    private val reqLocationBg = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> checkMissingPermissions() }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            currentGps = LatLng(loc.latitude, loc.longitude)
            lastGpsLatLng = currentGps
            lastGpsTimeMs = System.currentTimeMillis()
            if (pathAccumulator.isActive) {
                pathAccumulator.stopAndEmit()
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

        // Check if onboarding needed
        val onboardingComplete = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getBoolean("onboarding_complete", false)
        if (!onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Bind views
        notParkedView = findViewById(R.id.notParkedView)
        navView = findViewById(R.id.navView)
        bottomNav = findViewById(R.id.bottomNav)
        notParkedEmoji = findViewById(R.id.notParkedEmoji)
        notParkedText = findViewById(R.id.notParkedText)
        stateInfoText = findViewById(R.id.stateInfoText)
        arrowView = findViewById(R.id.navArrow)
        distanceText = findViewById(R.id.navDistance)
        accuracyText = findViewById(R.id.navAccuracy)
        statusText = findViewById(R.id.navStatus)
        floorText = findViewById(R.id.floorText)
        parkInfoText = findViewById(R.id.parkInfoText)

        // Missing permissions
        missingPermissionsView = findViewById(R.id.missingPermissionsView)
        permLocationFine = findViewById(R.id.permLocationFine)
        permSteps = findViewById(R.id.permSteps)
        permLocationBg = findViewById(R.id.permLocationBg)
        permBattery = findViewById(R.id.permBattery)

        permLocationFine.setOnClickListener {
            reqLocationFine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permSteps.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                reqSteps.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        permLocationBg.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                reqLocationBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        permBattery.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }

        sensorManager = getSystemService(SensorManager::class.java)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // DEV/PROD mode
        if (BuildConfig.APP_ENV == "PROD") {
            bottomNav.visibility = View.GONE
        } else {
            bottomNav.selectedItemId = R.id.nav_home
            bottomNav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> true
                    R.id.nav_debug -> {
                        startActivity(Intent(this, DebugActivity::class.java))
                        false
                    }
                    else -> false
                }
            }
        }

        // Start service if location permission is granted (onboarding handles the request)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startServiceAndGps()
        }

        ServiceHeartbeat.schedule(this)
    }

    private fun startServiceAndGps() {
        ExitDetectionService.start(this)
        startGps()
        startCompass()
    }

    private fun startGps() {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    private fun startCompass() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onResume() {
        super.onResume()
        checkMissingPermissions()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun checkMissingPermissions() {
        var anyMissing = false

        // Fine location
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        permLocationFine.visibility = if (!hasLocation) { anyMissing = true; View.VISIBLE } else View.GONE

        // Activity recognition (steps)
        val hasSteps = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        permSteps.visibility = if (!hasSteps) { anyMissing = true; View.VISIBLE } else View.GONE

        // Background location
        val hasBgLocation = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        permLocationBg.visibility = if (!hasBgLocation) { anyMissing = true; View.VISIBLE } else View.GONE

        // Battery optimization
        val pm = getSystemService(android.os.PowerManager::class.java)
        val hasBattery = pm.isIgnoringBatteryOptimizations(packageName)
        permBattery.visibility = if (!hasBattery) { anyMissing = true; View.VISIBLE } else View.GONE

        missingPermissionsView.visibility = if (anyMissing) View.VISIBLE else View.GONE
    }

    private fun updateDisplay() {
        val prefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)
        val state = prefs.getString("presence_state", "INIT") ?: "INIT"

        if (state == "EXITED") {
            showNavigation(prefs)
        } else {
            showNotParked(state)
        }
    }

    private fun showNotParked(state: String) {
        notParkedView.visibility = View.VISIBLE
        navView.visibility = View.GONE

        // Check critical permissions
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBgLocation = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasSteps = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation || !hasBgLocation || !hasSteps) {
            notParkedEmoji.text = "🚫"
            notParkedText.text = "Please Permit"
            stateInfoText.text = "Tap the warnings below to grant permissions"
            return
        }

        notParkedEmoji.text = "🚗"
        val label = when (state) {
            "INIT" -> "Ready"
            "IN_CAR" -> "In Car"
            "UNKNOWN" -> "Waiting for drive..."
            else -> "Not Parked"
        }
        notParkedText.text = label
        stateInfoText.text = if (state == "IN_CAR") "Parking will be saved when you exit" else ""
    }

    private fun showNavigation(prefs: android.content.SharedPreferences) {
        notParkedView.visibility = View.GONE
        navView.visibility = View.VISIBLE

        // Load parking spot
        val hasGps = prefs.getBoolean("has_gps", false)
        if (hasGps) {
            val lat = prefs.getFloat("latitude", 0f).toDouble()
            val lng = prefs.getFloat("longitude", 0f).toDouble()
            if (lat != 0.0 || lng != 0.0) {
                parkingGps = LatLng(lat, lng)
            }
        }

        // Floor
        val hasFloor = prefs.getBoolean("has_floor", false)
        if (hasFloor) {
            val parkingPressure = prefs.getFloat("parking_pressure", 0f)
            val currentPressure = prefs.getFloat("debug_pressure", 0f)
            if (parkingPressure > 0f && currentPressure > 0f) {
                val delta = parkingPressure - currentPressure
                val floorsBelow = (delta / 0.4f).roundToInt()
                val floorStr = com.findmycar.shared.formatFloorRelative(floorsBelow)
                if (floorStr != null) {
                    floorText.text = floorStr
                    floorText.visibility = View.VISIBLE
                } else {
                    floorText.visibility = View.GONE
                }
            } else {
                floorText.visibility = View.GONE
            }
        } else {
            floorText.visibility = View.GONE
        }

        // Parking time
        val parkingTimestamp = prefs.getLong("timestamp", 0L)
        if (parkingTimestamp > 0) {
            val elapsed = System.currentTimeMillis() - parkingTimestamp
            val minutes = elapsed / 60_000
            parkInfoText.text = if (minutes < 60) "Parked ${minutes} min ago"
            else {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(parkingTimestamp))
                "Parked at $time"
            }
        }

        // Compute navigation
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

        statusText.visibility = View.GONE

        // Rotate arrow
        val rotation = result.bearingToCarDeg - deviceHeadingDeg
        arrowView.rotation = rotation

        // Distance
        val dist = result.distanceMeters.roundToInt()
        distanceText.text = if (dist >= 1000) "${"%.1f".format(dist / 1000f)}km" else "${dist}m"

        accuracyText.text = if (result.isGpsBased) "GPS navigation" else "⚠️ Estimated"
    }

    // --- Sensor callbacks ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                deviceHeadingDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                stepDR.updateHeading(deviceHeadingDeg)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val steps = event.values[0].toInt()
                val gpsStale = (System.currentTimeMillis() - lastGpsTimeMs) > 15_000L
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
        handler.removeCallbacksAndMessages(null)
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
        try { fusedClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }
}
