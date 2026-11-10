package com.supertext.sdk.v1

/**
 * Why a Supertext call failed. The [value] strings are fixed and shared by all Supertext SDKs
 * (see `spec/errors.json`), so they can be stored or compared across languages.
 */
public enum class ErrorCode(
    /** The shared string value, e.g. `"invalid_language_pair"`. */
    public val value: String,
    /** The English message for this code (the same in every Supertext SDK), meant for logs. */
    public val message: String,
) {
    /** No API key configured. No request was sent. */
    MISSING_API_KEY("missing_api_key", "No Supertext API key is configured."),

    /** HTTP 401/403: the key is wrong, revoked or lacks access. */
    AUTHENTICATION_FAILURE("authentication_failure", "Supertext rejected the API key. Please check it."),

    /** HTTP 400 or 404 INVALID_LANGUAGE_PAIR: a code Supertext doesn't offer (e.g. `de` or `es-ES` as target). */
    INVALID_LANGUAGE_PAIR("invalid_language_pair", "Supertext does not support this language pair."),

    /**
     * No request was sent: the language has no Supertext code (no mapping and no exact match, see
     * [Languages.target]). Ask the administrator to map it.
     */
    UNSUPPORTED_LANGUAGE(
        "unsupported_language",
        "This language has no Supertext language code. Map it to a Supertext language.",
    ),

    /**
     * HTTP 400 FEATURE_ERROR: the language pair exists, but not with this option (e.g. politeness for `en-US`);
     * [SupertextException.feature] names it.
     */
    UNSUPPORTED_FEATURE("unsupported_feature", "Supertext does not support this option for the language pair."),

    /** HTTP 404: unknown file id (expired after 24 h or already deleted). */
    NOT_FOUND("not_found", "The Supertext file was not found (expired or already deleted)."),

    /** HTTP 413: the document is over the API's size limit. */
    PAYLOAD_TOO_LARGE("payload_too_large", "The document is too large for Supertext to translate."),

    /** HTTP 429 after all retries. */
    TOO_MANY_REQUESTS("too_many_requests", "Too many requests to Supertext. Please try again shortly."),

    /** HTTP 5xx. */
    SERVICE_UNAVAILABLE("service_unavailable", "The Supertext service is unavailable."),

    /** Any other HTTP status. */
    UNEXPECTED_STATUS("unexpected_status", "Supertext sent an unexpected HTTP status."),

    /** No HTTP answer: DNS, TLS, connection refused, request timeout. */
    TRANSPORT_ERROR("transport_error", "Could not reach Supertext."),

    /** The upload answered without a file_id. */
    NO_FILE_ID("no_file_id", "Supertext did not return a file id."),

    /** File status `error`. */
    TRANSLATION_ERROR("translation_error", "Supertext failed to translate the document."),

    /** File status `limit_exceeded`: the account's translation limit is used up. */
    QUOTA_EXCEEDED("quota_exceeded", "Your Supertext translation limit is used up."),

    /** File status `deleted` before the download. */
    FILE_DELETED("file_deleted", "The Supertext file was deleted before it could be downloaded."),

    /** The file wasn't done within the client's timeout. */
    TIMEOUT("timeout", "Timed out waiting for the Supertext translation to finish."),

    /** The download was empty, or (translateTexts) segments are missing from the translated document. */
    INCOMPLETE_RESPONSE("incomplete_response", "The Supertext translation is incomplete."),
    ;

    override fun toString(): String = value

    public companion object {
        /** The code for a shared string value; throws [IllegalArgumentException] for an unknown one. */
        @JvmStatic
        public fun fromValue(value: String): ErrorCode =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown Supertext error code: $value")
    }
}

/**
 * A failed Supertext call. [code] says what went wrong (see [ErrorCode]); integrations translate the code for
 * their users and may append [detail] (the API's or the network's own explanation, untranslated). The message is
 * English and meant for logs: the code's [message][ErrorCode.message], plus `" HTTP <status>."` and
 * `" Details: <detail>"` when known. Never contains the API key.
 *
 * Empty strings count as unknown (`null`).
 */
public class SupertextException(
    /** What went wrong. */
    public val code: ErrorCode,
    /** The HTTP status, or `null` when the error is not an HTTP answer (e.g. a file status). */
    public val status: Int? = null,
    detail: String? = null,
    apiErrorCode: String? = null,
    sourceLang: String? = null,
    targetLang: String? = null,
    feature: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message(code, status, detail.orNullIfEmpty()), cause) {

    /** The answer's body without tags, trimmed, at most 200 characters; or the network error's message. */
    public val detail: String? = detail.orNullIfEmpty()

    /** The `error_code` of a JSON error answer, e.g. `INVALID_LANGUAGE_PAIR`. */
    public val apiErrorCode: String? = apiErrorCode.orNullIfEmpty()

    /**
     * For [ErrorCode.INVALID_LANGUAGE_PAIR] and [ErrorCode.UNSUPPORTED_FEATURE]: the source language the API
     * reports, if any; for [ErrorCode.UNSUPPORTED_LANGUAGE]: the source code that couldn't be resolved.
     */
    public val sourceLang: String? = sourceLang.orNullIfEmpty()

    /**
     * For [ErrorCode.INVALID_LANGUAGE_PAIR] and [ErrorCode.UNSUPPORTED_FEATURE]: the target language the API
     * reports, if any; for [ErrorCode.UNSUPPORTED_LANGUAGE]: the target code that couldn't be resolved.
     */
    public val targetLang: String? = targetLang.orNullIfEmpty()

    /** For [ErrorCode.UNSUPPORTED_FEATURE]: the option the API names, e.g. `politeness`. */
    public val feature: String? = feature.orNullIfEmpty()

    private companion object {
        private const val serialVersionUID: Long = 2L

        private fun message(code: ErrorCode, status: Int?, detail: String?): String {
            var message = code.message
            if (status != null) message += " HTTP $status."
            if (detail != null) message += " Details: $detail"
            return message
        }
    }
}

internal fun String?.orNullIfEmpty(): String? = if (isNullOrEmpty()) null else this
