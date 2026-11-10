package com.arbelonson.ozen

import android.content.Intent
import com.arbelonson.ozen.core.tr

internal fun reportSheet(report: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, tr("דוח אבחון של אוזן", "Ozen diagnostics report"))
        .putExtra(Intent.EXTRA_TEXT, report)
    return Intent.createChooser(send, null)
}
