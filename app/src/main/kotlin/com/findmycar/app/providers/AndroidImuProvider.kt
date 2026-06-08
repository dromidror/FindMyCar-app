package com.findmycar.app.providers

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.findmycar.shared.ImuProvider

class AndroidImuProvider(
    private val sensorManager: SensorManager
) : ImuProvider, SensorEventListener {

    private var active = false
    private var listener: ((Float, Float, Float, Float, Float, Float) -> Unit)? = null
    private val acc = FloatArray(3)
    private val gyro = FloatArray(3)

    override fun start() {
        if (active) return
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        active = true
    }

    override fun stop() {
        if (!active) return
        sensorManager.unregisterListener(this)
        active = false
    }

    override fun isActive(): Boolean = active

    override fun onSample(listener: (Float, Float, Float, Float, Float, Float) -> Unit) {
        this.listener = listener
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                acc[0] = event.values[0]; acc[1] = event.values[1]; acc[2] = event.values[2]
                listener?.invoke(acc[0], acc[1], acc[2], gyro[0], gyro[1], gyro[2])
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = event.values[0]; gyro[1] = event.values[1]; gyro[2] = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
