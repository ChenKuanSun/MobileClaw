package ai.affiora.mobileclaw.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TtsSettings(
    val voiceName: String = "",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val autoRead: Boolean = false,
)

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<Voice>>(emptyList())
    val availableVoices: StateFlow<List<Voice>> = _availableVoices.asStateFlow()

    private val _settings = MutableStateFlow(TtsSettings())
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    fun initialize() {
        if (tts != null) return

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Speaking state is set before calling speak
                    }

                    override fun onDone(utteranceId: String?) {
                        _speakingMessageId.value = null
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speakingMessageId.value = null
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _speakingMessageId.value = null
                    }
                })

                // Load available voices
                val voices = tts?.voices?.toList() ?: emptyList()
                _availableVoices.value = voices.filter { !it.isNetworkConnectionRequired }

                // Apply stored settings
                applySettings(_settings.value)
            }
        }
    }

    fun speak(messageId: String, text: String) {
        if (!isInitialized) return

        // If already speaking this message, stop it
        if (_speakingMessageId.value == messageId) {
            stop()
            return
        }

        // Stop any current speech
        stop()

        // Strip markdown formatting for cleaner speech
        val cleanText = stripMarkdown(text)

        _speakingMessageId.value = messageId
        tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UUID.randomUUID().toString(),
        )
    }

    fun stop() {
        tts?.stop()
        _speakingMessageId.value = null
    }

    fun updateSettings(newSettings: TtsSettings) {
        _settings.value = newSettings
        applySettings(newSettings)
    }

    fun updateSpeed(speed: Float) {
        updateSettings(_settings.value.copy(speed = speed))
    }

    fun updatePitch(pitch: Float) {
        updateSettings(_settings.value.copy(pitch = pitch))
    }

    fun updateAutoRead(autoRead: Boolean) {
        updateSettings(_settings.value.copy(autoRead = autoRead))
    }

    fun selectVoice(voiceName: String) {
        updateSettings(_settings.value.copy(voiceName = voiceName))
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _speakingMessageId.value = null
    }

    private fun applySettings(settings: TtsSettings) {
        val engine = tts ?: return
        if (!isInitialized) return

        engine.setSpeechRate(settings.speed)
        engine.setPitch(settings.pitch)

        if (settings.voiceName.isNotBlank()) {
            val voice = _availableVoices.value.find { it.name == settings.voiceName }
            if (voice != null) {
                engine.voice = voice
            }
        }
    }

    private fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")  // bold
            .replace(Regex("\\*(.*?)\\*"), "$1")         // italic
            .replace(Regex("`(.*?)`"), "$1")              // inline code
            .replace(Regex("```[\\s\\S]*?```"), "")       // code blocks
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")  // headers
            .replace(Regex("^[\\-*]\\s+", RegexOption.MULTILINE), "")  // bullet points
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")  // links
            .trim()
    }
}
