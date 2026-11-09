package com.hush.focus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val colorOk = 0xFF34D399.toInt()
    private val colorOff = 0xFF4B5565.toInt()
    private val colorOnAccent = 0xFF04201C.toInt()
    private val colorMuted = 0xFF9AA4B5.toInt()

    private lateinit var am: AudioManager
    private val ui = Handler(Looper.getMainLooper())

    private var pendingMode: Mode? = null
    private var dragging = false

    private lateinit var segs: Map<Mode, TextView>
    private lateinit var dot: View
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvMic: TextView
    private lateinit var tvAmbient: TextView
    private lateinit var pbAmbient: ProgressBar
    private lateinit var cardSlider: View
    private lateinit var tvSliderTitle: TextView
    private lateinit var tvSliderValue: TextView
    private lateinit var seek: SeekBar
    private lateinit var switchMic: Switch
    private lateinit var tvInfo: TextView

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 250)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = render()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = render()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        am = getSystemService(AudioManager::class.java)

        segs = mapOf(
            Mode.OFF to findViewById<TextView>(R.id.segOff),
            Mode.TRANSPARENCY to findViewById<TextView>(R.id.segTransparency),
            Mode.FOCUS to findViewById<TextView>(R.id.segFocus)
        )
        dot = findViewById(R.id.dotStatus)
        tvName = findViewById(R.id.tvDeviceName)
        tvStatus = findViewById(R.id.tvDeviceStatus)
        tvBattery = findViewById(R.id.tvBattery)
        tvMic = findViewById(R.id.tvMic)
        tvAmbient = findViewById(R.id.tvAmbient)
        pbAmbient = findViewById(R.id.pbAmbient)
        cardSlider = findViewById(R.id.cardSlider)
        tvSliderTitle = findViewById(R.id.tvSliderTitle)
        tvSliderValue = findViewById(R.id.tvSliderValue)
        seek = findViewById(R.id.seekLevel)
        switchMic = findViewById(R.id.switchEarbudMic)
        tvInfo = findViewById(R.id.tvModeInfo)

        applyInsets(findViewById(R.id.root))
        if (!HushState.running) loadPrefs()
        switchMic.isEnabled = Build.VERSION.SDK_INT >= 31

        for ((mode, view) in segs) {
            view.setOnClickListener { onModeSelected(mode) }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (HushState.mode) {
                    Mode.TRANSPARENCY -> HushState.transparency = progress
                    Mode.FOCUS -> HushState.focus = progress
                    Mode.OFF -> {}
                }
                tvSliderValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                savePrefs()
            }
        })

        // onClick (not onCheckedChange) so programmatic updates never trigger it.
        switchMic.setOnClickListener {
            HushState.earbudMic = switchMic.isChecked
            savePrefs()
            if (HushState.mode != Mode.OFF) {
                // Restart the audio engine so it picks up the new microphone.
                startForegroundService(serviceIntent(HushService.ACTION_START))
            }
        }

        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        am.registerAudioDeviceCallback(deviceCallback, ui)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        am.unregisterAudioDeviceCallback(deviceCallback)
        super.onPause()
    }

    // ------------------------------------------------------------------ actions

    private fun onModeSelected(mode: Mode) {
        if (mode == HushState.mode) return

        if (mode == Mode.OFF) {
            HushState.mode = Mode.OFF
            stopService(Intent(this, HushService::class.java))
            render()
            return
        }

        if (Earbuds.findOutput(am) == null) {
            toast("Connect your Bluetooth earbuds first. Hush only runs through earbuds, otherwise the speaker would feed back into the mic.")
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            pendingMode = mode
            requestMissingPermissions()
            return
        }

        HushState.mode = mode
        if (!HushState.running) {
            HushState.running = true
            startForegroundService(serviceIntent(HushService.ACTION_START))
        }
        render()
    }

    private fun serviceIntent(action: String) =
        Intent(this, HushService::class.java).setAction(action)

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val mode = HushState.mode

        for ((m, v) in segs) {
            val selected = m == mode
            v.setBackgroundResource(if (selected) R.drawable.bg_seg_selected else 0)
            v.setTextColor(if (selected) colorOnAccent else colorMuted)
        }

        val dev = Earbuds.findOutput(am)
        if (dev != null) {
            val name = dev.productName?.toString()
            tvName.text = if (name.isNullOrBlank()) "Bluetooth earbuds" else name
            tvStatus.text = "Connected"
            dot.background.mutate().setTint(colorOk)
            val level = Earbuds.battery(this, dev.address)
            tvBattery.text = if (level != null) "$level%" else "Not reported"
        } else {
            tvName.text = "No earbuds connected"
            tvStatus.text = "Pair and connect them in Bluetooth settings"
            dot.background.mutate().setTint(colorOff)
            tvBattery.text = "-"
        }

        tvMic.text = if (HushState.earbudMic) "Earbud mic" else "Phone mic"
        switchMic.isChecked = HushState.earbudMic

        if (HushState.running && mode != Mode.OFF) {
            val db = HushState.ambientDb
            pbAmbient.progress = (((db + 70f) / 50f) * 100f).coerceIn(0f, 100f).roundToInt()
            tvAmbient.text = when {
                db < -55f -> "Quiet"
                db < -40f -> "Moderate"
                else -> "Loud"
            }
        } else {
            pbAmbient.progress = 0
            tvAmbient.text = "Idle"
        }

        if (mode == Mode.OFF) {
            cardSlider.visibility = View.GONE
        } else {
            cardSlider.visibility = View.VISIBLE
            tvSliderTitle.text =
                if (mode == Mode.TRANSPARENCY) "Transparency level" else "Mask intensity"
            if (!dragging) {
                val v = if (mode == Mode.TRANSPARENCY) HushState.transparency else HushState.focus
                seek.progress = v
                tvSliderValue.text = "$v%"
            }
        }

        tvInfo.text = when (mode) {
            Mode.OFF ->
                "Earbuds behave normally. Hush is not using the microphone."
            Mode.TRANSPARENCY ->
                "Outside sound is picked up by the microphone and mixed into your earbuds, " +
                    "on top of whatever is playing. Bluetooth adds roughly 0.2 s of delay, so " +
                    "it is great for hearing announcements, doors and alarms, but not for " +
                    "back-and-forth conversation."
            Mode.FOCUS ->
                "Plays a soft brown-noise mask that gets louder when your surroundings do, " +
                    "so distractions blur into the background. This is masking, not true " +
                    "phase-cancelling ANC, which an app cannot do over Bluetooth."
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val missing = neededPermissions().filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val mode = pendingMode
        pendingMode = null
        if (mode != null) {
            if (granted(Manifest.permission.RECORD_AUDIO)) {
                onModeSelected(mode)
            } else {
                toast("Hush needs microphone access. You can allow it in Settings > Apps > Hush > Permissions.")
            }
        }
        render()
    }

    // ------------------------------------------------------------------ prefs / insets / misc

    private fun loadPrefs() {
        val p = getSharedPreferences("hush", MODE_PRIVATE)
        HushState.transparency = p.getInt("transparency", 50)
        HushState.focus = p.getInt("focus", 50)
        HushState.earbudMic = Build.VERSION.SDK_INT >= 31 && p.getBoolean("earbudMic", false)
    }

    private fun savePrefs() {
        getSharedPreferences("hush", MODE_PRIVATE).edit()
            .putInt("transparency", HushState.transparency)
            .putInt("focus", HushState.focus)
            .putBoolean("earbudMic", HushState.earbudMic)
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
