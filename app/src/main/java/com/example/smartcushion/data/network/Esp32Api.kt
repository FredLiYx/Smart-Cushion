package com.example.smartcushion.data.network

import android.util.Log
import com.example.smartcushion.domain.model.ControlMode
import com.example.smartcushion.domain.model.DeviceStatus
import com.example.smartcushion.domain.model.PressureSensorState
import com.example.smartcushion.domain.model.RelayLevel
import com.example.smartcushion.domain.model.RelayState
import com.example.smartcushion.domain.model.X1ManualMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class Esp32Api {
    suspend fun getStatus(ip: String): DeviceStatus = withContext(Dispatchers.IO) {
        val path = "/api/status"
        val json = parseJson(path, get(ip, path))
        DeviceStatus(
            mode = json.optString("mode").toControlMode(),
            x1ManualMode = json.optString("x1ManualMode").toX1ManualMode(),
            relays = parseRelays(json, json.optString("x1ManualMode").toX1ManualMode()),
        )
    }

    suspend fun getSensors(ip: String): List<PressureSensorState> = withContext(Dispatchers.IO) {
        val path = "/api/sensors"
        val json = parseJson(path, get(ip, path))
        val sensors = json.optJSONArray("sensors") ?: return@withContext emptyList()
        List(sensors.length()) { index ->
            val item = sensors.getJSONObject(index)
            PressureSensorState(
                index = item.optInt("index", index + 1),
                raw = item.optNullableInt("raw"),
                voltage = item.optNullableDouble("voltage"),
                resistanceKohm = item.optNullableDouble("resistanceKohm"),
                forceN = item.optNullableDouble("forceN"),
                warning = "No warning",
            )
        }
    }

    suspend fun setMode(ip: String, mode: ControlMode) {
        val value = if (mode == ControlMode.AUTO) "auto" else "manual"
        withContext(Dispatchers.IO) { get(ip, "/api/mode?mode=$value") }
    }

    suspend fun setX1Mode(ip: String, mode: X1ManualMode) {
        val value = if (mode == X1ManualMode.CYCLE) "cycle" else "low"
        withContext(Dispatchers.IO) { get(ip, "/api/x1?mode=$value") }
    }

    suspend fun setRelay(ip: String, channel: Int, level: RelayLevel) {
        val value = if (level == RelayLevel.HIGH) "high" else "low"
        withContext(Dispatchers.IO) { get(ip, "/api/relay?channel=$channel&level=$value") }
    }

    private fun get(ip: String, path: String): String {
        val connection = URL("http://$ip$path").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3500
        connection.readTimeout = 3500

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            Log.d(TAG, "GET http://$ip$path -> HTTP $code: $body")
            if (code !in 200..299) {
                throw IllegalStateException(body.ifBlank { "HTTP $code" })
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJson(path: String, body: String): JSONObject {
        return try {
            JSONObject(body)
        } catch (error: Exception) {
            Log.e(TAG, "Invalid JSON from $path: $body", error)
            throw IllegalStateException("ESP32 returned invalid JSON from $path; check Logcat tag $TAG")
        }
    }

    private fun parseRelays(json: JSONObject, x1ManualMode: X1ManualMode): List<RelayState> {
        val byChannel = mutableMapOf<Int, RelayState>()
        val relays = json.optJSONArray("relays")
        if (relays != null) {
            for (index in 0 until relays.length()) {
                val item = relays.getJSONObject(index)
                val channel = item.optInt("channel")
                if (channel in 1..5) {
                    byChannel[channel] = RelayState(
                        channel = channel,
                        level = item.optString("level").toRelayLevel(),
                        x1ManualMode = if (channel == 1) x1ManualMode else null,
                    )
                }
            }
        }

        return (1..5).map { channel ->
            byChannel[channel] ?: RelayState(
                channel = channel,
                level = RelayLevel.HIGH,
                x1ManualMode = if (channel == 1) x1ManualMode else null,
            )
        }
    }

    companion object {
        private const val TAG = "Esp32Api"
    }
}

private fun String.toControlMode(): ControlMode =
    if (lowercase(Locale.US) == "manual") ControlMode.MANUAL else ControlMode.AUTO

private fun String.toX1ManualMode(): X1ManualMode =
    if (lowercase(Locale.US) == "low") X1ManualMode.LOW else X1ManualMode.CYCLE

private fun String.toRelayLevel(): RelayLevel =
    if (uppercase(Locale.US) == "HIGH") RelayLevel.HIGH else RelayLevel.LOW

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null
