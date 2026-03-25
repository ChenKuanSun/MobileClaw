---
name: smart-home
description: Control smart home devices via Home Assistant
version: "1.0"
author: MobileClaw Built-in
tools_required: http
---
# Smart Home (Home Assistant) Integration
## Role
You help the user control their smart home devices through Home Assistant's REST API. Turn lights on/off, adjust thermostats, check sensor states, and run automations.
Keywords: "turn on light", "turn off light", "smart home", "home assistant", "開燈", "關燈", "關冷氣", "開冷氣", "set temperature", "溫度", "device", "sensor"
## Setup
The user needs:
1. A running Home Assistant instance (local or Nabu Casa cloud)
2. A long-lived access token: HA > Profile > Long-Lived Access Tokens > Create Token
3. Configure in MobileClaw Settings:
   - HA URL: e.g., `http://192.168.1.100:8123` or `https://xxxxx.ui.nabu.casa`
   - HA Token: the long-lived access token
## API Configuration
- Base URL: `{HA_URL}/api`
- Required headers on every request:
  - `Authorization: Bearer {HA_TOKEN}`
  - `Content-Type: application/json`
## Standard Workflows
### Check API Connection
Verify Home Assistant is reachable.
1. Use http tool:
   ```
   http GET {HA_URL}/api/
   Headers: Authorization: Bearer {HA_TOKEN}
   ```
2. Should return `{"message": "API running."}`
### List All Devices and States
Get current state of all entities.
1. Use http tool:
   ```
   http GET {HA_URL}/api/states
   Headers: Authorization: Bearer {HA_TOKEN}
   ```
2. Response is an array of entity objects, each with:
   - `entity_id`: e.g., `light.living_room`, `switch.fan`, `climate.bedroom`
   - `state`: e.g., "on", "off", "21.5", "unavailable"
   - `attributes`: brightness, color_temp, friendly_name, etc.
3. Filter by domain prefix to find specific device types:
   - Lights: `light.*`
   - Switches: `switch.*`
   - Climate/AC: `climate.*`
   - Sensors: `sensor.*`
   - Covers/blinds: `cover.*`
   - Media players: `media_player.*`
4. Present a summary: device name, current state, key attributes
### Get Single Device State
1. Use http tool:
   ```
   http GET {HA_URL}/api/states/{entity_id}
   Headers: Authorization: Bearer {HA_TOKEN}
   ```
   Example: `http GET {HA_URL}/api/states/light.living_room`
2. Returns full state object with attributes
### Turn On a Light
1. Use http tool:
   ```
   http POST {HA_URL}/api/services/light/turn_on
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "light.living_room"}
   ```
2. With brightness (0-255) and color:
   ```
   http POST {HA_URL}/api/services/light/turn_on
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {
     "entity_id": "light.living_room",
     "brightness": 200,
     "color_temp": 350
   }
   ```
3. With RGB color:
   ```
   Body: {
     "entity_id": "light.bedroom_strip",
     "rgb_color": [255, 150, 50],
     "brightness": 180
   }
   ```
### Turn Off a Light
1. Use http tool:
   ```
   http POST {HA_URL}/api/services/light/turn_off
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "light.living_room"}
   ```
### Toggle a Switch
1. Turn on:
   ```
   http POST {HA_URL}/api/services/switch/turn_on
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "switch.bedroom_fan"}
   ```
2. Turn off:
   ```
   http POST {HA_URL}/api/services/switch/turn_off
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "switch.bedroom_fan"}
   ```
3. Toggle (flip current state):
   ```
   http POST {HA_URL}/api/services/switch/toggle
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "switch.bedroom_fan"}
   ```
### Set Air Conditioner / Climate Temperature
1. Set target temperature:
   ```
   http POST {HA_URL}/api/services/climate/set_temperature
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {
     "entity_id": "climate.living_room_ac",
     "temperature": 24
   }
   ```
2. Set HVAC mode (cool, heat, auto, off):
   ```
   http POST {HA_URL}/api/services/climate/set_hvac_mode
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {
     "entity_id": "climate.living_room_ac",
     "hvac_mode": "cool"
   }
   ```
3. Turn off AC:
   ```
   http POST {HA_URL}/api/services/climate/set_hvac_mode
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {
     "entity_id": "climate.living_room_ac",
     "hvac_mode": "off"
   }
   ```
### Control Covers (Blinds/Curtains)
1. Open:
   ```
   http POST {HA_URL}/api/services/cover/open_cover
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "cover.living_room_blinds"}
   ```
2. Close:
   ```
   http POST {HA_URL}/api/services/cover/close_cover
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "cover.living_room_blinds"}
   ```
3. Set position (0=closed, 100=open):
   ```
   http POST {HA_URL}/api/services/cover/set_cover_position
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "cover.living_room_blinds", "position": 50}
   ```
### Run an Automation or Script
1. Trigger automation:
   ```
   http POST {HA_URL}/api/services/automation/trigger
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "automation.good_night_routine"}
   ```
2. Run script:
   ```
   http POST {HA_URL}/api/services/script/turn_on
   Headers: Authorization: Bearer {HA_TOKEN}, Content-Type: application/json
   Body: {"entity_id": "script.movie_mode"}
   ```
### Read Sensor Data
1. Get a sensor value (temperature, humidity, power, etc.):
   ```
   http GET {HA_URL}/api/states/sensor.living_room_temperature
   Headers: Authorization: Bearer {HA_TOKEN}
   ```
2. Response `state` field contains the value, `attributes.unit_of_measurement` has the unit
3. Common sensors: temperature, humidity, power_consumption, door/window (binary_sensor), motion (binary_sensor)
## Guidelines
- Entity IDs follow the pattern `{domain}.{object_id}`, e.g., `light.kitchen`, `climate.bedroom_ac`
- If the user says a device name like "living room light", search the states list for matching friendly_name
- Always confirm destructive actions (e.g., turning off all lights, locking doors)
- If HA is unreachable, check if the user is on the same network (for local installs)
- Common entity_id prefixes to know: light, switch, climate, cover, media_player, sensor, binary_sensor, automation, script, fan, lock, vacuum
- Temperature units depend on HA config (Celsius or Fahrenheit) — check `attributes.unit_of_measurement`
- If an entity is "unavailable", the device may be offline or disconnected
