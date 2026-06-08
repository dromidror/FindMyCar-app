package com.findmycar.app.providers

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.findmycar.shared.CompassProvider

class AndroidCompassProvider(
    private val sensorManager: SensorManager
) : CompassProvider, SensorEventListener {

    private var headingDeg = 0f
    private var active = false

    override fun getHeadingDeg(): Float = headingDeg

    override fun start() {
        if (active) return
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        active = true
    }

    override fun stop() {
        if (!active) return
        sensorManager.unregisterListener(this)
        active = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rm = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rm, event.values)
            SensorManager.getOrientation(rm, orientation)
            headingDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
