# supertext-sdk (TypeScript)

Client for the Supertext AI translation API (v1): translate HTML and texts, with rate-limit retries and HTML packing handled for you, and the API's language list for your language mapping.

- Node 22+, Deno and Bun; ESM with type declarations; **no runtime dependencies** (uses the global `fetch`).
- Everything Supertext's CMS, PIM and shop plugins learned against the live API is built in: the auth header, language codes, the exact multipart upload, HTTP 429 retries, polling, cleanup, and packing many texts into one document.

For the protocol and integration advice (what to send as one segment, how to show errors, how to test), see the [integration guide](../../docs/integration-guide.md).

## Install

```sh
npm install supertext-sdk
```

You need an API key. No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

## Quick start

Everything that talks to the API is in the `v1` namespace, so a later API version can sit next to it: `import { SupertextClient } from 'supertext-sdk/v1'`, or `import { v1 } from 'supertext-sdk'` and `new v1.SupertextClient(…)`. `SDK_VERSION` is exported from both.

Translate texts and HTML fragments, keyed by your own ids:

```ts
import { SupertextClient } from 'supertext-sdk/v1'

const client = new SupertextClient(process.env.SUPERTEXT_API_KEY ?? '', {
  userAgent: 'My-CMS-Supertext-Plugin/1.0.0', // appended to the User-Agent
})

const translated = await client.translateTexts(
  {
    title: 'Hello & welcome',                                        // plain text
    body: { text: '<p>Our <b>new</b> shop opens on Monday.</p>', html: true },
    teaser: { text: 'Line 1\nLine 2' },                              // plain text, line breaks kept
  },
  'de-CH',
  { sourceLang: 'en', politeness: 'more' },
)
// { title: 'Hallo und willkommen', body: '<p>Unser <b>neuer</b> …</p>', teaser: 'Zeile 1\nZeile 2' }
```

- Keys come back in the same order. Pass a `Map` to get a `Map` back (a plain object reorders keys like `"10"`, `"2"`).
- Plain texts are escaped and read back as plain text; HTML fragments go as they are. Send a whole paragraph (with its inline formatting and links) as **one** segment: Supertext translates each segment on its own.
- Blank texts come back unchanged without a request. Many texts go into one document (up to 900,000 characters, `maxCharacters`); bigger sets are split and translated one after the other.

Translate a whole HTML document:

```ts
const html = await client.translateHtml('<!DOCTYPE html><html>…</html>', 'fr', { sourceLang: 'en' })
```

Lower-level steps, if you need them: `submit()`, `getStatus()`, `waitUntilDone()`, `download()`, `delete()` (never throws). `validateApiKey()` checks the key without cost (for a "test connection" button).

## Corrections from reviewers

With Supertext Localization Review, point the client at its review service (`baseUrl`) and fetch the corrections reviewers made, for content you don't translate again. Use your own `data-st-id`s (build the document yourself, `translateHtml`) so `segmentId` tells you the field; see the integration guide → *Corrections from reviewers*.

```ts
const corrections = await client.pendingCorrections({ targetLang: 'de-CH' })  // all pages
// write c.html where c.segmentId points, if the field still holds c.previousHtml
await client.acknowledgeCorrections(corrections)                              // → number accepted
```

Against Supertext's API these throw `not_found`.

## API key check

`client.checkApiKey()` is the automatic API key check Supertext's plugins run on every settings save ([guide, section 14](../../docs/integration-guide.md#14-the-api-key-check-and-version-reports)): `GET features` with a 10 s timeout, never throws. No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

```ts
import { SDK_VERSION } from 'supertext-sdk'
import { SupertextClient, apiKeyFingerprint } from 'supertext-sdk/v1'

const check = await new SupertextClient(apiKey).checkApiKey()
check // { status: 'ok' | 'invalid' | 'missing' | 'unreachable' | 'error', checkedAt, httpStatus, errorCode, fingerprint }
```

The fingerprint (`apiKeyFingerprint(key)`, SHA-256) stays local, to notice a changed key. Version reports to the Supertext Commando Center and its ping endpoint are [Supertext Plugin Health](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health): pass it `{ check: (key) => new SupertextClient(key).checkApiKey(), fingerprint: apiKeyFingerprint }` as the key checker and `SDK_VERSION` as `sdkVersion`.

## Languages

