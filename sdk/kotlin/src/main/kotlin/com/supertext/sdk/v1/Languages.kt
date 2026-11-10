package com.supertext.sdk.v1

/**
 * Languages of the Supertext AI file translation API v1 (`spec/languages.json`, from the live `GET features`,
 * October 2026). An integration maps its CMS languages to these codes (a combo box per language, [suggestTarget]
 * preselected); a CMS code that equals a Supertext code (apart from case and `_`/`-`) is used as it is, a bare
 * language Supertext offers only with a region or script gets its default (`de` → `de-DE`, see [DEFAULT_TARGETS]),
 * a region of a bare target is dropped (`es-ES` → `es`), anything else is an [ErrorCode.UNSUPPORTED_LANGUAGE] error
 * until the administrator maps it. For the live list, use [SupertextClient.getFeatures].
 *
 * ```kotlin
 * val mapping = mapOf("de" to "de-CH", "no" to "nb")      // the integration's language setting
 * val target = Languages.target(cmsLocale, mapping)        // "de" → "de-CH", "fr_ch" → "fr-CH", "en" → "en-US"
 * val source = Languages.source(cmsSourceLocale)           // "en-US" → "en", "" → "" (auto-detect)
 * client.translateHtml(html, target, source)
 * ```
 */
public object Languages {

    /** Source languages (bare codes). */
    @JvmField
    public val SOURCES: List<SourceLanguage> = listOf(
        SourceLanguage("bg", "Bulgarian"),
        SourceLanguage("cs", "Czech"),
        SourceLanguage("da", "Danish"),
        SourceLanguage("de", "German"),
        SourceLanguage("el", "Greek"),
        SourceLanguage("en", "English"),
        SourceLanguage("es", "Spanish"),
        SourceLanguage("fi", "Finnish"),
        SourceLanguage("fr", "French"),
        SourceLanguage("gsw", "Swiss German"),
        SourceLanguage("hr", "Croatian"),
        SourceLanguage("hu", "Hungarian"),
        SourceLanguage("it", "Italian"),
        SourceLanguage("ja", "Japanese"),
        SourceLanguage("ko", "Korean"),
        SourceLanguage("nb", "Norwegian Bokmål"),
        SourceLanguage("nl", "Dutch"),
        SourceLanguage("pl", "Polish"),
        SourceLanguage("pt", "Portuguese"),
        SourceLanguage("rm", "Romansh"),
        SourceLanguage("ru", "Russian"),
        SourceLanguage("sk", "Slovak"),
        SourceLanguage("sl", "Slovenian"),
        SourceLanguage("sq", "Albanian"),
        SourceLanguage("sr", "Serbian"),
        SourceLanguage("sv", "Swedish"),
        SourceLanguage("tr", "Turkish"),
        SourceLanguage("zh", "Chinese"),
    )

