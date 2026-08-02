package com.example.smartcushion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.smartcushion.controller.SmartCushionControllerProvider
import com.example.smartcushion.domain.model.AppUiState
import com.example.smartcushion.service.SmartCushionService
import com.example.smartcushion.ui.SmartCushionApp
import com.example.smartcushion.ui.theme.SmartCushionTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startSmartCushionService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionOrStartService()

        setContent {
            val controller = remember { SmartCushionControllerProvider.get(applicationContext) }
            val state by controller.uiState.collectAsState()

            SmartCushionTheme {
                SmartCushionApp(
                    state = state,
                    onModeChange = controller::setControlMode,
                    onX1ModeChange = controller::setX1Mode,
                    onRelayLevelChange = controller::setRelayLevel,
                    onDismissAlarm = controller::dismissAlarm,
                    onSimulateSensorAlarm = controller::simulateSensorAlarm,
                    onSimulateGlobalAlarm = controller::simulateGlobalAlarm,
                )
            }
        }
    }

    private fun requestNotificationPermissionOrStartService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startSmartCushionService()
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSmartCushionService()
        }
    }

    private fun startSmartCushionService() {
        val intent = Intent(this, SmartCushionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun SmartCushionPreview() {
    SmartCushionTheme {
        SmartCushionApp(
            state = AppUiState(),
            onModeChange = {},
            onX1ModeChange = {},
            onRelayLevelChange = { _, _ -> },
            onDismissAlarm = {},
            onSimulateSensorAlarm = {},
            onSimulateGlobalAlarm = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SmartCushionAppPreview() {
    SmartCushionPreview()
}
