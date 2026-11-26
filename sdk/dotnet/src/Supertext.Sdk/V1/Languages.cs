namespace Supertext.Sdk.V1;

/// <summary>A source language of the Supertext API (bare code).</summary>
/// <param name="Code">The code to send, e.g. <c>en</c>.</param>
/// <param name="Name">English name.</param>
public sealed record SourceLanguage(string Code, string Name);

/// <summary>A target language of the Supertext API, exactly as Supertext expects it.</summary>
/// <param name="Code">The code to send, e.g. <c>de-CH</c>, <c>es</c>, <c>zh-Hans</c>.</param>
/// <param name="Name">English name.</param>
/// <param name="Politeness">
/// Whether formal/informal (<see cref="V1.Politeness.More"/>, <see cref="V1.Politeness.Less"/>) is offered.
/// Otherwise the API answers <c>FEATURE_ERROR</c> (<see cref="SupertextErrorCodes.UnsupportedFeature"/>).
/// </param>
public sealed record TargetLanguage(string Code, string Name, bool Politeness);

/// <summary>
/// Languages of the Supertext AI file translation API v1 (spec/languages.json, from the live
/// <c>GET features</c>, October 2026). An integration maps its CMS languages to these codes (a combo
/// box per language, <see cref="SuggestTarget"/> preselected); a CMS code that equals a Supertext code
/// (apart from case and <c>_</c>/<c>-</c>) is used as it is, a bare language Supertext offers only with
/// a region or script gets its default (<c>de</c> → <c>de-DE</c>, see <see cref="DefaultTargets"/>), a
/// region of a bare target is dropped (<c>es-ES</c> → <c>es</c>), anything else is an
/// <see cref="SupertextErrorCodes.UnsupportedLanguage"/> error until the administrator maps it.
/// For the live list, use <see cref="SupertextClient.GetFeaturesAsync"/>.
/// </summary>
public static class Languages
{
    /// <summary>Source languages (bare codes).</summary>
    public static IReadOnlyList<SourceLanguage> Sources { get; } = Array.AsReadOnly(new SourceLanguage[]
    {
        new("bg", "Bulgarian"),
        new("cs", "Czech"),
        new("da", "Danish"),
        new("de", "German"),
        new("el", "Greek"),
        new("en", "English"),
        new("es", "Spanish"),
        new("fi", "Finnish"),
        new("fr", "French"),
        new("gsw", "Swiss German"),
        new("hr", "Croatian"),
        new("hu", "Hungarian"),
        new("id", "Indonesian"),
        new("it", "Italian"),
        new("ja", "Japanese"),
        new("ko", "Korean"),
        new("nb", "Norwegian Bokmål"),
        new("nl", "Dutch"),
        new("pl", "Polish"),
        new("pt", "Portuguese"),
        new("rm", "Romansh"),
        new("ro", "Romanian"),
        new("ru", "Russian"),
        new("sk", "Slovak"),
        new("sl", "Slovenian"),
        new("sq", "Albanian"),
        new("sr", "Serbian"),
        new("sv", "Swedish"),
        new("th", "Thai"),
        new("tr", "Turkish"),
        new("vi", "Vietnamese"),
        new("zh", "Chinese"),
    });

    /// <summary>Target languages, exactly as Supertext expects them.</summary>
    public static IReadOnlyList<TargetLanguage> Targets { get; } = Array.AsReadOnly(new TargetLanguage[]
    {
        new("bg", "Bulgarian", true),
        new("cs", "Czech", true),
        new("da", "Danish", true),
        new("de-AT", "German (Austria)", true),
        new("de-CH", "German (Switzerland)", true),
        new("de-DE", "German (Germany)", true),
        new("el", "Greek", true),
        new("en-GB", "English (United Kingdom)", false),
        new("en-US", "English (United States)", false),
        new("es", "Spanish", true),
        new("fi", "Finnish", true),
        new("fr-CH", "French (Switzerland)", true),
        new("fr-FR", "French (France)", true),
        new("gsw-u-sd-chbe", "Swiss German (Bern)", false),
        new("gsw-u-sd-chzh", "Swiss German (Zurich)", false),
        new("hr", "Croatian", true),
        new("hu", "Hungarian", true),
        new("id", "Indonesian", true),
        new("it-CH", "Italian (Switzerland)", true),
        new("it-IT", "Italian (Italy)", true),
        new("ja", "Japanese", true),
        new("ko", "Korean", true),
        new("nb", "Norwegian Bokmål", true),
        new("nl", "Dutch", true),
        new("pl", "Polish", true),
        new("pt-BR", "Portuguese (Brazil)", true),
        new("pt-PT", "Portuguese (Portugal)", true),
        new("rm", "Romansh", false),
        new("ro", "Romanian", true),
        new("ru", "Russian", true),
        new("sk", "Slovak", true),
        new("sl", "Slovenian", true),
        new("sq", "Albanian", false),
        new("sr-Cyrl", "Serbian (Cyrillic)", false),
        new("sr-Latn", "Serbian (Latin)", false),
        new("sv", "Swedish", true),
        new("th", "Thai", true),
        new("tr", "Turkish", true),
        new("vi", "Vietnamese", true),
        new("zh-Hans", "Chinese (Simplified)", true),
        new("zh-Hant", "Chinese (Traditional)", true),
    });

