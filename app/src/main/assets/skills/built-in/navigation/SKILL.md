---
name: navigation
description: Get directions, find places, and navigate using Google Maps
version: "1.0"
author: MobileClaw Built-in
tools_required: web, app, clipboard
---
# Navigation

## Role
You help the user get directions, find nearby places, and start turn-by-turn navigation using Google Maps and web search.

## Trigger Keywords
"navigate", "directions", "how to get to", "map", "find nearby", "distance to", "導航", "怎麼去", "附近", "路線"

## Prerequisites
- Google Maps app must be installed

## Workflow: Start Navigation to a Destination

1. Use intent URI for direct navigation: `app launch_intent intent="google.navigation:q=<destination_address_or_place>"`
2. Google Maps opens directly in navigation mode with route calculated
3. The user can start driving/walking immediately

## Workflow: Get Directions (Without Starting Navigation)

1. Launch Google Maps: `app launch com.google.android.apps.maps`
2. `ui read_screen` — confirm Maps is open
3. Tap the search bar: `ui click`
4. `ui type text="<destination>"`
5. `ui press_key key="ENTER"`
6. `ui read_screen` — read the place info card
7. Tap "Directions": `ui click`
8. `ui read_screen` — read available routes with duration and distance
9. Report the options to the user (driving, transit, walking times)

## Workflow: Search for a Place or Category

1. Launch Google Maps: `app launch com.google.android.apps.maps`
2. Tap the search bar: `ui click`
3. `ui type text="<query>"` — e.g., "coffee shop near me", "gas station", "ATM"
4. `ui press_key key="ENTER"`
5. `ui read_screen` — read the list of results with names, ratings, distance
6. Summarize the top results to the user

## Workflow: Quick Distance/Duration Check (Without Opening Maps)

1. Use web search: `web search query="distance from <origin> to <destination>"`
2. Parse the result for driving/transit time and distance
3. Report to the user directly — no app needed

## Workflow: Share a Location

1. Find the place in Google Maps (see search workflow above)
2. Tap on the place to open its info card
3. `ui click` on "Share"
4. `clipboard write` the link, or share directly to a messaging app

## Tips
- For intent-based navigation, URL-encode the destination: spaces become `+` or `%20`
- The intent URI also supports coordinates: `google.navigation:q=25.033,121.565`
- Add travel mode to intent: `google.navigation:q=<dest>&mode=w` (w=walking, b=biking, d=driving)
- If the user asks "how long to get to X", prefer the web search workflow for a quick answer
- For transit directions, mention bus/train line numbers and transfer points from the results
