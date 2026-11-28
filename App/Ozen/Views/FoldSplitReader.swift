import SwiftUI
import OzenKit

struct FoldSplitReader: ViewModifier {
    @Binding var split: FoldSplit?

    func body(content: Content) -> some View {
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            content.onGeometryChange(for: FoldSplit?.self) { proxy in
                let division = proxy.reservedRegions(kind: .division).first.map {
                    FoldRect(x: Double($0.frame.minX), y: Double($0.frame.minY), width: Double($0.frame.width), height: Double($0.frame.height))
                }
                return FoldSplit.make(width: Double(proxy.size.width), height: Double(proxy.size.height), division: division)
            } action: { split = $0 }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

extension FoldSplit {
    var captionsPadding: EdgeInsets {
        let inset = captionsInsets
        return EdgeInsets(top: inset.top, leading: inset.leading, bottom: inset.bottom, trailing: inset.trailing)
    }

    var controlsPadding: EdgeInsets {
        let inset = controlsInsets
        return EdgeInsets(top: 0, leading: inset.leading, bottom: 0, trailing: inset.trailing)
    }
}
