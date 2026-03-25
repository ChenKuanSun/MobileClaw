package ai.affiora.mobileclaw.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

class ContactsTool(
    private val context: Context
) : AndroidTool {

    override val name: String = "contacts"

    override val description: String =
        "Search contacts by name or look up a contact by phone number."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("get_by_number"))
                })
                put("description", JsonPrimitive("Action: 'search' by name or 'get_by_number' for reverse lookup."))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Name to search for (required for 'search' action)."))
            })
            put("number", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Phone number for reverse lookup (required for 'get_by_number')."))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max number of contacts to return. Default 20."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Error("Contacts permission not granted. Go to Android Settings > Apps > MobileClaw > Permissions > Contacts to grant it.")
        }

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> executeSearch(params)
                "get_by_number" -> executeGetByNumber(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'search' or 'get_by_number'.")
            }
        }
    }

    private fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")
        val limit = params["limit"]?.jsonPrimitive?.int ?: 20

        val resolver: ContentResolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        ) ?: return ToolResult.Error("Failed to query contacts")

        val results = buildJsonArray {
            var count = 0
            cursor.use { c ->
                while (c.moveToNext() && count < limit) {
                    val contactId = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    val hasPhone = c.getInt(2) > 0

                    add(buildJsonObject {
                        put("id", JsonPrimitive(contactId))
                        put("name", JsonPrimitive(name))
                        put("phones", getPhoneNumbers(resolver, contactId, hasPhone))
                        put("emails", getEmails(resolver, contactId))
                        put("note", JsonPrimitive(getNote(resolver, contactId)))
                    })
                    count++
                }
            }
        }

        return ToolResult.Success(results.toString())
    }

    private fun executeGetByNumber(params: Map<String, JsonElement>): ToolResult {
        val number = params["number"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: number")

        val resolver: ContentResolver = context.contentResolver
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )

        val cursor = resolver.query(
            lookupUri,
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME
            ),
            null,
            null,
            null
        ) ?: return ToolResult.Error("Failed to perform phone lookup")

        cursor.use { c ->
            if (!c.moveToFirst()) {
                return ToolResult.Success(buildJsonObject {
                    put("found", JsonPrimitive(false))
                    put("number", JsonPrimitive(number))
                    put("message", JsonPrimitive("No contact found for this number"))
                }.toString())
            }

            val contactId = c.getLong(0)
            val name = c.getString(1) ?: ""

            val result = buildJsonObject {
                put("found", JsonPrimitive(true))
                put("id", JsonPrimitive(contactId))
                put("name", JsonPrimitive(name))
                put("phones", getPhoneNumbers(resolver, contactId, hasPhone = true))
                put("emails", getEmails(resolver, contactId))
                put("note", JsonPrimitive(getNote(resolver, contactId)))
            }
            return ToolResult.Success(result.toString())
        }
    }

    private fun getPhoneNumbers(resolver: ContentResolver, contactId: Long, hasPhone: Boolean): kotlinx.serialization.json.JsonArray {
        if (!hasPhone) return buildJsonArray {}

        val phoneCursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )

        return buildJsonArray {
            phoneCursor?.use { pc ->
                while (pc.moveToNext()) {
                    val phone = pc.getString(0)
                    if (phone != null) add(JsonPrimitive(phone))
                }
            }
        }
    }

    private fun getEmails(resolver: ContentResolver, contactId: Long): kotlinx.serialization.json.JsonArray {
        val emailCursor = resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )

        return buildJsonArray {
            emailCursor?.use { ec ->
                while (ec.moveToNext()) {
                    val email = ec.getString(0)
                    if (email != null) add(JsonPrimitive(email))
                }
            }
        }
    }

    private fun getNote(resolver: ContentResolver, contactId: Long): String {
        val noteCursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null
        )

        noteCursor?.use { nc ->
            if (nc.moveToFirst()) {
                return nc.getString(0) ?: ""
            }
        }
        return ""
    }
}
