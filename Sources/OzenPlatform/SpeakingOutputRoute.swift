import AVFoundation
import OzenKit

@MainActor
public enum SpeakingOutputRoute {
    private static var chosenSpeaker = false
    nonisolated(unsafe) private static var routeObserver: NSObjectProtocol?

    public static func outputs() -> [SpeakingOutput] {
        startWatchingDevices()
        if chosenSpeaker { return [SpeakingOutput(kind: .phoneSpeaker, name: "")] }
        return AVAudioSession.sharedInstance().currentRoute.outputs.map {
            SpeakingOutput(kind: kind(of: $0), name: $0.portName)
        }
    }

    public static func useSpeaker() {
        startWatchingDevices()
        let session = AVAudioSession.sharedInstance()
        setPlayingCategory(session)
        chosenSpeaker = (try? session.overrideOutputAudioPort(.speaker)) != nil
    }

    private static func setPlayingCategory(_ session: AVAudioSession) {
        guard session.category != .playAndRecord else { return }
        try? session.setCategory(
            .playAndRecord,
            mode: .default,
            options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker]
        )
    }

    static func prepareToSpeak() {
        let session = AVAudioSession.sharedInstance()
        setPlayingCategory(session)
        guard chosenSpeaker else { return }
        try? session.setActive(true)
        if (try? session.overrideOutputAudioPort(.speaker)) == nil { chosenSpeaker = false }
    }

    private static func kind(of port: AVAudioSessionPortDescription) -> SpeakingOutput.Kind {
        switch port.portType {
        case .builtInSpeaker: return .phoneSpeaker
        case .builtInReceiver: return .earpiece
        case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
            return AudioPortType.of(connection: .bluetooth, name: port.portName) == .hearingAid ? .hearingAid : .bluetooth
        case .headphones, .headsetMic: return .wired
        case .carAudio: return .car
        default: return .other
        }
    }

    private static func startWatchingDevices() {
        guard routeObserver == nil else { return }
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { note in
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            let reason = raw.flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            guard reason == .newDeviceAvailable || reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { chosenSpeaker = false }
        }
    }
}
