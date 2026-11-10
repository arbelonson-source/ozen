import OzenKit
import SwiftUI

extension View {
    func starNotChangedAlert(isPresented: Binding<Bool>) -> some View {
        alert(tr("הסימון לא השתנה", "The star wasn’t changed"), isPresented: isPresented) {
            Button(tr("סגירה", "Close"), role: .cancel) {}
        } message: {
            Text(tr("השמירה בהיסטוריה לא עובדת כרגע. אפשר לנסות שוב עוד רגע.", "Saving to History isn’t working right now. Try again in a moment."))
        }
    }
}
