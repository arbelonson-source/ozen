import OzenKit
import SwiftUI
import UIKit

@MainActor
enum RecordingScreenPresenter {
    static func open(_ url: URL, viewModel: LiveCaptionViewModel) async {
        do {
            let file = try await RecordingFiles.open(url)
            let model = TranscribeRecordingModel(file: file, host: LiveRecordingHost(viewModel: viewModel))
            present(model, language: viewModel.uiLanguage)
        } catch {
            alert(FileTranscriptionProblem.couldNotRead.message)
        }
    }

    private static func present(_ model: TranscribeRecordingModel, language: UILanguage) {
        guard let top = topController() else {
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
        guard let top = topController() else { return }
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
