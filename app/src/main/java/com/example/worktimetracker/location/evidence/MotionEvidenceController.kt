package com.example.worktimetracker.location.evidence

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.SystemClock

/**
 * 运动证据控制器：优先使用显著运动传感器（SMD）唤醒扫描；设备缺失时退化为加速度计
 * 幅值判断。原始样本只在内存中短暂存在，不做持久化。
 */
class MotionEvidenceController(
    context: Context,
    private val onSignificantMotion: (eventTime: Long) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var significantMotionSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var accelerometer: Sensor? = null
    private var accelerometerActive = false
    private var lastAboveThresholdAt = 0L
    private var consecutiveAboveThreshold = 0

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values.getOrNull(0) ?: return
            val y = event.values.getOrNull(1) ?: return
            val z = event.values.getOrNull(2) ?: return
            val magnitude = kotlin.math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
            if (kotlin.math.abs(magnitude) > ACCELEROMETER_THRESHOLD_MPS2) {
                consecutiveAboveThreshold++
                if (consecutiveAboveThreshold >= CONSECUTIVE_SAMPLES_REQUIRED) {
                    consecutiveAboveThreshold = 0
                    onSignificantMotion(SystemClock.elapsedRealtime())
                }
            } else {
                consecutiveAboveThreshold = 0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        stop()
        if (significantMotionSensor != null) {
            requestTrigger()
        } else {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelerometer != null) {
                accelerometerActive = sensorManager.registerListener(
                    accelerometerListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }
    }

    fun stop() {
        significantMotionSensor?.let { sensorManager.cancelTriggerSensor(triggerListener, it) }
        if (accelerometerActive) {
            sensorManager.unregisterListener(accelerometerListener)
            accelerometerActive = false
        }
        accelerometer = null
        consecutiveAboveThreshold = 0
    }

    private fun requestTrigger() {
        significantMotionSensor?.let {
            sensorManager.requestTriggerSensor(triggerListener, it)
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            onSignificantMotion(SystemClock.elapsedRealtime())
            requestTrigger()
        }
    }

    companion object {
        const val ACCELEROMETER_THRESHOLD_MPS2 = 2.5f
        const val CONSECUTIVE_SAMPLES_REQUIRED = 3
    }
}
