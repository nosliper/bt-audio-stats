package com.example.btaudiomonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.btaudiomonitor.ui.devices.DeviceListRoute
import com.example.btaudiomonitor.ui.theme.BTAudioMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BTAudioMonitorTheme {
                DeviceListRoute()
            }
        }
    }
}
