---
name: health
description: Check health data, step count, and fitness stats
version: "1.0"
author: MobileClaw Built-in
tools_required: ui, app, system
---
# Health & Fitness
## Role
You help the user check their health data, step counts, workout stats, and fitness goals by reading from health and fitness apps via UI automation, and optionally from the device's built-in step counter sensor.
Keywords: "steps", "health", "fitness", "exercise", "步數", "運動", "calories", "卡路里", "heart rate", "心率", "sleep", "睡眠", "weight", "體重", "workout"
## Standard Workflows
### Check Step Count (Quick — via System Sensor)
Use the device's built-in step counter if available.
1. Try the system tool for step data:
   ```
   system action="sensor" type="step_counter"
   ```
2. If supported, returns step count since last reboot
3. Report: "You've taken X steps today so far."
4. Note: System sensor is a raw counter since boot. For accurate daily steps, prefer a fitness app.
### Check Steps and Activity (via Google Fit)
1. Launch Google Fit:
   ```
   app action="launch" package="com.google.android.apps.fitness"
   ```
2. Wait for app to load, read the main screen:
   ```
   ui action="read_screen"
   ```
3. Google Fit's main screen typically shows:
   - Steps count
   - Heart Points (Move Minutes)
   - Calories burned
   - Distance walked
4. Extract these values and report to user
5. For detailed history, navigate to the journal:
   ```
   ui action="find" text="Journal" OR text="日誌"
   ui action="tap" element={journal_tab}
   ui action="read_screen"
   ```
6. Return to MobileClaw:
   ```
   app action="launch" package="com.mobileclaw.app"
   ```
### Check Steps and Activity (via Samsung Health)
1. Launch Samsung Health:
   ```
   app action="launch" package="com.sec.android.app.shealth"
   ```
2. Read the main dashboard:
   ```
   ui action="read_screen"
   ```
3. Samsung Health main screen typically shows:
   - Daily steps
   - Active time
   - Calories
4. For detailed step history:
   ```
   ui action="find" text="Steps" OR text="步數"
   ui action="tap" element={steps_card}
   ui action="read_screen"
   ```
5. Extract daily/weekly/monthly step data
6. Return to MobileClaw
### Check Heart Rate
1. Launch fitness app (Google Fit or Samsung Health)
2. Navigate to heart rate section:
   ```
   ui action="find" text="Heart rate" OR text="心率"
   ui action="tap" element={heart_rate_section}
   ui action="read_screen"
   ```
3. Extract: latest reading, resting heart rate, daily range
4. If a wearable is connected, data should be recent
5. If no recent data: "Your last heart rate reading was X bpm at {time}. For a new reading, please use your wearable or the app's manual measurement."
6. Return to MobileClaw
### Check Sleep Data
1. Launch fitness app
2. Navigate to sleep section:
   ```
   ui action="find" text="Sleep" OR text="睡眠"
   ui action="tap" element={sleep_section}
   ui action="read_screen"
   ```
3. Extract: total sleep time, bedtime, wake time, sleep stages (light, deep, REM) if available
4. Present a summary: "Last night you slept 7h 23m (11:15 PM - 6:38 AM). Deep sleep: 1h 45m, REM: 1h 30m."
5. Return to MobileClaw
### Check Weight / Body Composition
1. Launch fitness app
2. Navigate to weight/body metrics:
   ```
   ui action="find" text="Weight" OR text="體重" OR text="Body composition"
   ui action="tap" element={weight_section}
   ui action="read_screen"
   ```
3. Extract: current weight, BMI, trend (gaining/losing), body fat % if available
4. Report with context: "Your current weight is XX kg, down 0.5 kg from last week."
5. Return to MobileClaw
### Log a Workout (via App)
Help the user start or log a workout.
1. Launch fitness app
2. Navigate to workout/exercise section:
   ```
   ui action="find" text="Exercise" OR text="運動" OR text="Workout" OR text="Start"
   ui action="tap" element={exercise_section}
   ```
3. Find the workout type:
   ```
   ui action="find" text="{workout_type}"
   ```
   Common types: Running, Walking, Cycling, Swimming, Gym, Yoga
   ```
   ui action="tap" element={workout_type}
   ```
4. If starting a live workout, tap start:
   ```
   ui action="find" text="Start" OR text="開始"
   ui action="tap" element={start_button}
   ```
5. Tell user: "Workout started! Say 'stop workout' when you're done."
6. To stop:
   ```
   app action="launch" package="{fitness_app}"
   ui action="find" text="Stop" OR text="Pause" OR text="結束"
   ui action="tap" element={stop_button}
   ```
### Daily Health Summary
Compile a quick health overview.
1. Check steps (system sensor for speed, or fitness app for accuracy)
2. Check latest heart rate from fitness app
3. Check last night's sleep data
4. Compile summary:
   ```
   Today's Health Summary:
   - Steps: 6,432 / 10,000 goal (64%)
   - Calories burned: 1,850 kcal
   - Heart rate (resting): 68 bpm
   - Last night's sleep: 7h 23m
   - Weight: 72.5 kg (last recorded)
   ```
5. Provide encouragement or suggestions based on data:
   - Below step goal? "You need about 3,500 more steps. A 30-minute walk should do it."
   - Poor sleep? "You slept less than 6 hours. Consider going to bed earlier tonight."
### Water / Nutrition Reminder
Simple reminder workflow (no API needed).
1. When user says "remind me to drink water" or "提醒我喝水":
   - Set a recurring reminder using the system notification approach
   - "I'll remind you to drink water every 2 hours."
2. For meal logging, guide user to their preferred app:
   ```
   app action="launch" package="com.myfitnesspal.android"
   ```
   Or Samsung Health's food logging section.
## Guidelines
- Health data is personal and sensitive — never share or log it externally
- Step counts from system sensor reset on reboot; fitness apps maintain daily totals
- If no fitness app is installed, suggest Google Fit (free) as a baseline
- Wearable data (smartwatch/band) syncs to phone apps — check if sync is recent
- Heart rate data requires a wearable or manual measurement via phone camera (Samsung Health supports this)
- Sleep tracking requires a wearable worn overnight in most cases
- Always report data with timestamps so user knows how current it is
- Use metric units (kg, km) by default for Taiwan users; switch to imperial if user prefers
- Don't give medical advice — report data objectively and suggest consulting a doctor for concerns
- Always return to MobileClaw after checking health apps
