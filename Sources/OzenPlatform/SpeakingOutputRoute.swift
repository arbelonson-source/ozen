import AVFoundation
import OzenKit

/// Where the phone's sound is going, for the Say screen, and the one
/// choice it offers: say replies on the phone's own speaker even though a
/// headset or hearing aid is connected. Not the default, since some
/// people use a Bluetooth speaker on purpose.
@MainActor
public enum SpeakingOutputRoute {
    private static var chosenSpeaker = false
    nonisolated(unsafe) private static var routeObserver: NSObjectProtocol?

    /// The outputs sound would come out of now. After the person chose the
    /// phone's speaker it says so even before the next reply is spoken,
    /// since the system applies the choice only when it plays.
    public static func outputs() -> [SpeakingOutput] {
        startWatchingDevices()
        if chosenSpeaker { return [SpeakingOutput(kind: .phoneSpeaker, name: "")] }
        return AVAudioSession.sharedInstance().currentRoute.outputs.map {
            SpeakingOutput(kind: kind(of: $0), name: $0.portName)
        }
    }

    public static func useSpeaker() {
        startWatchingDevices()
        chosenSpeaker = true
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
    }

    /// Before each spoken reply. Captions' own category plays whatever the
    /// silent switch says, so it is left alone while it is set; otherwise
    /// the default category would keep the silent switch muting replies.
    static func prepareToSpeak() {
        let session = AVAudioSession.sharedInstance()
        if session.category != .playAndRecord {
            try? session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker]
            )
        }
        guard chosenSpeaker else { return }
        try? session.setActive(true)
        try? session.overrideOutputAudioPort(.speaker)
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

    /// A headset or hearing aid coming or going is a new situation: the
    /// choice made for the last one is not carried over to it.
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