    /** Target languages, exactly as Supertext expects them. */
    @JvmField
    public val TARGETS: List<TargetLanguage> = listOf(
        TargetLanguage("bg", "Bulgarian", true),
        TargetLanguage("cs", "Czech", true),
        TargetLanguage("da", "Danish", true),
        TargetLanguage("de-AT", "German (Austria)", true),
        TargetLanguage("de-CH", "German (Switzerland)", true),
        TargetLanguage("de-DE", "German (Germany)", true),
        TargetLanguage("el", "Greek", true),
        TargetLanguage("en-GB", "English (United Kingdom)", false),
        TargetLanguage("en-US", "English (United States)", false),
        TargetLanguage("es", "Spanish", true),
        TargetLanguage("fi", "Finnish", true),
        TargetLanguage("fr-CH", "French (Switzerland)", true),
        TargetLanguage("fr-FR", "French (France)", true),
        TargetLanguage("gsw-u-sd-chbe", "Swiss German (Bern)", false),
        TargetLanguage("gsw-u-sd-chzh", "Swiss German (Zurich)", false),
        TargetLanguage("hr", "Croatian", true),
        TargetLanguage("hu", "Hungarian", true),
        TargetLanguage("it-CH", "Italian (Switzerland)", true),
        TargetLanguage("it-IT", "Italian (Italy)", true),
        TargetLanguage("ja", "Japanese", true),
        TargetLanguage("ko", "Korean", true),
        TargetLanguage("nb", "Norwegian Bokmål", true),
        TargetLanguage("nl", "Dutch", true),
        TargetLanguage("pl", "Polish", true),
        TargetLanguage("pt-BR", "Portuguese (Brazil)", true),
        TargetLanguage("pt-PT", "Portuguese (Portugal)", true),
        TargetLanguage("rm", "Romansh", false),
        TargetLanguage("ru", "Russian", true),
        TargetLanguage("sk", "Slovak", true),
        TargetLanguage("sl", "Slovenian", true),
        TargetLanguage("sq", "Albanian", false),
        TargetLanguage("sr-Cyrl", "Serbian (Cyrillic)", false),
        TargetLanguage("sr-Latn", "Serbian (Latin)", false),
        TargetLanguage("sv", "Swedish", true),
        TargetLanguage("tr", "Turkish", true),
        TargetLanguage("zh-Hans", "Chinese (Simplified)", true),
        TargetLanguage("zh-Hant", "Chinese (Traditional)", true),
    )

    /**
     * The default target of a bare language that Supertext offers only with a region or script. Swiss German
     * (`gsw`) has none: neither dialect is the obvious one.
     */
    @JvmField
    public val DEFAULT_TARGETS: Map<String, String> = linkedMapOf(
        "de" to "de-DE",
        "en" to "en-US",
        "fr" to "fr-FR",
        "it" to "it-IT",
        "pt" to "pt-PT",
        "sr" to "sr-Latn",
        "zh" to "zh-Hans",
    )

    private val CHINESE_REGIONS = mapOf("tw" to "zh-Hant", "hk" to "zh-Hant", "mo" to "zh-Hant", "cn" to "zh-Hans", "sg" to "zh-Hans")
    private val TARGET_OPTIONS: List<TargetLanguage> = TARGETS.sortedBy { it.name }
    private val SOURCE_BY_KEY: Map<String, SourceLanguage> = SOURCES.associateBy { key(it.code) }
    private val TARGET_BY_KEY: Map<String, TargetLanguage> = TARGETS.associateBy { key(it.code) }
    private val SCRIPT = Regex("[a-z]{4}")
    private val REGION = Regex("[a-z]{2}|[0-9]{3}")

    /** Case, `_` vs `-` and surrounding spaces don't matter when comparing codes. */
    private fun key(code: String?): String = code?.trim()?.replace('_', '-')?.lowercase() ?: ""

    /** The supported target that equals [code] (apart from case and `_`/`-`), else `null`. `de` → `null`, `de_ch` → `de-CH`. */
    @JvmStatic
    public fun matchTarget(code: String?): String? = TARGET_BY_KEY[key(code)]?.code

    /**
     * The default target for a CMS code that has no exact match, else `null`: a bare language's [DEFAULT_TARGETS]
     * entry (`de` → `de-DE`), Chinese regions (`zh-TW` → `zh-Hant`, `zh-CN` → `zh-Hans`), `language-Script-REGION`
     * (`sr-Latn-RS` → `sr-Latn`) and `language-REGION` of a bare target (`es-ES` → `es`). Never a guessed region:
     * `en-IE`, `de-LI` → `null`.
     */
    @JvmStatic
    public fun defaultTarget(code: String?): String? {
        val k = key(code)
        if (k.isEmpty() || k in TARGET_BY_KEY) return null
        val parts = k.split("-")
        val lang = parts[0]
        return when {
            parts.size == 1 -> DEFAULT_TARGETS[lang]
            parts.size == 2 && lang == "zh" -> CHINESE_REGIONS[parts[1]]
            parts.size == 3 && SCRIPT.matches(parts[1]) && REGION.matches(parts[2]) -> matchTarget("$lang-${parts[1]}")
            parts.size == 2 && REGION.matches(parts[1]) -> matchTarget(lang)
            else -> null
        }
    }

