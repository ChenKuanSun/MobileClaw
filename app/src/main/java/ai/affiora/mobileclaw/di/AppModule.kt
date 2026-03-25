package ai.affiora.mobileclaw.di

import android.content.Context
import ai.affiora.mobileclaw.data.db.AppDatabase
import ai.affiora.mobileclaw.data.db.ChatMessageDao
import ai.affiora.mobileclaw.data.db.ConversationDao
import ai.affiora.mobileclaw.data.db.PairedDeviceDao
import ai.affiora.mobileclaw.data.db.ToolExecutionDao
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "androidclaw.db"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideChatMessageDao(db: AppDatabase): ChatMessageDao {
        return db.chatMessageDao()
    }

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao {
        return db.conversationDao()
    }

    @Provides
    fun provideToolExecutionDao(db: AppDatabase): ToolExecutionDao {
        return db.toolExecutionDao()
    }

    @Provides
    fun providePairedDeviceDao(db: AppDatabase): PairedDeviceDao {
        return db.pairedDeviceDao()
    }

    @Provides
    @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferences {
        return UserPreferences(context)
    }
}
