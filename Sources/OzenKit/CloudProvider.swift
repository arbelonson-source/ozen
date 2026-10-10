import Foundation

public enum CloudProvider: String, Codable, Sendable, CaseIterable {
    case soniox
    case deepgram
    case openAI
    case groq
    case elevenLabs
    case gemini
    case speechmatics
    case assemblyAI
    case openRouter

    public var name: String {
        switch self {
        case .soniox: "Soniox"
        case .deepgram: "Deepgram"
        case .openAI: "OpenAI"
        case .groq: "Groq"
        case .elevenLabs: "ElevenLabs"
        case .gemini: "Google Gemini"
        case .speechmatics: "Speechmatics"
        case .assemblyAI: "AssemblyAI"
        case .openRouter: "OpenRouter"
        }
    }

    public var models: [String] {
        self == .openRouter ? CloudSpeech.models : [defaultModel]
    }

    public var defaultModel: String {
        switch self {
        case .soniox: SonioxSpeech.model
        case .deepgram: DeepgramSpeech.model
        case .openAI: OpenAICompatibleSpeech.openAI.model
        case .groq: OpenAICompatibleSpeech.groq.model
        case .elevenLabs: ElevenLabsSpeech.model
        case .gemini: GeminiSpeech.model
        case .speechmatics: SpeechmaticsSpeech.model
        case .assemblyAI: AssemblyAISpeech.model
        case .openRouter: CloudSpeech.accurateModel
        }
    }

    public var keychainService: String {
        self == .openRouter ? "com.arbelonson.ozen.openrouter" : "com.arbelonson.ozen.cloud.\(rawValue)"
    }

    public func covers(languageCode: String) -> Bool {
        switch self {
        case .soniox: SonioxSpeech.languages.contains(languageCode)
        case .deepgram: DeepgramSpeech.languages.contains(languageCode)
        case .gemini: GeminiSpeech.languageTags[languageCode] != nil
        case .speechmatics: SpeechmaticsSpeech.languages[languageCode] != nil
        case .assemblyAI: AssemblyAISpeech.languages.contains(languageCode)
        case .openAI, .groq, .elevenLabs, .openRouter: true
        }
    }

    public var streams: Bool {
        self == .soniox || self == .speechmatics || self == .assemblyAI
    }

    public var livePasses: Bool {
        self != .groq
    }

    public func engine(
        model: String? = nil,
        http: any CloudHTTP = URLSessionCloudHTTP(),
        connector: any CloudSocketConnecting,
        apiKey: @escaping @Sendable () -> String?
    ) -> any TranscriptionEngine {
        switch self {
        case .soniox: SonioxEngine(http: http, connector: connector, apiKey: apiKey)
        case .speechmatics: SpeechmaticsEngine(http: http, connector: connector, apiKey: apiKey)
        case .assemblyAI: AssemblyAIEngine(http: http, connector: connector, apiKey: apiKey)
        case .deepgram, .openAI, .groq, .elevenLabs, .gemini, .openRouter: CloudSpeechEngine(provider: self, model: model, http: http, apiKey: apiKey)
        }
    }

    public func transcriptionRequest(model: String, apiKey: String, wav: Data, languageCode: String, vocabulary: [String]) -> CloudHTTPRequest? {
        switch self {
        case .soniox, .speechmatics, .assemblyAI: nil
        case .deepgram: DeepgramSpeech.request(model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        case .openAI: OpenAICompatibleSpeech.request(OpenAICompatibleSpeech.openAI, model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        case .groq: OpenAICompatibleSpeech.request(OpenAICompatibleSpeech.groq, model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        case .elevenLabs: ElevenLabsSpeech.request(model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        case .gemini: GeminiSpeech.request(model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        case .openRouter: CloudSpeech.completionRequest(model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        }
    }

    public func transcript(from response: CloudHTTPResponse) throws(CloudSpeechError) -> String {
        switch self {
        case .soniox, .speechmatics, .assemblyAI: throw .badReply
        case .deepgram: try DeepgramSpeech.transcript(from: response)
        case .openAI, .groq: try OpenAICompatibleSpeech.transcript(from: response)
        case .elevenLabs: try ElevenLabsSpeech.transcript(from: response)
        case .gemini: try GeminiSpeech.transcript(from: response)
        case .openRouter: try CloudSpeech.transcript(from: response)
        }
    }

    public func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        switch self {
        case .soniox: SonioxSpeech.failure(from: response)
        case .speechmatics: SpeechmaticsSpeech.failure(from: response)
        case .assemblyAI: AssemblyAISpeech.failure(from: response)
        case .deepgram: DeepgramSpeech.failure(from: response)
        case .openAI, .groq: OpenAICompatibleSpeech.failure(from: response)
        case .elevenLabs: ElevenLabsSpeech.failure(from: response)
        case .gemini: GeminiSpeech.failure(from: response)
        case .openRouter: CloudSpeech.failure(from: response)
        }
    }

    public func keyCheckRequest(apiKey: String) -> CloudHTTPRequest {
        switch self {
        case .soniox: SonioxSpeech.keyCheckRequest(apiKey: apiKey)
        case .speechmatics: SpeechmaticsSpeech.keyCheckRequest(apiKey: apiKey)
        case .assemblyAI: AssemblyAISpeech.keyCheckRequest(apiKey: apiKey)
        case .deepgram: DeepgramSpeech.keyCheckRequest(apiKey: apiKey)
        case .openAI: OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.openAI, apiKey: apiKey)
        case .groq: OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.groq, apiKey: apiKey)
        case .elevenLabs: ElevenLabsSpeech.keyCheckRequest(apiKey: apiKey)
        case .gemini: GeminiSpeech.keyCheckRequest(apiKey: apiKey)
        case .openRouter: CloudSpeech.keyCheckRequest(apiKey: apiKey)
        }
    }

    public func acceptsKeyCheck(_ response: CloudHTTPResponse) -> Bool {
        self == .elevenLabs ? ElevenLabsSpeech.keyCheckPasses(response) : (200..<300).contains(response.status)
    }

    public func hasCreditLeft(keyCheck response: CloudHTTPResponse) -> Bool {
        self == .openRouter ? CloudSpeech.hasCreditLeft(keyCheck: response) : true
    }
}