    /** What a settings screen preselects for a CMS language: the exact match, else the default, else `null`. */
    @JvmStatic
    public fun suggestTarget(code: String?): String? = matchTarget(code) ?: defaultTarget(code)

    /**
     * The targets sorted by English name, for the language combo box. Show the name localized where the platform
     * can (e.g. `Locale.forLanguageTag(code).getDisplayName(locale)`) and the code next to it.
     */
    @JvmStatic
    public fun targetOptions(): List<TargetLanguage> = TARGET_OPTIONS

    /** The supported source for [code]: exact match, else its first subtag (`en-US` → `en`), else `null`. */
    @JvmStatic
    public fun matchSource(code: String?): String? {
        val k = key(code)
        if (k.isEmpty()) return null
        return (SOURCE_BY_KEY[k] ?: SOURCE_BY_KEY[k.substringBefore('-')])?.code
    }

    /**
     * The target code to send: the mapping's value (sent trimmed and otherwise as it is, even if it isn't in
     * [TARGETS], so a language Supertext adds later can be used before the SDK knows it), else an exact match
     * ([matchTarget]), else the default ([defaultTarget]).
     *
     * @param mapping CMS language → Supertext code, e.g. `mapOf("de" to "de-CH", "no" to "nb")`; keys are compared like codes.
     * @throws SupertextException [ErrorCode.UNSUPPORTED_LANGUAGE] (with [SupertextException.targetLang]) when there
     *   is none of them; nothing is sent
     */
    @JvmStatic
    @JvmOverloads
    public fun target(code: String?, mapping: Map<String, String>? = null): String =
        mapped(code, mapping) ?: matchTarget(code) ?: defaultTarget(code)
            ?: throw SupertextException(ErrorCode.UNSUPPORTED_LANGUAGE, targetLang = code?.trim() ?: "")

    /**
     * The source code to send: the mapping's value, else [matchSource]. An empty or blank code gives `""`
     * (auto-detect).
     *
     * @throws SupertextException [ErrorCode.UNSUPPORTED_LANGUAGE] (with [SupertextException.sourceLang]) when there is neither
     */
    @JvmStatic
    @JvmOverloads
    public fun source(code: String?, mapping: Map<String, String>? = null): String {
        if (code.isNullOrBlank()) return ""
        // One mapping serves both directions: a mapped target code is sent in its source form (de-CH -> de).
        val fromMapping = mapped(code, mapping)
        val result = if (fromMapping != null) matchSource(fromMapping) ?: fromMapping else matchSource(code)
        return result ?: throw SupertextException(ErrorCode.UNSUPPORTED_LANGUAGE, sourceLang = code.trim())
    }

    /** Whether a supported target offers formal/informal; `null` for a code that isn't in [TARGETS]. */
    @JvmStatic
    public fun supportsPoliteness(targetCode: String?): Boolean? = TARGET_BY_KEY[key(targetCode)]?.supportsPoliteness

    /** Whether [code] matches a supported target ([matchTarget] isn't `null`). */
    @JvmStatic
    public fun isSupportedTarget(code: String?): Boolean = matchTarget(code) != null

    /** Whether [code] is a supported source (exact match, apart from case and `_`/`-`). */
    @JvmStatic
    public fun isSupportedSource(code: String?): Boolean = key(code) in SOURCE_BY_KEY

    private fun mapped(code: String?, mapping: Map<String, String?>?): String? {
        if (mapping == null) return null
        val wanted = key(code)
        for ((from, to) in mapping) {
            @Suppress("SENSELESS_COMPARISON") // Java callers may pass null keys
            if (from != null && key(from) == wanted && !to.isNullOrBlank()) return to.trim()
        }
        return null
    }
}
