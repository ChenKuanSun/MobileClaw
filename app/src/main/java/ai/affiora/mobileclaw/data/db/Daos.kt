package ai.affiora.mobileclaw.data.db

import ai.affiora.mobileclaw.data.model.MessageRole
import ai.affiora.mobileclaw.data.model.ToolStatus
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ── Entities ────────────────────────────────────────────────────────────────

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: MessageRole,
    val content: String,
    val toolName: String?,
    val toolInput: String?,
    val timestamp: Long,
    val conversationId: String,
    @ColumnInfo(name = "claude_message_json")
    val claudeMessageJson: String? = null,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "tool_executions")
data class ToolExecutionEntity(
    @PrimaryKey val id: String,
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolStatus,
    val timestamp: Long,
    val conversationId: String,
)

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val publicKey: String,
    val lastSeen: Long,
    val isOnline: Boolean,
)

// ── DAOs ────────────────────────────────────────────────────────────────────

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesByConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getMessageById(id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Update
    suspend fun update(message: ChatMessageEntity)

    @Delete
    suspend fun delete(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT * FROM chat_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchMessages(query: String, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesByConversation(conversationId: String, limit: Int): List<ChatMessageEntity>
}

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentConversations(limit: Int): List<ConversationEntity>
}

@Dao
interface ToolExecutionDao {

    @Query("SELECT * FROM tool_executions WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getExecutionsByConversation(conversationId: String): Flow<List<ToolExecutionEntity>>

    @Query("SELECT * FROM tool_executions WHERE id = :id")
    suspend fun getExecutionById(id: String): ToolExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ToolExecutionEntity)

    @Update
    suspend fun update(record: ToolExecutionEntity)

    @Delete
    suspend fun delete(record: ToolExecutionEntity)

    @Query("DELETE FROM tool_executions WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface PairedDeviceDao {

    @Query("SELECT * FROM paired_devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE id = :id")
    suspend fun getDeviceById(id: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_devices WHERE isOnline = 1")
    fun getOnlineDevices(): Flow<List<PairedDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: PairedDeviceEntity)

    @Update
    suspend fun update(device: PairedDeviceEntity)

    @Delete
    suspend fun delete(device: PairedDeviceEntity)

    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun deleteById(id: String)
}
