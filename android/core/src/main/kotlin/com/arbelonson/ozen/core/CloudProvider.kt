package com.arbelonson.ozen.core

import kotlin.time.TimeSource

enum class CloudProvider(val rawValue: String) {
    Soniox("soniox"),
    Deepgram("deepgram"),
    OpenAI("openAI"),
    Groq("groq"),
    ElevenLabs("elevenLabs"),
    Gemini("gemini"),
    Speechmatics("speechmatics"),
    AssemblyAI("assemblyAI"),
    OpenRouter("openRouter"),
    ;

    val displayName: String
        get() = when (this) {
            Soniox -> "Soniox"
            Deepgram -> "Deepgram"
            OpenAI -> "OpenAI"
            Groq -> "Groq"
            ElevenLabs -> "ElevenLabs"
            Gemini -> "Google Gemini"
            Speechmatics -> "Speechmatics"
            AssemblyAI -> "AssemblyAI"
            OpenRouter -> "OpenRouter"
        }

    val models: List<String>
        get() = if (this == OpenRouter) CloudSpeech.models else listOf(defaultModel)

    val defaultModel: String
        get() = when (this) {
            Soniox -> SonioxSpeech.model
            Deepgram -> DeepgramSpeech.MODEL
            OpenAI -> OpenAICompatibleSpeech.openAI.model
            Groq -> OpenAICompatibleSpeech.groq.model
            ElevenLabs -> ElevenLabsSpeech.MODEL
            Gemini -> GeminiSpeech.MODEL
            Speechmatics -> SpeechmaticsSpeech.model
            AssemblyAI -> AssemblyAISpeech.model
            OpenRouter -> CloudSpeech.ACCURATE_MODEL
        }

    val keychainService: String
        get() = if (this == OpenRouter) "com.arbelonson.ozen.openrouter" else "com.arbelonson.ozen.cloud.$rawValue"

    fun covers(languageCode: String): Boolean = when (this) {
        Soniox -> languageCode in SonioxSpeech.languages
        Deepgram -> languageCode in DeepgramSpeech.languages
        Gemini -> GeminiSpeech.languageTags[languageCode] != null
        Speechmatics -> SpeechmaticsSpeech.languages[languageCode] != null
        AssemblyAI -> languageCode in AssemblyAISpeech.languages
        OpenAI, Groq, ElevenLabs, OpenRouter -> true
    }

    val streams: Boolean
        get() = this == Soniox || this == Speechmatics || this == AssemblyAI

    val livePasses: Boolean
        get() = this != Groq

    fun engine(
        model: String? = null,
        http: CloudHTTP = UrlConnectionCloudHTTP(),
        connector: CloudSocketConnecting,
        timeSource: TimeSource = TimeSource.Monotonic,
        apiKey: () -> String?,
    ): TranscriptionEngine = when (this) {
        Soniox -> SonioxEngine(http = http, connector = connector, timeSource = timeSource, apiKey = apiKey)
        Speechmatics -> SpeechmaticsEngine(http = http, connector = connector, timeSource = timeSource, apiKey = apiKey)
        AssemblyAI -> AssemblyAIEngine(http = http, connector = connector, timeSource = timeSource, apiKey = apiKey)
        Deepgram, OpenAI, Groq, ElevenLabs, Gemini, OpenRouter ->
            CloudSpeechEngine(provider = this, model = model, http = http, timeSource = timeSource, apiKey = apiKey)
    }

    fun transcriptionRequest(model: String, apiKey: String, wav: ByteArray, languageCode: String, vocabulary: List<String>): CloudHTTPRequest? =
        when (this) {
            Soniox, Speechmatics, AssemblyAI -> null
            Deepgram -> DeepgramSpeech.request(model, apiKey, wav, languageCode, vocabulary)
            OpenAI -> OpenAICompatibleSpeech.request(OpenAICompatibleSpeech.openAI, model, apiKey, wav, languageCode, vocabulary)
            Groq -> OpenAICompatibleSpeech.request(OpenAICompatibleSpeech.groq, model, apiKey, wav, languageCode, vocabulary)
            ElevenLabs -> ElevenLabsSpeech.request(model, apiKey, wav, languageCode, vocabulary)
            Gemini -> GeminiSpeech.request(model, apiKey, wav, languageCode, vocabulary)
            OpenRouter -> CloudSpeech.completionRequest(model, apiKey, wav, languageCode, vocabulary)
        }

    fun transcript(response: CloudHTTPResponse): String = when (this) {
        Soniox, Speechmatics, AssemblyAI -> throw CloudSpeechError.BadReply
        Deepgram -> DeepgramSpeech.transcript(response)
        OpenAI, Groq -> OpenAICompatibleSpeech.transcript(response)
        ElevenLabs -> ElevenLabsSpeech.transcript(response)
        Gemini -> GeminiSpeech.transcript(response)
        OpenRouter -> CloudSpeech.transcript(response)
    }

    fun failure(response: CloudHTTPResponse): CloudSpeechError = when (this) {
        Soniox -> SonioxSpeech.failure(response)
        Speechmatics -> SpeechmaticsSpeech.failure(response)
        AssemblyAI -> AssemblyAISpeech.failure(response)
        Deepgram -> DeepgramSpeech.failure(response)
        OpenAI, Groq -> OpenAICompatibleSpeech.failure(response)
        ElevenLabs -> ElevenLabsSpeech.failure(response)
        Gemini -> GeminiSpeech.failure(response)
        OpenRouter -> CloudSpeech.failure(response)
    }

    fun keyCheckRequest(apiKey: String): CloudHTTPRequest = when (this) {
        Soniox -> SonioxSpeech.keyCheckRequest(apiKey)
        Speechmatics -> SpeechmaticsSpeech.keyCheckRequest(apiKey)
        AssemblyAI -> AssemblyAISpeech.keyCheckRequest(apiKey)
        Deepgram -> DeepgramSpeech.keyCheckRequest(apiKey)
        OpenAI -> OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.openAI, apiKey)
        Groq -> OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.groq, apiKey)
        ElevenLabs -> ElevenLabsSpeech.keyCheckRequest(apiKey)
        Gemini -> GeminiSpeech.keyCheckRequest(apiKey)
        OpenRouter -> CloudSpeech.keyCheckRequest(apiKey)
    }

    fun acceptsKeyCheck(response: CloudHTTPResponse): Boolean =
        if (this == ElevenLabs) ElevenLabsSpeech.keyCheckPasses(response) else response.status in 200 until 300

    fun hasCreditLeft(keyCheck: CloudHTTPResponse): Boolean =
        if (this == OpenRouter) CloudSpeech.hasCreditLeft(keyCheck) else true
}
