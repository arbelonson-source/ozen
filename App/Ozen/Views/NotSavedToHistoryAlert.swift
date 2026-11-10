import OzenKit
import SwiftUI

extension View {
    func notSavedToHistoryAlert(_ title: String, isPresented: Binding<Bool>) -> some View {
        alert(title, isPresented: isPresented) {
            Button(tr("סגירה", "Close"), role: .cancel) {}
        } message: {
            Text(tr("השמירה בהיסטוריה לא עובדת כרגע. אפשר לנסות שוב עוד רגע.", "Saving to History isn’t working right now. Try again in a moment."))
        }
    }

    func starNotChangedAlert(isPresented: Binding<Bool>) -> some View {
        notSavedToHistoryAlert(tr("הסימון לא השתנה", "The star wasn’t changed"), isPresented: isPresented)
    }

    func nameNotSavedAlert(isPresented: Binding<Bool>) -> some View {
        notSavedToHistoryAlert(tr("השם לא נשמר", "The name wasn’t saved"), isPresented: isPresented)
    }
}
