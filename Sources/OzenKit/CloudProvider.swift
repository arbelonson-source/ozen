import Foundation

public enum CloudProvider: String, Codable, Sendable, CaseIterable {
    case openRouter
    case deepgram

    public var name: String {
        switch self {
        case .openRouter: "OpenRouter"
        case .deepgram: "Deepgram"
        }
    }

    public var models: [String] {
        switch self {
        case .openRouter: CloudSpeech.models
        case .deepgram: [DeepgramSpeech.model]
        }
    }

    public var defaultModel: String {
        switch self {
        case .openRouter: CloudSpeech.accurateModel
        case .deepgram: DeepgramSpeech.model
        }
    }

    public var keychainService: String {
        self == .openRouter ? "com.arbelonson.ozen.openrouter" : "com.arbelonson.ozen.cloud.\(rawValue)"
    }

    public func covers(languageCode: String) -> Bool {
        switch self {
        case .openRouter: true
        case .deepgram: DeepgramSpeech.languages.contains(languageCode)
        }
    }

    public func transcriptionRequest(model: String, apiKey: String, wav: Data, languageCode: String, vocabulary: [String]) -> CloudHTTPRequest {
        switch self {
        case .openRouter: CloudSpeech.completionRequest(model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        case .deepgram: DeepgramSpeech.request(model: model, apiKey: apiKey, wav: wav, languageCode: languageCode, vocabulary: vocabulary)
        }
    }

    public func transcript(from response: CloudHTTPResponse) throws(CloudSpeechError) -> String {
        switch self {
        case .openRouter: try CloudSpeech.transcript(from: response)
        case .deepgram: try DeepgramSpeech.transcript(from: response)
        }
    }

    public func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        switch self {
        case .openRouter: CloudSpeech.failure(from: response)
        case .deepgram: DeepgramSpeech.failure(from: response)
        }
    }

    public func keyCheckRequest(apiKey: String) -> CloudHTTPRequest {
        switch self {
        case .openRouter: CloudSpeech.keyCheckRequest(apiKey: apiKey)
        case .deepgram: DeepgramSpeech.keyCheckRequest(apiKey: apiKey)
        }
    }

    public func hasCreditLeft(keyCheck response: CloudHTTPResponse) -> Bool {
        switch self {
        case .openRouter: CloudSpeech.hasCreditLeft(keyCheck: response)
        case .deepgram: true
        }
    }
}
