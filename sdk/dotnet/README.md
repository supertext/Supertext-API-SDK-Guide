# Supertext SDK for .NET

Client for the Supertext AI translation API: translate HTML and texts, with the supported languages, rate-limit retries and HTML packing handled for you. NuGet package `Supertext.Sdk`, `net10.0`, no dependencies.

Everything that talks to the API v1 is in the namespace **`Supertext.Sdk.V1`**. A later API version gets `Supertext.Sdk.V2` next to it, so an integration can move at its own pace. Only the SDK's version, `Supertext.Sdk.SdkInfo.Version`, is outside the versioned namespace.

It packages what Supertext's CMS plugins learned against the live API (October 2026). Background and the protocol: [integration guide](../../docs/integration-guide.md).

## Install

```sh
dotnet add package Supertext.Sdk
```

You need an API key. No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role). Paste the key with or without its `Supertext-Auth-Key` prefix.

## Quick start

```csharp
using Supertext.Sdk.V1;

var client = new SupertextClient(Environment.GetEnvironmentVariable("SUPERTEXT_API_KEY"),
    new SupertextClientOptions { UserAgent = "My-CMS-Plugin/1.0.0" });

// Keyed texts: packed into one HTML document, translated, unpacked. Results come back in input order.
var translated = await client.TranslateTextsAsync(new Dictionary<string, Segment>
{
    ["title"] = "Hello & welcome",                                        // plain text (escaped, \n → <br>)
    ["body"] = new Segment("<p>Our <b>new</b> shop opens on Monday.</p>", Html: true),
}, targetLang: "de-CH", sourceLang: "en", politeness: Politeness.More);

Console.WriteLine(translated["title"]);

// A whole HTML document: upload, poll, download, delete.
string html = await client.TranslateHtmlAsync("<p>Hello</p>", "fr-CH");
```

Send each paragraph (heading, list item) as **one** segment, with formatting and links as inline tags. Every segment is translated on its own; splitting a sentence at its formatting breaks the translation.

`TranslateTextsAsync` takes any `IEnumerable<KeyValuePair<string, Segment>>`. Blank texts come back unchanged and cost no request. Large inputs are split into several documents of at most `maxCharacters` (default 900,000), sent one after the other.

The language codes are sent exactly as given (only trimmed): pass Supertext codes, resolved with `Languages` (below). An empty target throws `unsupported_language` without a request; a null or empty source is left out (Supertext detects the language).

The lower-level steps are public too: `GetFeaturesAsync` (the live language pairs, see below), `SubmitAsync`, `GetStatusAsync`, `WaitUntilDoneAsync`, `DownloadAsync`, `DeleteAsync` (never throws), `ValidateApiKeyAsync` (a cost-free key check that throws) and `CheckApiKeyAsync` (the same check as a result, see below). Every method takes a `CancellationToken`.

## Languages

`Languages.Sources` and `Languages.Targets` list what Supertext offers (`SourceLanguage(Code, Name)`, `TargetLanguage(Code, Name, Politeness)`). Sources are bare languages (`en`, `de`, `gsw`). Targets are exactly what Supertext expects: with a region for German, English, French, Italian and Portuguese (`de-CH`, `en-US`, `pt-BR`), with a script for Chinese and Serbian (`zh-Hans`, `sr-Latn`), a dialect for Swiss German (`gsw-u-sd-chzh`), bare for the others (`es`, `nl`, `sl`). Anything else is rejected by the API (`invalid_language_pair`).

`de` is not a target: only the administrator knows whether your site means Germany, Austria or Switzerland. Let the administrator map each CMS language (a combo box per language from `Languages.TargetOptions()`, `Languages.SuggestTarget(code)` preselected) and resolve it before you call the client. A language nobody mapped uses its default where one is obvious (`de` → `de-DE`, `en` → `en-US`, `zh` → `zh-Hans`, `zh-TW` → `zh-Hant`, `es-ES` → `es`; `Languages.DefaultTargets`); anything else (`en-IE`, `gsw`) is `unsupported_language`:

```csharp
// The integration's language setting: CMS language → Supertext code.
var mapping = new Dictionary<string, string> { ["de"] = "de-CH", ["no"] = "nb" };

var target = Languages.Target(cmsLanguage, mapping);  // "de" → "de-CH", "fr_ch" → "fr-CH" (exact match), "it" → "it-IT" (default), "en-IE" → unsupported_language
var source = Languages.Source(cmsSource, mapping);    // "en-US" → "en", "" → "" (auto-detect)
await client.TranslateTextsAsync(texts, target, source);

Languages.MatchTarget("de_ch");          // "de-CH" (case and _/- don't matter); "de" → null
Languages.MatchSource("en-GB");          // "en" (exact, else the first subtag)
Languages.DefaultTarget("es-ES");        // "es" (null without a default or with an exact match)
Languages.SuggestTarget("de");           // "de-DE": exact match, else the default, else null (preselect it)
Languages.TargetOptions();               // Targets sorted by name, for the combo box
Languages.SupportsPoliteness("en-US");   // false (true for de-CH; null for a code not in the list)
Languages.IsSupportedTarget("es-ES");    // false
Languages.IsSupportedSource("gsw");      // true
```

