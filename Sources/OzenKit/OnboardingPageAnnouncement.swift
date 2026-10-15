public enum OnboardingPageAnnouncement {
    public static func text(page: Int, total: Int, title: String) -> String {
        let count = max(total, 1)
        let shown = min(max(page, 0), count - 1) + 1
        return tr("עמוד %1 מתוך %2: %3", "Page %1 of %2: %3", args: ["\(shown)", "\(count)", "\(title)"])
    }
}
