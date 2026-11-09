# Supertext SDK for PHP

Client for the Supertext AI translation API: translate HTML and texts, with the supported languages, rate-limit retries and HTML packing handled for you.

PHP 8.2+, no other dependencies. The built-in HTTP transport uses ext-curl; if you plug in your own HTTP client (see [Your own HTTP client](#your-own-http-client)), curl isn't needed. For the protocol and the lessons behind it, see the [integration guide](../../docs/integration-guide.md).

## Install

```bash
composer require supertext/supertext-sdk
```

Without Composer, copy `sdk/php` into your project and `require 'path/to/sdk/php/autoload.php';`.

You need an API key. No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

## Quick start

Everything that talks to the API is in the namespace of its API version, `Supertext\Sdk\V1` (a later API version gets `Supertext\Sdk\V2` next to it, so integrations can move at their own pace). Only the HTTP transport (`Supertext\Sdk\Http`) and `Supertext\Sdk\Supertext::SDK_VERSION` are version-neutral.

```php
use Supertext\Sdk\V1\Client;
use Supertext\Sdk\V1\Segment;

$client = new Client(getenv('SUPERTEXT_API_KEY'), userAgent: 'MyCms-Supertext/1.0.0');

// Keyed texts: plain text (escaped, line breaks kept) or HTML fragments.
$translations = $client->translateTexts([
    'title' => 'Hello & welcome',
    'body'  => Segment::html('<p>Our <b>new</b> shop opens on <a href="https://example.com">Monday</a>.</p>'),
], targetLang: 'de-CH', sourceLang: 'en');
// ['title' => 'Hallo und willkommen', 'body' => '<p>Unser <b>neuer</b> Shop …</p>']

// A whole HTML document.
$html = $client->translateHtml('<html><body><p>Hello</p></body></html>', 'fr-CH');
```

`translateTexts()` returns the same keys in the same order. Blank texts come back unchanged and aren't sent; if nothing is left, no request is made. Texts are packed into as few documents as possible (`maxCharacters`, default 900,000) and the documents are translated one after the other. Note that PHP turns numeric string keys (`"1"`) into integers; the result uses the same keys as your array.

Send a whole paragraph, heading or field as **one** segment, with formatting and links as inline tags. Supertext translates every segment on its own: splitting a sentence at its formatting breaks the translation.

The steps are available on their own too: `submit()` → `waitUntilDone()` → `download()` → `delete()` (never throws), `getStatus()`, `getFeatures()` (the language pairs the API offers, see [Languages](#languages)), `validateApiKey()` (a cost-free check that throws) and `checkApiKey()` (the same check as a result, see [below](#api-key-check-version-reports-and-ping)). `HtmlDocument::build()` / `parse()` pack and unpack segments if you drive the steps yourself.

## Languages

The client sends language codes **as you give them** (trimmed). Supertext expects exact codes: the source as a bare language (`en`), the target as listed in `Languages::TARGETS`: regional for German, English, French, Italian and Portuguese (`de-CH`, `en-US`, `pt-BR`), a script for Chinese and Serbian (`zh-Hans`, `sr-Latn`), a dialect for Swiss German (`gsw-u-sd-chzh`), bare for the others (`es`, `nl`). Anything else is rejected with `invalid_language_pair`.

Whether a site's `de` means Germany or Switzerland is the administrator's decision. Give your integration a language setting (a combo box per CMS language from `Languages::targetOptions()`, `Languages::suggestTarget($code)` preselected) and resolve every CMS language with `Languages` before calling the client. A language nobody mapped uses its default where one is obvious (`de` → `de-DE`, `en` → `en-US`, `zh` → `zh-Hans`, `zh-TW` → `zh-Hant`, `es-ES` → `es`; `Languages::DEFAULT_TARGETS`); anything else (`en-IE`, `gsw`) is `unsupported_language`:

```php
use Supertext\Sdk\V1\Languages;

$mapping = ['de' => 'de-CH', 'no' => 'nb']; // the administrator's setting: CMS language → Supertext code

Languages::target('de', $mapping);  // "de-CH" (the mapping wins; its value is sent as it is)
Languages::target('fr_fr');         // "fr-FR" (equal to a supported code apart from case and _/-)
Languages::target('de');            // "de-DE" (the default)
Languages::target('en-IE');         // SupertextException unsupported_language (targetLang "en-IE"): ask the administrator to map it
Languages::suggestTarget('de_ch');  // "de-CH" (exact match, else the default, else null): preselect it in the combo box
Languages::source('en-US');         // "en" (sources have no regions)
Languages::source('');              // "" (auto-detect)

$client->translateTexts($texts, Languages::target($cmsLocale, $mapping), Languages::source($cmsSourceLocale));
```

- `Languages::SOURCES` (`code`, `name`) and `Languages::TARGETS` (`code`, `name`, `politeness`): the languages the API offered in October 2026, e.g. for the setting's drop-downs.
- `matchTarget($code)` / `matchSource($code)`: the supported code, or `null` (`matchSource` also takes the first subtag: `en-GB` → `en`). Use them to pre-fill the setting.
- `defaultTarget($code)`: the default for a code without an exact match, else null; `suggestTarget($code)`: exact match, else default, else null; `targetOptions()`: `TARGETS` sorted by name for the combo box (show the name localized where you can, e.g. Symfony Intl or `Locale::getDisplayName()`, and the code next to it).
- `target($code, $mapping = [])` / `source($code, $mapping = [])`: the code to send (mapping, exact match, for targets the default); `unsupported_language` (nothing is sent) when there is none. A mapping value is sent even if it isn't in the list, so a language Supertext adds later works before the SDK knows it.
- `supportsPoliteness($target)`: whether formal/informal is offered (`null` for an unknown code). Hide the choice for the others: the API answers `unsupported_feature`.
- `isSupportedTarget($code)`, `isSupportedSource($code)`.

For the live list, `$client->getFeatures()` returns the API's language pairs as `FeatureSupport` objects (`sourceLang`, `targetLang`, `features` such as `supports_politeness`); it is cost-free.

## Politeness

`Politeness::DEFAULT` (default), `Politeness::MORE` (formal: Sie, vous, Lei) or `Politeness::LESS` (informal: du, tu). Only `more` and `less` are sent; any other value lets Supertext decide. Not every target offers it (`Languages::supportsPoliteness()`; e.g. not `en-US`, `rm`, `sq`): sending `more` or `less` for those fails with `unsupported_feature`.

```php
$client->translateTexts($texts, 'de-CH', 'en', Politeness::MORE);
```

## Errors

Everything fails with `Supertext\Sdk\V1\SupertextException`. `$e->errorCode` is one of the constants below (the same strings in every Supertext SDK); `$e->status` (HTTP status), `$e->detail` (short text from the answer), `$e->apiErrorCode` (e.g. `INVALID_LANGUAGE_PAIR`), for language problems `$e->sourceLang` / `$e->targetLang`, and for `unsupported_feature` `$e->feature` (e.g. `politeness`) help with messages and logs. Messages are English and never contain the API key.

| Code | What to tell the user |
| --- | --- |
| `missing_api_key` | No API key is configured (nothing was sent). Show the account and API key links above. |
| `authentication_failure` | The API key is wrong, revoked or lacks access. Show the account and API key links above. |
| `invalid_language_pair` | Supertext doesn't support this language pair. Check the language settings. |
| `unsupported_language` | This language has no Supertext code (nothing was sent; `targetLang` or `sourceLang` names it). Ask the administrator to map it. |
| `unsupported_feature` | Supertext offers the language pair, but not with this option (`feature`, e.g. `politeness`). Hide or reset the option for that language. |
| `not_found` | The file expired or was deleted. Try again. |
| `payload_too_large` | The content is too large. Lower `maxCharacters` or split it. |
| `too_many_requests` | Too many requests, even after retries. Try again shortly. |
| `service_unavailable` | Supertext is temporarily unavailable. Try again later. |
| `unexpected_status` | Supertext answered unexpectedly (see `status` and `detail`). |
| `transport_error` | Supertext could not be reached (network, DNS, TLS, timeout). |
| `no_file_id` | The upload got no file id. Try again. |
| `translation_error` | Supertext could not translate the document. |
| `quota_exceeded` | The account's translation limit is used up. Upgrade the subscription. |
| `file_deleted` | The file was deleted before the download. Try again. |
| `timeout` | The translation took longer than `timeout`. Try again, or raise the timeout. |
| `incomplete_response` | The translation was empty or incomplete (`detail` lists missing keys). Try again. |

## Options

All constructor arguments after the key are optional and best passed by name:

| Option | Default | |
| --- | --- | --- |
| `baseUrl` | `https://api.supertext.com/v1/` | API base URL (a trailing slash is added) |
| `pollInterval` | `2.0` | Seconds between status polls |
| `timeout` | `180.0` | Seconds to wait for a translation, from the first poll |
| `requestTimeout` | `30.0` | Seconds per HTTP request |
| `maxRetries` | `4` | Retries after HTTP 429 (waits `Retry-After`, else 1, 2, 4, 8 s plus jitter; at most 30 s) |
| `userAgent` | `null` | Appended to `User-Agent: supertext-sdk-php`, e.g. `MyCms-Supertext/1.0.0` |
| `transport` | `CurlTransport` (needs ext-curl) | Any `Http\HttpTransport`, e.g. wrapping your framework's HTTP client ([below](#your-own-http-client)) |
| `sleep`, `clock`, `now` | real time | Injectable for tests (`now`: wall-clock Unix seconds, for `checkApiKey()`) |

A blank key doesn't fail in the constructor, only on the first request (`missing_api_key`), so you can create the client before the integration is configured. Long translations block the PHP process while polling; run them in a queue or CLI job, or raise `max_execution_time`.

## Your own HTTP client

CMSs and frameworks usually bring an HTTP client with their proxy, TLS and logging settings (Contao, Symfony and Craft use Symfony HttpClient or Guzzle). Wrap it in an `HttpTransport` and pass it as `transport`; then ext-curl isn't needed. Without curl and without a transport, `new Client()` throws a `LogicException` saying so.

Return every HTTP answer, including 4xx and 5xx, as an `HttpResponse`; throw only when there is no answer at all (the client turns that into `transport_error`). Example for Symfony HttpClient:

```php
use Supertext\Sdk\Http\HttpRequest;
use Supertext\Sdk\Http\HttpResponse;
use Supertext\Sdk\Http\HttpTransport;
use Symfony\Contracts\HttpClient\HttpClientInterface;

final class SymfonyHttpTransport implements HttpTransport
{
    public function __construct(private readonly HttpClientInterface $http)
    {
    }

    public function send(HttpRequest $request): HttpResponse
    {
        $response = $this->http->request($request->method, $request->url, [
            'headers' => $request->headers,
            'body' => $request->body ?? '',
            'max_duration' => $request->timeout,
        ]);

        // false: return 4xx/5xx answers instead of throwing.
        $headers = array_map(static fn (array $values): string => implode(', ', $values), $response->getHeaders(false));

        return new HttpResponse($response->getStatusCode(), $response->getContent(false), $headers);
    }
}

$client = new Client($apiKey, transport: new SymfonyHttpTransport($httpClient));
```

For Guzzle, send with `'http_errors' => false` and `'timeout' => $request->timeout`, and build the `HttpResponse` from the status, `(string) $response->getBody()` and the headers the same way.

## Corrections from reviewers

With Supertext Localization Review, point the client at its review service (`$baseUrl`) and fetch the corrections reviewers made, for content you don't translate again. Use your own `data-st-id`s (build the document yourself, `translateHtml()`) so `segmentId` tells you the field; see the integration guide → *Corrections from reviewers*.

```php
$corrections = $client->pendingCorrections('de-CH');  // list<Correction>, all pages
// write $c->html where $c->segmentId points, if the field still holds $c->previousHtml
$client->acknowledgeCorrections($corrections);        // → int accepted
```

Against Supertext's API these throw `not_found`.

## API key check

Every Supertext plugin checks its API key automatically (test vectors: `spec/api-key-check.json`).

`$client->checkApiKey()` sends the cost-free `GET features` (10 s timeout, or the request timeout if shorter; the usual 429 retries) and never throws. It returns an `ApiKeyCheck` with `status` (`ApiKeyCheck::OK` for 2xx, `INVALID` for 401/403, `MISSING` for a blank key without a request, `UNREACHABLE` without an answer or for 5xx, `ERROR` otherwise), `checkedAt` (`2026-10-13T08:00:00Z`), `httpStatus`, `errorCode` (the answer's `error_code`) and `fingerprint`. Show it on the settings page; for `invalid` and `missing`, add the account and API key links: No Supertext account yet? Create one at https://www.supertext.com/person/en/account/signin. Generate your API key at https://www.supertext.com/en/integrations/api (supertext.com → Integrations → API; requires the Admin role).

`Protocol::apiKeyFingerprint($key)` (`Supertext\Sdk\V1\Protocol`, next to `authHeader()` and `retryDelayMs()`) is the SHA-256 of the key as it is sent (prefix stripped), `''` for a blank key. It is kept locally to notice a changed key and is never sent.

Version reports to the Supertext Commando Center and its ping endpoint are [Supertext Plugin Health](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health), not this SDK. Its key checker is `fn ($key) => (new Client($key))->checkApiKey()` with `Protocol::apiKeyFingerprint` as the fingerprint, and its `sdkVersion` is `Supertext\Sdk\Supertext::SDK_VERSION`.

## Translation files

For translations made outside the API (a CAT tool, a translator, Supertext's web app), `Supertext\Sdk\Files\TranslationFiles` exports segments as an XLIFF 1.2 or HTML file and imports the translated file again (test vectors: `spec/translation-file.json`). It is not tied to an API version and never calls the API.

```php
use Supertext\Sdk\Files\{FileJob, FileSegment, ImportedSegment, TranslationFiles};

$job = new FileJob('Contao-Supertext-Translation', 'page:12', 'de-CH', 'fr-CH', [
    new FileSegment('title', $page->title),
    new FileSegment('body', $page->bodyHtml, html: true),
]);
file_put_contents('page-12.xlf', TranslationFiles::exportXliff([$job]));   // or exportHtml($job)

// Later, with the uploaded file:
foreach (TranslationFiles::read($uploaded) as $file) {
    $page = findPage($file->reference);                                      // e.g. "page:12"
    $current = [new FileSegment('title', $page->title), new FileSegment('body', $page->bodyHtml, html: true)];
    foreach (TranslationFiles::importTranslations($file, $current) as $segment) {
        match ($segment->status) {
            ImportedSegment::OK => $page->setTranslation($segment->key, $segment->text),
            ImportedSegment::SOURCE_CHANGED => $page->askBeforeWriting($segment->key, $segment->text),
            default => $report[] = [$segment->key, $segment->status],         // untranslated, invalid_markup, missing
        };
    }
}
```

`checksum()` (FNV-1a of the packed segment) marks each segment in the file, so `source_changed` tells you the source was edited since the export. Blank segments are left out; a job without text, an empty or duplicate key or empty metadata throws `TranslationFileError` with `$errorCode` `invalid_job`.

Imported files are untrusted: `read()` refuses DTDs and entity declarations (`invalid_file`), files larger than `TranslationFiles::MAX_FILE_CHARACTERS` (10,000,000 bytes, `too_large`) and files without the job's metadata (`missing_metadata`). `importTranslations()` takes markup only from the current source: XLIFF inline codes are replaced by the source's own tags, and an HTML translation may only use the tags, attributes and comments the source has, with safe URLs; anything else is `invalid_markup`.

## Testing against the stand-in

The repository ships a stand-in API (Node 22+) that behaves like the live API where integrations went wrong before:

```bash
node stand-in/stand-in.mjs   # prints http://127.0.0.1:8765/v1/
```

```php
$client = new Client('any-key', baseUrl: 'http://127.0.0.1:8765/v1/', pollInterval: 0.1);
```

Texts come back with `[de-DE] ` in front. In unit tests, pass a fake `transport` and a no-op `sleep` instead.

## Development

From the repository root:

```bash
composer install
vendor/bin/phpunit -c sdk/php/phpunit.xml.dist
vendor/bin/phpstan analyse -c sdk/php/phpstan.neon
find sdk/php -name '*.php' -not -path '*/vendor/*' -print0 | xargs -0 -n1 php -l
node stand-in/conformance.mjs -- php sdk/php/examples/conformance.php
```

The tests run every case in `spec/` (the test vectors shared by all Supertext SDKs).

## License

MIT
