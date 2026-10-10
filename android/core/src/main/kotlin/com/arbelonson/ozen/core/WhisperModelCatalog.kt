package com.arbelonson.ozen.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One Whisper model the app offers. Sizes were measured from the actual
 * `argmaxinc/whisperkit-coreml` repository listing on 2026-09-13 (and
 * the Hebrew model's release assets); Hebrew quality and speed are 1-5
 * ratings from measured Hebrew error rates and WhisperKit's iPhone
 * benchmarks - a guide for choosing, not a promise. For a hub model the
 * variant string is what `WhisperKit.download` matches against folder
 * names (`openai_whisper-<variant>`).
 */
data class WhisperModelOption(
    val variant: String,
    val displayName: String,
    val sizeMB: Int,
    /** 1 (barely usable for Hebrew) ... 5 (best Whisper can do). */
    val hebrewQuality: Int,
    /** 1 (too slow for live use on a phone) ... 5 (instant). */
    val speed: Int,
    val note: String,
    val isRecommended: Boolean,
    /** Where the files come from; see `WhisperModelSource`. */
    val source: WhisperModelSource = WhisperModelSource.WhisperKitHub,
    /**
     * The model's folder name on disk. WhisperKit's hub names folders
     * `openai_whisper-<variant>`; a model from elsewhere says its own.
     */
    val folderName: String = "openai_whisper-$variant",
    /**
     * The question mark's cutoffs for a model whose scores sit higher
     * than those `CaptionConfidence`'s were measured on; null keeps those.
     */
    val uncertainBelow: CaptionConfidence.Cutoffs? = null,
) {
    val id: String get() = variant

    /**
     * Room the install takes at its peak. A model from WhisperKit's hub
     * is its download; one from a release is compiled on the phone, and
     * the compiled bundles sit beside the packages until those are removed.
     */
    val installMegabytes: Int
        get() = when (source) {
            is WhisperModelSource.WhisperKitHub -> sizeMB
            is WhisperModelSource.OzenRelease -> sizeMB * 2
        }

    /**
     * Room still needed to finish installing with [onDiskBytes] of it
     * already in the model's folder: a download cut off part way resumes,
     * so only the rest has to fit.
     *
     * Megabytes here are a million bytes, as in [sizeMB] and as iPhone
     * Storage shows them. Counted in 1,048,576-byte ones, the whole Hebrew
     * model on the phone still left 38 MB "to download", and freeing the
     * room the app asked for left her about 5% short.
     */
    fun remainingInstallMegabytes(onDiskBytes: Long): Int =
        maxOf(installMegabytes - (onDiskBytes / StorageSpaceGate.BYTES_PER_MEGABYTE).toInt(), 1)

    /**
     * What is left to download with [onDiskBytes] already in the model's
     * folder; never below 1, which would read as "size unknown".
     */
    fun remainingDownloadMegabytes(onDiskBytes: Long): Int =
        maxOf(sizeMB - (onDiskBytes / StorageSpaceGate.BYTES_PER_MEGABYTE).toInt(), 1)

    /** Not an exact byte count - a human-scale label for the picker. */
    val sizeLabel: String
        get() = if (sizeMB >= 1000) {
            BigDecimal(sizeMB.toDouble() / 1000).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + " GB"
        } else {
            "$sizeMB MB"
        }
}

/**
 * The curated subset of WhisperKit's model zoo that makes sense for a
 * live Hebrew captioner on a modern iPhone. English-only variants
 * (`.en`, distil) are deliberately absent; the 3 GB full-precision
 * large models are listed but marked slow, so the choice is honest.
 */
object WhisperModelCatalog {
    val options: List<WhisperModelOption> = listOf(
        WhisperModelOption(
            variant = "tiny", displayName = "Tiny", sizeMB = 76,
            hebrewQuality = 1, speed = 5,
            note = "Fastest. Hebrew is mostly wrong — only for testing the microphone.",
            isRecommended = false,
        ),
        WhisperModelOption(
            variant = "base", displayName = "Base", sizeMB = 146,
            hebrewQuality = 1, speed = 5,
            note = "Very fast, still weak in Hebrew.",
            isRecommended = false,
        ),
        WhisperModelOption(
            variant = "small_216MB", displayName = "Small (compressed)", sizeMB = 217,
            hebrewQuality = 2, speed = 4,
            note = "Half the download of Small with nearly the same results.",
            isRecommended = false,
        ),
        WhisperModelOption(
            variant = "small", displayName = "Small", sizeMB = 486,
            hebrewQuality = 2, speed = 4,
            note = "Quick to download and responsive. Understandable Hebrew, with mistakes.",
            isRecommended = false,
        ),
        // ivrit.ai's Turbo with its encoder trained further on speech from
        // across a room, in noise and over a TV (October 2026, run a3):
        // 20.1% of words wrong on those recordings against 23.1% for
        // ivrit.ai's own, and 13.3% against 13.1% up close.
        //
        // It is also surer of itself. On the same 3,500 lines Whisper's
        // usual 0.6 / 0.8 marked 27% of the 707 it got badly wrong, against
        // 33% of ivrit.ai's 806; 0.65 / 0.83 marks 33%, with 4 fully right
        // lines among its 282 marks (ivrit.ai's: 5 of 297) and no right
        // short answer. Measured on the computer's copy of the model, whose
        // scores the phone's are averaged to match.
        WhisperModelOption(
            variant = "ozen-turbo-hebrew-a3-8bit", displayName = "Turbo Hebrew, noise-trained (Ozen)", sizeMB = 819,
            hebrewQuality = 5, speed = 3,
            note = "Turbo Hebrew trained further by Ozen on speech from across a room, in noise and over a TV: about a tenth fewer wrong words there, the same up close. As quick, the same download.",
            isRecommended = true,
            source = WhisperModelSource.OzenRelease(tag = "model-ozen-turbo-hebrew-a3-8bit-1"),
            folderName = "ozen_whisper-large-v3-turbo-hebrew-a3_8bit",
            uncertainBelow = CaptionConfidence.Cutoffs(shortLine = 0.65f, line = 0.83f),
        ),
        WhisperModelOption(
            variant = "ivrit-large-v3-turbo-8bit", displayName = "Turbo Hebrew (ivrit.ai)", sizeMB = 819,
            hebrewQuality = 5, speed = 3,
            note = "Turbo trained on 5,000 hours of Hebrew by ivrit.ai: a third fewer wrong words than Turbo on test recordings. As quick, a bigger download.",
            isRecommended = false,
            source = WhisperModelSource.OzenRelease(tag = "model-ivrit-large-v3-turbo-8bit-1"),
            folderName = "ivrit-ai_whisper-large-v3-turbo_8bit",
        ),
        WhisperModelOption(
            variant = "large-v3-v20240930_626MB", displayName = "Turbo (compressed)", sizeMB = 626,
            hebrewQuality = 4, speed = 3,
            note = "Much better Hebrew than Small for about the same download. Slightly slower per update.",
            isRecommended = false,
        ),
        WhisperModelOption(
            variant = "large-v3-v20240930", displayName = "Turbo", sizeMB = 1619,
            hebrewQuality = 4, speed = 3,
            note = "Full-precision Turbo. Same accuracy class as the compressed one, bigger download.",
            isRecommended = false,
        ),
        WhisperModelOption(
            variant = "medium", displayName = "Medium", sizeMB = 1529,
            hebrewQuality = 3, speed = 2,
            note = "Older mid-size model; Turbo is both better and faster.",
            isRecommended = false,
        ),
        // Decoded the way the app decodes, OpenAI's Large v3 got 20.4% of
        // words wrong on lectures and 28.1% on read sentences (September
        // 2026), and 16.6% on 839 broadcast clips (October): behind
        // OpenAI's Turbo on lectures, ahead on the others, and behind
        // ivrit.ai's Turbo (6.6%, 21.1%, 9.7%) on all three.
        WhisperModelOption(
            variant = "large-v3_947MB", displayName = "Large v3 (compressed)", sizeMB = 948,
            hebrewQuality = 4, speed = 1,
            note = "Less accurate in Hebrew than Turbo Hebrew, and too slow to feel live on a phone.",
            isRecommended = false,
        ),
        WhisperModelOption(
            variant = "large-v3", displayName = "Large v3", sizeMB = 3090,
            hebrewQuality = 4, speed = 1,
            note = "3 GB. Less accurate in Hebrew than Turbo Hebrew, and the slowest; for reference only.",
            isRecommended = false,
        ),
    )

    /**
     * The order the model screen shows them in: the recommended model
     * first, then the rest as above. In size order it came fifth; at the
     * largest text size each row is about a screen tall, so reaching it
     * meant scrolling past Tiny and three other weak ones first. The
     * order never depends on what is selected, so a row never moves from
     * under her finger when she picks it.
     */
    val listed: List<WhisperModelOption> = options.filter { it.isRecommended } + options.filter { !it.isRecommended }

    /**
     * What a fresh install gets. The recommended model: on 90 Hebrew test
     * clips (September 2026) Small got 27% of words wrong on lecture
     * speech and 48% on read sentences, OpenAI's Turbo 10% and 31%, and
     * ivrit.ai's Hebrew-trained Turbo 7% and 21%, so nobody should end up
     * on a weaker one without choosing it.
     */
    const val RECOMMENDED_VARIANT = "ozen-turbo-hebrew-a3-8bit"
    const val DEFAULT_VARIANT = RECOMMENDED_VARIANT

    fun option(variant: String): WhisperModelOption? = options.firstOrNull { it.variant == variant }

    /**
     * Whether the recommended model is clearly better in Hebrew than
     * [variant]: the case for a phone set up when Small was the default.
     * A variant the catalog doesn't know can't be judged, so it isn't.
     */
    fun recommendedImproves(variant: String): Boolean {
        val current = option(variant) ?: return false
        val recommended = option(RECOMMENDED_VARIANT) ?: return false
        return recommended.hebrewQuality > current.hebrewQuality
    }

    /**
     * The on-disk folder name of a variant: the option's own, or the name
     * WhisperKit's model repository uses for one the catalog doesn't
     * list. Kept here (next to the variant list) so the model store and
     * the download code can't drift apart on naming.
     */
    fun folderName(variant: String): String = option(variant)?.folderName ?: "openai_whisper-$variant"

    fun variantFromFolderName(name: String): String? {
        options.firstOrNull { it.folderName == name }?.let { return it.variant }
        val prefix = "openai_whisper-"
        if (!name.startsWith(prefix)) return null
        return name.substring(prefix.length)
    }
}
