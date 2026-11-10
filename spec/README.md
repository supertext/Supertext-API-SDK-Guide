# Shared test vectors

Every SDK in this repository loads these files in its tests and must pass every case; integrations that implement the protocol themselves can use them too. The rules each file encodes are in its `description` and in the [integration guide](../docs/integration-guide.md).

| File | What it pins down |
| --- | --- |
| [`languages.json`](languages.json) | The API's source and target languages (from the live `GET features`), matching CMS codes exactly, the defaults (`defaultTargets`, `defaultTarget`, `suggestTarget`), the combo box order (`targetOptions`), the integration's mapping, `unsupported_language`, which targets offer politeness |
| [`auth-header.json`](auth-header.json) | The `Authorization` header from a pasted key, prefix stripping, blank keys |
| [`retry-delay.json`](retry-delay.json) | Waits before retrying HTTP 429 (`Retry-After`, backoff, jitter, cap) |
| [`errors.json`](errors.json) | HTTP answers and file statuses → error codes, `detail`, `apiErrorCode`, language pair |
| [`html-document.json`](html-document.json) | Packing segments into the HTML document (byte-exact) and reading them back |
| [`batching.json`](batching.json) | Splitting many segments into documents under the size limit |
| [`translation-file.json`](translation-file.json) | Translation files: exporting segments as XLIFF 1.2 and HTML (byte-exact), the source checksum, reading files back (metadata, hostile and broken files) and importing translations (statuses, markup only from the current source) |
| [`api-key-check.json`](api-key-check.json) | The API key check `checkApiKey()`: the key fingerprint and how the answer becomes `ok`/`invalid`/`missing`/`unreachable`/`error` (version reports that use it are Supertext Plugin Health's, in the Commando Center repository) |
| [`corrections.json`](corrections.json) | Corrections from reviewers (Supertext Localization Review's review service): the requests, paging, which items count, the acknowledge body and batches |
| [`conformance.json`](conformance.json) | End-to-end scenarios against the [stand-in](../stand-in/stand-in.mjs) (`node stand-in/conformance.mjs -- <program>`) |

Changing a vector changes all six SDKs: update the SDKs and their tests in the same commit, and note the behaviour change in `CHANGELOG.md`.
