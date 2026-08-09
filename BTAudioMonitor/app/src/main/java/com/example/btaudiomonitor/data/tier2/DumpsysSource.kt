package com.example.btaudiomonitor.data.tier2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Supplies raw `dumpsys bluetooth_manager` output for [A2dpDumpsysParser].
 *
 * Kept behind an interface because there is more than one way to obtain privileged
 * output and they differ in setup cost: running `dumpsys` in-process after a one-time
 * `adb shell pm grant android.permission.DUMP`, or proxying through Shizuku. Tier 1
 * must never depend on any of them — a null result means "fall back to tier 1".
 */
interface DumpsysSource {

    /** Raw dump text, or null when it cannot be read (no permission, blocked, absent). */
    suspend fun readBluetoothManagerDump(): String?
}

/**
 * Runs `dumpsys` as a child process of this app.
 *
 * Viable only if `android.permission.DUMP` is held. That permission is
 * `signature|privileged|development` — the `development` flag is what makes it
 * grantable to a normal app via `adb shell pm grant`, unlike `BLUETOOTH_PRIVILEGED`
 * which is unobtainable (see CLAUDE.md's hard constraints). Holding the permission is
 * necessary but may not be sufficient: SELinux independently governs whether an
 * untrusted app may execute the binary and make the dump binder calls.
 */
class AppProcessDumpsysSource : DumpsysSource {

    override suspend fun readBluetoothManagerDump(): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("dumpsys", "bluetooth_manager")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            // A denial still exits non-zero or prints an error rather than a dump, so
            // require both a clean exit and something that looks like real output.
            if (exitCode == 0 && output.contains("A2dpService")) output else null
        } catch (e: Exception) {
            // IOException when exec is blocked, SecurityException when the platform
            // refuses outright. Either way the caller falls back to tier 1.
            null
        }
    }
}