    /// <summary>
    /// The default target of a bare language that Supertext offers only with a region or script.
    /// Swiss German (<c>gsw</c>) has none: neither dialect is the obvious one.
    /// </summary>
    public static IReadOnlyDictionary<string, string> DefaultTargets { get; } =
        new System.Collections.ObjectModel.ReadOnlyDictionary<string, string>(new Dictionary<string, string>
        {
            ["de"] = "de-DE",
            ["en"] = "en-US",
            ["fr"] = "fr-FR",
            ["it"] = "it-IT",
            ["pt"] = "pt-PT",
            ["sr"] = "sr-Latn",
            ["zh"] = "zh-Hans",
        });

    private static readonly Dictionary<string, string> ChineseRegions = new()
    {
        ["tw"] = "zh-Hant",
        ["hk"] = "zh-Hant",
        ["mo"] = "zh-Hant",
        ["cn"] = "zh-Hans",
        ["sg"] = "zh-Hans",
    };

    private static readonly Dictionary<string, SourceLanguage> SourcesByKey = Sources.ToDictionary(l => Key(l.Code));
    private static readonly Dictionary<string, TargetLanguage> TargetsByKey = Targets.ToDictionary(l => Key(l.Code));
    private static readonly IReadOnlyList<TargetLanguage> Options =
        Array.AsReadOnly(Targets.OrderBy(l => l.Name, StringComparer.Ordinal).ToArray());

    /// <summary>
    /// The default target for a CMS code that has no exact match, else null: a bare language's
    /// <see cref="DefaultTargets"/> entry (<c>de</c> → <c>de-DE</c>), Chinese regions (<c>zh-TW</c> →
    /// <c>zh-Hant</c>, <c>zh-CN</c> → <c>zh-Hans</c>), <c>language-Script-REGION</c> (<c>sr-Latn-RS</c> →
    /// <c>sr-Latn</c>) and <c>language-REGION</c> of a bare target (<c>es-ES</c> → <c>es</c>). Never a
    /// guessed region: <c>en-IE</c>, <c>de-LI</c> → null.
    /// </summary>
    public static string? DefaultTarget(string? code)
    {
        var key = Key(code);
        if (key.Length == 0 || TargetsByKey.ContainsKey(key))
        {
            return null;
        }
        var parts = key.Split('-');
        var lang = parts[0];
        if (parts.Length == 1)
        {
            return DefaultTargets.TryGetValue(lang, out var fallback) ? fallback : null;
        }
        if (parts.Length == 2 && lang == "zh")
        {
            return ChineseRegions.TryGetValue(parts[1], out var chinese) ? chinese : null;
        }
        if (parts.Length == 3 && IsScript(parts[1]) && IsRegion(parts[2]))
        {
            return MatchTarget(lang + "-" + parts[1]);
        }
        if (parts.Length == 2 && IsRegion(parts[1]))
        {
            return MatchTarget(lang);
        }
        return null;
    }

    /// <summary>What a settings screen preselects for a CMS language: the exact match, else the default, else null.</summary>
    public static string? SuggestTarget(string? code) => MatchTarget(code) ?? DefaultTarget(code);

    /// <summary>
    /// The targets sorted by English name, for the language combo box. Show the name localized where the
    /// platform can (e.g. <see cref="System.Globalization.CultureInfo.DisplayName"/>) and the code next to it.
    /// </summary>
    public static IReadOnlyList<TargetLanguage> TargetOptions() => Options;

    private static bool IsScript(string subtag) => subtag.Length == 4 && subtag.All(c => c is >= 'a' and <= 'z');

    private static bool IsRegion(string subtag) =>
        (subtag.Length == 2 && subtag.All(c => c is >= 'a' and <= 'z')) ||
        (subtag.Length == 3 && subtag.All(c => c is >= '0' and <= '9'));

