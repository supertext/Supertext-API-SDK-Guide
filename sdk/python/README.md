# Supertext SDK for Python

Client for the [Supertext](https://www.supertext.com) AI translation API: translate HTML and texts, with language codes, rate-limit retries and HTML packing handled for you.

- Python 3.11+, standard library only (no dependencies), fully typed.
- Packs what Supertext's CMS, PIM and shop plugins learned against the live API. Background: [integration guide](../../docs/integration-guide.md).

## Install

```sh
pip install supertext-sdk
```

```python
from supertext import v1          # or: from supertext.v1 import SupertextClient
```

Everything that talks to the API lives in `supertext.v1` (API v1). A later API version gets `supertext.v2` next to it, so an integration moves when it's ready. The top-level package holds only `v1` and `SDK_VERSION`.

## API key

The client needs a Supertext AI API key. No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

Paste it as shown there, with or without the `Supertext-Auth-Key` prefix. Keep it out of your code, e.g. in the `SUPERTEXT_API_KEY` environment variable. `client.validate_api_key()` checks it without cost.

## Quick start

### Texts (fields of a page, product, …)

```python
import os
from supertext import v1
from supertext.v1 import Languages, Segment

client = v1.SupertextClient(os.environ["SUPERTEXT_API_KEY"], user_agent="MyCms-Supertext/1.0.0")

translations = client.translate_texts(
    {
        "title": "Hello & welcome",                                   # plain text
        "body": Segment("<p>Our <b>new</b> shop opens on Monday.</p>", html=True),
        "teaser": Segment("Line 1\nLine 2"),                          # line breaks survive
    },
    target_lang=Languages.target("de_CH"),   # "de-CH", see Languages below
    source_lang="en",                        # leave out to let Supertext detect it
    politeness="more",
)
translations["title"]  # "Hallo und willkommen"
```

- The result has the same keys in the same order.
- Plain text is escaped on the way out and unescaped on the way back; HTML stays HTML.
- One segment per whole text (field, paragraph): Supertext translates each segment on its own, so never split a sentence at its formatting.
- Blank texts come back unchanged and aren't sent; if nothing is left, no request is made.
- Large inputs are split into several documents of at most `max_characters` (default 900,000) and translated one after the other.

### A whole HTML document

```python
html = client.translate_html("<html><body><h1>Hello</h1></body></html>", "fr-CH")
```

Both calls run the whole protocol: upload, poll the status, download, and always delete the file at Supertext afterwards. The single steps are available too: `submit`, `get_status`, `wait_until_done`, `download`, `delete`, and `get_features` / `validate_api_key` / `check_api_key` (all cost-free `GET features`).

## Languages

The client sends the language codes **as given** (only trimmed): nothing is guessed or converted. An empty target raises `unsupported_language` without a request; an empty source is left out (Supertext detects the language).

Supertext's codes are its own: sources are bare languages (`en`, `de`, `gsw`), targets are what Supertext offers exactly, regional for German, English, French, Italian and Portuguese (`de-CH`, `en-US`, `pt-BR`), a script for Chinese and Serbian (`zh-Hans`, `sr-Latn`), a dialect for Swiss German (`gsw-u-sd-chzh`) and bare for the others (`es`, `nl`). A bare `de` as target is rejected (`invalid_language_pair`). Let the administrator map each CMS language to a Supertext language (a combo box per language from `Languages.target_options()`, `Languages.suggest_target(code)` preselected), and resolve the codes with `Languages` before calling. A language nobody mapped uses its default where one is obvious (`de` → `de-DE`, `en` → `en-US`, `zh` → `zh-Hans`, `zh-TW` → `zh-Hant`, `es-ES` → `es`; `Languages.DEFAULT_TARGETS`); anything else (`en-IE`, `gsw`) is `unsupported_language`:

```python
from supertext.v1 import Languages, SupertextError

mapping = {"de": "de-CH", "no": "nb"}         # CMS language → Supertext code (the integration's setting)
Languages.target(cms_locale, mapping)         # "de" → "de-CH", "no" → "nb"
Languages.target("de_ch")                     # "de-CH": a CMS code equal to a Supertext code (case, _/-) is used
Languages.target("de")                        # "de-DE" (the default)
Languages.target("en-IE")                     # SupertextError unsupported_language (target_lang "en-IE"): map it
Languages.default_target("es-ES")             # "es"; None when there is no default (or an exact match)
Languages.suggest_target("de_ch")             # "de-CH": exact match, else the default, else None (preselect it)
Languages.target_options()                    # TARGETS sorted by name, for the combo box
Languages.source("en-US")                     # "en" (sources have no regions)
Languages.source("")                          # "" (auto-detect)
Languages.match_target("pt_br")               # "pt-BR"; None when there is no exact match
Languages.supports_politeness("en-US")        # False: hide the formal/informal choice; None if unknown
Languages.is_supported_target("es")           # True
Languages.TARGETS                             # tuple of TargetLanguage(code, name, politeness), for a settings page
Languages.SOURCES                             # tuple of SourceLanguage(code, name)
```

- `Languages.target(code, mapping=None)`: the mapping's value (sent trimmed, even if the SDK doesn't know it yet, so a language Supertext adds later works), else the exact match in `Languages.TARGETS`, else `default_target`, else `SupertextError(unsupported_language)` with `target_lang` set. Mapping keys are compared like codes (case, `_`/`-`, spaces).
- `Languages.source(code, mapping=None)`: the mapping's value, else `match_source` (exact, else the first subtag: `de_DE_formal` → `de`), else `unsupported_language` with `source_lang` set.
- In the combo box, show each name in the administrator's language where you can (e.g. Babel's `Locale(ui_locale).languages`) and the code next to it.
- The functions are also available on the module: `supertext.v1.languages.target(…)`.

