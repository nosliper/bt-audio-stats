# BT Audio Monitor

A personal-use Android app that lists connected Bluetooth audio devices and reports
live statistics about the link — including the actual negotiated codec and genuinely
measured throughput.

Not on the Play Store; built for a single dev device. See [CLAUDE.md](CLAUDE.md) for
architecture and the platform constraints behind the design.

## Requirements

- Android 13 (API 33) or newer
- JDK 17 and the Android SDK (platform 36)
- USB debugging enabled on the device

## Build and install

```sh
cd BTAudioMonitor
./gradlew installDebug
```

On first launch the app asks for the Bluetooth permission. That is all tier 1 needs:
connected devices, the active audio route, and the device's supported formats.

## Optional: enable measured stats

Everything above works unmodified without this step. The extra tier adds the **real
negotiated codec** and **measured throughput**, neither of which is reachable through
any public Android API.

It needs `android.permission.DUMP`, which cannot be granted from the phone's UI — only
over adb, once:

```sh
adb shell pm grant com.example.btaudiomonitor android.permission.DUMP
```

That's it. Reopen the app and the "Link stats" card fills in.

The grant survives app updates — an `installDebug` over the top keeps it (verified) —
and, like any granted permission, is stored by the system rather than held in memory, so
a reboot does not clear it. You only need to run it again if you **uninstall** the app.

### Why this works when `BLUETOOTH_PRIVILEGED` doesn't

`BLUETOOTH_PRIVILEGED` — which `BluetoothA2dp.getCodecStatus()` requires — is declared
`signature|privileged` and is genuinely unobtainable for a sideloaded app. `DUMP` is
declared `signature|privileged|**development**`, and that `development` flag is what
makes it grantable over adb. The app then reads `dumpsys bluetooth_manager` and parses
the codec state out of it.

## Using it

**Link stats** (codec, link format, over-the-air bitrate, MTU) are read on demand — once
when the screen opens, and whenever you tap *Refresh link stats*.

**Throughput** requires the *Run 60s benchmark* button. A single reading of a byte
counter cannot produce a rate, so throughput has to be measured across a window. Keep
audio playing for the whole minute.

> **Why it isn't live.** Reading `dumpsys` costs ~2.5 MB and 500–900 ms, and doing it
> once a second made Bluetooth audio audibly stutter. The benchmark reads the counter
> only at the start and end — two reads for a whole minute instead of sixty — which is
> both inaudible and *more* accurate, since timing jitter dominates short windows.

## Reading the numbers

Three different figures appear, and they are not interchangeable:

| Figure | Where | What it is |
|---|---|---|
| **Average throughput** | Benchmark result | Measured PCM into the codec. Real, from a byte counter. |
| **Over the air** | Link stats | Compressed bitrate the radio actually carries. |
| **PCM into encoder** | Audio route card | An *estimate*, shown only when measured stats are unavailable. |

The first two differ a lot — on a WH-1000XM3 at LDAC 96 kHz/32-bit, ~750 KB/s of PCM
goes in and ~40–120 KB/s comes out over the air, a compression ratio between roughly
6:1 and 18:1 depending on the bitrate LDAC has settled on. That bitrate moves on its
own: ABR mode was observed swinging between 330 and 990 kbps within a single minute,
while the PCM side stayed steady. This is why the compressed rate cannot be inferred
from the format, and why one instantaneous reading of it can mislead.

The "Audio route (device capabilities)" card describes what the headset and phone
*support*, not what the link is doing. Its sample rates come from
`AudioDeviceInfo.getSampleRates()`, which does not change when the codec changes —
verified by forcing a switch from LDAC 96 kHz/32-bit to AAC 44.1 kHz/16-bit and
watching every tier 1 field stay put. That is exactly why the measured tier exists.

## Troubleshooting

**"Link stats" says it needs the DUMP permission.** Run the `pm grant` command above.
If you just reinstalled after an *uninstall*, the grant was cleared.

**"Readable, but no A2DP codec is active."** Nothing is connected, or the headset is
paired to another phone. Bluetooth headsets with multipoint can hold two hosts, and
only the active one reports a codec.

**Benchmark says the codec changed or the counter reset.** Renegotiation zeroes the
encoder counters mid-run, so no valid average exists. Just run it again.

**Codec settings in Developer options don't stick.** They're transient and revert on
disconnect. To force a codec for a benchmark, set it *while audio is playing* and start
the run immediately.

## Tests

```sh
cd BTAudioMonitor
./gradlew testDebugUnitTest
```

The suite covers the `dumpsys` parser and the benchmark logic, against real captured
output from two Android versions in `app/src/test/resources/`. There are no UI tests by
design.
