package com.arbelonson.ozen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.TranscriptFormats
import java.io.File

enum class SubtitleFormat(val extension: String, val mime: String) {
    Srt("srt", "application/x-subrip"),
    Vtt("vtt", "text/vtt");

    fun render(lines: List<TranscribedLine>): String = if (this == Srt) TranscriptFormats.srt(lines) else TranscriptFormats.vtt(lines)
}

internal fun transcriptShareSheet(context: Context, name: String, lines: List<TranscribedLine>): Intent =
    textShareSheet(context, name, TranscriptFormats.text(lines))

internal fun subtitleShareSheet(context: Context, name: String, lines: List<TranscribedLine>, format: SubtitleFormat): Intent {
    val folder = File(context.cacheDir, CaptionSession.SHARED_FOLDER)
    folder.deleteRecursively()
    folder.mkdirs()
    val file = File(folder, safeFileName(name) + "." + format.extension)
    file.writeText(format.render(lines))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
    val send = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_SUBJECT, name)
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, null)
}

internal fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "recording" }.take(80)
