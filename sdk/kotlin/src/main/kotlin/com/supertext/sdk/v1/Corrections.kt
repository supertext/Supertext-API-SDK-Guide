package com.supertext.sdk.v1

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A correction a reviewer made in Supertext Localization Review that the integration hasn't written into the CMS
 * yet ([SupertextClient.pendingCorrections]; spec/corrections.json).
 */
public class Correction(
    /** The review service's id of this place (send back with [SupertextClient.acknowledgeCorrections]). */
    public val id: String,
    /** The `data-st-id` the integration gave the segment (its own reference to the field). */
    public val segmentId: String,
    /** `null`: the source language was detected. */
    public val sourceLang: String?,
    public val targetLang: String,
    public val politeness: Politeness,
    public val sourceHtml: String,
    /** What the integration received for this place last: check the field still holds it before overwriting an editor's change. */
    public val previousHtml: String,
    /** The corrected translation: write this into the field. */
    public val html: String,
    /** `corrected`, `approved`, … */
    public val status: String,
    /** ISO time of the latest change. */
    public val correctedAt: String,
    /** Changes with every new correction: acknowledge the version you wrote. */
    public val version: String,
) {
    override fun toString(): String =
        "Correction{id=$id, segmentId=$segmentId, targetLang=$targetLang, version=$version}"
}

/** The review service's two calls without HTTP (spec/corrections.json). */
internal object Corrections {

    const val PAGE_SIZE = 500
    const val MAX_PAGES = 100
    const val ACKNOWLEDGE_BATCH_SIZE = 1000

    private val VERSION = Regex("[0-9a-f]{16}")
    private val DIGITS = Regex("[0-9]+")

    /** One page of a pending-corrections answer. */
    class Page(val corrections: List<Correction>, val nextAfter: String?)

    fun path(targetLang: String?, sourceLang: String?, politeness: Politeness?, after: String?): String {
        val query = listOf(
            "target_lang" to targetLang,
            "source_lang" to sourceLang,
            "politeness" to politeness?.value,
            "after" to after,
            "limit" to PAGE_SIZE.toString(),
        ).mapNotNull { (name, value) ->
            value?.let { "$name=" + URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
        }
        return "review/corrections?" + query.joinToString("&")
    }

    fun parsePage(body: String): Page {
        val out = ArrayList<Correction>()
        val data = try {
            Json.parse(body)
        } catch (e: IllegalArgumentException) {
            return Page(out, null)
        }
        val page = data as? Map<*, *> ?: return Page(out, null)
        val list = page["corrections"] as? List<*> ?: return Page(out, null)
        for (raw in list) {
            val o = raw as? Map<*, *> ?: continue
            val id = nonEmpty(o["id"]) ?: continue
            val stId = nonEmpty(o["st_id"]) ?: continue
            val target = nonEmpty(o["target_lang"]) ?: continue
            val html = nonEmpty(o["html"]) ?: continue
            val version = o["version"] as? String ?: continue
            if (!VERSION.matches(version)) continue
            val politeness = when (o["politeness"]) {
                "more" -> Politeness.MORE
                "less" -> Politeness.LESS
                else -> Politeness.DEFAULT
            }
            out.add(
                Correction(
                    id, stId, nonEmpty(o["source_lang"]), target, politeness,
                    str(o["source_html"]), str(o["previous_html"]), html, str(o["status"]), str(o["corrected_at"]), version,
                ),
            )
        }
        val next = page["next_after"] as? String
        return Page(out, if (next != null && DIGITS.matches(next)) next else null)
    }

    fun acknowledgeBody(corrections: List<Correction>): String =
        Json.write(mapOf("corrections" to corrections.map { linkedMapOf("id" to it.id, "version" to it.version) }))

    fun acknowledgedCount(body: String): Int {
        val n = try {
            (Json.parse(body) as? Map<*, *>)?.get("acknowledged")
        } catch (e: IllegalArgumentException) {
            null // Not JSON: nothing counted.
        }
        if (n is Double && n >= 0 && n == Math.rint(n) && n <= Int.MAX_VALUE) return n.toInt()
        return 0
    }

    private fun str(value: Any?): String = value as? String ?: ""

    private fun nonEmpty(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }
}
