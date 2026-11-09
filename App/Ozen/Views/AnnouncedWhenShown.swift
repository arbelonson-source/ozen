import SwiftUI
import UIKit
import OzenKit

enum Announcement {
    static func post(_ text: String) {
        let announcement = NSAttributedString(string: text, attributes: [.accessibilitySpeechQueueAnnouncement: true])
        UIAccessibility.post(notification: .announcement, argument: announcement)
    }

    static func added(_ item: String) {
        post(tr("נוסף: %1", "Added: %1", args: ["\(item)"]))
    }
}

extension View {
    func announcing(_ text: String, whenTurningTrue condition: Bool, alsoWhenFirstShown: Bool = false) -> some View {
        onChange(of: condition, initial: alsoWhenFirstShown) { _, isTrue in
            guard isTrue else { return }
            Announcement.post(text)
        }
    }
}
