import SwiftUI
import OzenKit

struct FoldSplitReader: ViewModifier {
    @Binding var split: FoldSplit?

    func body(content: Content) -> some View {
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            content.onGeometryChange(for: FoldSplit?.self) { proxy in
                let division = proxy.reservedRegions(kind: .division, layoutDirectionBehavior: .fixed).first.map {
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
    func captionsPadding(rightToLeft: Bool) -> EdgeInsets {
        let inset = captionsInsets
        let (left, right) = (inset.left, inset.right)
        return EdgeInsets(top: inset.top, leading: rightToLeft ? right : left, bottom: inset.bottom, trailing: rightToLeft ? left : right)
    }

    func controlsPadding(rightToLeft: Bool) -> EdgeInsets {
        let inset = controlsInsets
        let (left, right) = (inset.left, inset.right)
        return EdgeInsets(top: 0, leading: rightToLeft ? right : left, bottom: 0, trailing: rightToLeft ? left : right)
    }
}
