import Foundation
import OzenKit

extension HistoryDays {
    static func localOffset(at time: TimeInterval) -> Int {
        TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: time))
    }

    static func heading(startedAt: TimeInterval, utcOffsetSeconds ownOffset: Int? = nil) -> String {
        let day = title(of: startedAt, ownUTCOffsetSeconds: ownOffset, now: Date().timeIntervalSince1970, utcOffsetSeconds: localOffset)
        let time = Date(timeIntervalSince1970: startedAt).formatted(inAppLanguage: .omitted, time: .shortened, utcOffsetSeconds: ownOffset)
        return TimeArticle.agreeing(tr("%1 בשעה %2", "%1 at %2", args: ["\(day)", "\(time)"]), time: time)
    }
}
