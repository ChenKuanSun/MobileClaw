package ai.affiora.mobileclaw.tools

import ai.affiora.mobileclaw.data.db.ChatMessageDao
import ai.affiora.mobileclaw.data.db.ConversationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionHistoryTool(
    private val chatMessageDao: ChatMessageDao,
    private val conversationDao: ConversationDao,
) : AndroidTool {

    override val name: String = "history"

    override val description: String =
        "Search and browse past conversations. Use to recall previous discussions, find information from earlier sessions, or list recent conversations."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("list_conversations"))
                    add(JsonPrimitive("read_conversation"))
                })
                put("description", JsonPrimitive("Action: 'search' messages, 'list_conversations', or 'read_conversation'."))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search query (required for 'search')."))
            })
            put("conversation_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Conversation ID (required for 'read_conversation')."))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max results to return (default: 10)."))
            })
        })
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> executeSearch(params)
                "list_conversations" -> executeListConversations(params)
                "read_conversation" -> executeReadConversation(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'search', 'list_conversations', or 'read_conversation'.")
            }
        }
    }

    private suspend fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

        return try {
            val messages = chatMessageDao.searchMessages(query, limit.coerceIn(1, 50))
            if (messages.isEmpty()) {
                return ToolResult.Success("No messages found matching '$query'.")
            }

            val result = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("count", JsonPrimitive(messages.size))
                put("results", buildJsonArray {
                    for (msg in messages) {
                        val conversation = conversationDao.getConversationById(msg.conversationId)
                        add(buildJsonObject {
                            put("conversation_id", JsonPrimitive(msg.conversationId))
                            put("conversation_title", JsonPrimitive(conversation?.title ?: "Unknown"))
                            put("role", JsonPrimitive(msg.role.name))
                            put("content", JsonPrimitive(msg.content.take(500)))
                            put("timestamp", JsonPrimitive(dateFormat.format(Date(msg.timestamp))))
                        })
                    }
                })
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Search failed: ${e.message}")
        }
    }

    private suspend fun executeListConversations(params: Map<String, JsonElement>): ToolResult {
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

        return try {
            val conversations = conversationDao.getRecentConversations(limit.coerceIn(1, 50))
            if (conversations.isEmpty()) {
                return ToolResult.Success("No conversations found.")
            }

            val result = buildJsonObject {
                put("count", JsonPrimitive(conversations.size))
                put("conversations", buildJsonArray {
                    for (conv in conversations) {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(conv.id))
                            put("title", JsonPrimitive(conv.title))
                            put("created_at", JsonPrimitive(dateFormat.format(Date(conv.createdAt))))
                            put("updated_at", JsonPrimitive(dateFormat.format(Date(conv.updatedAt))))
                        })
                    }
                })
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to list conversations: ${e.message}")
        }
    }

    private suspend fun executeReadConversation(params: Map<String, JsonElement>): ToolResult {
        val conversationId = params["conversation_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: conversation_id")
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20

        return try {
            val messages = chatMessageDao.getRecentMessagesByConversation(
                conversationId,
                limit.coerceIn(1, 100),
            )
            if (messages.isEmpty()) {
                return ToolResult.Success("No messages found for conversation '$conversationId'.")
            }

            val conversation = conversationDao.getConversationById(conversationId)

            val result = buildJsonObject {
                put("conversation_id", JsonPrimitive(conversationId))
                put("title", JsonPrimitive(conversation?.title ?: "Unknown"))
                put("message_count", JsonPrimitive(messages.size))
                put("messages", buildJsonArray {
                    for (msg in messages) {
                        add(buildJsonObject {
                            put("role", JsonPrimitive(msg.role.name))
                            put("content", JsonPrimitive(msg.content.take(1000)))
                            put("timestamp", JsonPrimitive(dateFormat.format(Date(msg.timestamp))))
                            if (msg.toolName != null) {
                                put("tool", JsonPrimitive(msg.toolName))
                            }
                        })
                    }
                })
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to read conversation: ${e.message}")
        }
    }
}
