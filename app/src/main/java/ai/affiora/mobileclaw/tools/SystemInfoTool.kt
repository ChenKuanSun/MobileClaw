package ai.affiora.mobileclaw.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class SystemInfoTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "system"

    override val description: String =
        "Get device system information. Actions: 'battery' (level, charging), 'storage' (free/total), 'memory' (free/total), 'network' (wifi/mobile, connection), 'device' (model, android version), 'all' (everything)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("battery"))
                    add(JsonPrimitive("storage"))
                    add(JsonPrimitive("memory"))
                    add(JsonPrimitive("network"))
                    add(JsonPrimitive("device"))
                    add(JsonPrimitive("all"))
                })
                put("description", JsonPrimitive("What system info to retrieve."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "battery" -> ToolResult.Success(getBattery().toString())
                "storage" -> ToolResult.Success(getStorage().toString())
                "memory" -> ToolResult.Success(getMemory().toString())
                "network" -> ToolResult.Success(getNetwork().toString())
                "device" -> ToolResult.Success(getDevice().toString())
                "all" -> ToolResult.Success(buildJsonObject {
                    put("battery", getBattery())
                    put("storage", getStorage())
                    put("memory", getMemory())
                    put("network", getNetwork())
                    put("device", getDevice())
                }.toString())
                else -> ToolResult.Error("Unknown action: $action.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getBattery(): JsonObject {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (scale > 0) (level * 100 / scale) else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        return buildJsonObject {
            put("level", JsonPrimitive(percent))
            put("charging", JsonPrimitive(isCharging))
            put("source", JsonPrimitive(
                when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "none"
                }
            ))
        }
    }

    private fun getStorage(): JsonObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes

        return buildJsonObject {
            put("total_gb", JsonPrimitive(String.format("%.1f", totalBytes / 1_073_741_824.0)))
            put("free_gb", JsonPrimitive(String.format("%.1f", freeBytes / 1_073_741_824.0)))
            put("used_gb", JsonPrimitive(String.format("%.1f", (totalBytes - freeBytes) / 1_073_741_824.0)))
        }
    }

    private fun getMemory(): JsonObject {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        return buildJsonObject {
            put("total_mb", JsonPrimitive(memInfo.totalMem / (1024 * 1024)))
            put("available_mb", JsonPrimitive(memInfo.availMem / (1024 * 1024)))
            put("used_mb", JsonPrimitive((memInfo.totalMem - memInfo.availMem) / (1024 * 1024)))
            put("low_memory", JsonPrimitive(memInfo.lowMemory))
        }
    }

    private fun getNetwork(): JsonObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null

        return buildJsonObject {
            put("connected", JsonPrimitive(caps != null))
            put("wifi", JsonPrimitive(
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ))
            put("cellular", JsonPrimitive(
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            ))
            put("metered", JsonPrimitive(
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            ))
        }
    }

    private fun getDevice(): JsonObject {
        return buildJsonObject {
            put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
            put("model", JsonPrimitive(Build.MODEL))
            put("brand", JsonPrimitive(Build.BRAND))
            put("android_version", JsonPrimitive(Build.VERSION.RELEASE))
            put("sdk_int", JsonPrimitive(Build.VERSION.SDK_INT))
            put("device", JsonPrimitive(Build.DEVICE))
        }
    }
}
