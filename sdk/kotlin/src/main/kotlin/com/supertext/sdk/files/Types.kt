package com.supertext.sdk.files

/** The kind of a translation file. [value] is the string shared by all Supertext SDKs. */
public enum class FileFormat(
    /** `xliff` or `html`. */
    public val value: String,
) {
    /** XLIFF 1.2: for CAT tools and translators. */
    XLIFF("xliff"),

    /** The HTML document `HtmlDocument.build` makes, with the job's metadata in its head. */
    HTML("html"),
}

/** One text of a job: plain text, or an HTML fragment when [html]. */
public data class FileSegment @JvmOverloads constructor(
    /** The integration's own key for the text, unique in the job, e.g. `title` or `body.3`. */
    val key: String,
    /** The text, or an HTML fragment when [html] is true. */
    val text: String,
    val html: Boolean = false,
) {
    override fun toString(): String = "FileSegment($key, ${if (html) "html" else "text"})"
}

/** One item to translate in a file: the integration's metadata and its segments. */
public class FileJob(
    /** The integration's name (its repository name), e.g. `Contao-Supertext-Translation`. */
    public val plugin: String,
    /** What the integration needs to find the item again, e.g. `page:12`; opaque to the SDK. */
    public val reference: String,
    public val sourceLanguage: String,
    public val targetLanguage: String,
    segments: List<FileSegment>,
) {
    /** The segments (a copy). */
    public val segments: List<FileSegment> = segments.toList()

    override fun equals(other: Any?): Boolean =
        other is FileJob && plugin == other.plugin && reference == other.reference &&
            sourceLanguage == other.sourceLanguage && targetLanguage == other.targetLanguage && segments == other.segments

    override fun hashCode(): Int = listOf(plugin, reference, sourceLanguage, targetLanguage, segments).hashCode()

    override fun toString(): String =
        "FileJob($plugin, $reference, $sourceLanguage > $targetLanguage, ${segments.size} segments)"
}

/** One unit of a file that was read: a segment's key and its source checksum at export time. */
public data class FileUnit(
    val key: String,
    /** The source's checksum at export time, or `null` when the file doesn't carry one. */
    val checksum: String?,
)

/** What [TranslationFiles.importTranslations] found for a segment. [value] is the string shared by all Supertext SDKs. */
public enum class ImportStatus(
    /** `ok`, `source_changed`, `untranslated`, `invalid_markup` or `missing`. */
    public val value: String,
) {
    /** Translated, and the source is unchanged since the export. */
    OK("ok"),

    /** Translated, but the source changed since the export (or the file has no checksum): the integration decides. */
    SOURCE_CHANGED("source_changed"),

    /** The file has no translation for the segment. */
    UNTRANSLATED("untranslated"),

    /** The translation uses markup the current source doesn't have. */
    INVALID_MARKUP("invalid_markup"),

    /** The file has no unit with the segment's key. */
    MISSING("missing"),
}

/** The result of [TranslationFiles.importTranslations] for one current segment. */
public data class ImportedSegment(
    val key: String,
    val status: ImportStatus,
    /** The translation (plain text or HTML, like the segment) for [ImportStatus.OK] and [ImportStatus.SOURCE_CHANGED], else `null`. */
    val text: String?,
) {
    override fun toString(): String = "ImportedSegment($key, ${status.value}" + (if (text == null) "" else ", $text") + ")"
}

/** One job read back from a file by [TranslationFiles.read] (an XLIFF file can hold several). */
public class TranslationFile internal constructor(
    public val format: FileFormat,
    /** The integration that exported the file. */
    public val plugin: String,
    /** The job's reference: find the item with it. */
    public val reference: String,
    public val sourceLanguage: String,
    public val targetLanguage: String,
    units: List<FileUnit>,
    /** The units' translations, by key; `null` for a file not returned by `read`. */
    internal val data: Map<String, TranslationFiles.UnitData>?,
) {
    /** A file description only; [TranslationFiles.importTranslations] accepts only files returned by `read`. */
    public constructor(
        format: FileFormat,
        plugin: String,
        reference: String,
        sourceLanguage: String,
        targetLanguage: String,
        units: List<FileUnit>,
    ) : this(format, plugin, reference, sourceLanguage, targetLanguage, units, null)

    /** The units in the file (a copy). */
    public val units: List<FileUnit> = units.toList()

    override fun equals(other: Any?): Boolean =
        other is TranslationFile && format == other.format && plugin == other.plugin && reference == other.reference &&
            sourceLanguage == other.sourceLanguage && targetLanguage == other.targetLanguage && units == other.units

    override fun hashCode(): Int = listOf(format, plugin, reference, sourceLanguage, targetLanguage, units).hashCode()

    override fun toString(): String =
        "TranslationFile(${format.value}, $plugin, $reference, $sourceLanguage > $targetLanguage, ${units.size} units)"
}

/** Why a translation file couldn't be made or read. [value] is the string shared by all Supertext SDKs. */
public enum class TranslationFileErrorCode(
    /** `invalid_job`, `invalid_file`, `missing_metadata` or `too_large`. */
    public val value: String,
) {
    /** Export: no jobs, an empty or blank metadata field or key, a control character, a duplicate key, nothing to translate. */
    INVALID_JOB("invalid_job"),

    /** Read: neither XLIFF nor HTML, not well-formed XML, a DTD; import: a file not returned by `read`. */
    INVALID_FILE("invalid_file"),

    /** Read: the file lacks the job's metadata (plugin, reference, languages) or, for HTML, the segment list. */
    MISSING_METADATA("missing_metadata"),

    /** Read: more than [TranslationFiles.MAX_FILE_CHARACTERS] characters. */
    TOO_LARGE("too_large"),
}

/** A translation file that couldn't be made or read. [code] says why; the message is English, for logs. */
public class TranslationFileException(
    /** Why it failed. */
    public val code: TranslationFileErrorCode,
    message: String,
) : RuntimeException(message) {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
