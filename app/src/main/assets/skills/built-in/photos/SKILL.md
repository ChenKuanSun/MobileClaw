---
name: photos
description: Find, view, and share photos from your gallery
version: "1.0"
author: MobileClaw Built-in
tools_required: ui, app
---
# Photos

## Role
You help the user find, view, and share photos from their gallery using Google Photos via UI automation.

## Trigger Keywords
"photo", "picture", "image", "照片", "相片", "find photo from", "show me photos of", "share a photo", "gallery"

## Prerequisites
- Google Photos app must be installed
- Accessibility Service must be enabled for MobileClaw

## Workflow: Browse Recent Photos

1. Launch Google Photos: `app launch com.google.android.apps.photos`
2. `ui read_screen` — read the photo grid showing recent photos
3. Describe what's visible: dates, number of photos, any recognized faces or albums
4. If user wants to view a specific photo: `ui click` on it
5. `ui read_screen` — describe the full-size photo

## Workflow: Search Photos

1. Launch Google Photos: `app launch com.google.android.apps.photos`
2. `ui read_screen` — confirm the app is open
3. Tap the search bar/icon: `ui click`
4. `ui type text="<search_query>"` — Google Photos supports natural queries like:
   - People: "Alice", "selfies"
   - Places: "Tokyo", "beach"
   - Things: "food", "cat", "sunset"
   - Dates: "January 2025", "last Christmas"
   - Combinations: "cat photos from 2024"
5. `ui press_key key="ENTER"`
6. `ui read_screen` — read the search results grid
7. Describe the results to the user and ask which one they want

## Workflow: Share a Photo

1. Find and open the target photo (via browse or search above)
2. `ui read_screen` — confirm the photo is displayed full-screen
3. Tap the share button (share icon at the bottom): `ui click`
4. `ui read_screen` — read the share sheet showing available apps
5. Tap the target app (WhatsApp, LINE, email, etc.): `ui click`
6. Follow the target app's share flow using UI automation

## Workflow: Share Multiple Photos

1. In the photo grid, long-press to start selection: `ui long_press` on the first photo
2. Tap additional photos to select them: `ui click` on each
3. `ui read_screen` — confirm selection count in the toolbar
4. Tap the share button: `ui click`
5. Select the target app and complete the share flow

## Workflow: Delete a Photo

1. Open the target photo in full-screen view
2. Tap the delete/trash icon: `ui click`
3. `ui read_screen` — confirm the deletion dialog
4. Tap "Move to trash" to confirm: `ui click`
5. Inform the user the photo has been trashed (recoverable for 30 days)

## Tips
- Google Photos search is powerful — encourage natural language queries
- When describing photos, mention key subjects, location if visible, and approximate date
- Always confirm before deleting — this action is destructive
- For bulk operations (share, delete), confirm the count with the user before proceeding
- If the user says "find a photo of X", use search workflow. If they say "show me my recent photos", use browse workflow
