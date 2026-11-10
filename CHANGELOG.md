# Changelog

All SDKs in this repository share one version. Format: one section per release, newest first; changes not yet released go under *Unreleased*.

## Unreleased

### Added

- **Kotlin SDK** (`sdk/kotlin`, Maven `com.supertext:supertext-sdk-kotlin`, Kotlin/JVM on Java 11+): the sixth SDK, with everything the others offer in the same packages as Java (`com.supertext.sdk.v1`, `com.supertext.sdk.files`) and Kotlin names: `SupertextClient(apiKey, baseUrl = …, pollInterval = 2.seconds, …)` with named and default arguments instead of a builder, `kotlin.time.Duration` for timings, properties instead of getters (`e.code`, `e.targetLang`, `check.status`), data classes for `Segment`, `SourceLanguage`, `TargetLanguage`, `FeatureSupport`, `FileSegment`, `ImportedSegment`, and `SDK_VERSION`. Passes every vector in `spec/` and the conformance scenarios; the only runtime dependency is the Kotlin standard library. Calls block (run them on an IO dispatcher from coroutines). Tested in CI on Java 11, 17, 21 and 25; the release attaches its jar.
- **Corrections from reviewers** (all six SDKs, `spec/corrections.json`, integration guide → *Corrections from reviewers*, API reference → *Review service*): with Supertext Localization Review, `pendingCorrections(targetLang?, sourceLang?, politeness?)` fetches the corrections the integration hasn't written into the CMS yet (all pages; `Correction` with `id`, `segmentId` = the `data-st-id` sent, `sourceLang`, `targetLang`, `politeness`, `sourceHtml`, `previousHtml`, `html`, `status`, `correctedAt`, `version`) and `acknowledgeCorrections(corrections)` confirms the ones written (batches of 1000; returns the number accepted). Only the review service answers them; against Supertext's API they throw `not_found`. TypeScript `pendingCorrections`/`acknowledgeCorrections`, PHP `pendingCorrections()`/`acknowledgeCorrections()`, Python `pending_corrections`/`acknowledge_corrections`, .NET `PendingCorrectionsAsync`/`AcknowledgeCorrectionsAsync`, Java and Kotlin `pendingCorrections`/`acknowledgeCorrections`.

### Changed

- The repository is now `Supertext/Supertext-API-SDK-Guide` (was `Supertext-API-SDK-and-Guide`); package metadata, links and docs point to the new name (GitHub redirects the old one).
- Tested in CI on Node 26, Python 3.14 and 3.15, Java 25 and .NET 10, in addition to the versions tested before. TypeScript SDK built and type-checked with TypeScript 7.0, the native compiler (was 5.9); the published JavaScript is unchanged (the type declarations only use single quotes now).

- PHP SDK: tested in CI on PHP 8.5. No more `curl_close()` (deprecated in PHP 8.5, without effect since PHP 8.0), and the UTF-8 encoding helpers pass `chr()` values PHPStan can prove are bytes; behaviour unchanged.

- Integration guide → *Translation files*: Supertext's web app keeps an HTML file's head and `data-st-id` attributes (confirmed), so the translated file imports as it is.

### Removed

- PHP SDK: support for PHP 8.1 (end of life since 31 December 2025). Requires PHP 8.2+.
- Python SDK: support for Python 3.10 (end of life since October 2026). Requires Python 3.11+ (uses `datetime.UTC`).
- TypeScript SDK: support for Node 18 (end of life since 30 April 2025) and Node 20 (end of life since 30 April 2026). Requires Node 22+ (`engines`).
- .NET SDK: support for .NET 8 and .NET 9 (end of life on 10 November 2026). The package now targets `net10.0`.
- The Java SDK keeps Java 11 (still supported by Eclipse Temurin and other vendors).

## 0.5.0 — 2026-10-18

New: translation files, an alternative to the API for integrations. Nothing changes for existing API use.

### Added

- **Translation files** (all five SDKs, `spec/translation-file.json`, integration guide → *Translation files: XLIFF and HTML*): export an item's segments as **XLIFF 1.2** (`exportXliff(jobs)`, one `<file>` per job, formatting as `<bpt>`/`<ept>`/`<ph>` inline codes) or **HTML** (`exportHtml(job)`, the API's document with the job's metadata in its head), translate them outside the API (a CAT tool, a translator, Supertext's web app), then `read(content)` the file and `importTranslations(file, currentSegments)`: per segment `ok`, `source_changed` (checksum differs from the export), `untranslated`, `invalid_markup` or `missing`. Imported files are treated as untrusted: size limit, no DTDs, markup only from the current source (XLIFF codes replaced by the source's tags; HTML only with the source's tags and attributes and safe links). Not versioned with the API: TypeScript `TranslationFiles` from `supertext-sdk`, PHP `Supertext\Sdk\Files\TranslationFiles`, Python `supertext.files`, .NET `Supertext.Sdk.Files.TranslationFiles`, Java `com.supertext.sdk.files.TranslationFiles`; errors `invalid_job`, `invalid_file`, `missing_metadata`, `too_large`.

