package com.example.smartcushion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.example.smartcushion.controller.SmartCushionController
import com.example.smartcushion.domain.model.AppUiState
import com.example.smartcushion.ui.SmartCushionApp
import com.example.smartcushion.ui.theme.SmartCushionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { SmartCushionController(applicationContext) }
            val state by controller.uiState.collectAsState()

            DisposableEffect(controller) {
                controller.start()
                onDispose { controller.stop() }
            }

            SmartCushionTheme {
                SmartCushionApp(
                    state = state,
                    onModeChange = controller::setControlMode,
                    onX1ModeChange = controller::setX1Mode,
                    onRelayLevelChange = controller::setRelayLevel,
                )
            }
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
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SmartCushionAppPreview() {
    SmartCushionPreview()
}