The client sends the language codes it is given (trimmed). Supertext accepts exactly the codes `GET features` lists: bare sources (`en`), and targets in one exact form each: `de-CH`, `en-US`, `pt-BR`, but bare `es`, `nl`, `sl`, and `zh-Hans`, `sr-Latn`, `gsw-u-sd-chzh` ([guide, section 6](../../docs/integration-guide.md#6-language-codes)). Let administrators map each CMS language to a Supertext code (a combo box per language from `Languages.targetOptions()`, `Languages.suggestTarget(code)` preselected), and resolve it with `Languages`. A language nobody mapped uses its default where one is obvious (`de` → `de-DE`, `en` → `en-US`, `zh` → `zh-Hans`, `zh-TW` → `zh-Hant`, `es-ES` → `es`; `Languages.DEFAULT_TARGETS`); anything else (`en-IE`, `gsw`) is `unsupported_language`:

```ts
import { Languages } from 'supertext-sdk/v1'

const mapping = { de: 'de-CH', no: 'nb' } // the plugin's language setting: CMS language → Supertext code
await client.translateTexts(texts, Languages.target(cmsLocale, mapping), { sourceLang: Languages.source(sourceLocale, mapping) })

Languages.target('de', mapping) // 'de-CH' (mapping)
Languages.target('de_ch')       // 'de-CH' (exact match apart from case and _/-)
Languages.target('es')          // 'es'
Languages.target('de')          // 'de-DE' (default)
Languages.target('en-IE')       // throws SupertextError unsupported_language: map it
Languages.defaultTarget('es-ES') // 'es' (null when there is no default or an exact match)
Languages.suggestTarget('de_ch') // 'de-CH' (exact match, else the default, else null): preselect it
Languages.source('en-US')       // 'en' (sources are bare)
Languages.matchTarget('zh-CN')  // null (Supertext has zh-Hans / zh-Hant)
Languages.supportsPoliteness('en-US') // false: hide formal/informal for it
Languages.targetOptions()       // [{ code: 'sq', name: 'Albanian', politeness: false }, …] sorted by name, for the combo box
await client.getFeatures()      // the live list: [{ sourceLang, targetLang, features }]
```

In the combo box, show each name in the administrator's language where you can (`new Intl.DisplayNames([uiLocale], { type: 'language' }).of(code)`) and the code next to it.

## Politeness

`politeness: 'more'` asks for formal address (Sie, vous, Lei), `'less'` for informal (du, tu). `'default'` (or leaving it out) lets Supertext decide and sends nothing. Not every target offers it (`Languages.supportsPoliteness`); otherwise the API answers `unsupported_feature`.

## Errors

Every failure is a `SupertextError` with a stable `code` (also as constants: `ErrorCode.QuotaExceeded`), an English `message`, and where known `status` (HTTP), `detail` (the API's own text, at most 200 characters), `apiErrorCode` (e.g. `INVALID_LANGUAGE_PAIR`), `sourceLang`/`targetLang`, `feature`. The API key never appears in any of them. Translate the code for your users:

| Code | What to tell the user |
| --- | --- |
| `missing_api_key` | No API key is configured (nothing was sent). Show where to enter it and the account/API key links above. |
| `authentication_failure` | The API key was rejected (HTTP 401/403). Check or regenerate it (links above). |
| `invalid_language_pair` | Supertext doesn't support this language pair (HTTP 404); check the language mapping. |
| `unsupported_language` | The language has no Supertext code (no mapping, no exact match, no default; nothing was sent). Map it in the language settings. |
| `unsupported_feature` | The pair exists, but not with this option (`feature`, e.g. `politeness` for `en-US`). Use the default tone. |
| `not_found` | The translation file is gone (expired or deleted). Try again. |
| `payload_too_large` | The content is too large; translate less at once (or lower `maxCharacters`). |
| `too_many_requests` | Too many requests right now (after 4 retries). Try again in a minute. |
| `service_unavailable` | Supertext is unavailable (HTTP 5xx). Try again later. |
| `unexpected_status` | Unexpected answer from Supertext; show `status` and `detail`. |
| `transport_error` | Supertext couldn't be reached (network, DNS, TLS, timeout); `detail` has the reason. |
| `no_file_id` | Supertext didn't accept the document. Try again. |
| `translation_error` | Supertext couldn't translate the document. |
| `quota_exceeded` | The account's translation limit is used up; upgrade the subscription. |
| `file_deleted` | The file was deleted before the download. Try again. |
| `timeout` | The translation took too long (`timeoutMs`). Try again or translate less at once. |
| `incomplete_response` | The translation came back empty or incomplete (`detail` lists missing keys). Try again. |

```ts
import { SupertextError } from 'supertext-sdk/v1'

try {
  await client.translateTexts(texts, target)
} catch (error) {
  if (error instanceof SupertextError) showMessage(t(`supertext.error.${error.code}`), error.detail)
  else throw error
}
```

## Options

`new SupertextClient(apiKey, options)`; a blank key doesn't throw here, only the first request does (`missing_api_key`). A pasted `Supertext-Auth-Key ` prefix is stripped.

| Option | Default | |
| --- | --- | --- |
| `baseUrl` | `https://api.supertext.com/v1/` (`LIVE_BASE_URL`) | e.g. the stand-in for tests |
| `pollIntervalMs` | `2000` | wait between status polls |
| `timeoutMs` | `180000` | give up waiting for a translation (`timeout`) |
| `requestTimeoutMs` | `30000` | per HTTP request (`transport_error`) |
| `maxRetries` | `4` | retries after HTTP 429 (Retry-After, else 1/2/4/8 s with jitter) |
| `userAgent` | none | appended: `supertext-sdk-typescript My-Plugin/1.0.0` |
| `fetch` | global `fetch` | proxies, tests |
| `sleep`, `now` | timers, `Date.now` | tests |

The client builds the multipart upload itself instead of using `FormData`: the API answers HTTP 415 unless the file part's Content-Type is exactly `text/html`, and some runtimes (Bun) add a charset to it.

Also exported: `authHeader()`, `retryDelayMs()`, `HtmlDocument.build()/parse()` (the document format), `planBatches()`, `errorForStatus()`, `errorForFileStatus()`.

## Translation files

`TranslationFiles` exports segments as an XLIFF 1.2 or HTML file, to be translated outside the API (a CAT tool, a translator, Supertext's web app), and imports the translated file again (test vectors: `spec/translation-file.json`). It doesn't call the API, so it comes from `supertext-sdk`, not `supertext-sdk/v1`.

```ts
import { TranslationFiles, type FileJob } from 'supertext-sdk'

const job: FileJob = {
  plugin: 'MyCms-Supertext-Translation', reference: 'page:12', sourceLanguage: 'de-CH', targetLanguage: 'fr-CH',
  segments: [{ key: 'title', text: 'Käse & Brot' }, { key: 'body', text: '<p>Unser <b>neuer</b> Laden</p>', html: true }],
}
const xliff = TranslationFiles.exportXliff([job]) // or TranslationFiles.exportHtml(job)

// Later, with the uploaded file:
for (const file of TranslationFiles.read(uploaded)) { // one per job (<file>) in an XLIFF file
  const current = await collectSegments(file.reference) // the item's segments as they are now
  for (const r of TranslationFiles.importTranslations(file, current)) {
    if (r.status === 'ok') await save(file.reference, file.targetLanguage, r.key, r.text!)
    else if (r.status === 'source_changed') await askOrSave(r) // translated, but the source changed since
    else report(r.key, r.status) // untranslated, invalid_markup, missing
  }
}
```

- `exportXliff` and `exportHtml` leave out blank segments; a job with nothing to translate, empty metadata or duplicate keys throws `TranslationFileError` (`code` `invalid_job`). `read` throws `too_large`, `invalid_file` or `missing_metadata`.
- Each unit carries a checksum of its source (`TranslationFiles.checksum(segment)`); `source_changed` means the source differs from the exported one (or the file has no checksum): decide whether to keep the translation.
- Imported files are untrusted: `read` refuses DTDs and entity declarations (nothing is ever expanded) and files over `MAX_FILE_CHARACTERS` (10,000,000). Markup in the result comes only from the current source: XLIFF inline codes are replaced by the source's tags, and an HTML file's translation may only use the tags, attributes and comments the source has, with safe URLs; anything else is `invalid_markup`.

## Testing against the stand-in

The repo's stand-in API (`stand-in/stand-in.mjs`, Node, no dependencies) behaves like the live API where integrations went wrong before, and returns each text with `[<target>] ` in front:

```sh
node stand-in/stand-in.mjs          # http://127.0.0.1:8765/v1/
```

```ts
const client = new SupertextClient('test-key', { baseUrl: 'http://127.0.0.1:8765/v1/' })
```

## Development

Working on the SDK needs Node 22.12+ (Vitest 5); the published package runs on Node 22+.

```sh
cd sdk/typescript
npm ci
npm run typecheck   # tsc --noEmit
npm test            # Vitest: unit tests and the shared vectors in ../../spec
npm run build       # tsc → dist/
npm run conformance # builds, then runs examples/conformance.mjs against the stand-in
```

The conformance run from the repo root: `node stand-in/conformance.mjs -- node sdk/typescript/examples/conformance.mjs` (after `npm run build`).

## License

MIT
