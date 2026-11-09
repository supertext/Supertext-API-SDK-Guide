# Developer guide

For people working on this repository. To build an integration, read the [integration guide](integration-guide.md) instead.

## Layout

```
docs/                  integration guide, API reference, this file
spec/                  shared test vectors (JSON) every SDK must pass
stand-in/stand-in.mjs  stand-in for the Supertext API (Node, no dependencies)
stand-in/conformance.mjs  runs an SDK's conformance program against the stand-in
sdk/typescript/        npm supertext-sdk
sdk/php/               Packagist supertext/supertext-sdk (composer.json is at the repo root)
sdk/python/            PyPI supertext-sdk (import supertext)
sdk/dotnet/            NuGet Supertext.Sdk
sdk/java/              Maven com.supertext:supertext-sdk
```

## How the SDKs stay the same

1. **The spec is the source of truth.** Behaviour is defined by the vectors in [`spec/`](../spec/) (each file's `description` states the rules) and summarised in the integration guide. Every SDK's tests load the vector files directly from `spec/`.
2. **Conformance.** Every SDK has a small conformance program (`examples/conformance.*`): it reads a request as JSON on stdin, calls `translateTexts` against `SUPERTEXT_API_URL`/`SUPERTEXT_API_KEY` and prints the result as JSON. `node stand-in/conformance.mjs -- <program>` runs the scenarios in [`spec/conformance.json`](../spec/conformance.json) against a fresh stand-in each (429 retry, language codes sent as given, key prefix, auth failure, failed translation, file deleted, blank input, a code Supertext doesn't offer, politeness where it isn't offered).
3. **Same API, idiomatic names, versioned.** Everything that talks to the API lives in a `v1` namespace (TypeScript `supertext-sdk/v1` / `v1.…`, PHP `Supertext\Sdk\V1`, Python `supertext.v1`, .NET `Supertext.Sdk.V1`, Java `com.supertext.sdk.v1`); only the SDK version (`SDK_VERSION`, `SdkInfo`), the generic HTTP layer and the translation files (`TranslationFiles`: TypeScript `supertext-sdk`, PHP `Supertext\Sdk\Files`, Python `supertext.files`, .NET `Supertext.Sdk.Files`, Java `com.supertext.sdk.files`; they don't call the API) are outside it. A later API version gets `v2` next to it, and `spec/` then gets a `v2/` folder. Inside: `translateTexts`, `translateHtml`, the single steps, `getFeatures`, `Languages`, `HtmlDocument`, one error type with the fixed string codes, and the review service's `pendingCorrections`/`acknowledgeCorrections` (`Correction`). The translation files have their own error type (`invalid_job`, `invalid_file`, `missing_metadata`, `too_large`). Durations are milliseconds in TypeScript and seconds (or `TimeSpan`/`Duration`) elsewhere; the defaults are the same (poll 2 s, timeout 180 s, request timeout 30 s, 4 retries, 900,000 characters per document).
4. **No runtime dependencies** (the .NET SDK uses only the base library). Each SDK has an injectable HTTP layer, sleep and clock for tests. The translation files parse XML with a small hand-written parser in every SDK (no DTDs, no recursion), so uploaded files behave the same everywhere and nothing is ever expanded.

To change behaviour: change the vector (or add one), then every SDK and its tests, the guide and `CHANGELOG.md`, in one commit.

## Tests and CI

`.github/workflows/ci.yml` runs per SDK: static checks, unit tests (fake HTTP layer plus the spec vectors), conformance against the stand-in, and a package build. Locally, from the repo root:

| SDK | Commands |
| --- | --- |
| TypeScript | `cd sdk/typescript && npm ci && npm run typecheck && npm test && npm run build`, then `node stand-in/conformance.mjs -- node sdk/typescript/examples/conformance.mjs` |
| PHP | `composer install`, `vendor/bin/phpunit -c sdk/php/phpunit.xml.dist`, `vendor/bin/phpstan analyse -c sdk/php/phpstan.neon` (level 8), `node stand-in/conformance.mjs -- php sdk/php/examples/conformance.php` |
| Python | `cd sdk/python && pip install pytest mypy ruff build && ruff check . && ruff format --check . && mypy --strict && pytest`, then `node stand-in/conformance.mjs -- python3 sdk/python/examples/conformance.py` |
| .NET | `dotnet build sdk/dotnet -warnaserror && dotnet test sdk/dotnet`, `dotnet build sdk/dotnet/examples/Conformance -c Release`, then `node stand-in/conformance.mjs -- dotnet sdk/dotnet/examples/Conformance/bin/Release/net10.0/Conformance.dll` |
| Java | `cd sdk/java && mvn -B verify`, then `node ../../stand-in/conformance.mjs -- java -cp target/classes examples/Conformance.java` |

CI matrix: Node 22/24/26, PHP 8.2–8.5 (plus PHP 8.3 without ext-curl, which only the built-in transport needs), Python 3.11–3.15, .NET 10, Java 11/17/21/25. Minimum versions follow vendor support: a runtime is dropped when it reaches end of life (Java 11 stays while vendors such as Eclipse Temurin still support it).

Nothing in CI talks to the live API. Before a release, run each SDK's conformance program once against the live API with a test key (`SUPERTEXT_API_URL=https://api.supertext.com/v1/`, `SUPERTEXT_API_KEY=…`, a request with a few real sentences on stdin) and check the translations.

## Code quality and security checks

- *Checks* workflow (`checks.yml`): actionlint and zizmor lint the workflows on every push and PR; dependency review fails a PR that adds a package with a known vulnerability (moderate or worse).
- *Links* workflow (`links.yml`): lychee checks all Markdown links weekly and when docs change; broken links open or update the issue "Broken links in the docs"; exclusions in `.lycheeignore`.
- PHP: PHPStan level 8 on `sdk/php/src` in CI. Python: `mypy --strict` and ruff. .NET: warnings as errors with nullable reference types. Java: `-Xlint:all -Werror`. TypeScript: `strict` type check.
- GitHub settings (set by Remy's setup script, not in the repo): secret scanning with push protection (a push containing a known token format is rejected; findings under Security → Secret scanning) and CodeQL default setup (findings under Security → Code scanning and as PR comments; CodeQL covers TypeScript, Python, C# and Java here, PHPStan covers PHP).
- Before starting work in this repo, look at its open findings: code scanning alerts, secret scanning alerts, Dependabot PRs and the "Broken links in the docs" issue.

## Dependency updates

Dependabot (`.github/dependabot.yml`) checks npm (`sdk/typescript`), Composer (root), NuGet (`sdk/dotnet`), Maven (`sdk/java`) and GitHub Actions weekly and groups minor and patch updates. Python has no pinned dev dependencies (CI installs the current pytest, mypy, ruff). Ignored major updates, because they would drop a supported platform:

- `@types/node`: the TypeScript SDK supports Node 22, so its types must match Node 22 (major updates stay ignored until Node 22 reaches end of life, April 2027).
- `phpunit/phpunit` 12: needs PHP 8.3; the PHP SDK supports 8.2 (end of life 31 December 2026; then PHPUnit 12 can be taken).
- JUnit 6: needs Java 17; the Java SDK supports Java 11.

## Releasing

All SDKs share one version. `.github/workflows/release.yml` publishes a GitHub release when the version is officially bumped; never tag or create a release by hand.

1. In `CHANGELOG.md`, move the *Unreleased* entries into a new section `## X.Y.Z — YYYY-MM-DD` below an empty *Unreleased*.
2. Set the same version in `sdk/typescript/package.json` (and `package-lock.json`), `sdk/python/pyproject.toml`, `sdk/dotnet/src/Supertext.Sdk/Supertext.Sdk.csproj`, `sdk/java/pom.xml` and the SDK version constants (`sdk/typescript/src/version.ts`, `sdk/php/src/Supertext.php`, `sdk/python/src/supertext/version.py`, `sdk/dotnet/src/Supertext.Sdk/SdkVersion.cs`, `sdk/java/src/main/java/com/supertext/sdk/SdkInfo.java`). Composer takes the PHP package version from the Git tag; the constant is what plugins pass to Supertext Plugin Health as `sdkVersion`.
3. Push to `main`. The workflow checks the version files, builds the npm tarball, the Python wheel and sdist, the NuGet package and the jar, tags `vX.Y.Z` and creates the release with the changelog section and the packages attached (0.x as pre-release).

Not automated yet (needs accounts and secrets, Remy):

- **npm**: `npm publish` of `sdk/typescript` (npm account or trusted publishing).
- **PyPI**: trusted publishing for `supertext-sdk`.
- **NuGet**: API key for `Supertext.Sdk` (reserve the `Supertext` prefix).
- **Maven Central**: namespace `com.supertext` (verify the domain), signing key.
- **Packagist**: submit the repository URL once; Packagist then picks up tags.

## API key check and Supertext Plugin Health

Version reports to the [Commando Center](https://github.com/Supertext/Commando-Center-Supertext-Translation) and its ping endpoint are not in this repository: they are **Supertext Plugin Health** (the center's `plugin-health/`, since SDK 0.3.0). The SDK provides what Plugin Health's reporter calls: `checkApiKey()` (`spec/api-key-check.json`), `apiKeyFingerprint` and the version constant. Their names and the check's fields (`status`, `checkedAt`, `httpStatus`, `errorCode`, `fingerprint`) are a contract with Plugin Health: add fields if needed, never rename or remove them.

## Adding a language

Copy the structure of an existing SDK: client with injectable HTTP layer in a `v1` namespace, `Languages`, `HtmlDocument`, error type, tests that load every `spec/*.json` file, a conformance program, a README with the API key links. Add a CI job, a Dependabot entry, a row in the README's SDK table and the version file in `release.yml` → `VERSION_FILES`.
