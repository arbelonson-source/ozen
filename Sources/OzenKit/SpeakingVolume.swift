public enum SpeakingVolume {
    public static func notice(outputVolume: Float) -> String? {
        guard outputVolume < 0.01 else { return nil }
        return tr("עוצמת המדיה בטלפון על אפס, ולכן אף אחד לא ישמע את המשפטים.", "The phone’s media volume is at zero, so nobody will hear these phrases.")
    }
}
