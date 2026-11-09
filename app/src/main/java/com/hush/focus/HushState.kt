package com.hush.focus

enum class Mode { OFF, TRANSPARENCY, FOCUS }

/**
 * Single in-process source of truth shared by the UI and the audio service.
 * Fields are @Volatile because the audio thread reads them every 20 ms.
 */
object HushState {
    @Volatile var mode: Mode = Mode.OFF
    @Volatile var transparency: Int = 50      // 0..100
    @Volatile var focus: Int = 50             // 0..100
    @Volatile var earbudMic: Boolean = false  // false = phone mic
    @Volatile var ambientDb: Float = -90f     // smoothed surroundings level, dBFS
    @Volatile var running: Boolean = false
}
