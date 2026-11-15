package com.arbelonson.ozen.core

object DamagedModelNotice {
    fun text(): String = tr(
        "מודל הדיבור בטלפון הזה נפגם, ולכן אוזן הסירה אותו ותוריד אותו שוב.",
        "The speech model on this phone was damaged, so Ozen removed it and will download it again.",
    )
}
