package com.example.smartcushion.domain.model

enum class ControlMode {
    AUTO,
    MANUAL
}

enum class X1ManualMode {
    CYCLE,
    LOW
}

enum class RelayLevel {
    HIGH,
    LOW
}

data class RelayState(
    val channel: Int,
    val name: String = "X$channel",
    val level: RelayLevel? = null,
    val x1ManualMode: X1ManualMode? = null,
    val updating: Boolean = false,
) {
    val displayState: String
        get() = when (channel) {
            1 -> if (x1ManualMode == X1ManualMode.LOW) "OFF" else "ON"
            else -> if (level == RelayLevel.HIGH) "Empty" else "Filled"
        }
}

data class PressureSensorState(
    val index: Int,
    val raw: Int? = null,
    val voltage: Double? = null,
    val resistanceKohm: Double? = null,
    val forceN: Double? = null,
    val warning: String = "No warning",
)

data class AppUiState(
    val connected: Boolean = false,
    val deviceIp: String? = null,
    val controlMode: ControlMode = ControlMode.AUTO,
    val x1ManualMode: X1ManualMode = X1ManualMode.CYCLE,
    val relays: List<RelayState> = (1..5).map { channel ->
        RelayState(
            channel = channel,
            x1ManualMode = if (channel == 1) X1ManualMode.CYCLE else null,
            level = RelayLevel.HIGH,
        )
    },
    val sensors: List<PressureSensorState> = (1..4).map { PressureSensorState(it) },
    val busyMessage: String? = null,
    val errorMessage: String? = null,
)

data class DeviceStatus(
    val mode: ControlMode,
    val x1ManualMode: X1ManualMode,
    val relays: List<RelayState>,
)
