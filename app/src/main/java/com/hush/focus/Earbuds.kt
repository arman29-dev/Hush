package com.hush.focus

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Helpers for finding the connected Bluetooth earbuds. */
object Earbuds {

    /** The Bluetooth audio output currently in use, or null if none is connected. */
    fun findOutput(am: AudioManager): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    /** Bluetooth-capable microphone input (only exists while the earbud mic is routed). */
    fun findMicInput(am: AudioManager): AudioDeviceInfo? {
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    }

    /**
     * Battery percentage if the earbuds report it to Android, else null.
     * Uses a hidden platform method, so this quietly returns null whenever Android
     * blocks it or the earbuds don't report a level (common on basic TWS models).
     */
    fun battery(context: Context, address: String?): Int? {
        if (address.isNullOrBlank()) return null
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return null
            val device = adapter.bondedDevices.firstOrNull { it.address == address }
                ?: return null
            val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
            if (level in 0..100) level else null
        } catch (t: Throwable) {
            null
        }
    }
}
