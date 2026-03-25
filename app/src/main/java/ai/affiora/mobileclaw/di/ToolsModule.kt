package ai.affiora.mobileclaw.di

import android.content.Context
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.tools.AlarmTimerTool
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.AppLauncherTool
import ai.affiora.mobileclaw.tools.BrightnessTool
import ai.affiora.mobileclaw.tools.CalendarTool
import ai.affiora.mobileclaw.tools.CallLogTool
import ai.affiora.mobileclaw.tools.ClipboardTool
import ai.affiora.mobileclaw.tools.ContactsTool
import ai.affiora.mobileclaw.tools.FileSystemTool
import ai.affiora.mobileclaw.tools.FlashlightTool
import ai.affiora.mobileclaw.tools.HttpTool
import ai.affiora.mobileclaw.tools.NavigationTool
import ai.affiora.mobileclaw.tools.OpenAiTool
import ai.affiora.mobileclaw.tools.PhotoTool
import ai.affiora.mobileclaw.tools.ScheduleTool
import ai.affiora.mobileclaw.tools.MediaControlTool
import ai.affiora.mobileclaw.tools.NotificationCache
import ai.affiora.mobileclaw.tools.NotificationTool
import ai.affiora.mobileclaw.tools.PhoneCallTool
import ai.affiora.mobileclaw.tools.ScreenCaptureTool
import ai.affiora.mobileclaw.tools.SkillAuthorTool
import ai.affiora.mobileclaw.tools.SmsTool
import ai.affiora.mobileclaw.tools.SystemInfoTool
import ai.affiora.mobileclaw.tools.UIAutomationTool
import ai.affiora.mobileclaw.tools.VolumeTool
import ai.affiora.mobileclaw.tools.WebBrowserTool
import ai.affiora.mobileclaw.tools.ClawNotificationListener
import ai.affiora.mobileclaw.agent.ScheduleEngine
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Provides
    @Singleton
    fun provideSmsTool(@ApplicationContext context: Context): SmsTool {
        return SmsTool(context)
    }

    @Provides
    @Singleton
    fun provideCallLogTool(@ApplicationContext context: Context): CallLogTool {
        return CallLogTool(context)
    }

    @Provides
    @Singleton
    fun provideContactsTool(@ApplicationContext context: Context): ContactsTool {
        return ContactsTool(context)
    }

    @Provides
    @Singleton
    fun provideCalendarTool(@ApplicationContext context: Context): CalendarTool {
        return CalendarTool(context)
    }

    @Provides
    @Singleton
    fun provideNotificationCache(): NotificationCache {
        return ClawNotificationListener.Companion
    }

    @Provides
    @Singleton
    fun provideNotificationTool(notificationCache: NotificationCache): NotificationTool {
        return NotificationTool(notificationCache)
    }

    @Provides
    @Singleton
    fun provideWebBrowserTool(
        @ApplicationContext context: Context,
        httpClient: HttpClient,
    ): WebBrowserTool {
        return WebBrowserTool(context, httpClient)
    }

    @Provides
    @Singleton
    fun provideSkillAuthorTool(@ApplicationContext context: Context): SkillAuthorTool {
        return SkillAuthorTool(context)
    }

    @Provides
    @Singleton
    fun provideAppLauncherTool(@ApplicationContext context: Context): AppLauncherTool {
        return AppLauncherTool(context)
    }

    @Provides
    @Singleton
    fun provideClipboardTool(@ApplicationContext context: Context): ClipboardTool {
        return ClipboardTool(context)
    }

    @Provides
    @Singleton
    fun provideAlarmTimerTool(@ApplicationContext context: Context): AlarmTimerTool {
        return AlarmTimerTool(context)
    }

    @Provides
    @Singleton
    fun provideFlashlightTool(@ApplicationContext context: Context): FlashlightTool {
        return FlashlightTool(context)
    }

    @Provides
    @Singleton
    fun provideVolumeTool(@ApplicationContext context: Context): VolumeTool {
        return VolumeTool(context)
    }

    @Provides
    @Singleton
    fun provideBrightnessTool(@ApplicationContext context: Context): BrightnessTool {
        return BrightnessTool(context)
    }

    @Provides
    @Singleton
    fun provideMediaControlTool(@ApplicationContext context: Context): MediaControlTool {
        return MediaControlTool(context)
    }

    @Provides
    @Singleton
    fun provideSystemInfoTool(@ApplicationContext context: Context): SystemInfoTool {
        return SystemInfoTool(context)
    }

    @Provides
    @Singleton
    fun provideUIAutomationTool(@ApplicationContext context: Context): UIAutomationTool {
        return UIAutomationTool(context)
    }

    @Provides
    @Singleton
    fun provideScreenCaptureTool(@ApplicationContext context: Context): ScreenCaptureTool {
        return ScreenCaptureTool(context)
    }

    @Provides
    @Singleton
    fun provideFileSystemTool(@ApplicationContext context: Context): FileSystemTool {
        return FileSystemTool(context)
    }

    @Provides
    @Singleton
    fun providePhoneCallTool(@ApplicationContext context: Context): PhoneCallTool {
        return PhoneCallTool(context)
    }

    @Provides
    @Singleton
    fun provideHttpTool(
        @ApplicationContext context: Context,
        httpClient: HttpClient,
        connectorManager: ConnectorManager,
    ): HttpTool {
        return HttpTool(context, httpClient, connectorManager)
    }

    @Provides
    @Singleton
    fun provideNavigationTool(@ApplicationContext context: Context): NavigationTool {
        return NavigationTool(context)
    }

    @Provides
    @Singleton
    fun provideScheduleTool(
        @ApplicationContext context: Context,
        scheduleEngine: ScheduleEngine,
    ): ScheduleTool {
        return ScheduleTool(context, scheduleEngine)
    }

    @Provides
    @Singleton
    fun provideOpenAiTool(
        @ApplicationContext context: Context,
        httpClient: HttpClient,
        userPreferences: UserPreferences,
    ): OpenAiTool {
        return OpenAiTool(context, httpClient, userPreferences)
    }

    @Provides
    @Singleton
    fun providePhotoTool(@ApplicationContext context: Context): PhotoTool {
        return PhotoTool(context)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        smsTool: SmsTool,
        callLogTool: CallLogTool,
        contactsTool: ContactsTool,
        calendarTool: CalendarTool,
        notificationTool: NotificationTool,
        webBrowserTool: WebBrowserTool,
        skillAuthorTool: SkillAuthorTool,
        appLauncherTool: AppLauncherTool,
        clipboardTool: ClipboardTool,
        alarmTimerTool: AlarmTimerTool,
        flashlightTool: FlashlightTool,
        volumeTool: VolumeTool,
        brightnessTool: BrightnessTool,
        mediaControlTool: MediaControlTool,
        systemInfoTool: SystemInfoTool,
        uiAutomationTool: UIAutomationTool,
        screenCaptureTool: ScreenCaptureTool,
        fileSystemTool: FileSystemTool,
        phoneCallTool: PhoneCallTool,
        httpTool: HttpTool,
        navigationTool: NavigationTool,
        scheduleTool: ScheduleTool,
        openAiTool: OpenAiTool,
        photoTool: PhotoTool,
    ): Map<String, AndroidTool> {
        val tools: List<AndroidTool> = listOf(
            smsTool,
            callLogTool,
            contactsTool,
            calendarTool,
            notificationTool,
            webBrowserTool,
            skillAuthorTool,
            appLauncherTool,
            clipboardTool,
            alarmTimerTool,
            flashlightTool,
            volumeTool,
            brightnessTool,
            mediaControlTool,
            systemInfoTool,
            uiAutomationTool,
            screenCaptureTool,
            fileSystemTool,
            phoneCallTool,
            httpTool,
            navigationTool,
            scheduleTool,
            openAiTool,
            photoTool,
        )
        return tools.associateBy { it.name }
    }
}
