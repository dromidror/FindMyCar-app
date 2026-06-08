package com.findmycar.app.providers

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.findmycar.shared.StepProvider

class AndroidStepProvider(
    private val sensorManager: SensorManager
) : StepProvider, SensorEventListener {

    private var stepCount = 0
    private var lastStepMs = 0L
    private var active = false
    private var listener: (() -> Unit)? = null

    override fun isWalking(): Boolean {
        return stepCount > 0 && (System.currentTimeMillis() - lastStepMs) < 3000L
    }

    override fun stepsSinceReset(): Int = stepCount

    override fun resetSteps() { stepCount = 0 }

    override fun onStep(listener: () -> Unit) { this.listener = listener }

    override fun start() {
        if (active) return
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        active = true
    }

    override fun stop() {
        if (!active) return
        sensorManager.unregisterListener(this)
        active = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            stepCount++
            lastStepMs = System.currentTimeMillis()
            listener?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
