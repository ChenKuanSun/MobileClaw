# Permissions

MobileClaw requests the following Android permissions. Each is used by a specific tool.

| Permission | Tool | Why |
|-----------|------|-----|
| `INTERNET` | All AI tools | Connect to AI provider APIs |
| `ACCESS_NETWORK_STATE` | System | Check network connectivity |
| `READ_SMS` | SmsTool | Search SMS messages |
| `SEND_SMS` | SmsTool | Send SMS messages |
| `READ_CALL_LOG` | CallLogTool | Query missed/incoming/outgoing calls |
| `READ_CONTACTS` | ContactsTool | Search and lookup contacts |
| `READ_CALENDAR` | CalendarTool | Query calendar events |
| `WRITE_CALENDAR` | CalendarTool | Create calendar events |
| `POST_NOTIFICATIONS` | AgentService | Show foreground service notification |
| `FOREGROUND_SERVICE` | AgentService | Keep agent running in background |
| `FOREGROUND_SERVICE_SPECIAL_USE` | AgentService | Android 14+ foreground service type |
| `RECEIVE_BOOT_COMPLETED` | BootReceiver | Auto-start agent after device reboot |
| `CAMERA` | Attachments | Take photos to send to AI |
| `CALL_PHONE` | PhoneCallTool | Initiate phone calls |
| `RECORD_AUDIO` | VoiceInput | Speech-to-text voice input |
| `SET_ALARM` | AlarmTimerTool | Set alarms and timers |
| `READ_MEDIA_IMAGES` | PhotoTool | Search device photos (API 33+) |
| `READ_MEDIA_AUDIO` | OpenAiTool | Access audio files for transcription (API 33+) |
| `READ_EXTERNAL_STORAGE` | PhotoTool | Access photos on older Android (API <33) |

### Special Permissions (require manual enablement)

| Permission | How to Enable | Why |
|-----------|--------------|-----|
| `BIND_ACCESSIBILITY_SERVICE` | Settings > Accessibility > MobileClaw | Read and interact with any app's UI |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Settings > Notifications > Notification access | Read notifications from all apps |
