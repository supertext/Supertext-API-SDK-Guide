package com.supertext.sdk.v1

/** Form of address in the translation. */
public enum class Politeness(
    /** The API value: `default`, `more` or `less`. */
    public val value: String,
) {
    /** Let Supertext decide (not sent). */
    DEFAULT("default"),

    /** Formal, e.g. German "Sie", French "vous". */
    MORE("more"),

    /** Informal, e.g. German "du", French "tu". */
    LESS("less"),
    ;

    public companion object {
        /**
         * Parses `more` or `less` (case-insensitive). Anything else, including `default`, `null` and unknown
         * values, is [DEFAULT]: politeness is then simply not sent.
         */
        @JvmStatic
        public fun fromValue(value: String?): Politeness {
            if (value.isNullOrBlank()) return DEFAULT
            val v = value.trim().lowercase()
            return entries.firstOrNull { it.value == v } ?: DEFAULT
        }
    }
}

/** One text to translate: plain text (escaped, line breaks kept) or an HTML fragment (sent as it is). */
public data class Segment(
    val text: String,
    /** An HTML segment: formatting and links as inline tags (`<b>`, `<a href>`). */
    val html: Boolean = false,
) {
    override fun toString(): String = (if (html) "Segment.html(" else "Segment.text(") + text + ")"

    public companion object {
        /** A plain-text segment. */
        @JvmStatic
        public fun text(text: String): Segment = Segment(text, false)

        /** An HTML segment: formatting and links as inline tags (`<b>`, `<a href>`). */
        @JvmStatic
        public fun html(html: String): Segment = Segment(html, true)
    }
}

/** A source language of the API (a bare code such as `en`), see [Languages.SOURCES]. */
public data class SourceLanguage(
    /** The code to send, e.g. `en`. */
    val code: String,
    /** English name, e.g. `English`. */
    val name: String,
) {
    override fun toString(): String = "$code ($name)"
}

/** A target language of the API, exactly as Supertext expects it (e.g. `de-CH`, `es`), see [Languages.TARGETS]. */
public data class TargetLanguage(
    /** The code to send, e.g. `de-CH`. */
    val code: String,
    /** English name, e.g. `German (Switzerland)`. */
    val name: String,
    /**
     * Whether formal/informal ([Politeness.MORE]/[Politeness.LESS]) is offered. Otherwise the API answers
     * FEATURE_ERROR ([ErrorCode.UNSUPPORTED_FEATURE]).
     */
    val supportsPoliteness: Boolean,
) {
    override fun toString(): String = "$code ($name" + (if (supportsPoliteness) ", politeness" else "") + ")"
}

/** One language pair from `GET features` ([SupertextClient.getFeatures]). */
public data class FeatureSupport(
    val sourceLang: String,
    val targetLang: String,
    /** E.g. `supports_politeness`, `supports_glossary`. */
    val features: List<String>,
) {
    override fun toString(): String = "$sourceLang → $targetLang $features"
}
