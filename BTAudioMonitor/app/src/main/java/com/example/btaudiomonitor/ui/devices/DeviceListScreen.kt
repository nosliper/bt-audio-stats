package com.example.btaudiomonitor.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.btaudiomonitor.data.MAX_POLL_INTERVAL_MS
import com.example.btaudiomonitor.data.MIN_POLL_INTERVAL_MS
import com.example.btaudiomonitor.data.model.AudioRouteInfo
import com.example.btaudiomonitor.data.model.ConnectedDevice
import com.example.btaudiomonitor.ui.format.formatChannelCount
import com.example.btaudiomonitor.ui.format.formatElapsedSince
import com.example.btaudiomonitor.ui.format.formatSampleRate
import com.example.btaudiomonitor.ui.format.formatThroughput
import com.example.btaudiomonitor.ui.format.label

@Composable
fun DeviceListRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: DeviceListViewModel = viewModel(factory = DeviceListViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(granted)
    }

    DeviceListScreen(
        state = uiState,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onPollIntervalChange = viewModel::setPollIntervalMs,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    state: BtAudioUiState,
    onRequestPermission: () -> Unit,
    onPollIntervalChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("BT Audio Monitor") }) },
    ) { innerPadding ->
        if (!state.hasBluetoothPermission) {
            PermissionRationale(onRequestPermission, Modifier.padding(innerPadding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AudioRouteCard(
                    route = state.audioRoute,
                    lastUpdatedAtMillis = state.lastUpdatedAtMillis,
                )
            }
            item {
                PollIntervalControl(
                    pollIntervalMs = state.pollIntervalMs,
                    onPollIntervalChange = onPollIntervalChange,
                )
            }
            item {
                Text(
                    text = "Connected devices (${state.devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.devices.isEmpty()) {
                item {
                    Text(
                        text = "No Bluetooth audio devices connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(state.devices, key = { "${it.profile}:${it.address}" }) { device ->
                    ConnectedDeviceCard(device)
                }
            }
        }
    }
}

@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Bluetooth permission needed",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Text(
            text = "This app reads the list of connected Bluetooth audio devices and the " +
                "active audio route. It needs the Bluetooth permission to do that — " +
                "nothing is sent off the device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(top = 16.dp))
        Button(onClick = onRequestPermission) { Text("Grant permission") }
    }
}

@Composable
private fun AudioRouteCard(route: AudioRouteInfo?, lastUpdatedAtMillis: Long?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Active audio route", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(top = 4.dp))
            if (route == null) {
                Text(
                    text = "No Bluetooth audio output is currently active.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                StatRow("Type", route.deviceTypeLabel)
                route.productName?.let { StatRow("Device", it) }
                StatRow("Max supported", formatSampleRate(route.maxSampleRateHz))
                StatRow("Pipeline rate", formatSampleRate(route.pipelineSampleRateHz))
                StatRow("Channels", formatChannelCount(route.channelCount))
                StatRow("Encoding", route.encodingLabel)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (route.isAudioActive) {
                    StatRow("PCM into encoder", formatThroughput(route.estimatedBytesPerSecond))
                    Text(
                        text = "Constant while playing. This is the uncompressed PCM fed " +
                            "to the Bluetooth codec at the HAL's output rate — not the " +
                            "over-the-air bitrate, which is lower after LDAC/SBC/AAC " +
                            "compression and isn't readable without a privileged API.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        text = "Idle — no audio is currently playing.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "While playing, this shows the uncompressed PCM rate fed " +
                            "to the Bluetooth codec.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                text = "Updated ${formatElapsedSince(lastUpdatedAtMillis)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PollIntervalControl(pollIntervalMs: Long, onPollIntervalChange: (Long) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Refresh interval: ${pollIntervalMs} ms",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = pollIntervalMs.toFloat(),
                onValueChange = { onPollIntervalChange(it.toLong()) },
                valueRange = MIN_POLL_INTERVAL_MS.toFloat()..MAX_POLL_INTERVAL_MS.toFloat(),
                steps = 8,
            )
        }
    }
}

@Composable
private fun ConnectedDeviceCard(device: ConnectedDevice) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = device.name ?: "(unnamed device)",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = device.profile.label(), style = MaterialTheme.typography.labelMedium)
                Text(text = device.bondState.label(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
