package com.example.smartcushion.controller

import android.content.Context
import com.example.smartcushion.data.network.Esp32Api
import com.example.smartcushion.data.network.UdpDiscoveryClient
import com.example.smartcushion.data.network.WifiStatus
import com.example.smartcushion.domain.model.AppUiState
import com.example.smartcushion.domain.model.ControlMode
import com.example.smartcushion.domain.model.PressureSensorState
import com.example.smartcushion.domain.model.RelayLevel
import com.example.smartcushion.domain.model.RelayState
import com.example.smartcushion.domain.model.X1ManualMode
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)

    private var discoveryJob: Job? = null
    private var statusJob: Job? = null
    private var sensorJob: Job? = null
    private var lastDiscoveryAt = 0L

    private val mutableUiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
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
        if (!state.connected || ip == null) return

        try {
            val sensors = esp32Api.getSensors(ip)
            if (sensors.isNotEmpty()) {
                mutableUiState.update { it.copy(sensors = completeSensors(sensors), errorMessage = null) }
            }
        } catch (error: Exception) {
            mutableUiState.update { it.copy(errorMessage = error.message ?: "Sensor request failed") }
        }
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
    }
}
