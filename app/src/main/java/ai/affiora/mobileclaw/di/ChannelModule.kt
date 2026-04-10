package ai.affiora.mobileclaw.di

import android.content.Context
import ai.affiora.mobileclaw.channels.Channel
import ai.affiora.mobileclaw.channels.ChannelManager
import ai.affiora.mobileclaw.channels.FeishuChannel
import ai.affiora.mobileclaw.channels.MatrixChannel
import ai.affiora.mobileclaw.channels.NotificationChannel
import ai.affiora.mobileclaw.channels.SlackChannel
import ai.affiora.mobileclaw.channels.SmsChannel
import ai.affiora.mobileclaw.channels.TeamsChannel
import ai.affiora.mobileclaw.channels.TelegramChannel
import ai.affiora.mobileclaw.channels.WhatsAppChannel
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChannelModule {

    @Provides @Singleton @IntoSet
    fun provideTelegramChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
        userPreferences: UserPreferences,
    ): Channel {
        return TelegramChannel(connectorManager, httpClient, context, userPreferences).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideSmsChannel(
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return SmsChannel(context).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideNotificationChannel(
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return NotificationChannel(context).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideMatrixChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return MatrixChannel(connectorManager, httpClient, context).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideSlackChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return SlackChannel(connectorManager, httpClient, context).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideFeishuChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return FeishuChannel(connectorManager, httpClient, context).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideWhatsAppChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return WhatsAppChannel(connectorManager, httpClient, context).also {
            it.channelManager = channelManager
        }
    }

    @Provides @Singleton @IntoSet
    fun provideTeamsChannel(
        connectorManager: ConnectorManager,
        httpClient: HttpClient,
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): Channel {
        return TeamsChannel(connectorManager, httpClient, context).also {
            it.channelManager = channelManager
        }
    }
}
