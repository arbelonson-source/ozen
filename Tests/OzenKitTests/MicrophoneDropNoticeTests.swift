import Testing
@testable import OzenKit

@Suite("MicrophoneDropNotice")
struct MicrophoneDropNoticeTests {
    private let builtIn = AudioInputDescriptor(uid: "builtin", portName: "iPhone Microphone", portType: .builtInMic)
    private let lapel = AudioInputDescriptor(uid: "bluetooth:aa", portName: "Lapel Pro", portType: .bluetooth)
    private let earbuds = AudioInputDescriptor(uid: "bluetooth:bb", portName: "Earbuds", portType: .bluetooth)
    private let wired = AudioInputDescriptor(uid: "wired:", portName: "Headset", portType: .wired)
    private let usb = AudioInputDescriptor(uid: "usb:1", portName: "USB Lavalier", portType: .usb)

    @Test("a Bluetooth microphone she picked that is lost while listening is warned about, one nobody picked is not")
    func pickedBluetoothIsWarnedAbout() {
        var notice = MicrophoneDropNotice()
        notice.inputChanged(from: earbuds, to: builtIn, isListening: true, preferredUID: lapel.uid)
        #expect(notice.lost == nil)
        notice.inputChanged(from: lapel, to: builtIn, isListening: false, preferredUID: lapel.uid)
        #expect(notice.lost == nil)
        notice.inputChanged(from: lapel, to: builtIn, isListening: true, preferredUID: lapel.uid)
        #expect(notice.lost == lapel)
    }

    @Test("wired and USB microphones are warned about whether or not they were picked")
    func wiredAndUsbAsBefore() {
        var notice = MicrophoneDropNotice()
        notice.inputChanged(from: wired, to: builtIn, isListening: true, preferredUID: "builtin")
        #expect(notice.lost == wired)
        notice.dismiss()
        notice.inputChanged(from: usb, to: builtIn, isListening: true, preferredUID: nil)
        #expect(notice.lost == usb)
    }
}