The list is from the live API (October 2026). For the live list, `client.get_features()` returns the language pairs Supertext offers right now, without cost:

```python
for pair in client.get_features():
    pair.source_lang, pair.target_lang, pair.features   # "de", "en-US", ("supports_glossary",)
```

Entries without string `source_lang` / `target_lang` are skipped.

## Politeness

`politeness="more"` asks for formal address (Sie, vous, Lei), `"less"` for informal (du, tu), `"default"` (the default) lets Supertext decide and sends nothing. Not every target offers it (`Languages.supports_politeness`); for one that doesn't (e.g. `en-US`), the API answers `unsupported_feature`, so hide the choice there.

## Errors

Every failure raises `supertext.v1.SupertextError`. Decide what to show from `error.code` (constants in `supertext.v1.ErrorCode`); `error.message` is English, `error.status` the HTTP status if any, `error.detail` the API's own text. For `invalid_language_pair` and `unsupported_feature`, `error.source_lang` / `error.target_lang` carry what the API reported, and for `unsupported_feature` `error.feature` the option (e.g. `politeness`); for `unsupported_language` they carry the code that has no Supertext language. `error.api_error_code` is the API's `error_code`. The API key never appears in errors.

| `code` | What to tell the user |
| --- | --- |
| `missing_api_key` | No API key is configured (nothing was sent). Show the account and API key links above. |
| `authentication_failure` | The API key is wrong, revoked or lacks access (HTTP 401/403). Show the links above. |
| `invalid_language_pair` | Supertext doesn't support this language pair (HTTP 400/404 `INVALID_LANGUAGE_PAIR`). Check the language settings. |
| `unsupported_language` | The language has no Supertext code (nothing was sent): `Languages.target`/`source` found no match or mapping, or the target was empty. Ask the administrator to map it. |
| `unsupported_feature` | The pair exists, but not with this option (HTTP 400 `FEATURE_ERROR`, e.g. politeness for `en-US`; `feature` names it). |
| `not_found` | The file is gone at Supertext (expired after 24 h or deleted). Try again. |
| `payload_too_large` | The content is too large; lower `max_characters` or split it. |
| `too_many_requests` | Too many requests, even after retrying. Try again shortly. |
| `service_unavailable` | Supertext is unavailable (HTTP 5xx). Try again later. |
| `unexpected_status` | Supertext answered with an unexpected HTTP status (see `status`/`detail`). |
| `transport_error` | Supertext couldn't be reached (network, DNS, TLS, request timeout). |
| `no_file_id` | The upload answer had no file id. Try again. |
| `translation_error` | Supertext couldn't translate the document. |
| `quota_exceeded` | The account's translation limit is used up. |
| `file_deleted` | The file was deleted at Supertext before the download. Try again. |
| `timeout` | The translation took longer than `timeout`. Try again, or raise `timeout`. |
| `incomplete_response` | The translation came back empty or with texts missing (`detail`: `missing: key1, key2`). |

```python
try:
    translations = client.translate_texts(texts, Languages.target(cms_locale, mapping))
except v1.SupertextError as error:
    if error.code == v1.ErrorCode.AUTHENTICATION_FAILURE:
        ...
```

## Corrections from reviewers

With Supertext Localization Review, point the client at its review service (`base_url`) and fetch the corrections reviewers made, for content you don't translate again. Use your own `data-st-id`s (build the document yourself, `translate_html`) so `segment_id` tells you the field; see the integration guide → *Corrections from reviewers*.

```python
corrections = client.pending_corrections("de-CH")  # list[Correction], all pages
# write c.html where c.segment_id points, if the field still holds c.previous_html
client.acknowledge_corrections(corrections)         # → int accepted
```

Against Supertext's API these raise `not_found`.

## API key check

