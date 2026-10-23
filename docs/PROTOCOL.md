# DeskBuddy protocol

Three surfaces, all plain JSON. The ESP32 never talks to the internet.

| Surface | Port | Who |
| --- | --- | --- |
| HTTP | 80 | browser page, `/api/status`, `/setup` |
| WebSocket | 80 (`/ws`) | browser face |
| TCP lines | 81 | Android sensor node |

Every message is a flat JSON object with a `t` (type) field. Lines on :81 are
newline-delimited; WebSocket messages are one object per frame.

## Server → clients

### `hello`

Sent when a client connects, and to the phone right after accept.

```json
{"t":"hello","fw":"1.0.0","kind":"web","name":"Mochi","ap":0,
 "apSsid":"DeskBuddy-59CC","apPass":"deskbuddy","staIp":"192.168.1.42",
 "state":{"t":"state","mood":"happy","hap":86,"en":72,"full":64,"bond":137,
          "lvl":2,"age":120,"sleep":0,"pets":12,"feeds":3,"plays":5,
          "cur":0.52,"app":0.61,"lazy":0.40,"dark":0,"temp":48.2}}
```

### `state`

Broadcast once a second and after every interaction. `hap`, `en`, `full` are
0–100; `bond` is cumulative and `lvl = 1 + bond/100`.

```json
{"t":"state","name":"Mochi","mood":"excited","hap":88,"en":60,"full":71,
 "bond":203,"lvl":3,"age":840,"sleep":0,"pets":18,"feeds":4,"plays":7,
 "cur":0.52,"app":0.61,"lazy":0.40,"dark":0,"temp":47.9}
```

`mood` ∈ `happy, curious, sleepy, sleeping, hungry, annoyed, dizzy, love,
startled, bored, excited`.

### `react`

Play an effect / expression.

```json
{"t":"react","e":"pet","v":0.85,"x":-1,"y":-1,"mood":"love"}
```

| `e` | Trigger |
| --- | --- |
| `pet` | petted (streamed while dragging) |
| `poke` | tapped |
| `feed` | fed |
| `play` | played |
| `sleep` / `wake` | sleep toggled |
| `shake` | phone was shaken |
| `noise` | loud sound |
| `dark` / `bright` | light sensor threshold |
| `level` | level up |
| `name` | renamed |
| `reset` | stats reset |

`x`,`y` are 0–1 body coordinates for pokes (`-1` = absent). Clients may fire
their own local effects immediately and ignore `v`.

### `say`

```json
{"t":"say","text":"my tummy is making dial-up noises."}
```

### `sens`

Phone telemetry relayed to browser clients 4×/second.

```json
{"t":"sens","ax":0.42,"ay":9.10,"az":3.60,"lux":240.0,
 "mic":0.18,"batt":77.0}
```

## Clients → server

### Touch (browser and phone)

```json
{"t":"touch","kind":"pet","v":0.55,"x":0.50,"y":0.40}
{"t":"touch","kind":"tap","x":0.48,"y":0.35}
```

### Command (browser and phone)

```json
{"t":"cmd","c":"feed"}
```

`c` ∈ `feed, play, sleep, wake, toggle, pet, poke, rename, reset, status, say`.
`rename` and `say` take `"v":"text"`.

### Sensors (phone only, 20 Hz)

```json
{"t":"sensors","ax":0.42,"ay":9.10,"az":3.60,"gx":0.01,"gy":-0.02,"gz":0.00,
 "lux":240.0,"prox":5.0,"mic":0.18,"batt":77,"chg":0,"scr":1}
```

`ax…az` are m/s² (gravity included), `gx…gz` rad/s, `lux` from the light
sensor, `prox` centimetres, `mic` a normalised RMS loudness 0–1, `batt` percent,
`chg` charging flag.

The brain derives shake events from `|accelerometer|` deviating from 9.81 m/s²
by more than ~5 m/s² several times inside 300 ms.

### Keepalive

```json
{"t":"ping"}
```

Any traffic resets the idle timer. Web clients time out after 60 s, phones after
45 s.

## HTTP

| Path | Purpose |
| --- | --- |
| `GET /` | the face, gzip-compressed HTML |
| `GET /api/status` | device + state + sensors snapshot |
| `GET /setup?ssid=…&pass=…` | save WiFi credentials and reboot |
| `GET /setup?forget=1` | wipe WiFi credentials and reboot |
| `GET /api/reboot` | restart |
| `GET /generate_204` etc. | captive-portal bounce to `/` |

`/api/status` example:

```json
{"ok":true,"fw":"1.0.0","ap":0,"host":"deskbuddy.local","staIp":"192.168.1.42",
 "apIp":"192.168.4.1","apSsid":"DeskBuddy-59CC","apPass":"deskbuddy",
 "web":1,"phone":1,"uptime":120,
 "state":{…},"sensors":{…}}
```

## mDNS

Service `_deskbuddy._tcp` on port 80 plus `_http._tcp`, host
`deskbuddy.local`. The Android app uses this for discovery and falls back to a
manual address.
