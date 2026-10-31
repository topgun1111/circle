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
import android.os.Looper
import android.os.PowerManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Double tap on the back of the phone, from the accelerometer.
 * A tap = a sudden jump (jerk) on the Z axis (perpendicular to the screen) while the phone is
 * otherwise steady. Two jumps 100-450 ms apart = trigger. Runs only while the screen is on.
 */
class BackTapDetector(private val ctx: Context, private val onDoubleTap: () -> Unit) : SensorEventListener {

    @Volatile var sensitivity = 5 // 1 (hard taps only) .. 10 (very light taps)

    // live diagnostics for the setup screen
    @Volatile var samples = 0L; private set
    @Volatile var doubles = 0; private set
    @Volatile private var peak = 0f

    /** Jerk (m/s² per sample) needed to count as a tap at the current sensitivity. */
    val threshold get() = 1.7f - 0.14f * sensitivity

    /** Strongest tap-like jolt since the last call. */
    fun takePeak(): Float = peak.also { peak = 0f }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var registered = false
    private var wanted = false

    private var px = 0f; private var py = 0f; private var pz = 0f
    private var havePrev = false
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
    val running get() = registered

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
        havePrev = false; motion = 0f; firstTapNs = 0L; lastSpikeNs = 0L
        val h = Handler(t.looper)
        // 5 ms = 200 Hz, the most Android allows without the HIGH_SAMPLING_RATE_SENSORS permission
        registered = try {
            sm.registerListener(this, accel, 5_000, 0, h)
        } catch (_: SecurityException) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, h)
        }
    }

    private fun unregister() {
        if (registered) sm.unregisterListener(this)
        registered = false
        thread?.quitSafely(); thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        samples++
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (!havePrev) { px = x; py = y; pz = z; havePrev = true; return }
        val dx = abs(x - px); val dy = abs(y - py); val dz = abs(z - pz)
        px = x; py = y; pz = z

        val calm = motion < 0.45f // measured before this sample, so the tap itself doesn't count
        motion = 0.9f * motion + 0.1f * (dx + dy + dz)

        if (!calm || dz < 0.4f || dz < max(dx, dy)) return // not a Z-dominant jolt on a steady phone
        if (dz > peak) peak = dz
        if (dz < threshold) return

        val t = e.timestamp
        if (t - lastSpikeNs < 70_000_000L) { lastSpikeNs = t; return } // ringing of the same tap
        lastSpikeNs = t

        if (firstTapNs != 0L && t - firstTapNs in 100_000_000L..450_000_000L) {
            firstTapNs = 0L
            doubles++
            main.post(onDoubleTap)
        } else {
            firstTapNs = t
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