`Target` returns the mapping's value (trimmed, sent even if it isn't in the list, so a language Supertext adds later works before the SDK knows it), else an exact match, else the default (`DefaultTarget`), else throws `SupertextException` with code `unsupported_language` and `TargetLang` set; nothing is sent. `Source` works the same way (`SourceLang` set), and an empty source stays empty. Mapping keys are compared like codes (case, `_`/`-` and spaces don't matter); blank values are ignored. Use `SuggestTarget` to preselect each CMS language in the combo box; show the name in the administrator's language where you can (`new CultureInfo(code).DisplayName` under the UI culture, or your CMS's own names) and the code next to it.

**The live list.** `GetFeaturesAsync()` returns the language pairs the API offers right now (`GET features`, no cost) as `FeatureSupport(SourceLang, TargetLang, Features)`, with features such as `supports_politeness`, e.g. for a settings page. Malformed entries are skipped.

## Politeness

`Politeness.Default` (nothing is sent), `Politeness.More` (formal: Sie, vous, Lei) or `Politeness.Less` (informal: du, tu). Not every target offers it (`TargetLanguage.Politeness`, `Languages.SupportsPoliteness`): for `en-US`, `rm`, `sq` and others the API answers `unsupported_feature`. Hide the formal/informal choice for those targets; the SDK sends what it is given.

## Errors

Everything the API or the client can't handle is a `SupertextException` with `Code` (constants in `SupertextErrorCodes`), an English `Message`, and `Status`, `Detail`, `ApiErrorCode`, `SourceLang`/`TargetLang` and `Feature` where known. The API key never appears in messages or details. Cancelling your token throws `OperationCanceledException` as usual.

| Code | Meaning | What to tell the user |
| --- | --- | --- |
| `missing_api_key` | No key configured; nothing was sent | Enter the API key in the settings (with the account and API-key links above) |
| `authentication_failure` | HTTP 401/403 | The API key is wrong or revoked: check it (links above) |
| `invalid_language_pair` | HTTP 400 (404 for an unknown language) `INVALID_LANGUAGE_PAIR` | Supertext doesn't support this language combination; check the language mapping |
| `unsupported_language` | No request was sent: the language has no Supertext code (no mapping, no exact match, no default) or the target is empty | Map this language to a Supertext language in the settings |
| `unsupported_feature` | HTTP 400 `FEATURE_ERROR`: the pair exists, but not with this option (`Feature`, e.g. `politeness`) | Formal/informal isn't available for this language; choose the default |
| `not_found` | HTTP 404 | The file expired or was deleted; try again |
| `payload_too_large` | HTTP 413 | The content is too large; translate less at once |
| `too_many_requests` | HTTP 429 after all retries | Too many requests; try again in a moment |
| `service_unavailable` | HTTP 5xx | Supertext is unavailable; try again later |
| `unexpected_status` | Any other HTTP status | Unexpected answer from Supertext (show `Status`/`Detail`) |
| `transport_error` | No answer (DNS, TLS, refused, request timeout) | Supertext can't be reached; check the server's network |
| `no_file_id` | Upload answered without a file id | Unexpected answer from Supertext; try again |
| `translation_error` | File status `error` | Supertext couldn't translate this content |
| `quota_exceeded` | File status `limit_exceeded` | The account's translation limit is used up |
| `file_deleted` | File status `deleted` | The file was deleted before download; try again |
| `timeout` | Not done within `Timeout` | The translation is taking too long; try again or translate less at once |
| `incomplete_response` | Empty download or missing texts (`Detail`: `missing: key1, key2`) | The translation came back incomplete; try again |

## Corrections from reviewers

With Supertext Localization Review, point the client at its review service (`BaseUrl`) and fetch the corrections reviewers made, for content you don't translate again. Use your own `data-st-id`s (build the document yourself, `TranslateHtmlAsync`) so `SegmentId` tells you the field; see the integration guide → *Corrections from reviewers*.

```csharp
var corrections = await client.PendingCorrectionsAsync("de-CH");  // all pages
// write c.Html where c.SegmentId points, if the field still holds c.PreviousHtml
await client.AcknowledgeCorrectionsAsync(corrections);            // → number accepted
```

Against Supertext's API these throw `not_found`.

## API key check

`CheckApiKeyAsync()` calls `GET features` (no cost, at most `ApiKeyCheck.Timeout` = 10 s, the usual 429 retries) and never throws (test vectors: `spec/api-key-check.json`). It returns an `ApiKeyCheck` (`Status`: `ok`, `invalid` (401/403), `missing` (blank key, nothing sent), `unreachable` (no answer or 5xx), `error`; plus `CheckedAt`, `HttpStatus`, `ErrorCode` and `Fingerprint`, a SHA-256 of the key from `ApiKeyCheck.FingerprintOf(key)` to notice a changed one; the key itself is never kept). Show it on your settings page. If it says `invalid` or `missing`: no Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

Version reports to the Supertext Commando Center and its ping endpoint are [Supertext Plugin Health](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health), not this SDK. Pass it `CheckApiKeyAsync()` and `ApiKeyCheck.FingerprintOf` as the key checker and `SdkInfo.Version` as the SDK version.

## Options

`SupertextClientOptions` (all optional):

| Option | Default | |
| --- | --- | --- |
| `BaseUrl` | `https://api.supertext.com/v1/` | A trailing slash is added if missing |
| `PollInterval` | 2 s | Wait between status polls |
| `Timeout` | 180 s | Give up waiting for a file |
| `RequestTimeout` | 30 s | Per HTTP request, including reading the answer |
| `MaxRetries` | 4 | Retries after HTTP 429 (`Retry-After` in seconds, else 1/2/4/8/16 s … with jitter; max. 30 s) |
| `UserAgent` | none | Appended to `supertext-sdk-dotnet`, e.g. `My-CMS-Plugin/1.0.0` |
| `HttpClient` | shared internal client | E.g. from `IHttpClientFactory`; its `Timeout` should be ≥ `RequestTimeout` |
| `Sleep` | `Task.Delay` | Replace in tests |
| `TimeProvider` | `TimeProvider.System` | Clock for the polling timeout and the key check's `CheckedAt`; replace in tests |

Also public: `SupertextClient.AuthHeader(key)`, `SupertextClient.RetryDelayMs(attempt, retryAfter)`, `HtmlDocument.Build` / `Parse` if you build documents yourself.

## Translation files

For texts translated outside the API (a CAT tool, a translator, Supertext's web app), `Supertext.Sdk.Files.TranslationFiles` exports segments as an XLIFF 1.2 file (one `<file>` per job) or as an HTML file (the `HtmlDocument` document with the job's metadata in its head), and imports them again. It isn't tied to an API version and makes no requests (test vectors: `spec/translation-file.json`).

```csharp
using Supertext.Sdk.Files;

// Export: one job per item and language pair. Reference is whatever you need to find the item again.
var job = new FileJob("My-CMS-Plugin", "page:12", "de", "fr-CH", [
    new FileSegment("title", "Käse & Brot"),
    new FileSegment("body", "<p>Ein <b>fetter</b> Text.</p>", Html: true),
]);
string xliff = TranslationFiles.ExportXliff([job]);   // or TranslationFiles.ExportHtml(job)

// Later: read the uploaded file, find each item by Reference, collect its current segments again, import.
foreach (var file in TranslationFiles.Read(uploaded))
{
    var current = CollectSegments(file.Reference);   // your code: the item's current FileSegments
    foreach (var s in TranslationFiles.ImportTranslations(file, current))
    {
        if (s.Status == ImportStatuses.Ok) Save(file.Reference, file.TargetLanguage, s.Key, s.Text!);
        else if (s.Status == ImportStatuses.SourceChanged) AskOrSave(s);   // the source changed since the export: you decide
        else Report(s.Key, s.Status);                                      // untranslated, invalid_markup, missing
    }
}
```

`ImportTranslations` returns one `ImportedSegment(Key, Status, Text)` per non-blank current segment, in their order; `Text` is set for `ok` and `source_changed` only. Statuses (`ImportStatuses`): `ok`, `source_changed` (the checksum in the file differs from the current source's, or the file has none), `untranslated`, `invalid_markup` (the translation uses markup the current source doesn't have), `missing` (no such key in the file). Errors are a `TranslationFileException` with `Code` (`TranslationFileErrorCodes`): `invalid_job` (export: no jobs, empty or control characters in metadata or keys, duplicate keys, only blank segments), `invalid_file`, `missing_metadata`, `too_large`.

Imported files are untrusted: `Read` refuses DTDs (a DOCTYPE or any `<!` declaration is never expanded) and files over `TranslationFiles.MaxFileCharacters` (10,000,000). Markup comes only from your current source: XLIFF codes are replaced by the source's tags, and HTML translations may only use the source's tags, attributes and comments, with safe URLs (relative, http, https, mailto, tel).

## Testing against the stand-in

The repository's stand-in API (`stand-in/stand-in.mjs`, Node 22+) behaves like the live API where integrations went wrong before, and prefixes each translated segment with the target code (`[de-CH]`):

```sh
node stand-in/stand-in.mjs          # http://127.0.0.1:8765/v1/
```

Point `BaseUrl` at it. For unit tests, pass an `HttpClient` with your own `HttpMessageHandler`, plus `Sleep` and `TimeProvider` fakes (see `tests/`).

## Development

From the repository root:

```sh
dotnet build sdk/dotnet -warnaserror
dotnet test sdk/dotnet                       # unit tests + spec/*.json vectors
dotnet build sdk/dotnet/examples/Conformance -c Release
node stand-in/conformance.mjs -- dotnet sdk/dotnet/examples/Conformance/bin/Release/net10.0/Conformance.dll
dotnet pack sdk/dotnet/src/Supertext.Sdk -c Release -o sdk/dotnet/artifacts
```

## License

MIT
