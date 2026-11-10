package com.supertext.sdk.v1

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The result of the automatic API key check (spec/api-key-check.json), from [SupertextClient.checkApiKey].
 * Plugins run it on every settings save, when a key from the environment changed and on "Test connection", and
 * show it to administrators.
 *
 * Holds the key's SHA-256 [fingerprint] (to notice a changed key), never the key.
 */
public class ApiKeyCheck(
    public val status: Status,
    /** When the check ran: UTC ISO 8601 without fractions (`2026-10-13T08:00:00Z`). */
    public val checkedAt: String,
    /** The HTTP status Supertext answered with; `null` without an answer. */
    public val httpStatus: Int? = null,
    errorCode: String? = null,
    fingerprint: String? = "",
) {
    /** What the check found. [value] is the string shared by all Supertext SDKs. */
    public enum class Status(
        /** `ok`, `invalid`, `missing`, `unreachable` or `error`. */
        public val value: String,
    ) {
        /** Supertext accepted the key (2xx). */
        OK("ok"),

        /** Supertext rejected the key (401/403). */
        INVALID("invalid"),

        /** No key configured; nothing was sent. */
        MISSING("missing"),

        /** No answer, or a 5xx answer. */
        UNREACHABLE("unreachable"),

        /** Any other answer. */
        ERROR("error"),
        ;

        public companion object {
            /** The status for a value, or `null` for an unknown one. */
            @JvmStatic
            public fun fromValue(value: String?): Status? = entries.firstOrNull { it.value == value }
        }
    }

    /** The answer's JSON `error_code` (at most 100 characters); `null` if none. */
    public val errorCode: String? = errorCode?.let { truncate(it, 100) }

    /** SHA-256 of the key (lower-case hex); `""` for a blank key. Keep it locally, it is never sent. */
    public val fingerprint: String = fingerprint ?: ""

    /** The stored form: `status`, `checkedAt`, `httpStatus`, `errorCode`, `fingerprint`. */
    internal fun toMap(): Map<String, Any?> = linkedMapOf(
        "status" to status.value,
        "checkedAt" to checkedAt,
        "httpStatus" to httpStatus,
        "errorCode" to errorCode,
        "fingerprint" to fingerprint,
    )

    override fun equals(other: Any?): Boolean =
        other is ApiKeyCheck && status == other.status && checkedAt == other.checkedAt &&
            httpStatus == other.httpStatus && errorCode == other.errorCode && fingerprint == other.fingerprint

    override fun hashCode(): Int = listOf(status, checkedAt, httpStatus, errorCode, fingerprint).hashCode()

    override fun toString(): String =
        "ApiKeyCheck{status=${status.value}, checkedAt=$checkedAt, httpStatus=$httpStatus, errorCode=$errorCode}"

    public companion object {
        /** Longest timeout of the check (the client's request timeout, if shorter). */
        public val TIMEOUT: Duration = 10.seconds

        private val PASTED_PREFIX = Regex("^supertext-auth-key(\\s+|$)", RegexOption.IGNORE_CASE)
        private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        /**
         * SHA-256 (lower-case hex) of the key as [SupertextClient.authHeader] would send it (trimmed, a pasted
         * `Supertext-Auth-Key ` prefix removed); `""` for a blank key.
         */
        @JvmStatic
        public fun fingerprint(apiKey: String?): String {
            val key = PASTED_PREFIX.replaceFirst(apiKey?.trim() ?: "", "").trim()
            if (key.isEmpty()) return ""
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /** `2026-10-13T08:00:00Z`: UTC, fractions of a second dropped. */
        @JvmStatic
        public fun isoSeconds(instant: Instant): String = ISO_SECONDS.format(instant.truncatedTo(ChronoUnit.SECONDS))

        /** A check that failed with this exception ([checkedAt] and [fingerprint] from the caller). */
        internal fun fromError(error: Throwable, checkedAt: String, fingerprint: String): ApiKeyCheck {
            if (error !is SupertextException) return ApiKeyCheck(Status.ERROR, checkedAt, null, null, fingerprint)
            val httpStatus = error.status
            val status = when {
                error.code == ErrorCode.MISSING_API_KEY -> Status.MISSING
                httpStatus == 401 || httpStatus == 403 -> Status.INVALID
                httpStatus == null || httpStatus >= 500 -> Status.UNREACHABLE
                else -> Status.ERROR
            }
            return ApiKeyCheck(status, checkedAt, httpStatus, error.apiErrorCode, fingerprint)
        }

        /** The stored form back; `null` when it isn't a valid check. */
        internal fun fromMap(value: Any?): ApiKeyCheck? {
            val map = value as? Map<*, *> ?: return null
            val status = Status.fromValue(map["status"] as? String) ?: return null
            val checkedAt = map["checkedAt"] as? String ?: return null
            return ApiKeyCheck(
                status,
                checkedAt,
                (map["httpStatus"] as? Number)?.toInt(),
                map["errorCode"] as? String,
                map["fingerprint"] as? String ?: "",
            )
        }

        private fun truncate(text: String, codePoints: Int): String =
            if (text.codePointCount(0, text.length) <= codePoints) text
            else text.substring(0, text.offsetByCodePoints(0, codePoints))
    }
}