## 0.4.0 — 2026-10-17

Behaviour change for integrations: a target language nobody mapped now uses its default where one is obvious, instead of failing.

### Added

- **Default targets** in `Languages` (all five SDKs, `spec/languages.json` → `defaultTargets`, `defaultTarget`, `suggestTarget`, `targetOptions`): `de` → `de-DE`, `en` → `en-US`, `fr` → `fr-FR`, `it` → `it-IT`, `pt` → `pt-PT`, `zh` → `zh-Hans`, `sr` → `sr-Latn`; `zh-TW`/`zh-HK`/`zh-MO` → `zh-Hant`, `zh-CN`/`zh-SG` → `zh-Hans`; `language-Script-REGION` → `language-Script` (`sr-Latn-RS` → `sr-Latn`); a region of a bare target → the bare target (`es-ES`, `es-419` → `es`, `nl_NL` → `nl`, `nb-NO` → `nb`). Nothing else is guessed: `de-LI`, `en-IE`, `fr-BE`, `sr-RS`, `gsw`, `no` and `de_DE_formal` still need a mapping.
- `defaultTarget(code)` (the default, or null for a code with an exact match or without a default), `suggestTarget(code)` (exact match, else default, else null: what a settings screen preselects), `targetOptions()` (the targets sorted by English name, for the language combo box) and the `DEFAULT_TARGETS` table. TypeScript `Languages.defaultTarget`/`suggestTarget`/`targetOptions`/`DEFAULT_TARGETS` (also as named exports); PHP `Languages::defaultTarget()`/`suggestTarget()`/`targetOptions()`/`DEFAULT_TARGETS`; Python `Languages.default_target`/`suggest_target`/`target_options`/`DEFAULT_TARGETS`; .NET `Languages.DefaultTarget`/`SuggestTarget`/`TargetOptions()`/`DefaultTargets`; Java `Languages.defaultTarget`/`suggestTarget`/`targetOptions()`/`DEFAULT_TARGETS`.

### Changed

- `Languages.target(code, mapping)`: the mapping, else the exact match, else **the default**, else `unsupported_language`. `Languages.target('de')` is now `de-DE` (was `unsupported_language`), `es-ES` is `es`. `matchTarget` stays exact-only; sources are unchanged.
- Integration guide → *Language codes*: the mapping is a combo box per CMS language with Supertext's codes, the suggested code preselected, names localized with the platform's display-name facility and the code shown next to them; the defaults table; an environment or config mapping overrides the defaults.

## 0.3.0 — 2026-10-16

Breaking change for plugins that use the version reports: the Commando Center support from 0.1.0 moved out of the SDK.

### Removed

- **Version reports, the reporting setting and the ping endpoint** (`InstallationReporter`, `ReporterState`/`reporterState`, `ReporterResult`, `ReporterOptions`, `ReportEvent`, `NameVersion`, `reportsEnabled`, `pingResponse`/`PingResponse`, the report URL, timeout and backoff constants) in all five SDKs, with `spec/commando/` and the reporter parts of `spec/reporting.json`. They are now **Supertext Plugin Health**, which lives next to the center in the Commando Center repository (`Supertext/Commando-Center-Supertext-Translation`, `plugin-health/`), so the protocol, the center and the client change together. Plugins vendor Plugin Health like the SDK and pass the SDK's key check in as its key checker: `checkApiKey()` plus `apiKeyFingerprint()` (TypeScript; PHP `Client::checkApiKey()` and `Protocol::apiKeyFingerprint()`, Python `check_api_key()` and `api_key_fingerprint()`, .NET `CheckApiKeyAsync()` and `ApiKeyCheck.FingerprintOf()`, Java `checkApiKey()` and `ApiKeyCheck.fingerprint()`), and the SDK version constant as `sdkVersion`. The reporter state plugins have stored stays valid: Plugin Health reads the same JSON, so no new `installed` report.

### Changed

- The API key check stays in the SDK, unchanged (`checkApiKey()`, `ApiKeyCheck` with `status`, `checkedAt`, `httpStatus`, `errorCode`, `fingerprint`, the fingerprint function, `SDK_VERSION`). Its vectors moved from `spec/reporting.json` to `spec/api-key-check.json`.
- .NET: the key check's timeout is `ApiKeyCheck.Timeout` (was `Reporting.KeyCheckTimeout`); Java: `ApiKeyCheck.TIMEOUT` (was `Reporting.KEY_CHECK_TIMEOUT`).

## 0.2.1 — 2026-10-14

### Fixed

