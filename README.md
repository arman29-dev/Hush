<div align="center">

<img src="hush_logo.svg" width="128" alt="Hush logo" />

# Hush

**Calm for your earbuds.**

A tiny Android app that turns ordinary Bluetooth earbuds into a transparency aid and an adaptive noise mask — no third-party libraries, no account, no network access.

[![Status](https://img.shields.io/badge/status-pre--release-orange)](#project-status)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84)](#requirements)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)](#build-from-source)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

</div>

---

## What it does

Most earbuds only give you whatever noise control the manufacturer shipped. Hush adds two practical modes on top of any Bluetooth earbuds that Android can route audio to:

| Mode | What it does |
|---|---|
| **Off** | Earbuds behave normally. Hush does not use the microphone. |
| **Transparency** | Sound from the phone microphone is mixed into your earbuds on top of your music, so you can hear announcements, doors, traffic and alarms without taking them out. |
| **Focus** | Plays a soft brown-noise mask that gets louder as your surroundings get louder, so distractions blur into the background. |

## Features

- **Transparency mode** with a 0–100 % level slider.
- **Focus mode** with an adaptive brown-noise mask and an intensity slider.
- **Earbud dashboard** showing the connected device name, battery (when the earbuds report it) and a live surroundings level.
- **Phone mic or earbud mic** — use the phone's microphone for better sound, or switch to the earbuds' own mic when you need it.
- **Foreground service** with an ongoing notification and a one-tap **Turn off** action, so Hush keeps running while you use other apps.
- **Safety first** — Hush refuses to run without Bluetooth earbuds connected (a speaker plus mic would feed back), and switches itself off if the earbuds disconnect.
- **Remembers your settings** across launches.
- **Tiny and dependency-free** — built only with the Android framework; the release APK is around 50 KB.
- **Private by design** — audio is processed in memory on the device. Nothing is recorded, stored, or sent anywhere, and the app has no internet permission.

## Requirements

- **Android 8.0 (API 29) or newer.**
- **Bluetooth earbuds or headphones** connected to the phone.
- Microphone permission, plus nearby-devices (Android 12+) and notification permission (Android 13+).
- The earbud-microphone option additionally requires **Android 12 (API 31) or newer**.

## Install

1. Download the APK from the [**Releases**](../../releases) page.
2. Open it on your phone and allow installing from your browser or file manager when prompted.
3. Launch **Hush** and grant the microphone, nearby-devices and notification permissions.
4. Connect your earbuds in Android Bluetooth settings. The status dot turns green.
5. Pick **Transparency** or **Focus**, then adjust the slider.

> **Note:** This is an early **pre-release**. It builds cleanly but has not yet been tested on physical hardware. See [Project status](#project-status).

## Usage

1. **Pair and connect** your earbuds in Android's Bluetooth settings first.
2. Open Hush. The dashboard shows the device name and battery level when available.
3. Tap **Transparency** to hear your surroundings, or **Focus** to mask them.
4. Drag the slider to set the transparency level or mask intensity.
5. Toggle **Use earbud microphone** if you want the earbuds' mic instead of the phone's.
6. Tap **Off**, or **Turn off** in the notification, to stop. The microphone is released immediately.

For long sessions, set **Settings → Apps → Hush → Battery → Unrestricted** so Android does not kill the audio service.

## Limitations

Hush is honest about what an app can and cannot do over Bluetooth:

- **No true active noise cancellation (ANC).** Real cancellation requires anti-noise within about 1 ms of the original sound. Bluetooth audio adds roughly 150–250 ms of delay, so phase-cancelling ANC is not possible from an app. **Focus** uses adaptive masking instead, which does work.
- **Transparency has about 0.2 s of delay.** It is great for announcements, doors and alarms, but not for back-and-forth conversation.
- **The earbud microphone lowers audio quality.** Android only exposes the earbuds' mic through the call (SCO) profile, which drops the audio to call quality while Hush is running. In Focus mode with the earbud mic, the mask level stops adapting because the mask would feed back into the mic.
- **Earbuds are required.** Hush will not run through the phone speaker, because the speaker would feed back into the microphone.
- **Battery level is optional.** Many budget true-wireless earbuds do not report battery to Android; the dashboard then shows "Not reported".
- **Android may stop background apps.** Aggressive battery optimisation can kill the service; set the app to Unrestricted if this happens.
- **Not yet hardware-tested.** The code compiles and the APK is signed, but real-device behaviour (routing, latency, earbud mic) still needs verification.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Reads the microphone for transparency and room-level adaptation. |
| `MODIFY_AUDIO_SETTINGS` | Routes audio to the earbuds and, optionally, switches to the earbud mic. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Keeps the audio engine alive while the screen is off or another app is open. |
| `POST_NOTIFICATIONS` | Shows the required "Hush is active" notification (Android 13+). |
| `BLUETOOTH_CONNECT` | Reads the connected earbuds' name and battery (Android 12+). |

Hush does **not** request internet access and contains no analytics or tracking.

## Build from source

### Prerequisites

- **JDK 17**
- **Android SDK** with platform **35** and build-tools **34.0.0+**
- The included Gradle wrapper (Gradle 8.9) — no separate Gradle install needed

### Steps

```bash
git clone https://github.com/arman29-dev/Hush.git
cd Hush

# Point Gradle at your Android SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# Debug build (signed with the local debug key, easy to test)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release build (needs a signing key, see below)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### Signing a release build

Copy `keystore.properties.example` to `keystore.properties`, create a keystore, and fill in the values:

```bash
keytool -genkeypair -v \
  -keystore hush-release.jks -alias hush \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Hush, O=Personal, C=IN"
```

```properties
# keystore.properties
storeFile=hush-release.jks
storePassword=your-password
keyAlias=hush
keyPassword=your-password
```

`keystore.properties` and `*.jks` are ignored by Git and must never be committed. Keep your keystore backed up: updates must be signed with the same key.

### Install over USB

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Project structure

```
Hush/
├── app/
│   ├── build.gradle.kts              app config, signing, release shrinking
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       permissions + foreground mic service
│       ├── java/com/hush/focus/
│       │   ├── MainActivity.kt       dashboard, mode selector, sliders
│       │   ├── HushService.kt        audio engine (transparency + adaptive mask)
│       │   ├── Earbuds.kt            finds connected earbuds and battery level
│       │   └── HushState.kt          shared state between UI and service
│       └── res/                      layout, colours, theme, launcher icon
├── gradle/wrapper/                   Gradle wrapper (Gradle 8.9)
├── hush_logo.svg                     standalone logo
├── keystore.properties.example       signing template
├── LICENSE                           MIT
└── README.md
```

## Project status

**v0.1.0 — pre-release.** The project compiles cleanly and produces a signed release APK, but it was written without a physical Android device available for testing. Expect rough edges in real-world routing and latency. Bug reports and fixes are very welcome — please open an [issue](../../issues) with your phone model, earbuds model and Android version.

## Contributing

Issues and pull requests are welcome. Please keep changes consistent with the existing structure and style, and avoid adding third-party dependencies unless there is a strong reason.

## License

Released under the [MIT License](LICENSE). © 2026 Arman Das.
