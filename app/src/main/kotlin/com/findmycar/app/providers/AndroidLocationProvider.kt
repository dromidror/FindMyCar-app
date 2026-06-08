package com.findmycar.app.providers

import android.annotation.SuppressLint
import android.os.Looper
import com.findmycar.shared.LatLng
import com.findmycar.shared.LocationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority

class AndroidLocationProvider(
    private val fusedClient: FusedLocationProviderClient
) : LocationProvider {

    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastSpeedKmh = 0f
    private var lastAccuracy = 0f
    private var lastFixTimeMs = 0L
    private var active = false
    private var currentIntervalMs = 1000L
    private var listener: ((LatLng, Float, Float) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLat = loc.latitude
            lastLng = loc.longitude
            lastSpeedKmh = loc.speed * 3.6f
            lastAccuracy = loc.accuracy
            lastFixTimeMs = System.currentTimeMillis()
            listener?.invoke(LatLng(lastLat, lastLng), lastSpeedKmh, lastAccuracy)
        }
    }

    override fun getLastLocation(): LatLng? {
        if (lastFixTimeMs == 0L) return null
        return LatLng(lastLat, lastLng)
    }

    override fun getSpeedKmh(): Float? {
        if (!isAvailable()) return null
        return lastSpeedKmh
    }

    override fun getAccuracy(): Float? {
        if (!isAvailable()) return null
        return lastAccuracy
    }

    override fun isAvailable(): Boolean {
        return lastFixTimeMs > 0 && (System.currentTimeMillis() - lastFixTimeMs) < 10_000L
    }

    @SuppressLint("MissingPermission")
    override fun startUpdates(intervalMs: Long) {
        if (active) return
        currentIntervalMs = intervalMs
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            active = true
        } catch (_: SecurityException) {}
    }

    override fun stopUpdates() {
        if (!active) return
        fusedClient.removeLocationUpdates(locationCallback)
        active = false
    }

    @SuppressLint("MissingPermission")
    override fun setInterval(intervalMs: Long) {
        if (intervalMs == currentIntervalMs) return
        currentIntervalMs = intervalMs
        if (active) {
            fusedClient.removeLocationUpdates(locationCallback)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .build()
            try {
                fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            } catch (_: SecurityException) {}
        }
    }

    override fun onLocationChanged(listener: (LatLng, Float, Float) -> Unit) {
        this.listener = listener
    }
}
