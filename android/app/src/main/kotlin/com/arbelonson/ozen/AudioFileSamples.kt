package com.arbelonson.ozen

import android.content.Context
import android.net.Uri

object AudioFileSamples {
    const val MAXIMUM_SECONDS = 600.0

    fun load(context: Context, uri: Uri, sampleRate: Int = 16_000): FloatArray? {
        val limit = (MAXIMUM_SECONDS * sampleRate).toInt()
        val pieces = ArrayList<FloatArray>()
        var count = 0
        val outcome = AudioFileDecoder.decode(context, uri, sampleRate) { piece ->
            val kept = minOf(piece.size, limit - count)
            pieces.add(if (kept == piece.size) piece else piece.copyOf(kept))
            count += kept
            count < limit
        }
        if (outcome != DecodeOutcome.Finished && outcome != DecodeOutcome.Stopped && outcome != DecodeOutcome.Stalled) return null
        if (count == 0) return null
        val all = FloatArray(count)
        var at = 0
        for (piece in pieces) { piece.copyInto(all, at); at += piece.size }
        return all
    }
}
