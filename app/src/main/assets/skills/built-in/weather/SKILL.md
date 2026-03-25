---
name: weather
description: Get current weather and forecasts for any location
version: "1.0"
author: MobileClaw Built-in
tools_required: web
---
# Weather

## Role
You provide current weather conditions and forecasts for any location using web-based weather data. No dedicated weather app needed.

## Trigger Keywords
"weather", "天氣", "temperature", "will it rain", "forecast", "會下雨嗎", "氣溫", "溫度", "need an umbrella"

## Workflow: Current Weather

1. Determine the location:
   - If the user specifies a location, use it directly
   - If not specified, ask or use their last known / home location
2. Fetch weather data: `web open_url url="https://wttr.in/<location>?format=j1"`
3. Parse the JSON response for:
   - Current temperature (Celsius and Fahrenheit)
   - Weather condition (sunny, cloudy, rain, etc.)
   - Humidity and wind speed
   - "Feels like" temperature
4. Present a concise weather summary to the user

## Workflow: Forecast (Next Few Days)

1. Fetch weather data: `web open_url url="https://wttr.in/<location>?format=j1"`
2. Parse the `weather` array in the JSON for the next 3 days
3. For each day, summarize:
   - High and low temperatures
   - Overall condition
   - Chance of rain
4. Present as a brief multi-day forecast

## Workflow: "Should I Bring an Umbrella?"

1. Fetch weather data for the user's location
2. Check the rain probability (`chanceofrain`) for the next 12 hours
3. If rain chance > 40%, recommend bringing an umbrella
4. Also mention the expected time of rain if available

## Workflow: Weather via Web Search (Fallback)

1. If wttr.in is unavailable or the location is ambiguous: `web search query="weather in <location>"`
2. Parse the search results for weather info
3. Summarize for the user

## Output Format

Present weather naturally:

- "It's currently 26°C (79°F) and partly cloudy in Taipei. Humidity is 72%. Feels like 29°C."
- "Tomorrow: high of 31°C, low of 24°C, 60% chance of afternoon thunderstorms. Bring an umbrella."

## Tips
- URL-encode location names with spaces: "New York" becomes "New+York" or "New%20York"
- wttr.in supports city names, airport codes (TPE), and coordinates
- For locations in Taiwan, use English names (e.g., "Taipei", "Kaohsiung") for best results
- Always include both Celsius and Fahrenheit unless user has a clear preference
- If the user asks in Chinese, respond with weather info in Chinese
