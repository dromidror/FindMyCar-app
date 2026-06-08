package com.findmycar.app.providers

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.findmycar.shared.BarometerProvider

class AndroidBarometerProvider(
    private val sensorManager: SensorManager
) : BarometerProvider, SensorEventListener {

    private var currentPressure: Float? = null
    private var continuous = false
    private var oneShotCallback: ((Float) -> Unit)? = null

    override fun getPressureHpa(): Float? = currentPressure

    override fun readOnce(callback: (Float) -> Unit) {
        if (currentPressure != null && continuous) {
            callback(currentPressure!!)
            return
        }
        oneShotCallback = callback
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun startContinuous() {
        if (continuous) return
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        continuous = true
    }

    override fun stopContinuous() {
        if (!continuous) return
        sensorManager.unregisterListener(this)
        continuous = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0]
            oneShotCallback?.let {
                it(event.values[0])
                oneShotCallback = null
                if (!continuous) sensorManager.unregisterListener(this)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
