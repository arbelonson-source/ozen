package com.arbelonson.ozen.core

object SpeakingVolume {
    const val QUIET_BELOW = 0.07f

    fun notice(level: Int, max: Int): String? = when {
        level <= 0 -> tr("עוצמת המדיה בטלפון על אפס, ולכן אף אחד לא ישמע את המשפטים.", "The phone’s media volume is at zero, so nobody will hear these phrases.")
        max > 0 && level.toFloat() / max < QUIET_BELOW -> tr("עוצמת המדיה בטלפון נמוכה מאוד, ולכן ייתכן שאף אחד לא ישמע את המשפטים.", "The phone’s media volume is very low, so nobody may hear these phrases.")
        else -> null
    }
}
