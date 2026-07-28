# ESP32 Cushion Relay HTTP API

This project runs an ESP32 relay controller with WiFi HTTP control. The device
starts an HTTP server on port `80` after joining the configured WiFi network.

## WiFi

Fill the WiFi credentials in `src/main.cpp`:

```cpp
constexpr char WIFI_SSID[] = "your-wifi-name";
constexpr char WIFI_PASSWORD[] = "your-wifi-password";
```

After the ESP32 connects, it broadcasts a UDP discovery packet every 2 seconds.
The phone app can listen for this packet and use the sender IP address:

| Item | Value |
| --- | --- |
| UDP port | `4210` |
| Payload | `ESP32_CUSHION` |

After the app discovers the ESP32 IP, call:

```text
http://<esp32-ip>
```

All API responses are JSON except `/`, which returns a plain text route summary.
CORS headers are enabled for phone apps or browser-based tools.

WiFi connection behavior:

- On boot, if the ESP32 cannot connect to WiFi within 10 seconds, it stops WiFi
  connection attempts and runs the default auto relay cycle.
- If WiFi disconnects after a successful connection, the ESP32 keeps the current
  control mode while trying to reconnect.
- If reconnecting still fails after 1 minute, it stops WiFi connection attempts
  and restores the default auto relay cycle.

## Relay Logic

Relay channels use the existing `RelayController` mapping:

| Relay | GPIO |
| --- | --- |
| X1 | 23 |
| X2 | 16 |
| X3 | 17 |
| X4 | 18 |
| X5 | 19 |

The firmware reports and accepts logical levels as `HIGH` and `LOW`.

### Auto Mode

Auto mode is the default mode.

- X1 runs its own `270s HIGH + 30s LOW` cycle.
- X2-X5 run a 20 minute cycle:
  - 0-5 min: X2-X5 LOW
  - 5-10 min: X2 and X3 HIGH
  - 10-15 min: X2-X5 LOW
  - 15-20 min: X4 and X5 HIGH

### Manual Mode

Manual mode allows direct control of X2-X5.

X1 is special in manual mode. It has only two options:

- `cycle`: keep running the `270s HIGH + 30s LOW` cycle.
- `low`: keep X1 LOW.

Trying to control X1 with the normal relay endpoints returns an error.

## API

### Get Status

```http
GET /api/status
```

Returns current mode, X1 manual mode, WiFi status, and relay states.

`wifi.state` can be:

| Value | Meaning |
| --- | --- |
| `connecting` | WiFi is currently trying to connect or reconnect. |
| `connected` | WiFi is connected and HTTP control is available. |
| `stopped` | WiFi attempts have timed out and the device is running locally. |

Example:

```text
http://<esp32-ip>/api/status
```

Example response:

```json
{
  "ok": true,
  "mode": "auto",
  "x1ManualMode": "cycle",
  "wifi": {
    "connected": true,
    "state": "connected",
    "ip": "192.168.1.80"
  },
  "udpDiscovery": {
    "port": 4210,
    "message": "ESP32_CUSHION"
  },
  "relays": [
    {
      "channel": 1,
      "name": "X1",
      "pin": 23,
      "level": "HIGH",
      "high": true
    }
  ]
}
```

### Set Control Mode

```http
GET  /api/mode?mode=auto|manual
POST /api/mode?mode=auto|manual
```

Parameters:

| Name | Values | Description |
| --- | --- | --- |
| `mode` | `auto`, `manual` | Select automatic cycle or manual control. |

Examples:

```text
http://<esp32-ip>/api/mode?mode=auto
http://<esp32-ip>/api/mode?mode=manual
```

### Set X1 Manual Mode

```http
GET  /api/x1?mode=cycle|low
POST /api/x1?mode=cycle|low
```

This endpoint switches the device to manual mode if it is not already manual.

Parameters:

| Name | Values | Description |
| --- | --- | --- |
| `mode` | `cycle`, `low` | `cycle` keeps X1 on the 270s/30s cycle. `low` keeps X1 LOW. |

Accepted aliases:

| Canonical | Aliases |
| --- | --- |
| `cycle` | `auto`, `270+30` |
| `low` | `off`, `0` |

Examples:

```text
http://<esp32-ip>/api/x1?mode=cycle
http://<esp32-ip>/api/x1?mode=low
```

### Set One Relay

```http
GET  /api/relay?channel=2..5&level=high|low
POST /api/relay?channel=2..5&level=high|low
```

This endpoint switches the device to manual mode if it is not already manual.

Parameters:

| Name | Values | Description |
| --- | --- | --- |
| `channel` | `2`, `3`, `4`, `5` | Relay channel to control. X1 is not allowed here. |
| `level` | `high`, `low` | Target logical level. |

Accepted level aliases:

| HIGH | LOW |
| --- | --- |
| `high` | `low` |
| `on` | `off` |
| `true` | `false` |
| `1` | `0` |

`state` can be used instead of `level`.

Examples:

```text
http://<esp32-ip>/api/relay?channel=2&level=high
http://<esp32-ip>/api/relay?channel=5&level=low
```

### Set Multiple Relays

```http
GET  /api/relays?x2=low&x3=high&x4=low&x5=low
POST /api/relays?x2=low&x3=high&x4=low&x5=low
```

This endpoint switches the device to manual mode if it is not already manual.
Provide one or more of `x2`, `x3`, `x4`, and `x5`. X1 is not allowed here.
Any omitted relay keeps its current manual value.

Examples:

```text
http://<esp32-ip>/api/relays?x2=high&x3=high
http://<esp32-ip>/api/relays?x4=high&x5=high&x2=low&x3=low
```

### Get All Pressure Sensors

```http
GET /api/sensors
```

Returns readings from the four reserved pressure sensor inputs.

Sensor pins follow `ref/esp32_pressure_relay_wiring.yaml`:

| Sensor | GPIO |
| --- | --- |
| 1 | 32 |
| 2 | 33 |
| 3 | 34 |
| 4 | 35 |

Example:

```text
http://<esp32-ip>/api/sensors
```

Example response:

```json
{
  "ok": true,
  "sensors": [
    {
      "index": 1,
      "pin": 32,
      "raw": 1234,
      "voltage": 0.994,
      "resistanceKohm": 33.205,
      "forceN": 13.732
    }
  ]
}
```

### Get One Pressure Sensor

```http
GET /api/sensor?index=1..4
```

Parameters:

| Name | Values | Description |
| --- | --- | --- |
| `index` | `1`, `2`, `3`, `4` | Sensor number to read. |

Example:

```text
http://<esp32-ip>/api/sensor?index=1
```

## Error Response

Invalid requests return JSON like:

```json
{
  "ok": false,
  "error": "X1 manual control must be cycle or low; use /api/x1?mode=cycle|low"
}
```

Common errors:

| Case | Result |
| --- | --- |
| `/api/relay?channel=1&level=high` | Rejected because X1 only supports `cycle` or `low` in manual mode. |
| `/api/relays?x1=low` | Rejected for the same reason. |
| Missing or invalid channel | Returns `400`. |
| Missing or invalid level/mode/index | Returns `400`. |
