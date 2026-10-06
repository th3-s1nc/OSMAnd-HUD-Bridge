package io.github.th3s1nc.osmandhudbridge.track

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.th3s1nc.osmandhudbridge.BridgeBus

/** Hört während einer Aufnahme Barometer, Drehsensor und Schwerkraft ab (nur was gewünscht ist, spart Akku). */
class SensorTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.applicationContext.getSystemService(SensorManager::class.java)

    var alt = AltitudeFilter()
        private set
    var leanEst = LeanEstimator()
        private set
    var running = false
        private set

    fun start(baro: Boolean, lean: Boolean) {
        stop()
        alt = AltitudeFilter()
        leanEst = LeanEstimator()
        val m = sm ?: return
        running = true
        if (baro) {
            val s = m.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (s != null) m.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
            else BridgeBus.log("Kein Barometer im Handy: Höhe kommt aus dem GPS")
        }
        if (lean) {
            val gyro = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val grav = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (gyro != null && grav != null) {
                m.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
                m.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME)
            } else {
                BridgeBus.log("Drehsensor fehlt: keine Schräglage")
            }
        }
    }

    fun stop() {
        sm?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> alt.onPressure(e.values[0])
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> leanEst.onGravity(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_GYROSCOPE -> leanEst.onGyro(e.values[0], e.values[1], e.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
