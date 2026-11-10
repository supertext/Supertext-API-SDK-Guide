# Working on this repository

Part of Supertext's translation plugins project. This repo is not a plugin: it holds the **integration guide** and the **SDKs** (TypeScript, PHP, Python, .NET, Java, Kotlin) for the Supertext AI file translation API, distilled from the ~30 `Supertext/<System>-Supertext-Translation` plugins. See `docs/DEVELOPER.md`.

## Documentation rule (always)

Every change that affects behaviour, the public API, options or the repo structure updates in the same commit: `docs/integration-guide.md` (and `docs/api-reference.md` for protocol facts), the affected SDK READMEs, `docs/DEVELOPER.md` (layout, commands, CI, releasing), the root `README.md` (short overview) and `CHANGELOG.md` under *Unreleased*. Before finishing any task, check the docs still match the code.

## One behaviour, six SDKs (always)

- The vectors in `spec/` define the behaviour. Every SDK's tests load them; every SDK passes `node stand-in/conformance.mjs -- <its conformance program>`.
- A behaviour change starts with the vector, then **all six SDKs**, their tests, the guide and the changelog, in one commit. Never let one SDK drift.
- Same concepts and defaults everywhere (`translateTexts`, `translateHtml`, single steps, `getFeatures`, `Languages`, `HtmlDocument`, one error type with the fixed string codes, `TranslationFiles`, `pendingCorrections`/`acknowledgeCorrections` for the review service, `spec/corrections.json`), named the way each language expects.
- **Translation files** (`TranslationFiles`, `spec/translation-file.json`): XLIFF 1.2 and HTML export/import, outside `v1` (no API calls). Imported files are untrusted: keep the size limit, the hand-written XML parser that refuses any `<!` declaration, no recursion on nesting, no allocation by positions from the file, and markup only from the integration's current source.
- No runtime dependencies in the SDKs. No logging inside the SDKs. The API key never appears in messages, details, `toString` or logs.

## Supertext account and API key links (always)

Wherever the docs or a README mention the API key, show both links: "No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role)."

## Shared Supertext protocol (lessons from the live API, October 2026)

- **Auth header:** `Authorization: Supertext-Auth-Key <key>`. `Authentication` gets 403; no prefix gets 400. Strip a pasted prefix and send exactly one.
- **Upload:** multipart; the file part's Content-Type must be exactly `text/html` (a charset gives 415). Max 1,000,000 characters per document.
- **Rate limit:** HTTP 429 `RATE_LIMIT_EXCEEDED`; retry up to 4 times (`Retry-After`, else 1/2/4/8 s with jitter, cap 30 s). Documents one after the other.
- **Rich text:** each `data-st-id` element is translated on its own: one per paragraph with inline tags, never one per formatted run.
- **Languages:** only the codes `GET features` lists (`spec/languages.json`): 28 bare sources; 37 targets, regional only for de/en/fr/it/pt (`de-CH`, `en-US`), script for zh/sr (`zh-Hans`, `sr-Latn`), `gsw-u-sd-chzh`/`chbe`, bare for the rest (`es`, `nl`, `sl`). Anything else: HTTP 404 `INVALID_LANGUAGE_PAIR`. Politeness on a target without it (en, gsw, rm, sq, sr): HTTP 400 `FEATURE_ERROR`. **Defaults only where obvious:** the SDK resolves a CMS target by the plugin's mapping (a combo box per CMS language, `suggestTarget` preselected), else an exact match (case, `_`/`-`), else a default (`defaultTargets`: bare de/en/fr/it/pt → `de-DE`/`en-US`/`fr-FR`/`it-IT`/`pt-PT`, zh → `zh-Hans`, sr → `sr-Latn`, zh-TW/HK/MO → `zh-Hant`, zh-CN/SG → `zh-Hans`, a region of a bare target → the bare target: `es-ES` → `es`), else `unsupported_language`. Nothing else is guessed (`en-IE`, `de-LI`, `gsw`, `sr-RS` need a mapping). The client sends codes as given.
- **Translation files:** Supertext's web app keeps an uploaded HTML file's `<head>` (the `supertext:*` metas) and the `data-st-id` attributes, so `TranslationFiles` HTML exports import back unchanged (confirmed October 2026).
- **Versioned API:** everything API-bound is in the `v1` namespace of each SDK; a later API version gets `v2` next to it (and `spec/v2/`).

**Commando Center:** the SDK only provides the API key check (`checkApiKey()`, `apiKeyFingerprint`, `ApiKeyCheck`; `spec/api-key-check.json`) and `SDK_VERSION`. Version reports and the ping endpoint live in **Supertext Plugin Health** (Commando-Center-Supertext-Translation → `plugin-health/`), which calls exactly these: never rename them or the check's fields (`status`, `checkedAt`, `httpStatus`, `errorCode`, `fingerprint`). There is no `spec/commando/` here any more.

When a plugin learns something new about the API, add it here, to the guide, and (if the SDKs should handle it) to the spec and all SDKs.

## Before committing

Run the commands for every SDK you touched (`docs/DEVELOPER.md` → *Tests and CI*), including conformance. Spec changes touch all six. Workflow changes must pass actionlint and zizmor; third-party actions are pinned to commit SHAs.

## Releases (always)

`.github/workflows/release.yml` releases when `CHANGELOG.md` gets a new `## X.Y.Z — YYYY-MM-DD` section and the version files (`sdk/typescript/package.json`, `sdk/python/pyproject.toml`, `sdk/dotnet/src/Supertext.Sdk/Supertext.Sdk.csproj`, `sdk/java/pom.xml`, `sdk/kotlin/pom.xml` and the six SDK version constants, see `docs/DEVELOPER.md` → *Releasing*) carry the same number. Never tag or create a release by hand. Publishing to npm, PyPI, NuGet, Maven Central and Packagist isn't automated yet (`docs/DEVELOPER.md` → *Releasing*).

## Repo setup (always)

`LICENSE` (MIT, also copied into each `sdk/*` folder for the packages), `SECURITY.md`, `.github/dependabot.yml` (npm, Composer, NuGet, Maven for Java and Kotlin, GitHub Actions), `checks.yml` and `links.yml`, CI per SDK. On GitHub: About box, `main` protected, Wiki and Projects off, Dependabot alerts, private vulnerability reporting, secret scanning with push protection and CodeQL default setup on. Claude sessions can't change GitHub repo settings (HTTP 403): the repo is in Remy's setup script (`set-github-about`).

## Checks and alerts (always)

Before starting work, look at open findings: code scanning alerts (`gh api 'repos/Supertext/Supertext-API-SDK-Guide/code-scanning/alerts?state=open'`), secret scanning alerts (`…/secret-scanning/alerts?state=open`), open Dependabot PRs and the issue "Broken links in the docs". Fix what the task touches or what is quick.

## Plugin list (always)

`README.md` carries the shared list of all Supertext plugins between the `<!-- supertext-plugins:start -->` and `<!-- supertext-plugins:end -->` markers, identical to the plugin repos (current block: the project's `plugin-conventions.md`). Update it here too when the list changes.
