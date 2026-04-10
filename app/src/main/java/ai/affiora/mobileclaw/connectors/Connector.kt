package ai.affiora.mobileclaw.connectors

data class ConnectorConfig(
    val id: String,
    val name: String,
    val icon: String,
    val authType: ConnectorAuthType,
    val authorizationUrl: String?,
    val tokenUrl: String?,
    val clientId: String?,
    val scopes: List<String>,
    val redirectUri: String,
    val description: String,
)

enum class ConnectorAuthType {
    OAUTH2_PKCE,
    GOOGLE_SIGNIN,
    API_KEY,
    BOT_TOKEN,
}

enum class ConnectorStatus {
    NOT_CONNECTED,
    CONNECTED,
    EXPIRED,
}

object Connectors {
    val NOTION = ConnectorConfig(
        id = "notion",
        name = "Notion",
        icon = "edit_note",
        authType = ConnectorAuthType.OAUTH2_PKCE,
        authorizationUrl = "https://api.notion.com/v1/oauth/authorize",
        tokenUrl = "https://api.notion.com/v1/oauth/token",
        clientId = null,
        scopes = emptyList(),
        redirectUri = "mobileclaw://oauth/callback",
        description = "Search, read, and create Notion pages",
    )

    val GITHUB = ConnectorConfig(
        id = "github",
        name = "GitHub",
        icon = "code",
        authType = ConnectorAuthType.OAUTH2_PKCE,
        authorizationUrl = "https://github.com/login/oauth/authorize",
        tokenUrl = "https://github.com/login/oauth/access_token",
        clientId = null,
        scopes = listOf("repo", "read:user"),
        redirectUri = "mobileclaw://oauth/callback",
        description = "Manage repos, issues, and pull requests",
    )

    val SLACK = ConnectorConfig(
        id = "slack",
        name = "Slack",
        icon = "chat",
        authType = ConnectorAuthType.OAUTH2_PKCE,
        authorizationUrl = "https://slack.com/oauth/v2/authorize",
        tokenUrl = "https://slack.com/api/oauth.v2.access",
        clientId = null,
        scopes = listOf("chat:write", "channels:read", "channels:history"),
        redirectUri = "mobileclaw://oauth/callback",
        description = "Send messages and read Slack channels",
    )

    val SPOTIFY = ConnectorConfig(
        id = "spotify",
        name = "Spotify",
        icon = "music_note",
        authType = ConnectorAuthType.OAUTH2_PKCE,
        authorizationUrl = "https://accounts.spotify.com/authorize",
        tokenUrl = "https://accounts.spotify.com/api/token",
        clientId = null,
        scopes = listOf("user-modify-playback-state", "user-read-playback-state", "playlist-modify-public"),
        redirectUri = "mobileclaw://oauth/callback",
        description = "Control playback, search, create playlists",
    )

    val GOOGLE = ConnectorConfig(
        id = "google",
        name = "Google (Gmail, Calendar, Drive)",
        icon = "calendar_today",
        authType = ConnectorAuthType.GOOGLE_SIGNIN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = listOf(
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/drive",
        ),
        redirectUri = "",
        description = "Gmail, Calendar, and Drive access",
    )

    val TELEGRAM = ConnectorConfig(
        id = "telegram",
        name = "Telegram",
        icon = "send",
        authType = ConnectorAuthType.BOT_TOKEN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "Send messages via Telegram Bot",
    )

    val OPENAI = ConnectorConfig(
        id = "openai",
        name = "OpenAI",
        icon = "smart_toy",
        authType = ConnectorAuthType.API_KEY,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "DALL-E image generation, Whisper STT, TTS",
    )

    val MATRIX = ConnectorConfig(
        id = "matrix",
        name = "Matrix",
        icon = "forum",
        authType = ConnectorAuthType.BOT_TOKEN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "Decentralized chat (homeserver + user_id + access_token as JSON)",
    )

    val SLACK_APP = ConnectorConfig(
        id = "slack_app",
        name = "Slack (Socket Mode)",
        icon = "chat",
        authType = ConnectorAuthType.BOT_TOKEN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "Real-time messaging via WebSocket ({\"app_token\":\"xapp-...\",\"bot_token\":\"xoxb-...\"})",
    )

    val FEISHU = ConnectorConfig(
        id = "feishu",
        name = "Feishu / Lark",
        icon = "groups",
        authType = ConnectorAuthType.BOT_TOKEN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "Enterprise chat ({\"app_id\":\"cli_...\",\"app_secret\":\"...\",\"domain\":\"open.feishu.cn\"})",
    )

    val WHATSAPP = ConnectorConfig(
        id = "whatsapp",
        name = "WhatsApp",
        icon = "message",
        authType = ConnectorAuthType.BOT_TOKEN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "Outbound messages ({\"access_token\":\"EAA...\",\"phone_number_id\":\"...\"})",
    )

    val TEAMS = ConnectorConfig(
        id = "teams",
        name = "Microsoft Teams",
        icon = "groups",
        authType = ConnectorAuthType.BOT_TOKEN,
        authorizationUrl = null,
        tokenUrl = null,
        clientId = null,
        scopes = emptyList(),
        redirectUri = "",
        description = "Outbound messaging ({\"tenant_id\":\"...\",\"client_id\":\"...\",\"client_secret\":\"...\"})",
    )

    val ALL = listOf(NOTION, GITHUB, SLACK, SPOTIFY, GOOGLE, TELEGRAM, OPENAI, MATRIX, SLACK_APP, FEISHU, WHATSAPP, TEAMS)
}
