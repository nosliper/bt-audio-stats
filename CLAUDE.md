# CLAUDE.md

Project context for Claude Code. Read this before making changes.

## What this app is

A personal-use Android app that lists connected Bluetooth audio devices and displays
live statistics about them, including an approximation of real-time data throughput.

Not shipping to Play Store. Single target device. Optimize for correctness and
clarity over backward compatibility or broad device support.

## Build configuration

| Setting | Value | Rationale |
|---|---|---|
| `minSdk` | 33 | `BluetoothCodecStatus`, `BluetoothCodecConfig`, and `BluetoothLeAudio` become public SDK at API 33. `POST_NOTIFICATIONS` also lands here. |
| `targetSdk` | 36 | Android 16, matches the dev device. |
| `compileSdk` | 36 | Same. |
| Language | Kotlin | No Java sources. |
| UI | Jetpack Compose (Material 3) | No XML layouts, no Fragments. |
| Async | Coroutines + `Flow` | No RxJava, no `AsyncTask`, no raw threads. |

Do not lower `minSdk` to widen compatibility. Do not add `compileOptions` blocks for
desugaring — API 33 is high enough that it is unnecessary.

## Hard constraints — read this section before writing Bluetooth code

**`BLUETOOTH_PRIVILEGED` is not available to this app and never will be.**

It is declared `signature|privileged` in the platform manifest. It cannot be granted
via `adb shell pm grant`, cannot be obtained by a sideloaded app, and cannot be worked
around by rooting alone. Any code path that depends on it is dead code.

The following compile fine on API 33+ but throw or return null at runtime without it:

- `BluetoothA2dp.getCodecStatus(BluetoothDevice)` — the actual negotiated codec
- `BluetoothDevice.getBatteryLevel()` — hidden API, also privileged
- `BluetoothAdapter.getBluetoothActivityEnergyInfo()` — system API
- `BluetoothDevice.getMetadata()` for most keys

If a task seems to require one of these, stop and say so rather than writing code that
silently fails. Do not use reflection to reach hidden APIs — it is blocked by the
non-SDK interface restrictions on API 31+ and produces confusing runtime behavior.

**There is no public per-device byte counter for Bluetooth audio.** `TrafficStats` does
not cover A2DP or LE Audio. Real throughput is not directly readable in tier 1 (below).

**The live audio format of another app's playback is redacted.** `AudioManager`'s
`AudioPlaybackCallback` delivers `AudioPlaybackConfiguration` objects, but an
unprivileged app sees other apps' players sanitized to zeros:

```
FormatInfo{isSpatialized=false, channelMask=0x0, sampleRate=0}
deviceIds:[]  u/pid:-1/-1  sessionId:0
```

Verified on the dev device while Tidal streamed 44.1 kHz. You get real values only for
your *own* players. There is therefore no way to know the sample rate of the audio
actually playing, which is why the tier 1 throughput figure is a constant, not a live
reading. Do not build a "live" throughput display on this API.

**These compile-adjacent APIs are not in the public SDK** — they exist in AOSP and in
`dumpsys` output, so they are easy to reach for by mistake:

- `AudioPlaybackConfiguration.isActive()` — not public. Referencing it silently
  resolves to `kotlinx.coroutines.isActive` in a coroutine scope and produces a
  baffling "Not enough information to infer type argument for 'R'" error.
- `AudioFormat.channelCountFromOutChannelMask(int)` — not public. Each `CHANNEL_OUT_*`
  constant is a distinct bit, so `Integer.bitCount(mask)` is the correct substitute.

**Platform state can go stale with no event to correct it.** Two cases confirmed on the
dev device (Samsung / One UI, API 36):

- `ACTION_CONNECTION_STATE_CHANGED` for A2DP/HEADSET/LE_AUDIO/HEARING_AID was **never
  delivered** for a real, verified reconnect (receiver registered, Bluetooth toggled
  off/on, device came back per `dumpsys bluetooth_manager`, zero broadcasts received).
- `AudioManager.isMusicActive()` stayed `true` after playback stopped, lagging its own
  `onPlaybackConfigChanged` callback — deep-buffered playback drains after the event.

Consequence: **never rely on an event callback as the sole source of truth.** Pair every
one with a poll that re-reads the state, or the UI will latch a wrong value forever.

## Architecture: two tiers

The app is built in two layers. Tier 1 must work standalone with Shizuku absent,
denied, or not installed. Tier 2 is strictly additive.

### Tier 1 — unprivileged (required, always functional)

Everything here works with only `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` runtime
permissions.

Available data sources:

