package com.example.circlesearch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import kotlin.math.abs

/**
 * Double tap on the back of the phone, detected from the accelerometer.
 * A tap is a short, sharp spike on the Z axis (perpendicular to the screen) while the phone is
 * otherwise still. Two spikes 100–450 ms apart = trigger. Runs only while the screen is on.
 */
class BackTapDetector(private val ctx: Context, private val onDoubleTap: () -> Unit) : SensorEventListener {

    @Volatile var sensitivity = 5 // 1 (hard taps only) .. 10 (very light taps)

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    private var registered = false
    private var wanted = false

    // gravity estimate (low-pass), motion level (EMA of |high-pass|), tap state
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var primed = 0
    private var motion = 0f
    private var lastSpikeNs = 0L
    private var firstTapNs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) unregister() else if (wanted) register()
        }
    }
    private var receiverOn = false

    val available get() = accel != null

    fun start() {
        if (accel == null) return
        wanted = true
        if (!receiverOn) {
            ctx.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            })
            receiverOn = true
        }
        if ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) register()
    }

    fun stop() {
        wanted = false
        unregister()
        if (receiverOn) {
            try { ctx.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverOn = false
        }
    }

    private fun register() {
        if (registered || accel == null) return
        val t = HandlerThread("backtap").also { it.start() }
        thread = t
        primed = 0; motion = 0f; firstTapNs = 0L; lastSpikeNs = 0L
        // 200 Hz needs HIGH_SAMPLING_RATE_SENSORS (declared in the manifest)
        registered = try {
            sm.registerListener(this, accel, 5_000, 0, Handler(t.looper))
        } catch (_: SecurityException) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper))
        }
    }

    private fun unregister() {
        if (registered) sm.unregisterListener(this)
        registered = false
        thread?.quitSafely(); thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (primed < 50) { // seed gravity from the first samples
            gx = x; gy = y; gz = z; primed++; return
        }
        val a = 0.92f
        gx = a * gx + (1 - a) * x; gy = a * gy + (1 - a) * y; gz = a * gz + (1 - a) * z
        val hx = x - gx; val hy = y - gy; val hz = z - gz

        val threshold = 3.2f - 0.25f * sensitivity // lvl 5 -> 1.95 m/s²
        val az = abs(hz)
        // Evaluate the spike against motion measured *before* it, so the tap itself doesn't count.
        val calm = motion < 0.6f
        motion = 0.98f * motion + 0.02f * (abs(hx) + abs(hy) + az)

        if (az < threshold || !calm) return
        // Must be Z-dominant: a tap on the back, not a shake or a tilt.
        if (az < 1.2f * maxOf(abs(hx), abs(hy))) return

        val t = e.timestamp
        if (t - lastSpikeNs < 70_000_000L) { lastSpikeNs = t; return } // ringing of the same tap
        lastSpikeNs = t

        val gap = t - firstTapNs
        if (firstTapNs != 0L && gap in 100_000_000L..450_000_000L) {
            firstTapNs = 0L
            Handler(android.os.Looper.getMainLooper()).post(onDoubleTap)
        } else {
            firstTapNs = t
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