`client.check_api_key()` sends `GET features` (at most 10 s) and never raises (test vectors: `spec/api-key-check.json`). It returns a frozen `ApiKeyCheck` with `status` (`ok`, `invalid` for 401/403, `missing` for a blank key without a request, `unreachable` for no answer or 5xx, `error` for anything else), `checked_at` (`2026-10-13T08:00:00Z`), `http_status`, `error_code` (the API's, at most 100 characters) and `fingerprint` (`api_key_fingerprint(key)`, SHA-256 of the key, to notice a changed key; the key itself is never stored or sent). `to_dict()` gives the camelCase form (`status`, `checkedAt`, `httpStatus`, `errorCode`, `fingerprint`). Show the result on the settings page; for `invalid` or `missing`, show the links: No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

Version reports to the Supertext Commando Center and its ping endpoint are [Supertext Plugin Health](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health), not this SDK. Pass it `SupertextClient(key).check_api_key()` and `api_key_fingerprint` as the key checker and `supertext.SDK_VERSION` as the SDK version.

## Options

```python
v1.SupertextClient(
    api_key,
    base_url=v1.LIVE_BASE_URL,         # "https://api.supertext.com/v1/"
    poll_interval=2.0,                 # seconds between status polls
    timeout=180.0,                     # seconds until a translation gives up (timeout)
    request_timeout=30.0,              # seconds per HTTP request (transport_error)
    max_retries=4,                     # HTTP 429 retries (Retry-After, else 1/2/4/8 s + jitter; max 30 s)
    user_agent=None,                   # appended to "supertext-sdk-python", e.g. "MyCms-Supertext/1.0.0"
    transport=None,                    # callable(HttpRequest) -> HttpResponse; default: urllib
    sleep=time.sleep,                  # injectable for tests
    clock=time.monotonic,              # injectable for tests
    now=time.time,                     # wall clock for check_api_key(); injectable for tests
)
```

A blank key doesn't fail in the constructor, only on the first request. The transport (`supertext.transport`, re-exported from `supertext.v1`) is shared by all API versions. A custom `transport` raises `OSError` when there's no HTTP answer; the client turns that into `transport_error`.

## Translation files

`supertext.files` exports segments as an XLIFF 1.2 or HTML file, to be translated outside the API (a CAT tool, a translator, Supertext's web app), and imports the translated file again (test vectors: `spec/translation-file.json`). It doesn't call the API, so it isn't versioned with it.

```python
from supertext import files
from supertext.files import FileJob, FileSegment

job = FileJob("MyCms-Supertext-Translation", "page:12", "de-CH", "fr-CH", [
    FileSegment("title", "Käse & Brot"),
    FileSegment("body", "<p>Unser <b>neuer</b> Laden</p>", html=True),
])
xliff = files.export_xliff([job])                      # or files.export_html(job)

# Later, with the uploaded file:
for file in files.read(uploaded):                      # one per job (<file>) in an XLIFF file
    current = collect_segments(file.reference)         # the item's segments as they are now
    for result in files.import_translations(file, current):
        if result.status == "ok":
            save(file.reference, file.target_language, result.key, result.text)
        elif result.status == "source_changed":        # translated, but the source changed since
            ask_or_save(result)
        else:                                          # untranslated, invalid_markup, missing
            report(result.key, result.status)
```

- `export_xliff` and `export_html` leave out blank segments; a job with nothing to translate, empty metadata or duplicate keys raises `TranslationFileError` (`code` `invalid_job`). `read` raises `too_large`, `invalid_file` or `missing_metadata`.
- Each unit carries a checksum of its source; `checksum(segment)` computes it. `source_changed` means the source differs from the exported one (or the file has no checksum): decide whether to keep the translation.
- Imported files are untrusted: `read` refuses DTDs and entity declarations (nothing is ever expanded) and files over `MAX_FILE_CHARACTERS` (10,000,000). Markup in the result comes only from the current source: XLIFF inline codes are replaced by the source's tags, and an HTML file's translation may only use the tags, attributes and comments the source has, with safe URLs; anything else is `invalid_markup`.

## Testing your integration

Use the stand-in API from this repository instead of the live API: it checks the request the way the live API does and puts the target code in front of every text (`[de-CH] …`).

```sh
node stand-in/stand-in.mjs            # http://127.0.0.1:8765/v1/
```

```python
client = v1.SupertextClient("any-key", base_url="http://127.0.0.1:8765/v1/", poll_interval=0.1)
```

For unit tests, pass a fake `transport` (and `sleep`) instead.

## Developing this SDK

From `sdk/python`:

```sh
pip install pytest mypy ruff
ruff check . && ruff format --check .
mypy --strict
pytest
```

Conformance against the stand-in, from the repository root:

```sh
node stand-in/conformance.mjs -- python3 sdk/python/examples/conformance.py
```

## License

MIT