    /// <summary>Case, <c>_</c> vs <c>-</c> and surrounding spaces don't matter when comparing codes.</summary>
    private static string Key(string? code) => (code ?? string.Empty).Trim().Replace('_', '-').ToLowerInvariant();

    /// <summary>The supported target that equals <paramref name="code"/> (apart from case and <c>_</c>/<c>-</c>), else null. <c>de</c> → null, <c>de_ch</c> → <c>de-CH</c>.</summary>
    public static string? MatchTarget(string? code) => TargetsByKey.TryGetValue(Key(code), out var language) ? language.Code : null;

    /// <summary>The supported source for <paramref name="code"/>: exact match, else its first subtag (<c>en-US</c> → <c>en</c>), else null.</summary>
    public static string? MatchSource(string? code)
    {
        var key = Key(code);
        if (key.Length == 0)
        {
            return null;
        }
        if (SourcesByKey.TryGetValue(key, out var exact))
        {
            return exact.Code;
        }
        return SourcesByKey.TryGetValue(key.Split('-')[0], out var language) ? language.Code : null;
    }

    /// <summary>
    /// The target code to send: the mapping's value (sent trimmed, even if not in the list), else an
    /// exact match (<see cref="MatchTarget"/>), else the default (<see cref="DefaultTarget"/>).
    /// </summary>
    /// <param name="code">The CMS language.</param>
    /// <param name="mapping">CMS language → Supertext code, e.g. <c>{ ["de"] = "de-CH", ["no"] = "nb" }</c> (the integration's language setting). Keys are compared like codes.</param>
    /// <exception cref="SupertextException"><see cref="SupertextErrorCodes.UnsupportedLanguage"/> (with <see cref="SupertextException.TargetLang"/>) when there is none of them; nothing is sent.</exception>
    public static string Target(string? code, IReadOnlyDictionary<string, string>? mapping = null)
    {
        var result = Mapped(code, mapping) ?? MatchTarget(code) ?? DefaultTarget(code);
        if (result is null)
        {
            var trimmed = (code ?? string.Empty).Trim();
            throw Unsupported(targetLang: trimmed.Length > 0 ? trimmed : null);
        }
        return result;
    }

    /// <summary>
    /// The source code to send: the mapping's value, else <see cref="MatchSource"/>. An empty code
    /// stays empty (Supertext detects the language).
    /// </summary>
    /// <param name="code">The CMS language.</param>
    /// <param name="mapping">CMS language → Supertext code. Keys are compared like codes.</param>
    /// <exception cref="SupertextException"><see cref="SupertextErrorCodes.UnsupportedLanguage"/> (with <see cref="SupertextException.SourceLang"/>) when there is neither.</exception>
    public static string Source(string? code, IReadOnlyDictionary<string, string>? mapping = null)
    {
        var trimmed = (code ?? string.Empty).Trim();
        if (trimmed.Length == 0)
        {
            return string.Empty;
        }
        // One mapping serves both directions: a mapped target code is sent in its source form (de-CH → de).
        var fromMapping = Mapped(code, mapping);
        if (fromMapping is not null)
        {
            return MatchSource(fromMapping) ?? fromMapping;
        }
        return MatchSource(code) ?? throw Unsupported(sourceLang: trimmed);
    }

    /// <summary>Whether a supported target offers formal/informal; null for a code that isn't in the list.</summary>
    public static bool? SupportsPoliteness(string? targetCode) =>
        TargetsByKey.TryGetValue(Key(targetCode), out var language) ? language.Politeness : null;

    /// <summary>Whether <paramref name="code"/> matches a supported target (<see cref="MatchTarget"/>).</summary>
    public static bool IsSupportedTarget(string? code) => MatchTarget(code) is not null;

    /// <summary>Whether <paramref name="code"/> is a supported source (exact match, apart from case and <c>_</c>/<c>-</c>).</summary>
    public static bool IsSupportedSource(string? code) => SourcesByKey.ContainsKey(Key(code));

    private static string? Mapped(string? code, IReadOnlyDictionary<string, string>? mapping)
    {
        if (mapping is null)
        {
            return null;
        }
        var wanted = Key(code);
        foreach (var (from, to) in mapping)
        {
            if (Key(from) == wanted && !string.IsNullOrWhiteSpace(to))
            {
                return to.Trim();
            }
        }
        return null;
    }

    private static SupertextException Unsupported(string? sourceLang = null, string? targetLang = null) =>
        new(SupertextErrorCodes.UnsupportedLanguage,
            "This language has no Supertext language code. Map it to a Supertext language.",
            sourceLang: sourceLang, targetLang: targetLang);
}
