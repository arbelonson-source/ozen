public enum SpeakingVolume {
    public static let quietBelow: Float = 0.07

    public static func notice(outputVolume: Float) -> String? {
        if outputVolume < 0.01 {
            return tr("עוצמת המדיה בטלפון על אפס, ולכן אף אחד לא ישמע את המשפטים.", "The phone’s media volume is at zero, so nobody will hear these phrases.")
        }
        guard outputVolume < quietBelow else { return nil }
        return tr("עוצמת המדיה בטלפון נמוכה מאוד, ולכן ייתכן שאף אחד לא ישמע את המשפטים.", "The phone’s media volume is very low, so nobody may hear these phrases.")
    }
}
