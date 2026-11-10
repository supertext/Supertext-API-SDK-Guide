# Supertext API integration guide

How to build a Supertext AI translation integration for a CMS, shop, PIM or any other system: the protocol, the rules the live API enforces, and what Supertext's own plugins (more than 30 of them, October 2026) learned the hard way.

You can follow this guide with any HTTP client. The [SDKs](../README.md#sdks) for TypeScript, PHP, Python, .NET, Java and Kotlin implement everything here and are tested against the same [test vectors](../spec/) and the same [stand-in API](../stand-in/stand-in.mjs). If your language has an SDK, use it and read this guide for the parts the SDK can't decide for you: what to send as one segment, how to write translations back, how to show errors.

Contents:

1. [Before you start](#1-before-you-start)
2. [The flow](#2-the-flow)
3. [Authentication](#3-authentication)
4. [Uploading a document](#4-uploading-a-document)
5. [Waiting, downloading, cleaning up](#5-waiting-downloading-cleaning-up)
6. [Language codes](#6-language-codes)
7. [The HTML document: segments](#7-the-html-document-segments)
8. [Rich text, Markdown and placeholders](#8-rich-text-markdown-and-placeholders)
9. [Rate limits, batching and background work](#9-rate-limits-batching-and-background-work)
10. [Errors and what to tell the user](#10-errors-and-what-to-tell-the-user)
11. [Writing translations back](#11-writing-translations-back)
12. [Security](#12-security)
13. [Testing](#13-testing)
14. [The API key check and version reports](#14-the-api-key-check-and-version-reports)
15. [Translation files: XLIFF and HTML](#15-translation-files-xliff-and-html)
16. [Checklist](#16-checklist)

The HTTP details are summarised in the [API reference](api-reference.md).

## 1. Before you start

**API key.** Every request needs a Supertext API key. No Supertext account yet? Create one at [supertext.com](https://www.supertext.com/person/en/account/signin). Generate your API key at [supertext.com → Integrations → API](https://www.supertext.com/en/integrations/api) (requires the Admin role).

Show both links wherever an administrator enters the key or is told about it: next to the settings field, in "no API key" and "authentication failed" messages, in your installation docs. Links in a UI open in a new tab (`target="_blank" rel="noopener"`); where only plain text is possible, write out the URLs.

**Base URL.** `https://api.supertext.com/v1/`. Make it configurable (for the stand-in in tests) and keep exactly one trailing slash.

**Where the key lives.** In your system's settings (stored encrypted if the system can) or in the environment variable `SUPERTEXT_API_KEY`. Never in code, in a repository, in logs or in error messages. See [Security](#12-security).

**Test connection.** `GET features` costs nothing and fails with 401/403 for a bad key: use it for a "Test connection" button or a status page.

## 2. The flow

The AI file API translates one HTML document per request:

```
POST   translate/ai/file                    upload the HTML file + languages   → { "file_id": "…" }
GET    translate/ai/file/{id}/status        poll until "done"                   → { "status": "translating" | "done" | … }
GET    translate/ai/file/{id}/translation   download the translated HTML
DELETE translate/ai/file/{id}               clean up (always, even after errors)
```

Supertext keeps your markup and attributes and translates the text. To translate many fields at once (title, body, meta description, …), pack them into one document, each in its own `<div data-st-id="N">`, and read them back by id ([section 7](#7-the-html-document-segments)).

With an SDK this is one call. The SDKs keep everything that talks to the API in a `v1` namespace (TypeScript `supertext-sdk/v1`, PHP `Supertext\Sdk\V1`, Python `supertext.v1`, .NET `Supertext.Sdk.V1`, Java and Kotlin `com.supertext.sdk.v1`), so a later API version can be used next to it:

```ts
// TypeScript
const out = await client.translateTexts({ title: 'Hello & welcome', body: { text: '<p>Our <b>new</b> shop.</p>', html: true } }, 'de-CH', { sourceLang: 'en' })
```

```php
// PHP
$out = $client->translateTexts(['title' => 'Hello & welcome', 'body' => Segment::html('<p>Our <b>new</b> shop.</p>')], targetLang: 'de-CH', sourceLang: 'en');
```

```python
# Python
out = client.translate_texts({"title": "Hello & welcome", "body": Segment("<p>Our <b>new</b> shop.</p>", html=True)}, target_lang="de-CH", source_lang="en")
```

```csharp
// C#
var out = await client.TranslateTextsAsync(new Dictionary<string, Segment> { ["title"] = "Hello & welcome", ["body"] = new Segment("<p>Our <b>new</b> shop.</p>", Html: true) }, targetLang: "de-CH", sourceLang: "en");
```

```java
// Java
Map<String, Segment> in = new LinkedHashMap<>();
in.put("title", Segment.text("Hello & welcome"));
in.put("body", Segment.html("<p>Our <b>new</b> shop.</p>"));
Map<String, String> out = client.translateTexts(in, "de-CH", "en", Politeness.DEFAULT);
```

```kotlin
// Kotlin
val out = client.translateTexts(mapOf("title" to Segment.text("Hello & welcome"), "body" to Segment.html("<p>Our <b>new</b> shop.</p>")), targetLang = "de-CH", sourceLang = "en")
```

Without an SDK, the same with curl:

```sh
KEY="Supertext-Auth-Key $SUPERTEXT_API_KEY"
API=https://api.supertext.com/v1

ID=$(curl -s -H "Authorization: $KEY" \
  -F target_lang=de-CH -F source_lang=en \
  -F 'file=@content.html;type=text/html' \
  "$API/translate/ai/file" | jq -r .file_id)

curl -s -H "Authorization: $KEY" "$API/translate/ai/file/$ID/status"        # repeat until "done"
curl -s -H "Authorization: $KEY" "$API/translate/ai/file/$ID/translation" > content.de-CH.html
curl -s -X DELETE -H "Authorization: $KEY" "$API/translate/ai/file/$ID"
```

## 3. Authentication

```
Authorization: Supertext-Auth-Key <key>
```

- The header name is `Authorization`. `Authentication` gets HTTP 403.
- The prefix `Supertext-Auth-Key ` is required. A bare key gets HTTP 400.
- Supertext shows the key **with** the prefix, so administrators often paste both. Strip a pasted prefix (case-insensitive, any whitespace) and always send exactly one.
- A blank key is a configuration error: don't send a request, tell the user where to enter the key.

Test vectors: [`spec/auth-header.json`](../spec/auth-header.json).

## 4. Uploading a document

`POST translate/ai/file` with `multipart/form-data`:

| Field | Value |
| --- | --- |
| `target_lang` | A target code exactly as `GET features` lists it: `de-CH`, `en-US`, `es`, `zh-Hans` ([section 6](#6-language-codes)). |
| `source_lang` | Bare language: `en`, `de`. Leave the field out to let Supertext detect the language. |
| `politeness` | `more` (formal: Sie, vous, Lei) or `less` (informal: du, tu). Leave it out for Supertext's default. |
| `file` | The HTML document. Filename `content.html`, part Content-Type **exactly** `text/html`. |

Answer: `{"file_id": "…"}`.

Lessons:

- **The file part's Content-Type must be exactly `text/html`.** Supertext compares it with an allow-list; `text/html; charset=utf-8` is rejected with HTTP 415. Many multipart helpers add a charset (Bun's `FormData`, .NET's `StringContent`), so check the bytes you actually send, or build the multipart body yourself. Declare the encoding inside the document instead: `<meta charset="utf-8">`.
- **One document is at most 1,000,000 characters.** Stay below it (the SDKs split at 900,000) and send larger sets as several documents, one after the other.
- **Politeness** is per target language in practice: many systems let the administrator choose formal or informal address per language (German "Sie" vs "du"). Some locales carry it in the code (Polylang's `de_DE_formal`); map that to `politeness=more`.

## 5. Waiting, downloading, cleaning up

**Poll** `GET translate/ai/file/{id}/status`:

| Status | Meaning | What to do |
| --- | --- | --- |
| `translating` (or anything unknown) | Still working | Wait and poll again |
| `done` | Ready | Download |
| `error` | Supertext couldn't translate it | Error `translation_error` |
| `limit_exceeded` | The account's translation limit is used up | Error `quota_exceeded`: the customer needs to upgrade |
| `deleted` | The file is gone | Error `file_deleted`: try again |

Poll right away, then every 2 seconds. Give up after a few minutes (the SDKs: 180 s) with a timeout error. A typical page is done in a few seconds.

**Download** `GET translate/ai/file/{id}/translation` returns the translated HTML. Treat an empty answer as an error.

**Delete** `DELETE translate/ai/file/{id}` after every successful upload, whatever happened afterwards (download failed, parsing failed, timeout): put it in a `finally`. Ignore errors from the delete itself. Files expire after 24 hours anyway, but customer content shouldn't stay longer than needed.

## 6. Language codes

Supertext accepts exactly the codes `GET features` lists (cost-free; it returns every source/target pair with its features). As of October 2026 ([`spec/languages.json`](../spec/languages.json), the SDKs' `Languages`):

- **28 sources, all bare:** bg, cs, da, de, el, en, es, fi, fr, gsw, hr, hu, it, ja, ko, nb, nl, pl, pt, rm, ru, sk, sl, sq, sr, sv, tr, zh.
- **37 targets, each in one exact form:**
  - regional for German, English, French, Italian and Portuguese: `de-AT`, `de-CH`, `de-DE`, `en-GB`, `en-US`, `fr-CH`, `fr-FR`, `it-CH`, `it-IT`, `pt-BR`, `pt-PT`;
  - a script for Chinese and Serbian: `zh-Hans`, `zh-Hant`, `sr-Cyrl`, `sr-Latn`;
  - a dialect for Swiss German: `gsw-u-sd-chzh` (Zurich), `gsw-u-sd-chbe` (Bern);
  - **bare for all others:** `bg`, `cs`, `da`, `el`, `es`, `fi`, `hr`, `hu`, `ja`, `ko`, `nb`, `nl`, `pl`, `rm`, `ru`, `sk`, `sl`, `sq`, `sv`, `tr`.
- No pair within one language (`en` → `en-GB` isn't offered).
- **Politeness** (formal/informal) for every target except `en-GB`, `en-US`, `gsw-u-sd-*`, `rm`, `sq`, `sr-Cyrl`, `sr-Latn`.

Anything else is HTTP 404 `INVALID_LANGUAGE_PAIR` (bare `de`, `es-ES`, `sl-SI`, `zh-CN`: the default regions SDK 0.1 added were wrong for most languages; the defaults since 0.4.0 below only produce listed codes). Politeness `more`/`less` for a target without it is HTTP 400 `FEATURE_ERROR` (`feature: politeness`).

**A mapping, with defaults.** Systems name their languages differently (Shopify `de`, `pt-BR`; Magento `de_CH`; Directus `en-US`; WordPress `de_DE_formal`), and only the administrator knows whether a site's `de` means Germany, Austria or Switzerland. So every integration has a **language mapping**: for each CMS language a **combo box with Supertext's target codes** (`Languages.targetOptions()`: code, English name and politeness, sorted by name; show the name localized with the platform's own display-name facility where it has one, e.g. `Intl.DisplayNames`, `Locale.getDisplayName`, `CultureInfo`, Babel, Symfony Intl, and always the code next to it), with the **suggested code preselected** (`Languages.suggestTarget(code)`: the exact match, else the default below). A language nobody mapped still translates with its default, so a fresh installation works without visiting the screen; a language without a default is marked "please choose" and fails until it is mapped. Where settings come from the environment or a config file (`SUPERTEXT_LANGUAGE_MAP`, `appsettings.json`, …), the same mapping overrides the defaults there.

**Defaults** (`Languages.defaultTarget(code)`, [`spec/languages.json`](../spec/languages.json) → `defaultTargets`), only where they are obvious:

| CMS code | Default target | Why |
| --- | --- | --- |
| `de` | `de-DE` | Supertext offers German only with a region: the language's home country |
| `en` | `en-US` | as above |
| `fr` | `fr-FR` | as above |
| `it` | `it-IT` | as above |
| `pt` | `pt-PT` | as above |
| `zh` | `zh-Hans` | Chinese only with a script: simplified, the most used |
| `sr` | `sr-Latn` | Serbian only with a script |
| `zh-TW`, `zh-HK`, `zh-MO` / `zh-CN`, `zh-SG` | `zh-Hant` / `zh-Hans` | the script used there |
| *language*-*Script*-*REGION* (`zh-Hant-TW`, `sr-Latn-RS`) | *language*-*Script* | the script is given |
| *language*-*REGION* of a bare target (`es-ES`, `es-419`, `nl_NL`, `sl-SI`, `cs-CZ`, `nb-NO`) | the bare target (`es`, `nl`, `sl`, `cs`, `nb`) | Supertext has only the one form |

Nothing else is guessed: a region Supertext doesn't list for a regional-only language (`de-LI`, `en-IE`, `en-AU`, `fr-BE`, `fr-LU`, `pt-AO`), `sr-RS` (script unknown), Swiss German `gsw` (two dialects, neither obvious), `no` and longer codes (`de_DE_formal`) have no default and need a mapping.

How the SDKs resolve a CMS language (`Languages.target(code, mapping)` / `Languages.source(code, mapping)`):

1. **The mapping**, if it has a non-blank entry for the code (keys compared ignoring case and `_`/`-`). Its value is sent as it is, so a language Supertext adds later works before the SDK lists it; for the source, a known value is sent in its source form (`{"en": "en-US"}` sends source `en`, target `en-US`), so one mapping serves both directions.
2. **An exact match**: a CMS code that equals a Supertext code apart from case and `_`/`-` (`de_CH` → `de-CH`, `ES` → `es`; `Languages.matchTarget`, exact only). Sources also match by their first part (`en-US` as a source is `en`; sources have no regions, so the source side needs no defaults).
3. **For targets, the default** above (`de` → `de-DE`, `es-ES` → `es`).
4. **Otherwise the error `unsupported_language`**, and nothing is sent. Tell the editor which language isn't mapped and the administrator where to map it.

The client sends the codes it is given unchanged (trimmed). Save the mapping only for languages the administrator changed (or save the preselected value explicitly: both give the same result), and hide the formal/informal choice for targets that don't support it (`Languages.supportsPoliteness`). For an always-current list, call `getFeatures()` instead of the built-in one.

## 7. The HTML document: segments

Pack every text you want translated into one document, one element per text:

```html
<!DOCTYPE html>
<html><head><meta charset="utf-8"></head><body>
<div data-st-id="0">Hello &amp; welcome</div>
<div data-st-id="1"><p>Our <b>new</b> shop opens on <a href="https://www.supertext.com">Monday</a>.</p></div>
<div data-st-id="2">Line 1<br>Line 2</div>
</body></html>
```

- **Each `data-st-id` element is translated on its own.** That is what makes the format work, and what breaks translations when segments are cut wrong ([section 8](#8-rich-text-markdown-and-placeholders)).
- **Plain text**: escape `& < > " '`, send line breaks as `<br>`. On the way back: `<br>` → line break, drop any other tags, collapse whitespace, decode entities.
- **HTML fragments** go as they are, and come back as inner HTML.
- Map ids back to your fields with a list or map you keep; the id is just the position.
- Read the answer with a parser, or with depth-counting as the SDKs do: Supertext may change attribute order or quotes, and HTML segments can contain nested `<div>`s. Don't expect segments in the same order.
- A segment missing from the answer is an error for that field: don't write an empty value.
- Skip blank texts (nothing to translate) and copy them as they are.

The exact packing and parsing rules, with edge cases, are in [`spec/html-document.json`](../spec/html-document.json) and [`spec/batching.json`](../spec/batching.json).

### What to send

Translate what readers read: titles, text and rich-text fields, teasers, SEO title and description, image alt texts and captions, button labels, option and attribute labels in PIMs and shops.

Don't send: slugs and URLs (let the system create the slug from the translated title, or keep the source slug, but don't machine-translate it), ids, references and relations, dates, numbers, SKUs, colour codes, code blocks, embed codes, settings, empty fields. Copy non-translatable fields from the source as the system expects.

Keep the list of translated fields in one place in your code and document it for editors ("What is translated").

## 8. Rich text, Markdown and placeholders

**One paragraph per segment.** Send a whole paragraph, heading or list item as **one** `data-st-id` element, with its formatting and links as inline tags (`<b>`, `<i>`, `<a href>`), and map the result back to your editor's rich-text nodes. Never give each formatted run its own `data-st-id`: Supertext then translates sentence fragments, and you get lower-case starts, words moved outside the bold, wrong grammar. Every plugin that started with "one segment per text node" (Lexical, Slate, Portable Text, ProseMirror JSON) had to change this.

**Keep the structure.** Editors often render HTML that doesn't match their own serialization as read-only or "modified". Don't rebuild the markup from the translation: replace the inner HTML of the leaf blocks (paragraphs, headings, list items) and keep everything around them as it was in the source.

**Block editors and JSON content.** Walk the structure, send each text-bearing block as a segment, write the translations back into a copy of the structure. Keep block ids, types and settings unchanged. Nested blocks (block lists, grids, Matrix, Elemental) are walked recursively. Some systems store rich text inside blocks as a JSON string inside JSON: decode it, translate, encode it the same way.

**Markdown.** Convert each block (paragraph, heading, list item) to an HTML fragment with inline tags, translate, convert back, and leave code blocks, front matter, link targets and image paths untouched.

**Placeholders and merge tags** (`*|FNAME|*`, `{{name}}`, `%s`, shortcodes): replace each with an inline placeholder element (`<span data-ph="3"></span>`) before sending, put the original back afterwards, and refuse a translation that lost a placeholder. Supertext keeps markup, so placeholders survive; text placeholders like `{{name}}` don't reliably.

**Entities and encoding.** Know how your system stores text: some store plain text HTML-encoded (`&amp;`), some raw, some changed between versions. Decode before you build the segment and encode the result the way the system expects, or you get `&amp;amp;` in the output.

## 9. Rate limits, batching and background work

**HTTP 429.** The API limits requests per second per key (`RATE_LIMIT_EXCEEDED`). Translating into several languages at once hits it. Retry a 429 up to 4 times: wait `Retry-After` seconds if the header is a number, else 1, 2, 4, 8 seconds plus a little random jitter; never more than 30 seconds. Vectors: [`spec/retry-delay.json`](../spec/retry-delay.json).

**One document per item and language.** Batch all fields of a page (and its content elements) into one document per target language, not one request per field. Translate the languages one after the other.

**Never call the API from inside a fine-grained hook.** Some systems fire one event per content element ("create and copy" in Neos sends one command per node). Queue the work in the hook and translate the queue in one batch per language afterwards, at the end of the request or in a job.

**Long work belongs in a background job.** A single page is done in seconds and can run in the request. Bulk translation (a whole site, hundreds of products) runs in a queue or job with progress the user can see, and survives a page reload.

## 10. Errors and what to tell the user

The SDKs throw one error type with a stable `code`, an English `message`, and where known `status`, `detail` (the API's own text, at most 200 characters, untranslated), `apiErrorCode`, `sourceLang`/`targetLang`. Show users a message in their interface language chosen by `code`, and add `detail` where it helps:

| Code | Cause | Tell the user |
| --- | --- | --- |
| `missing_api_key` | No key configured | Where to enter the key, plus the account and API key links |
| `authentication_failure` | HTTP 401/403 | The key was rejected: check or regenerate it (links) |
| `invalid_language_pair` | HTTP 404 (or 400) `INVALID_LANGUAGE_PAIR` | Supertext doesn't translate from X into Y: check the language mapping (the code must be one of `GET features`, e.g. `de-CH`, `es`) |
| `unsupported_language` | Nothing sent: the language has no mapping, no exact match and no default | Language X has no Supertext language yet: map it in the plugin's language settings |
| `unsupported_feature` | HTTP 400 `FEATURE_ERROR` (`feature`) | Formal/informal address isn't available for this language: switch it to *Default* |
| `quota_exceeded` | Status `limit_exceeded` | The Supertext translation limit is used up: upgrade the subscription |
| `too_many_requests` | HTTP 429 after retries | Too many requests right now: try again in a minute |
| `payload_too_large` | HTTP 413 | Too much content at once: translate less (or lower the batch size) |
| `translation_error` | Status `error` | Supertext couldn't translate this content |
| `timeout` | Not done in time | It took too long: try again |
| `service_unavailable` | HTTP 5xx | Supertext is unavailable: try again later |
| `transport_error` | Network, DNS, TLS, request timeout | Supertext couldn't be reached (`detail` has the reason) |
| `not_found`, `file_deleted`, `no_file_id`, `incomplete_response`, `unexpected_status` | Rare protocol problems | Try again; show `status`/`detail` for support |

Full list: [`spec/errors.json`](../spec/errors.json).

- When translating into several languages, report per language: one failed language shouldn't hide the others that worked.
- A settings page that tests the key shouldn't answer a bad Supertext key with HTTP 401 from your own backend: some admin UIs sign the user out on any 401. Use 400 or 502 with the error code.
- Never show or log the key, also not inside a `detail` (some proxies echo request headers).

## 11. Writing translations back

- **Use the system's own translation model.** Language versions, localized fields, linked translations, Fluent locales, store views: write into what editors already know, so they can review and edit the result in the usual place.
- **Don't overwrite silently.** If a translation exists, ask before replacing it (an "overwrite existing translations" option or a confirmation with the affected languages), or only fill empty fields.
- **New translations start unpublished** (draft, hidden, "needs review") where the system has such a state, so a human reviews AI output before it goes live. Say so in the user guide.
- **Never write to the source item** to store status or errors: some systems (Ghost) then reject the editor's next save ("someone else is editing"). Keep job status in your own table, page or log.
- **Copy, then translate.** Start the target from a copy of the source (structure, media, settings), then write the translated fields. Don't copy fields that belong to the target (language, URL path, relations to other translations).
- **Clear the system's caches** after writing, if it caches typed objects (Orchard's content parts, Silverstripe's versioned objects): otherwise the old value gets saved back.

### Corrections from reviewers (Supertext Localization Review)

With [Supertext Localization Review](https://github.com/Supertext/Localization-Review-Supertext-Translation), customers and Supertext linguists correct translations on the website itself. The integration then talks to its **review service** instead of the API (a different base URL, e.g. a *Translation review* setting): it answers exactly like the API and passes everything on with the customer's key. Corrections come back two ways:

- **On the next translation**: the review service returns the corrected text for segments it knows. Nothing to build.
- **Without translating again**: fetch the corrections the integration hasn't written yet and write them into the CMS. `pendingCorrections(targetLang?, sourceLang?, politeness?)` returns them (all pages); each has `segmentId` (the `data-st-id` you sent), `html` (the corrected text), `previousHtml` (what you received last for that place) and a `version`. After writing, call `acknowledgeCorrections(corrections)`; only the fetched version counts, so a correction made meanwhile stays pending.

```ts
for (const c of await client.pendingCorrections({ targetLang: 'de-CH' })) {
  const field = findField(c.segmentId)               // your own id → the field
  if (field && field.value === c.previousHtml) field.value = c.html  // don't overwrite an editor's change
}
await client.acknowledgeCorrections(written)          // the ones you wrote (or decided to skip)
```

- **Use your own `data-st-id`s** that say where the text belongs (Contao: `tl_content.12.text`), i.e. build the document yourself and translate it with `translateHtml`. `translateTexts` and `HtmlDocument` number the segments (`0`, `1`, …) per document, which says nothing about the field afterwards.
- **Don't overwrite an editor's change**: write `html` only when the field still holds `previousHtml`; otherwise show the correction to the editor, or skip it. Acknowledge what you wrote (and what you deliberately skipped, or it stays pending).
- Only call these when the base URL is the review service: Supertext's API answers 404 (`not_found`). Run it in the background (a scheduled job or an *Apply review corrections* button), like translating.
- The review service tells plugins apart by the name in the User-Agent (`userAgent`), so keep it the same across versions.

## 12. Security

- Call Supertext **from the server only**, so the browser never sees the key.
- Store the key encrypted where the system supports it, or take it from `SUPERTEXT_API_KEY`. Show only that a key is set, never the key itself, on settings pages.
- Never log the key, request headers or full request bodies (customer content).
- Check permissions: only users who may edit the target content may translate it, and only administrators may change the key. Protect actions with the system's CSRF mechanism.
- Validate ids and language codes from requests before using them.

## 13. Testing

**Unit tests with a fake HTTP layer.** Check the exact multipart fields, the part Content-Type, the headers, the 429 retries (with a fake sleep), polling and timeout (with a fake clock), that the delete happens after failures, and the error codes. The SDKs' tests show how.

**Shared test vectors.** [`spec/`](../spec/) holds JSON test vectors for language codes, the auth header, retry delays, error mapping, the HTML document, batching and the API key check. Load them in your tests if you implement the protocol yourself.

**The stand-in API.** [`stand-in/stand-in.mjs`](../stand-in/stand-in.mjs) behaves like the live API where integrations went wrong (auth header, language codes, Content-Type, size limit, a `translating` status before `done`) and "translates" by putting `[de-CH] ` in front of each segment, so untranslated text is easy to spot:

```sh
node stand-in/stand-in.mjs                  # http://127.0.0.1:8765/v1/
STAND_IN_RATE_LIMIT=1 node stand-in/stand-in.mjs   # first upload gets HTTP 429
STAND_IN_SAMPLES=./samples node stand-in/stand-in.mjs  # real translations from samples/sample-de.json etc.
```

Point your integration at it (base URL setting or environment variable), any key works except `invalid`. Documents containing `STAND-IN:error`, `STAND-IN:limit` or `STAND-IN:deleted` get that status. `GET /__stand-in/stats` shows counters and the last upload's fields.

With `STAND_IN_SAMPLES` the stand-in returns real translations for known texts, which is how the plugins take documentation screenshots that show real translated content.

**Against the live API.** Try a few real items in each target language before a release, including rich text with formatting and links, a long page, and a language with a region override. Use a test account and test content only.

## 14. The API key check and version reports

**The API key check** (`checkApiKey()` on the client; [`spec/api-key-check.json`](../spec/api-key-check.json)): the cost-free `GET features` with a 10 s timeout, mapped to `ok` (2xx), `invalid` (401/403), `missing` (no key; nothing sent), `unreachable` (no answer, timeout, 5xx) or `error` (anything else, with the HTTP status and Supertext's `error_code`). It never throws. The result (`status`, `checkedAt`, `httpStatus`, `errorCode`, `fingerprint`) carries a SHA-256 fingerprint of the key (`apiKeyFingerprint()`), so a plugin notices a changed key from the environment without storing the key twice; the fingerprint stays local and is never sent. Run it on every settings save, when a key from the environment changed, after a translation got HTTP 401/403 and on *Test connection*, and show the result with the messages from the Commando Center's guide (four languages; `invalid` and `missing` carry the account and API key links).

**Version reports** to the Supertext **Commando Center** (which plugin, system and runtime version each site runs, whether its API key works, whether the site is reachable) are not part of the SDK. Plugins use **Supertext Plugin Health** from the Commando Center repository ([Commando-Center-Supertext-Translation → `plugin-health/`](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health)), bundled like the SDK, which also answers the center's ping. It doesn't depend on the SDK: pass the SDK's `checkApiKey()` and `apiKeyFingerprint()` as its key checker and `SDK_VERSION` as `sdkVersion`. The protocol is the center's `docs/DEVELOPER.md` → *Report protocol*.

## 15. Translation files: XLIFF and HTML

An alternative to the API: the integration exports an item's segments as a file, someone translates it elsewhere (a CAT tool such as Trados, memoQ or Phrase; a translator; Supertext's web app), and the integration imports it again. The SDKs do the file formats (`TranslationFiles`; [`spec/translation-file.json`](../spec/translation-file.json)); the integration does the buttons, finding the item again and writing back, exactly as it writes back API translations. Nothing here calls the API, so it isn't in the `v1` namespace: TypeScript `TranslationFiles` from `supertext-sdk`, PHP `Supertext\Sdk\Files\TranslationFiles`, Python `supertext.files`, .NET `Supertext.Sdk.Files.TranslationFiles`, Java `com.supertext.sdk.files.TranslationFiles`.

**A job** is one item in one target language: `plugin` (the integration's repository name), `reference` (whatever the integration needs to find the item again, such as `page:12`; the SDK doesn't read it), `sourceLanguage`, `targetLanguage`, and the same keyed segments `translateTexts` takes (key unique in the job, text, `html`). Blank segments are left out; a job with nothing to translate is refused (`invalid_job`).

**Two formats:**

- **XLIFF 1.2** (`exportXliff(jobs)`): one `<file>` per job, so a bulk export of many items or languages is one file. `original` holds the reference, `product-name` the plugin, one `<trans-unit>` per segment with the key as `id` and a checksum of the source in `extradata`. Formatting in HTML segments becomes inline codes (`<bpt>`/`<ept>` for a tag pair, `<ph>` for `<br>`, images and the like) that CAT tools protect, so translators see text and tags they can move but not break.
- **HTML** (`exportHtml(job)`): the document the API gets (section 7), with the job's metadata and each segment's key and checksum as `<meta name="supertext:…">` in its head. It can be uploaded to Supertext's web app, which keeps the head and the `data-st-id` attributes (confirmed October 2026), so the translated file imports as it is; anyone else who translates HTML must leave both alone. An HTML file can't mark a segment as untranslated: imported unchanged, it writes the source text back.

**Importing** is two steps, because the integration needs the metadata before it can collect the current content:

1. `read(content)` checks the file and returns its jobs (XLIFF: one per `<file>`; HTML: one) with `plugin`, `reference`, the languages and the keys. Check that `plugin` is yours and the target language is one the site has, then load the item by `reference` (with the user's permissions).
2. `importTranslations(file, currentSegments)` with the item's segments collected again exactly as for the export. One result per non-blank segment: `ok` (write it back), `source_changed` (the source was edited after the export: show the count and let the editor choose to apply or skip, default skip), `untranslated`, `invalid_markup` (the translation broke or added markup) or `missing` (the file doesn't have that key, e.g. a field added since). Write back with the same rules as API results (section 11): don't overwrite existing translations without asking, new translations unpublished for review, and tell the user how many segments were imported and why the others weren't.

**Safety: an imported file is untrusted input.** `read` refuses files over 10,000,000 characters (`too_large`), anything that isn't XLIFF or HTML or isn't well-formed (`invalid_file`; a DTD or entity declaration is always refused, so nothing is expanded), and files without the metadata (`missing_metadata`). `importTranslations` takes markup only from the current source: XLIFF codes are replaced by the source's own tags (the code content in the file is ignored), and an HTML translation may only use tags, attributes and comments the source segment has, with links that are relative or `http`, `https`, `mailto` or `tel`; text is escaped. Only accept uploads from users who may edit the item, and limit the upload size before reading.

**In the plugin UI:** *Export for translation* (format: XLIFF or HTML; target languages; for list views several items) and *Import translation* (file upload, then a summary: imported, skipped because the source changed, untranslated, invalid). File names like `<plugin>-<reference>-<target>.xlf` help people keep files apart; the SDK doesn't depend on them.

## 16. Checklist

- [ ] `Authorization: Supertext-Auth-Key <key>`, pasted prefix stripped, blank key = no request
- [ ] Language mapping per CMS language (combo box of the Supertext targets, `suggestTarget` preselected), defaults only from `Languages.defaultTarget`; `unsupported_language` shown with where to map it; formal/informal only where the target supports it
- [ ] File part Content-Type exactly `text/html`, `<meta charset="utf-8">` in the document
- [ ] All fields of an item in one document per language; documents under 1,000,000 characters
- [ ] One `data-st-id` per paragraph/field, formatting as inline tags, structure kept when writing back
- [ ] 429 retried (Retry-After, else 1/2/4/8 s + jitter), languages one after the other, no API calls inside per-element hooks
- [ ] Status polling with a timeout; delete after every upload, also after errors
- [ ] Missing segments are errors, not empty values
- [ ] Existing translations not overwritten without asking; new translations unpublished for review
- [ ] Error messages by code, in the user's language, with the account and API key links for key errors
- [ ] Key stored safely, never logged, server-side only; permissions checked
- [ ] Key checked on every settings save (and when an environment key changes, on 401/403, on *Test connection*), result shown with both links
- [ ] Version reports and the ping endpoint with Supertext Plugin Health (*Send version information to Supertext* setting, on by default; reports from server code only)
- [ ] Translation files (if offered): `TranslationFiles` for both formats, the item found by `reference`, segments collected again for the import, `source_changed` asked about, upload size and permissions checked
- [ ] Tests with a fake HTTP layer and against the stand-in; a manual round against the live API