- `BluetoothProfile.ServiceListener` + `getConnectedDevices()` for A2DP, HEADSET,
  LE_AUDIO, HEARING_AID
- `BluetoothDevice`: name, address, bond state, device class, `getUuids()`
- `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` → `AudioDeviceInfo` for the active
  route: sample rates, channel masks, encodings. This is the most useful public
  signal available and should drive the stats UI. Note these are **capability lists,
  not current settings** (see throughput note below).
- `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` — the platform's native
  output rate, the best public proxy for what the HAL actually feeds the encoder.
- `AudioDeviceCallback` for route change events
- Polling `getConnectedDevices()` on the profile proxies — this, **not** the
  connection-state broadcasts, is what reliably reflects reality. See hard constraints.
- Broadcast receivers for `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED`. Treat any
  broadcast as a *hint to refresh sooner*, never as the source of truth.

Throughput in tier 1 is an **estimate derived** from sample rate, channel count, and
encoding — not a measurement. Label it as such in the UI. Do not present an estimated
figure as if it were measured.

Two traps when computing it, both hit in practice:

- **Do not use `max(AudioDeviceInfo.getSampleRates())`.** That array is an unordered
  list of what the *headset supports* (e.g. `[48000, 96000, 44100, 88200]` for a
  WH-1000XM3), not what is in use. Using the max overstated the real figure by 2.2x.
  Base the estimate on `PROPERTY_OUTPUT_SAMPLE_RATE` instead, and show the capability
  separately if it is interesting.
- **The result is PCM fed *into* the codec, not over-the-air traffic.** After
  LDAC/SBC/AAC compression the radio carries far less — LDAC negotiates 330/660/990
  kbps (~40-121 KB/s) against a ~187 KB/s PCM input. Never label the PCM number as
  Bluetooth throughput; the real number needs tier 2.

The figure is necessarily **constant while playing**. That is a property of the
available data, not a bug to be fixed in tier 1 — do not "make it move."

### Tier 2 — Shizuku (optional, degrades gracefully)

Shizuku grants shell-level (adb) privileges after a one-time pairing. This unlocks:

- Parsing `dumpsys bluetooth_manager` for active codec config and bitpool
- Tailing the HCI snoop log (`btsnoop_hci.log`, requires the developer option enabled)
  and counting ACL packet bytes — the only route to genuinely measured throughput

Rules for this tier:

- Every Shizuku call site must have a tier 1 fallback. No crashes, no empty screens.
- `dumpsys` output format is undocumented and varies by OEM and Android version.
  Parse defensively: never assume field order, never assume a field exists, always
  handle a total parse failure by falling back to tier 1 values.
- Do not make Shizuku a compile-time hard dependency of tier 1 modules.

## Permissions

Declare exactly these. Do not add `ACCESS_FINE_LOCATION`.

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

The `neverForLocation` flag is what lets us skip the location permission. Legacy
`BLUETOOTH` / `BLUETOOTH_ADMIN` are not needed at `minSdk` 33 — do not add them back.

`POST_NOTIFICATIONS` is only for the foreground service notification if live polling
runs outside the foreground. If polling only happens while the UI is visible, drop it.

Current state: **not declared.** Polling is scoped to the ViewModel and stops when the
UI goes away, so it isn't needed. Add it back only alongside an actual foreground
service — not as a speculative fix.

## Conventions

- Profile proxies must be released in `onCleared()` / `onDestroy()` via
  `BluetoothAdapter.closeProfileProxy()`. Leaking these is the most common bug here.
- All Bluetooth calls need a `BLUETOOTH_CONNECT` check first. Wrap them; do not
  scatter `@SuppressLint("MissingPermission")` around the codebase.
- Poll interval for live stats: 1000 ms default, user-adjustable. Do not poll faster
  than 500 ms — it drains battery for no visible benefit. The same interval drives the
  repository's actual re-query cadence, not just a UI heartbeat.
- Prefer `callbackFlow` for wrapping listener and broadcast APIs, but combine the
  callback with a poll loop inside the same flow — see the staleness cases in hard
  constraints.
- Keep all Bluetooth access behind a repository interface so the UI layer never
  touches `BluetoothAdapter` directly.

## Things not to do

- Do not suggest `getCodecStatus()` as a solution. See the hard constraints section.
- Do not add a dependency to solve something the platform SDK already does.
- Do not add analytics, crash reporting, or any network calls. This app makes zero
  network requests.
- Do not scaffold tests for UI. A few unit tests around the `dumpsys` parser are
  worth having; nothing else is.
- Do not add ProGuard/R8 rules or release signing config unless asked.