- `Languages.source(code, mapping)`: a mapped value is sent in its source form, so one mapping serves both directions. With `{"en": "en-US"}` (needed because `en` isn't a target), English as the source language was sent as `en-US` and rejected; it is now `en`. A mapped value the list doesn't know is still sent as it is.

## 0.2.0 — 2026-10-13

Breaking changes for integrations: the namespace and the language codes.

### Changed

- **Versioned API namespace** in all SDKs: everything that talks to the API moved to `v1` (TypeScript `supertext-sdk/v1` or `import { v1 } from 'supertext-sdk'`, PHP `Supertext\Sdk\V1`, Python `supertext.v1`, .NET `Supertext.Sdk.V1`, Java `com.supertext.sdk.v1`), so a later API version can be offered next to it. The SDK version constant (`SDK_VERSION`, PHP `Supertext::SDK_VERSION`, .NET `SdkInfo.Version`, Java `SdkInfo.VERSION`) and the generic HTTP layer stay outside. Python's client class is now `SupertextClient` like the others.
- **No more language-code guessing.** `LanguageCodes` (default regions, normalizing, variant stripping) is replaced by `Languages`: the API's 28 source and 37 target languages from the live `GET features` (`spec/languages.json`), with names and whether a target offers formal/informal. A CMS code is resolved by the integration's mapping, else an exact match (case and `_`/`-` ignored: `de_CH` → `de-CH`), else the new error `unsupported_language`; nothing is sent then. The client sends the codes it is given (trimmed). The old default regions were wrong for most languages: Supertext wants bare `es`, `nl`, `sl`, `cs`, … (not `es-ES`, `sl-SI`), `zh-Hans`/`zh-Hant` and `sr-Latn`/`sr-Cyrl`, and doesn't offer ar, et, lt, lv, ro, th, uk or vi at all.

### Added

- `getFeatures()` on the client: the language pairs the API offers right now, with their features.
- Error `unsupported_feature` (HTTP 400 `FEATURE_ERROR`, with `feature`): e.g. politeness for `en-US`, `rm`, `sq`, `sr-*` or `gsw-*`. `Languages.supportsPoliteness()` tells integrations where to offer formal/informal.
- The stand-in accepts exactly the live language list, answers unknown pairs with HTTP 404 like the live API, rejects politeness where it isn't offered, and lists the pairs on `GET features`.

### Fixed

- `invalid_language_pair` is documented with HTTP 404, which the live API now answers for every pair it doesn't offer.

## 0.1.0 — 2026-10-13

### Changed

- Language codes: locale variants and extensions are dropped (`de_DE_formal` → `de-DE`, `en-US-x-twain` → `en-US`); WordPress's formal/informal locales no longer reach Supertext as an unknown code.
- TypeScript: test tooling moved to Vitest 5, which fixes critical advisories in Vitest's tinypool dependency (development only; the package has no dependencies). Working on the SDK needs Node 22.12+; the package still runs on Node 18+, checked in CI with the conformance scenarios.
- PHP: ext-curl is no longer required. Only the built-in `CurlTransport` needs it; integrations that pass their own `HttpTransport` (e.g. Contao with Symfony HttpClient) install and run without curl. Without curl and without a transport, the client says so when it's created.

### Fixed

- All SDKs: an unknown language (e.g. target `xx-YY`) is reported as `invalid_language_pair`, with the codes the API names. The live API answers it with HTTP 404 and `error_code` `INVALID_LANGUAGE_PAIR`; the SDKs only recognised HTTP 400 and reported `not_found`.

### Added

- Integration guide (`docs/integration-guide.md`) and API reference (`docs/api-reference.md`): the AI file translation protocol and the lessons from Supertext's CMS, PIM and shop plugins.
- SDKs for TypeScript (`supertext-sdk` on npm), PHP (`supertext/supertext-sdk`), Python (`supertext-sdk`), .NET (`Supertext.Sdk`) and Java (`com.supertext:supertext-sdk`): translate texts and HTML with language codes, the auth header, HTTP 429 retries, polling, cleanup and HTML packing handled.
- Shared test vectors (`spec/`), a stand-in API (`stand-in/stand-in.mjs`) and conformance scenarios every SDK passes in CI.
- All SDKs: Commando Center support, so plugins don't implement it themselves (`spec/reporting.json`, `docs/integration-guide.md` → *Version reports*):
  - `checkApiKey()` on the client: the automatic API key check (`GET features`, 10 s), `ok`/`invalid`/`missing`/`unreachable`/`error` with HTTP status, Supertext's error code and a local key fingerprint. Never throws.
  - `InstallationReporter`: version reports (`installed`, `settings_saved`, `upgraded`, `api_key_check`, `check`) with a 3 s timeout, no new attempt for an hour after a failure, and a plain JSON state the plugin stores.
  - `reportsEnabled()` (setting plus `SUPERTEXT_VERSION_REPORTS`), `pingResponse()` for the plugin's public ping endpoint, and an `SDK_VERSION` constant for the reports.
  - `spec/commando/`: the center's schema and vectors.
