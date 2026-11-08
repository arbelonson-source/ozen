import SwiftUI
import OzenKit

struct SoundsOnlySection: View {
    @Bindable var viewModel: LiveCaptionViewModel

    var body: some View {
        if viewModel.soundAlertPreferences.isEnabled {
            Section {
                Button {
                    Task { await viewModel.listenForSoundsOnly() }
                } label: {
                    Label(tr("התראות על צלילים בלבד, בלי כתוביות", "Sound alerts only, without captions"), systemImage: "ear.badge.waveform")
                }
                .disabled(viewModel.isListeningForSoundsOnly)
                .accessibilityIdentifier("soundsOnlyButton")
            } footer: {
                Text(tr("ללילה: הכתוביות נעצרות ומודל הדיבור משוחרר, כך ש־iOS פחות צפוי לסגור את אוזן. ההתראות על צלילים ממשיכות להאזין.", "For the night: captions stop and the speech model is let go, so iOS is less likely to close Ozen. Sound alerts keep listening."))
            }
        }
    }
}
