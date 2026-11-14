# Supertext SDK for Kotlin

Client for the Supertext AI translation API in idiomatic Kotlin: translate HTML and texts, with the supported languages, rate-limit retries and HTML packing handled for you.

Kotlin/JVM on Java 11+. The only runtime dependency is the Kotlin standard library; HTTP goes through the JDK's `java.net.http.HttpClient`. For the protocol and the lessons behind it, see the [integration guide](../../docs/integration-guide.md). Working in Java? Use the [Java SDK](../java/README.md); this one is built for Kotlin (named and default arguments, nullable types, `kotlin.time.Duration`, data classes).

## Install

```kotlin
// build.gradle.kts
implementation("com.supertext:supertext-sdk-kotlin:0.6.0")
```

```xml
<dependency>
  <groupId>com.supertext</groupId>
  <artifactId>supertext-sdk-kotlin</artifactId>
  <version>0.6.0</version>
</dependency>
```

The package isn't on Maven Central yet; until it is, use the jar attached to a [release](https://github.com/Supertext/Supertext-API-SDK-Guide/releases) or build it from `sdk/kotlin` (`mvn package`).

Everything that talks to the API is versioned: API v1 lives in the package `com.supertext.sdk.v1`. A later API version gets `com.supertext.sdk.v2` next to it, so an integration can move at its own pace. Version-neutral parts stay in `com.supertext.sdk`: `SDK_VERSION` (this SDK's version) and the HTTP layer (`Transport`, `JdkHttpTransport`).

## API key

No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

Pass the key as administrators paste it: a leading `Supertext-Auth-Key ` is stripped. Keep it out of your code, e.g. in `SUPERTEXT_API_KEY`.

## Quick start

```kotlin
import com.supertext.sdk.v1.*

val client = SupertextClient(System.getenv("SUPERTEXT_API_KEY"), userAgent = "MyCms-Supertext/1.0.0")

// Texts: packed into one HTML document (or several, if large), read back by key.
val german = client.translateTexts(
    mapOf(
        "title" to Segment.text("Hello & welcome"),
        "body" to Segment.html("<p>Our <b>new</b> shop opens on Monday.</p>"),
    ),
    targetLang = "de-CH",
    sourceLang = "en",
    politeness = Politeness.MORE,
)
german["title"] // "Hallo und willkommen"

// A whole HTML document: upload, wait, download, delete.
val html = client.translateHtml("<p data-st-id=\"1\">Good morning</p>", "fr-CH", "en")
```

Plain-text segments are escaped and keep their line breaks; HTML segments are sent as they are. Keep a whole paragraph in **one** segment with formatting as inline tags (`<b>`, `<i>`, `<a href>`): Supertext translates every segment on its own, so a segment per formatted run breaks sentences apart. Blank texts come back unchanged and aren't sent. The result keeps the order of `segments` (`mapOf` and `linkedMapOf` keep insertion order).

The calls block and throw `InterruptedException` when the thread is interrupted; the uploaded file is still deleted. From coroutines, run them on an IO dispatcher (`withContext(Dispatchers.IO) { client.translateTexts(…) }`). Documents are translated one after the other (the API limits requests per second).

Lower-level calls: `validateApiKey()` (free check of the key; throws), `checkApiKey()` (never throws, see below), `getFeatures()` (see *Languages*), `submit`, `getStatus`, `waitUntilDone`, `download`, `delete` (never throws).

## Languages

The client sends language codes **as given** (trimmed); it doesn't convert them. An empty target is `unsupported_language` without a request; an empty source is left out (auto-detect).

Supertext's codes are fixed: sources are bare (`en`, `de`, `gsw`), targets are what Supertext expects exactly: regional for German, English, French, Italian and Portuguese (`de-CH`, `en-US`, `pt-BR`), a script for Chinese and Serbian (`zh-Hans`, `sr-Latn`), a dialect for Swiss German (`gsw-u-sd-chzh`), bare for the others (`es`, `nl`, `sl`). `Languages.SOURCES` and `Languages.TARGETS` list them (`SourceLanguage`/`TargetLanguage`: `code`, `name`, and for targets `supportsPoliteness`). A CMS language that equals a Supertext code (apart from case, `_`/`-` and spaces) is used as it is; a language nobody mapped uses its default where one is obvious (`de` → `de-DE`, `en` → `en-US`, `zh` → `zh-Hans`, `zh-TW` → `zh-Hant`, `es-ES` → `es`; `Languages.DEFAULT_TARGETS`); anything else (`en-IE`, `gsw`) needs a mapping: a combo box per language (`Languages.targetOptions()`, `Languages.suggestTarget(code)` preselected) that the administrator can change.

```kotlin
val mapping = mapOf("de" to "de-CH", "no" to "nb") // CMS language → Supertext code (your setting)

Languages.target("de_ch")             // "de-CH" (exact match)
Languages.target("de", mapping)       // "de-CH" (mapped)
Languages.target("de")                // "de-DE" (default)
Languages.target("en-IE")             // throws SupertextException unsupported_language, targetLang "en-IE": ask for a mapping
Languages.source("en-US")             // "en" (a source is the first subtag)
Languages.source("")                  // "" (auto-detect)

Languages.matchTarget("de")           // null: no exact match
Languages.defaultTarget("es-ES")      // "es" (null without a default or with an exact match)
Languages.suggestTarget("de_ch")      // "de-CH": exact match, else the default, else null (preselect it; null: "please choose")
Languages.targetOptions()             // TARGETS sorted by name, for the combo box
Languages.matchSource("de_DE_formal") // "de"
Languages.isSupportedTarget("es")     // true
Languages.isSupportedSource("xx")     // false
Languages.supportsPoliteness("en-US") // false (null for an unknown code): hide the formal/informal choice

client.translateTexts(segments, Languages.target(cmsLocale, mapping), Languages.source(cmsSourceLocale, mapping))
```

A mapping value is sent trimmed and otherwise unchanged, even if it isn't in `TARGETS`, so a language Supertext adds later works before the SDK knows it. In the combo box, show each name in the administrator's language where you can (`Locale.forLanguageTag(code).getDisplayName(uiLocale)`) and the code next to it.

**Live list.** `client.getFeatures()` returns the language pairs the API offers right now (`GET features`, free of charge) as `FeatureSupport` (`sourceLang`, `targetLang`, `features`, e.g. `supports_politeness`, `supports_glossary`). Malformed entries are skipped. Use it for a settings page's language choice; `Languages` is the list as of this SDK version.

## Politeness

`Politeness.DEFAULT` (not sent), `MORE` (formal: Sie, vous, Lei) or `LESS` (informal: du, tu). `Politeness.fromValue("more")` parses settings; any other value (including `default` and `null`) gives `DEFAULT`, so nothing is sent. Not every target offers it (`Languages.supportsPoliteness`); for those the API answers `unsupported_feature`, so hide the choice there. The SDK sends what it is given.

## Errors

Every failure is a `SupertextException` with `code` (`ErrorCode`), `status` (HTTP status or `null`), `detail`, `apiErrorCode`, for language errors `sourceLang`/`targetLang` and, for `unsupported_feature`, `feature` (e.g. `politeness`). The message is English, for logs: the code's message (`ErrorCode.message`, the same in every Supertext SDK), plus ` HTTP <status>.` and ` Details: <detail>` when known; it never contains the API key. `ErrorCode.value` gives the string shared by all Supertext SDKs.

```kotlin
try {
    client.translateTexts(segments, target)
} catch (e: SupertextException) {
    when (e.code) {
        ErrorCode.AUTHENTICATION_FAILURE, ErrorCode.MISSING_API_KEY -> showKeyLinks()
        ErrorCode.UNSUPPORTED_LANGUAGE -> askForMapping(e.targetLang ?: e.sourceLang)
        else -> showError(e.code.value, e.detail)
    }
}
```

| Code | Tell the user |
| --- | --- |
| `missing_api_key` | No API key configured (add the account and key links above). Nothing was sent. |
| `authentication_failure` | The API key was rejected: check it, or generate a new one (links above). |
| `invalid_language_pair` | Supertext doesn't support this language combination (HTTP 400 or 404). Check the language settings. |
| `unsupported_language` | This language has no Supertext code: map it to a Supertext language in the settings. Nothing was sent. |
| `unsupported_feature` | Supertext doesn't offer this option (`feature`, e.g. formal/informal) for the language pair. |
| `not_found` | The translation job no longer exists. Try again. |
| `payload_too_large` | The content is too large to translate in one go. |
| `too_many_requests` | Supertext is busy. Try again in a moment. |
| `service_unavailable` | Supertext is temporarily unavailable. Try again later. |
| `unexpected_status` | Supertext answered unexpectedly (HTTP status in the details). |
| `transport_error` | Supertext couldn't be reached (network, DNS, TLS, timeout). |
| `no_file_id` | Supertext didn't accept the document. Try again. |
| `translation_error` | Supertext couldn't translate the content. |
| `quota_exceeded` | The Supertext account's translation limit is used up. |
| `file_deleted` | The translation was deleted before it could be fetched. Try again. |
| `timeout` | The translation took too long. Try again, or raise the timeout. |
| `incomplete_response` | The translation came back incomplete. Try again. |

## Corrections from reviewers

With Supertext Localization Review, point the client at its review service (`baseUrl`) and fetch the corrections reviewers made, for content you don't translate again. Use your own `data-st-id`s (build the document yourself, `translateHtml`) so `segmentId` tells you the field; see the integration guide → *Corrections from reviewers*.

```kotlin
val corrections = client.pendingCorrections(targetLang = "de-CH") // all pages
// write c.html where c.segmentId points, if the field still holds c.previousHtml
client.acknowledgeCorrections(corrections)                      // → Int accepted
```

Against Supertext's API these throw `NOT_FOUND`.

## API key check

Behaviour shared by all Supertext SDKs: `spec/api-key-check.json`.

`client.checkApiKey()` calls `GET features` (at most `ApiKeyCheck.TIMEOUT` = 10 s, the usual 429 retries) and never throws. It returns an `ApiKeyCheck`: `status` (`OK` 2xx, `INVALID` 401/403, `MISSING` blank key, nothing sent, `UNREACHABLE` no answer or 5xx, `ERROR` anything else), `checkedAt` (`2026-10-13T08:00:00Z`), `httpStatus`, `errorCode` and `fingerprint` (SHA-256 of the key, to notice a changed key; `ApiKeyCheck.fingerprint(key)`). Show the result on the settings page. If it is `INVALID` or `MISSING`, add the links: no Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

Version reports to the Supertext Commando Center and its ping endpoint are [Supertext Plugin Health](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health), not this SDK. Pass it `checkApiKey()` and `ApiKeyCheck::fingerprint` as the key checker and `com.supertext.sdk.SDK_VERSION` as the SDK version.

## Options

```kotlin
SupertextClient(
    apiKey,
    baseUrl = SupertextClient.LIVE_BASE_URL,  // e.g. a stand-in for tests
    pollInterval = 2.seconds,                  // between status polls
    timeout = 180.seconds,                     // per document
    requestTimeout = 30.seconds,               // per HTTP request → transport_error
    maxRetries = 4,                            // after HTTP 429 (Retry-After in seconds, else 1/2/4/8 s + jitter; at most 30 s)
    userAgent = "MyCms-Supertext/1.0.0",       // appended to "supertext-sdk-kotlin"
    transport = JdkHttpTransport(myHttpClient), // or your own Transport (tests, OkHttp, Ktor, …)
    sleeper = { Thread.sleep(it.inWholeMilliseconds) }, // replace in tests
    nanoTime = System::nanoTime,               // replace in tests
    wallClock = Clock.systemUTC(),             // checkedAt of checkApiKey(); replace in tests
)
```

`translateTexts(segments, target, source, politeness, maxCharacters)` sets the document size (default `SupertextClient.DEFAULT_MAX_CHARACTERS`, 900,000). Durations must be positive and `maxRetries` not negative (`IllegalArgumentException`); a blank key doesn't fail here, the first request throws `missing_api_key` without sending anything.

## Translation files

For texts translated outside the API (a CAT tool, a translator, Supertext's web app), `com.supertext.sdk.files.TranslationFiles` exports segments as an XLIFF 1.2 file (one `<file>` per job) or as the HTML document `HtmlDocument.build` makes, with the job's metadata, and imports the translated file again. Not tied to an API version: nothing here calls the API. Behaviour shared by all Supertext SDKs: `spec/translation-file.json`.

```kotlin
// Export: one job per item and target language; reference is whatever finds the item again.
val job = FileJob(
    "MyCms-Supertext-Translation", "page:12", "de-CH", "fr-CH",
    listOf(FileSegment("title", page.title), FileSegment("body", page.bodyHtml, html = true)),
)
val xliff = TranslationFiles.exportXliff(listOf(job)) // or TranslationFiles.exportHtml(job)

// Later, with the translated file the user uploaded:
for (file in TranslationFiles.read(uploaded)) {
    val page = pages.find(file.reference)
    val current = page.segments() // collected again, the same way as for the export
    for (s in TranslationFiles.importTranslations(file, current)) {
        when (s.status) {
            ImportStatus.OK -> page.setTranslation(file.targetLanguage, s.key, s.text!!)
            ImportStatus.SOURCE_CHANGED -> report(s, "the source changed since the export") // or write it anyway: your decision
            else -> report(s, s.status.value) // untranslated, invalid_markup, missing
        }
    }
}
```

`checksum(segment)` (FNV-1a of the packed segment) only detects changed sources: `SOURCE_CHANGED` means the text was translated, but the source differs from the export (or the file has no checksum). Errors are a `TranslationFileException` with `code` (`TranslationFileErrorCode`: `invalid_job` when exporting; `invalid_file`, `missing_metadata`, `too_large` when reading).

Uploaded files are untrusted: `read` refuses DTDs and other `<!` declarations (no entity is ever expanded) and files over `TranslationFiles.MAX_FILE_CHARACTERS` (10,000,000 characters), and `importTranslations` takes markup only from the current source: XLIFF codes are replaced by the source's tags, and HTML translations may only use the source's tags, attributes and comments, with safe URLs (relative, http, https, mailto, tel).

## Testing against the stand-in

The repository's stand-in behaves like the live API where integrations went wrong (auth header, language codes, politeness, `text/html`, 429):

```sh
node stand-in/stand-in.mjs            # from the repo root; prints http://127.0.0.1:8765/v1/
```

```kotlin
val client = SupertextClient("test-key", baseUrl = "http://127.0.0.1:8765/v1/")
```

Translations come back with the target code in front of each segment (`[de-CH] …`).

## Development

```sh
cd sdk/kotlin
mvn -B verify      # compile (JVM 11, explicit API, warnings as errors), unit tests incl. spec/*.json
node ../../stand-in/conformance.mjs -- java -cp 'target/classes:target/test-classes:target/lib/*' ConformanceKt
```

The tests read the shared vectors from `../../spec/`. The conformance program (`examples/Conformance.kt`) is compiled with the tests, never into the jar. Licence: MIT.
