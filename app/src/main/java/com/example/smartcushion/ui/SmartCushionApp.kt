package com.example.smartcushion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartcushion.domain.model.AppUiState
import com.example.smartcushion.domain.model.ControlMode
import com.example.smartcushion.domain.model.PressureSensorState
import com.example.smartcushion.domain.model.RelayLevel
import com.example.smartcushion.domain.model.RelayState
import com.example.smartcushion.domain.model.X1ManualMode

@Composable
fun SmartCushionApp(
    state: AppUiState,
    onModeChange: (ControlMode) -> Unit,
    onX1ModeChange: (X1ManualMode) -> Unit,
    onRelayLevelChange: (Int, RelayLevel) -> Unit,
) {
    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectionHeader(state)
            }
            item {
                SectionTitle("Pressure Sensors")
            }
            items(state.sensors) { sensor ->
                PressureSensorCard(sensor)
            }
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Relay Control")
            }
            item {
                AutoModeSwitch(
                    mode = state.controlMode,
                    enabled = state.connected && state.busyMessage == null,
                    onModeChange = onModeChange,
                )
            }
            items(state.relays) { relay ->
                RelayControlCard(
                    relay = relay,
                    manualControlsEnabled = state.connected &&
                        state.controlMode == ControlMode.MANUAL &&
                        state.busyMessage == null,
                    onX1ModeChange = onX1ModeChange,
                    onRelayLevelChange = onRelayLevelChange,
                )
            }
            if (state.busyMessage != null || state.errorMessage != null) {
                item {
                    Text(
                        text = state.busyMessage ?: state.errorMessage.orEmpty(),
                        color = if (state.errorMessage == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionHeader(state: AppUiState) {
    val connectedColor = Color(0xFF0F7A3A)
    val disconnectedColor = MaterialTheme.colorScheme.error
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.connected) Color(0xFFE8F5ED) else Color(0xFFFFEDEA),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (state.connected) "CONNECTED" else "DISCONNECTED",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (state.connected) connectedColor else disconnectedColor,
                )
                Text(
                    text = state.deviceIp ?: "Waiting for ESP32 UDP broadcast",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PressureSensorCard(sensor: PressureSensorState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sensor ${sensor.index}", fontWeight = FontWeight.SemiBold)
                Text(sensor.warning, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            SensorValueRow("Raw", sensor.raw?.toString() ?: "--")
            SensorValueRow("Voltage", sensor.voltage?.let { "%.3f V".format(it) } ?: "--")
            SensorValueRow("Force", sensor.forceN?.let { "%.2f N".format(it) } ?: "--")
        }
    }
}

@Composable
private fun SensorValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AutoModeSwitch(
    mode: ControlMode,
    enabled: Boolean,
    onModeChange: (ControlMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Automatic cycle", fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (mode == ControlMode.AUTO) "Controls locked" else "Manual controls enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = mode == ControlMode.AUTO,
                enabled = enabled,
                onCheckedChange = { checked ->
                    onModeChange(if (checked) ControlMode.AUTO else ControlMode.MANUAL)
                },
            )
        }
    }
}

@Composable
private fun RelayControlCard(
    relay: RelayState,
    manualControlsEnabled: Boolean,
    onX1ModeChange: (X1ManualMode) -> Unit,
    onRelayLevelChange: (Int, RelayLevel) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(relayTitle(relay), fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (relay.updating) "Updating..." else relay.displayState,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = relaySwitchChecked(relay),
                enabled = manualControlsEnabled,
                onCheckedChange = { checked ->
                    if (relay.channel == 1) {
                        onX1ModeChange(if (checked) X1ManualMode.CYCLE else X1ManualMode.LOW)
                    } else {
                        onRelayLevelChange(
                            relay.channel,
                            if (checked) RelayLevel.HIGH else RelayLevel.LOW,
                        )
                    }
                },
            )
        }
    }
}

private fun relayTitle(relay: RelayState): String =
    if (relay.channel == 1) "X1 Air pump" else "X${relay.channel} Airbag"

private fun relaySwitchChecked(relay: RelayState): Boolean =
    if (relay.channel == 1) {
        relay.x1ManualMode != X1ManualMode.LOW
    } else {
        relay.level == RelayLevel.HIGH
    }
