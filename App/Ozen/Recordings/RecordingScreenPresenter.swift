import OzenKit
import SwiftUI
import UIKit

@MainActor
enum RecordingScreenPresenter {
    static func open(_ url: URL, viewModel: LiveCaptionViewModel) async {
        if let busy = FileTranscriptionProblem.forNewShare(isTranscribing: viewModel.pipeline.isTranscribingRecording) {
            alert(busy.message)
            return
        }
        do {
            let file = try await RecordingFiles.open(url)
            await waitForDialogToClear()
            if let busy = FileTranscriptionProblem.forNewShare(isTranscribing: viewModel.pipeline.isTranscribingRecording) {
                file.finish()
                alert(busy.message)
                return
            }
            let model = TranscribeRecordingModel(file: file, host: LiveRecordingHost(viewModel: viewModel))
            present(model, language: viewModel.uiLanguage)
        } catch {
            alert(FileTranscriptionProblem.couldNotRead.message)
        }
    }

    /// A confirmation dialog on top can't show a new screen and would be
    /// closed by trying: the file is kept and shown once the dialog is gone.
    private static func waitForDialogToClear() async {
        guard topController() is UIAlertController else { return }
        UIAccessibility.post(notification: .announcement, argument: tr("הקובץ ייפתח אחרי שהחלון הפתוח ייסגר.", "The file will open once the open dialog is closed."))
        for _ in 0..<240 {
            try? await Task.sleep(for: .milliseconds(500))
            if !(topController() is UIAlertController) { return }
        }
    }

    private static func present(_ model: TranscribeRecordingModel, language: UILanguage) {
        guard let top = topController(), !(top is UIAlertController) else {
            UIAccessibility.post(notification: .announcement, argument: tr("סגרו את החלון הפתוח ואז שתפו את הקובץ שוב.", "Close this screen first, then share the file again."))
            Task { await model.close() }
            return
        }
        let controller = UIHostingController(rootView: AnyView(EmptyView()))
        controller.rootView = AnyView(
            NavigationStack {
                TranscribeRecordingView(model: model, onClose: { [weak controller] in controller?.dismiss(animated: true) })
            }
            .readingDirection(of: language)
        )
        top.present(controller, animated: true)
    }

    private static func alert(_ message: String) {
        guard let top = topController(), !(top is UIAlertController) else {
            UIAccessibility.post(notification: .announcement, argument: message)
            return
        }
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: tr("סגירה", "Close"), style: .cancel))
        top.present(alert, animated: true)
    }

    private static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        var top = scene?.keyWindow?.rootViewController ?? scene?.windows.first?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
