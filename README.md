# Supertext API: integration guide and SDKs

Everything you need to connect a CMS, shop, PIM or your own software to Supertext's AI translation API: a guide built from more than 30 Supertext plugins, and SDKs for five languages that handle the tricky parts for you.

- **[Integration guide](docs/integration-guide.md)**: the protocol, language codes, how to cut content into segments, rich text, rate limits, errors, writing translations back, security, testing, XLIFF and HTML translation files, and a checklist.
- **[API reference](docs/api-reference.md)**: endpoints, fields, statuses and HTTP errors on one page.
- **SDKs**: below.
- **[Test vectors](spec/)** and a **[stand-in API](stand-in/stand-in.mjs)** for your own tests.

You need a Supertext API key. No Supertext account yet? Create one at [supertext.com](https://www.supertext.com/person/en/account/signin). Generate your API key at [supertext.com → Integrations → API](https://www.supertext.com/en/integrations/api) (requires the Admin role).

## SDKs

| Language | Package | Requirements | Docs |
| --- | --- | --- | --- |
| TypeScript / JavaScript | `npm install supertext-sdk` | Node 18+, Deno, Bun; no dependencies | [sdk/typescript](sdk/typescript/README.md) |
| PHP | `composer require supertext/supertext-sdk` | PHP 8.1+; no dependencies (ext-curl only for the built-in HTTP transport) | [sdk/php](sdk/php/README.md) |
| Python | `pip install supertext-sdk` | Python 3.10+; standard library only | [sdk/python](sdk/python/README.md) |
| .NET | `dotnet add package Supertext.Sdk` | .NET 8+ | [sdk/dotnet](sdk/dotnet/README.md) |
| Java | `com.supertext:supertext-sdk` (Maven) | Java 11+; no dependencies | [sdk/java](sdk/java/README.md) |

The packages aren't on the public registries yet; until they are, install from this repository or the files attached to a [release](https://github.com/Supertext/Supertext-API-SDK-and-Guide/releases).

All five offer the same things, named the way each language expects, in a **`v1` namespace** for the API version (TypeScript `supertext-sdk/v1`, PHP `Supertext\Sdk\V1`, Python `supertext.v1`, .NET `Supertext.Sdk.V1`, Java `com.supertext.sdk.v1`), so a later API version can sit next to it:

- `translateTexts(segments, target, source?, politeness?)`: many keyed texts (plain or HTML) in one go, split into documents under the size limit, translated one after the other, read back by key.
- `translateHtml(html, target, source?, politeness?)`: one HTML document, the full round trip.
- The single steps (`submit`, `getStatus`, `waitUntilDone`, `download`, `delete`), `validateApiKey` (a cost-free key check for settings pages) and `getFeatures` (the language pairs the API offers right now).
- `pendingCorrections` and `acknowledgeCorrections`: corrections reviewers made in [Supertext Localization Review](https://github.com/Supertext/Localization-Review-Supertext-Translation), fetched from its review service and confirmed once written into the CMS. See the [guide](docs/integration-guide.md#corrections-from-reviewers-supertext-localization-review).
- `checkApiKey`: the automatic key check with its result (status, HTTP status, Supertext's error code, a local key fingerprint). Version reports to the Commando Center are [Supertext Plugin Health](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health)'s job; it uses this check. See the [guide](docs/integration-guide.md#14-the-api-key-check-and-version-reports).
- `Languages`: the API's source and target languages, an exact match for CMS codes (`de_CH` → `de-CH`), the integration's language mapping, the options and suggestion for a mapping combo box, defaults only where they are obvious (`de` → `de-DE`, `zh` → `zh-Hans`, `es-ES` → `es`), and which targets offer formal/informal. Anything else unmapped (`en-IE`, `gsw`) is the error `unsupported_language`. The client sends the codes it is given.
- `HtmlDocument`: the segment packing, if you drive the steps yourself.
- `TranslationFiles` (outside `v1`, it doesn't call the API): export segments as XLIFF 1.2 or HTML for translation elsewhere (a CAT tool, a translator, Supertext's web app) and import the translated file safely, with a check whether the source changed since. See the [guide](docs/integration-guide.md#15-translation-files-xliff-and-html).
- One error type with stable codes (`authentication_failure`, `invalid_language_pair`, `quota_exceeded`, …) to show users a message in their language.
- Built in: the `Supertext-Auth-Key` header (pasted prefixes stripped), the exact multipart upload the API accepts, HTTP 429 retries with `Retry-After`, polling with a timeout, and deleting the file afterwards, also after errors.

Quick look (TypeScript; the others are in their READMEs):

```ts
import { Languages, SupertextClient } from 'supertext-sdk/v1'

const client = new SupertextClient(process.env.SUPERTEXT_API_KEY ?? '')
const mapping = { de: 'de-CH' } // the plugin's language setting: CMS language → Supertext code
const german = await client.translateTexts(
  { title: 'Hello & welcome', body: { text: '<p>Our <b>new</b> shop opens on Monday.</p>', html: true } },
  Languages.target('de', mapping), // 'de-CH'; unmapped, 'de' would be its default 'de-DE'
  { sourceLang: Languages.source('en-US'), politeness: 'more' },
)
```

## Testing your integration

```sh
node stand-in/stand-in.mjs     # Supertext stand-in on http://127.0.0.1:8765/v1/
```

It enforces the rules the live API enforces (auth header, language codes, the file's Content-Type, size limit) and "translates" by putting `[de-CH] ` in front of each segment. See [Testing](docs/integration-guide.md#13-testing).

## Contributing

See [docs/DEVELOPER.md](docs/DEVELOPER.md): repository layout, running the tests, adding a language, releasing. Security problems: [SECURITY.md](SECURITY.md).

<!-- supertext-plugins:start (shared list, keep identical in every Supertext plugin repo) -->
## Supertext plugins for other systems

Supertext offers AI and professional translation plugins for these systems:

### Content management systems (CMS)

| System | Plugin | Type of integration | What it does |
| --- | --- | --- | --- |
| Adobe Experience Manager | [supertext-aem-connector](https://github.com/Supertext/supertext-aem-connector) | Translation connector: two AEM content packages for AEM's Translation Integration Framework. | Sends AEM translation projects to Supertext and imports the results |
| ApostropheCMS | [Apostrophe-Supertext-Translation](https://github.com/Supertext/Apostrophe-Supertext-Translation) | Apostrophe module (npm): a translation provider for Apostrophe's own *Localize…* step. | Translates pages and pieces as editors localize them, widgets and rich text included |
| Contao | [Contao-Supertext-Translation](https://github.com/Supertext/Contao-Supertext-Translation) | Contao bundle (Composer) that adds a back-end action. | *Translate with Supertext* in the site structure: pages or whole websites into other languages |
| Craft CMS | [CraftCms-Supertext-Translation](https://github.com/Supertext/CraftCms-Supertext-Translation) | Craft plugin (Composer) with a panel on the entry page. | Translates entries into your other sites, Matrix and rich text included |
| Directus | [Directus-Supertext-Translation](https://github.com/Supertext/Directus-Supertext-Translation) | Directus extension bundle (npm): interface, endpoint, Flow operation and module. | *Translate with Supertext* box on the item form, fills the Translations field |
| django CMS | [djangoCMS-Supertext-Translation](https://github.com/Supertext/djangoCMS-Supertext-Translation) | Django app (Python package) that adds a toolbar entry. | Translates pages and their plugins from the toolbar |
| Drupal | [tmgmt_supertext_ai](https://www.drupal.org/project/tmgmt_supertext_ai) | Drupal module: a translator provider for the Translation Management Tool (TMGMT), by MD Systems. | Translates TMGMT jobs with Supertext AI |
| Ghost | [Ghost-Supertext-Translation](https://github.com/Supertext/Ghost-Supertext-Translation) | Separate connector service (Ghost has no admin plugins): works through internal tags, webhooks and the Admin API. | Tag a post `#translate-…` and a translated draft appears |
| Grav | [Grav-Supertext-Translation](https://github.com/Supertext/Grav-Supertext-Translation) | Grav 2 plugin with an Admin2 panel. | Supertext panel in the page editor, Markdown kept intact |
| HubSpot | [HubSpot-Supertext-Translation](https://github.com/Supertext/HubSpot-Supertext-Translation) | Hosted app connected to HubSpot with OAuth, working through HubSpot's multi-language content. | Translates website and landing pages, blog posts, marketing emails and knowledge base articles into draft language versions |
| Joomla | [Joomla-Supertext-Translation](https://github.com/Supertext/Joomla-Supertext-Translation) | Joomla system plugin (installable package). | Translates articles into linked, unpublished language versions |
| Magnolia | [Magnolia-Supertext-Translation](https://github.com/Supertext/Magnolia-Supertext-Translation) | Magnolia module with a *Translate with Supertext* action in the Pages app. | Translates pages, areas and components into the site's other languages |
| Neos | [Neos-Supertext-Translation](https://github.com/Supertext/Neos-Supertext-Translation) | Neos package (Composer) that hooks into the content repository, with a Supertext backend module for translation files. | Translates automatically when an editor creates a page in another language |
| Orchard Core | [OrchardCore-Supertext-Translation](https://github.com/Supertext/OrchardCore-Supertext-Translation) | Orchard Core module (.NET) with an admin page and a localization hook. | Translates content items into other cultures, on demand or on localization |
| Payload CMS | [Payload-Supertext-Translation](https://github.com/Supertext/Payload-Supertext-Translation) | Payload plugin (npm) added to `payload.config`. | *Translate* button for localized collections and globals |
| Silverstripe | [Silverstripe-Supertext-Translation](https://github.com/Supertext/Silverstripe-Supertext-Translation) | Silverstripe module (Composer) on top of Fluent. | Supertext tab translates pages and Elemental blocks into Fluent locales |
| Strapi | [Strapi-Supertext-Translation](https://github.com/Supertext/Strapi-Supertext-Translation) | Strapi 5 plugin (npm) with a Content Manager panel. | Translates entries into other locales from the Content Manager |
| TYPO3 | [Typo3-Supertext-Translation](https://github.com/Supertext/Typo3-Supertext-Translation) | TYPO3 extension (Composer) that hooks into TYPO3's own localization, with a Supertext backend module for translation files. | Translates pages and content elements as editors localize them |
| Umbraco | [Umbraco-Supertext-Translation](https://github.com/Supertext/Umbraco-Supertext-Translation) | Umbraco package (NuGet) with a backoffice extension. | *Translate with Supertext* for pages, block lists and grids included |
| Wagtail | [Wagtail-Supertext-Translation](https://github.com/Supertext/Wagtail-Supertext-Translation) | Python package: a machine translator for wagtail-localize. | Translates pages and snippets inside wagtail-localize's editor |
| WordPress (Polylang) | [Wordpress-Polylang-Supertext-Translation](https://github.com/Supertext/Wordpress-Polylang-Supertext-Translation) | WordPress plugin: a machine-translation service for Polylang Pro, plus professional translation orders. | AI translation next to DeepL in Polylang, and human translation orders |

### Product information management (PIM)

| System | Plugin | Type of integration | What it does |
| --- | --- | --- | --- |
| Akeneo PIM | [Akeneo-Supertext-Translation](https://github.com/Supertext/Akeneo-Supertext-Translation) | Akeneo PIM bundle with a *Translate with Supertext* action on the product page. | Translates products and product models into your other locales |
| AtroPIM | [AtroPIM-Supertext-Translation](https://github.com/Supertext/AtroPIM-Supertext-Translation) | AtroCore module with a button on the product and a mass action in the list. | Translates products and other AtroCore records into your other languages |
| Pimcore | [Pimcore-Supertext-Translation](https://github.com/Supertext/Pimcore-Supertext-Translation) | Pimcore bundle (Composer) with a *Translate with Supertext* button in Pimcore Studio. | Translates documents into linked language versions and data objects' localized fields |

### E-commerce

| System | Plugin | Type of integration | What it does |
| --- | --- | --- | --- |
| Magento | [Magento-Supertext-Translation](https://github.com/Supertext/Magento-Supertext-Translation) | Magento 2 module (also Mage-OS) with a mass action in the admin lists and a button on the edit pages. | Translates products, categories, CMS pages and blocks into your store views' languages |
| PrestaShop | [PrestaShop-Supertext-Translation](https://github.com/Supertext/PrestaShop-Supertext-Translation) | PrestaShop module with a bulk action in the back-office lists. | Translates products, categories and CMS pages into your shop's other languages |
| Shopify | [Shopify-Supertext-Translation](https://github.com/Supertext/Shopify-Supertext-Translation) | Shopify app in the Shopify admin. | Translates products, collections, pages and blog posts into all your shop's languages |
| Wix | [Wix-Supertext-Translation](https://github.com/Supertext/Wix-Supertext-Translation) | Wix app with a dashboard page (hosted service), working through Wix Multilingual. | *In development:* translates Wix Stores products and other Wix Multilingual content into your site's languages |

### Email marketing

| System | Plugin | Type of integration | What it does |
| --- | --- | --- | --- |
| Mailchimp | [Mailchimp-Supertext-Translation](https://github.com/Supertext/Mailchimp-Supertext-Translation) | Hosted app connected to Mailchimp with OAuth: Supertext folders in Mailchimp and a translate page. | *In development:* turns a campaign into one draft per language, each sent to the subscribers with that language |

### Design files (XLIFF round trip)

| Application | Plugin | Type of integration | What it does |
| --- | --- | --- | --- |
| Adobe InDesign | [Adobe-InDesign-Translation](https://github.com/Supertext/Adobe-InDesign-Translation) | InDesign scripts (ExtendScript). | Exports all text to XLIFF 1.2 for any CAT tool and imports the translations with formatting intact |
| Adobe Illustrator | [Adobe-Illustrator-Translation](https://github.com/Supertext/Adobe-Illustrator-Translation) | Illustrator scripts (ExtendScript). | Exports all text to XLIFF 1.2 for any CAT tool and imports the translations with formatting intact |
| CorelDRAW | [CorelDRAW-Supertext-Translation](https://github.com/Supertext/CorelDRAW-Supertext-Translation) | CorelDRAW VBA macro. | Exports all text to XLIFF 1.2 for any CAT tool and imports the translations with formatting intact |
<!-- supertext-plugins:end -->

## License

[MIT](LICENSE)
