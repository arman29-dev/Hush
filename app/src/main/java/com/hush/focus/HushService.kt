package com.hush.focus

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.Toast
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Foreground service that owns the audio loop.
 *
 *  - Transparency: microphone -> gain -> earbuds (mixed with whatever media is playing)
 *  - Focus:        adaptive brown-noise mask whose loudness follows the room level
 *
 * One loop handles both modes, so switching between them is instant and glitch-free.
 */
class HushService : Service() {

    companion object {
        const val ACTION_START = "com.hush.focus.START"
        const val ACTION_STOP = "com.hush.focus.STOP"
        private const val CHANNEL_ID = "hush_active"
        private const val NOTIFICATION_ID = 1
    }

    /** One engine run. Flipping [active] to false asks the loop to finish. */
    private class Session {
        @Volatile var active = true
    }

    private lateinit var am: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hush is active", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HushState.mode = Mode.OFF
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            toast("Hush could not start: microphone permission is needed")
            HushState.mode = Mode.OFF
            HushState.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        startEngine()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        HushState.mode = Mode.OFF
        HushState.running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- engine control

    private fun startEngine() {
        stopEngine()
        val s = Session()
        session = s
        HushState.running = true
        val useEarbudMic = HushState.earbudMic
        worker = Thread({ engine(s, useEarbudMic) }, "hush-audio").also { it.start() }
    }

    private fun stopEngine() {
        session?.active = false
        try {
            worker?.join(1500)
        } catch (_: InterruptedException) {
        }
        worker = null
    }

    // ---------------------------------------------------------------- audio loop

    @SuppressLint("MissingPermission")
    private fun engine(s: Session, wantEarbudMic: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        var routed = false

        try {
            if (wantEarbudMic) {
                routed = routeToEarbudMic()
                if (!routed) {
                    HushState.earbudMic = false
                    toast("Earbud mic not available - using the phone mic instead")
                }
            }

            val rate = if (routed) 16000 else 44100
            val frame = rate / 50 // 20 ms
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, encoding)
            if (minIn <= 0 || minOut <= 0) throw IllegalStateException("Audio format not supported")

            val r = AudioRecord.Builder()
                .setAudioSource(
                    if (routed) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.MIC
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minIn, frame * 2 * 4))
                .build()
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone is busy or unavailable")
            }
            if (routed) {
                Earbuds.findMicInput(am)?.let { r.setPreferredDevice(it) }
            }

            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (routed) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, frame * 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t

            val inBuf = ShortArray(frame)
            val outBuf = ShortArray(frame)
            val rnd = java.util.Random()

            var gain = 0f          // current output gain (ramped per sample to avoid clicks)
            var env = -80f         // smoothed ambient level in dBFS
            var brown = 0f         // brown-noise filter state
            var tick = 0

            r.startRecording()
            t.play()

            while (s.active) {
                val n = r.read(inBuf, 0, frame)
                if (n < 0) throw IllegalStateException("Microphone read failed ($n)")
                if (n == 0) continue

                // --- measure the room: RMS -> dBFS -> fast attack / slow release
                var sum = 0.0
                for (i in 0 until n) {
                    val x = inBuf[i] / 32768.0
                    sum += x * x
                }
                val rms = sqrt(sum / n)
                val db = if (rms > 1e-6) (20.0 * log10(rms)).toFloat() else -120f
                env += (if (db > env) 0.35f else 0.03f) * (db - env)
                HushState.ambientDb = env

                val mode = HushState.mode
                if (mode == Mode.OFF) break

                val target: Float = when (mode) {
                    Mode.TRANSPARENCY -> {
                        val level = HushState.transparency / 100f
                        6f * level * level // gentle curve, up to +15 dB
                    }
                    Mode.FOCUS -> {
                        // With the earbud mic the mask leaks into the mic, so don't adapt
                        // (it would feed back on itself). Use a fixed mid value instead.
                        val adapt = if (routed) 0.5f else ((env + 65f) / 35f).coerceIn(0f, 1f)
                        (HushState.focus / 100f) * (0.25f + 0.75f * adapt) * 0.8f
                    }
                    else -> 0f
                }

                val step = (target - gain) / n
                if (mode == Mode.TRANSPARENCY) {
                    for (i in 0 until n) {
                        gain += step
                        outBuf[i] = toPcm(tanh(inBuf[i] / 32768f * gain))
                    }
                } else {
                    for (i in 0 until n) {
                        gain += step
                        val white = rnd.nextFloat() * 2f - 1f
                        brown = (brown + 0.02f * white) / 1.02f
                        outBuf[i] = toPcm(brown * 4f * gain)
                    }
                }
                t.write(outBuf, 0, n)

                // Once a second: make sure the earbuds are still connected.
                // Without them the speaker + mic would howl, so shut down.
                tick++
                if (tick % 50 == 0 && Earbuds.findOutput(am) == null) {
                    toast("Earbuds disconnected - Hush turned off")
                    break
                }
            }
        } catch (e: Exception) {
            toast("Hush stopped: ${e.message ?: "audio error"}")
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            if (routed) {
                try {
                    am.clearCommunicationDevice()
                    am.mode = AudioManager.MODE_NORMAL
                } catch (_: Exception) {
                }
            }
            if (session === s) {
                HushState.running = false
                HushState.ambientDb = -90f
            }
            // The loop ended on its own (error, disconnect, mode OFF): shut the service down.
            if (s.active) {
                s.active = false
                HushState.mode = Mode.OFF
                stopSelf()
            }
        }
    }

    /** Switch Android to "call" routing so the earbuds' own microphone becomes the input. */
    private fun routeToEarbudMic(): Boolean {
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val dev: AudioDeviceInfo? = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            val ok = dev != null && am.setCommunicationDevice(dev)
            if (!ok) am.mode = AudioManager.MODE_NORMAL
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun toPcm(x: Float): Short =
        (x.coerceIn(-1f, 1f) * 32767f * 0.95f).toInt().toShort()

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HushService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hush)
            .setContentTitle("Hush is active")
            .setContentText("Tap to open, or turn it off")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_hush), "Turn off", stop
                ).build()
            )
            .build()
    }
}
