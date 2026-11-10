import Foundation

public enum ProblemReportLine {
    public static func text(markedAt: String) -> String {
        TimeArticle.agreeing(tr("אוזן בטלפון הזה: סומנה בעיה בכתוביות בשעה %1.", "Ozen on this phone: a captions problem was marked at %1.", args: ["\(markedAt)"]), time: markedAt)
    }

    public static func onTop(of report: String, markedAt: String) -> String {
        text(markedAt: markedAt) + "\n" + report
    }
}
