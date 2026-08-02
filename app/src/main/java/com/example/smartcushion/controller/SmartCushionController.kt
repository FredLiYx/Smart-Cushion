package com.example.smartcushion.controller

import android.content.Context
import com.example.smartcushion.data.network.Esp32Api
import com.example.smartcushion.data.network.UdpDiscoveryClient
import com.example.smartcushion.data.network.WifiStatus
import com.example.smartcushion.domain.model.AlarmState
import com.example.smartcushion.domain.model.AlarmType
import com.example.smartcushion.domain.model.AppUiState
import com.example.smartcushion.domain.model.ControlMode
import com.example.smartcushion.domain.model.PressureSensorState
import com.example.smartcushion.domain.model.RelayLevel
import com.example.smartcushion.domain.model.RelayState
import com.example.smartcushion.domain.model.X1ManualMode
import com.example.smartcushion.notification.SmartCushionNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SmartCushionController(context: Context) {
    private val appContext = context.applicationContext
    private val wifiStatus = WifiStatus(appContext)
    private val discoveryClient = UdpDiscoveryClient(appContext)
    private val esp32Api = Esp32Api()
    private val notifier = SmartCushionNotifier(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)

    private var discoveryJob: Job? = null
    private var statusJob: Job? = null
    private var sensorJob: Job? = null
    private var lastDiscoveryAt = 0L
    private var nextGlobalAlarmAtMillis = 0L
    private var sensorAlarmManualOverrideActive = false
    private val sensorAboveThresholdSince = mutableMapOf<Int, Long>()

    private val mutableUiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (nextGlobalAlarmAtMillis == 0L) {
            nextGlobalAlarmAtMillis = System.currentTimeMillis() + GLOBAL_ALARM_INTERVAL_MS
        }
        discoveryJob = scope.launch { discoveryLoop() }
        statusJob = scope.launch { statusLoop() }
        sensorJob = scope.launch { sensorLoop() }
    }

    fun stop() {
        discoveryJob?.cancel()
        statusJob?.cancel()
        sensorJob?.cancel()
        started.set(false)
    }

    fun dismissAlarm(alarmId: String) {
        val now = System.currentTimeMillis()
        val alarm = mutableUiState.value.alarms.firstOrNull { it.id == alarmId } ?: return

        when (alarm.type) {
            AlarmType.GLOBAL -> nextGlobalAlarmAtMillis = now + GLOBAL_ALARM_INTERVAL_MS
            AlarmType.SENSOR -> {
                val sensorIndex = alarm.sensorIndex
                val sensorVoltage = mutableUiState.value.sensors
                    .firstOrNull { it.index == sensorIndex }
                    ?.voltage

                if (sensorIndex != null && sensorVoltage != null && sensorVoltage > SENSOR_ALARM_VOLTAGE) {
                    sensorAboveThresholdSince[sensorIndex] = now
                } else if (sensorIndex != null) {
                    sensorAboveThresholdSince.remove(sensorIndex)
                }
            }
        }

        mutableUiState.update { state ->
            state.copy(alarms = state.alarms.filterNot { it.id == alarmId })
        }
        notifier.cancelAlarm(alarmId)
        if (sensorAlarmManualOverrideActive) {
            scope.launch { reconcileSensorAlarmRelayControl() }
        }
    }

    fun setControlMode(mode: ControlMode) {
        val ip = mutableUiState.value.deviceIp ?: return
        scope.launch {
            runCommand("Switching mode") {
                esp32Api.setMode(ip, mode)
            }
            refreshStatus()
        }
    }

    fun setX1Mode(mode: X1ManualMode) {
        val ip = mutableUiState.value.deviceIp ?: return
        markRelayUpdating(channel = 1, updating = true)
        scope.launch {
            runCommand("Updating X1") {
                esp32Api.setX1Mode(ip, mode)
            }
            markRelayUpdating(channel = 1, updating = false)
            refreshStatus()
        }
    }

    fun setRelayLevel(channel: Int, level: RelayLevel) {
        val ip = mutableUiState.value.deviceIp ?: return
        markRelayUpdating(channel, updating = true)
        scope.launch {
            runCommand("Updating X$channel") {
                esp32Api.setRelay(ip, channel, level)
            }
            markRelayUpdating(channel, updating = false)
            refreshStatus()
        }
    }

    fun simulateSensorAlarm(sensorIndex: Int) {
        if (sensorIndex !in 1..4) return
        triggerAlarm(createSensorAlarm(sensorIndex, System.currentTimeMillis(), simulated = true))
    }

    fun simulateGlobalAlarm() {
        val now = System.currentTimeMillis()
        triggerAlarm(createGlobalAlarm(now, simulated = true))
    }

    private suspend fun discoveryLoop() {
        while (started.get()) {
            if (!wifiStatus.isWifiConnected()) {
                lastDiscoveryAt = 0L
                mutableUiState.update {
                    it.copy(connected = false, deviceIp = null, errorMessage = null)
                }
                delay(1000)
                continue
            }

            val discoveredIp = discoveryClient.listenOnce()
            val now = System.currentTimeMillis()
            if (discoveredIp != null) {
                lastDiscoveryAt = now
                mutableUiState.update {
                    it.copy(connected = true, deviceIp = discoveredIp, errorMessage = null)
                }
            } else {
                val isConnected = lastDiscoveryAt > 0 && now - lastDiscoveryAt <= DISCOVERY_WINDOW_MS
                mutableUiState.update { it.copy(connected = isConnected) }
            }
        }
    }

    private suspend fun statusLoop() {
        while (started.get()) {
            refreshStatus()
            delay(STATUS_REFRESH_MS)
        }
    }

    private suspend fun sensorLoop() {
        while (started.get()) {
            refreshSensors()
            checkGlobalAlarm()
            delay(SENSOR_REFRESH_MS)
        }
    }

    private suspend fun refreshStatus() {
        val state = mutableUiState.value
        val ip = state.deviceIp
        if (!state.connected || ip == null) return

        try {
            val status = esp32Api.getStatus(ip)
            mutableUiState.update {
                it.copy(
                    controlMode = status.mode,
                    x1ManualMode = status.x1ManualMode,
                    relays = mergeUpdatingFlags(status.relays, it.relays),
                    errorMessage = null,
                )
            }
        } catch (error: Exception) {
            mutableUiState.update { it.copy(errorMessage = error.message ?: "Status request failed") }
        }
    }

    private suspend fun refreshSensors() {
        val state = mutableUiState.value
        val ip = state.deviceIp
        if (!state.connected || ip == null) {
            sensorAboveThresholdSince.clear()
            return
        }

        try {
            val sensors = esp32Api.getSensors(ip)
            if (sensors.isNotEmpty()) {
                val completeSensors = completeSensors(sensors)
                mutableUiState.update { it.copy(sensors = completeSensors, errorMessage = null) }
                checkSensorAlarms(completeSensors)
            }
        } catch (error: Exception) {
            sensorAboveThresholdSince.clear()
            mutableUiState.update { it.copy(errorMessage = error.message ?: "Sensor request failed") }
        }
    }

    private fun checkSensorAlarms(sensors: List<PressureSensorState>) {
        val now = System.currentTimeMillis()
        sensors.forEach { sensor ->
            val alarmId = sensorAlarmId(sensor.index)
            if (hasActiveAlarm(alarmId)) return@forEach

            val voltage = sensor.voltage
            if (voltage == null || voltage <= SENSOR_ALARM_VOLTAGE) {
                sensorAboveThresholdSince.remove(sensor.index)
                return@forEach
            }

            val thresholdStartedAt = sensorAboveThresholdSince.getOrPut(sensor.index) { now }
            if (now - thresholdStartedAt >= SENSOR_ALARM_DURATION_MS) {
                triggerAlarm(createSensorAlarm(sensor.index, now))
            }
        }
    }

    private fun checkGlobalAlarm() {
        val now = System.currentTimeMillis()
        if (nextGlobalAlarmAtMillis == 0L) {
            nextGlobalAlarmAtMillis = now + GLOBAL_ALARM_INTERVAL_MS
            return
        }
        if (now < nextGlobalAlarmAtMillis || hasActiveAlarm(GLOBAL_ALARM_ID)) return

        triggerAlarm(createGlobalAlarm(now))
    }

    private fun triggerAlarm(alarm: AlarmState) {
        if (hasActiveAlarm(alarm.id)) return

        mutableUiState.update { state ->
            state.copy(alarms = state.alarms + alarm)
        }
        notifier.showAlarm(alarm)
        if (alarm.type == AlarmType.SENSOR) {
            sensorAlarmManualOverrideActive = true
            scope.launch { reconcileSensorAlarmRelayControl() }
        }
    }

    private fun hasActiveAlarm(alarmId: String): Boolean =
        mutableUiState.value.alarms.any { it.id == alarmId }

    private suspend fun reconcileSensorAlarmRelayControl() {
        val state = mutableUiState.value
        val ip = state.deviceIp
        if (!state.connected || ip == null) return

        val activeSensorIndexes = state.alarms
            .filter { it.type == AlarmType.SENSOR }
            .mapNotNull { it.sensorIndex }
            .toSet()

        if (activeSensorIndexes.isEmpty()) {
            if (sensorAlarmManualOverrideActive && state.alarms.isEmpty()) {
                runCommand("Restoring automatic mode") {
                    esp32Api.setMode(ip, ControlMode.AUTO)
                }
                sensorAlarmManualOverrideActive = false
                refreshStatus()
            }
            return
        }

        runCommand("Applying alarm airbag control") {
            esp32Api.setMode(ip, ControlMode.MANUAL)
            SENSOR_TO_RELAY_CHANNEL.forEach { (sensorIndex, relayChannel) ->
                esp32Api.setRelay(
                    ip = ip,
                    channel = relayChannel,
                    level = if (sensorIndex in activeSensorIndexes) {
                        RelayLevel.HIGH
                    } else {
                        RelayLevel.LOW
                    },
                )
            }
        }
        mutableUiState.update { currentState ->
            currentState.copy(
                controlMode = ControlMode.MANUAL,
                relays = currentState.relays.map { relay ->
                    val sensorIndex = relayChannelToSensorIndex(relay.channel)
                    if (sensorIndex == null) {
                        relay
                    } else {
                        relay.copy(
                            level = if (sensorIndex in activeSensorIndexes) {
                                RelayLevel.HIGH
                            } else {
                                RelayLevel.LOW
                            },
                        )
                    }
                },
            )
        }
        refreshStatus()
    }

    private fun createSensorAlarm(
        sensorIndex: Int,
        triggeredAtMillis: Long,
        simulated: Boolean = false,
    ): AlarmState {
        val prefix = if (simulated) "Simulated: " else ""
        return AlarmState(
            id = sensorAlarmId(sensorIndex),
            type = AlarmType.SENSOR,
            title = "${prefix}Sensor $sensorIndex pressure alarm",
            message = "Sensor $sensorIndex is detecting high pressure for 1 hour.",
            sensorIndex = sensorIndex,
            triggeredAtMillis = triggeredAtMillis,
        )
    }

    private fun createGlobalAlarm(
        triggeredAtMillis: Long,
        simulated: Boolean = false,
    ): AlarmState {
        val prefix = if (simulated) "Simulated: " else ""
        return AlarmState(
            id = GLOBAL_ALARM_ID,
            type = AlarmType.GLOBAL,
            title = "${prefix}Reposition patient",
            message = "Smart Cushion has been running for 2 hours. Please reposition the patient.",
            triggeredAtMillis = triggeredAtMillis,
        )
    }

    private suspend fun runCommand(message: String, block: suspend () -> Unit) {
        mutableUiState.update { it.copy(busyMessage = message, errorMessage = null) }
        try {
            block()
        } catch (error: Exception) {
            mutableUiState.update { it.copy(errorMessage = error.message ?: "Request failed") }
        } finally {
            mutableUiState.update { it.copy(busyMessage = null) }
        }
    }

    private fun markRelayUpdating(channel: Int, updating: Boolean) {
        mutableUiState.update { state ->
            state.copy(relays = state.relays.map {
                if (it.channel == channel) it.copy(updating = updating) else it
            })
        }
    }

    private fun mergeUpdatingFlags(
        freshRelays: List<RelayState>,
        currentRelays: List<RelayState>,
    ): List<RelayState> = freshRelays.map { fresh ->
        fresh.copy(updating = currentRelays.firstOrNull { it.channel == fresh.channel }?.updating == true)
    }

    private fun completeSensors(sensors: List<PressureSensorState>): List<PressureSensorState> =
        (1..4).map { index ->
            sensors.firstOrNull { it.index == index } ?: PressureSensorState(index)
        }

    companion object {
        private const val DISCOVERY_WINDOW_MS = 3000L
        private const val STATUS_REFRESH_MS = 5000L
        private const val SENSOR_REFRESH_MS = 5000L
        private const val SENSOR_ALARM_VOLTAGE = 4.0
        private const val SENSOR_ALARM_DURATION_MS = 60 * 60 * 1000L
        private const val GLOBAL_ALARM_INTERVAL_MS = 2 * 60 * 60 * 1000L
        private const val GLOBAL_ALARM_ID = "global_reposition"
        private val SENSOR_TO_RELAY_CHANNEL = mapOf(
            1 to 2,
            2 to 3,
            3 to 4,
            4 to 5,
        )

        private fun sensorAlarmId(sensorIndex: Int): String = "sensor_$sensorIndex"

        private fun relayChannelToSensorIndex(relayChannel: Int): Int? =
            SENSOR_TO_RELAY_CHANNEL.entries.firstOrNull { it.value == relayChannel }?.key
    }
}
