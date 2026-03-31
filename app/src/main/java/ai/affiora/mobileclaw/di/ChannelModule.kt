package ai.affiora.mobileclaw.di

import android.content.Context
import ai.affiora.mobileclaw.channels.ChannelManager
import ai.affiora.mobileclaw.channels.NotificationChannel
import ai.affiora.mobileclaw.channels.SmsChannel
import ai.affiora.mobileclaw.channels.TelegramChannel
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChannelModule {

    @Provides
    @Singleton
    fun provideTelegramChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
        userPreferences: UserPreferences,
    ): TelegramChannel {
        return TelegramChannel(connectorManager, httpClient, context, userPreferences).also {
            it.channelManager = channelManager
        }
    }

    @Provides
    @Singleton
    fun provideSmsChannel(
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): SmsChannel {
        return SmsChannel(context).also {
            it.channelManager = channelManager
        }
    }

    @Provides
    @Singleton
    fun provideNotificationChannel(
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): NotificationChannel {
        return NotificationChannel(context).also {
            it.channelManager = channelManager
        }
    }
}
