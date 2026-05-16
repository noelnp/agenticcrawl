package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DetailLinkFinder(private val llm: LlmClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun find(rowHtml: String, fields: List<TargetField>): DetailLinkSelector? {
        if (rowHtml.isBlank()) return null
        val rowRoot = Jsoup.parseBodyFragment(rowHtml).body().firstElementChild() ?: run {
            log.warn("could not parse row HTML")
            return null
        }

        var feedback: String? = null
        for (attempt in 0..MAX_RETRIES) {
            val candidate = callLlm(rowHtml, fields, feedback)
            if (candidate == null) {
                log.debug("no detailLink emitted on attempt {}", attempt + 1)
                return null
            }
            val verdict = verify(rowRoot, candidate)
            if (verdict.valid) {
                log.info("detailLink verified on attempt {} selector='{}'", attempt + 1, candidate.selector)
                return candidate
            }
            log.warn("detailLink failed verification (attempt {}): {}", attempt + 1, verdict.reason)
            feedback = verdict.reason
        }
        log.warn("detailLink exhausted retries without verifying — returning none")
        return null
    }

    private fun callLlm(rowHtml: String, fields: List<TargetField>, feedback: String?): DetailLinkSelector? {
        val fieldFingerprint = fields.joinToString("\n") { "- ${it.name}: ${quoted(it.text)}" }
        val feedbackBlock = feedback?.let { "\n\nPrevious attempt was rejected:\n$it\n\nFix it." } ?: ""

        val instructions = """
            You are given the HTML of one row from a listings page. The row
            represents an item (a product, a match, an article, a profile, etc.).
            The visible values in this row are:

            $fieldFingerprint

            Find this row's PRIMARY navigational anchor — the single <a> that, when
            clicked, takes the user to this specific item's own detail page on the
            same site. Anchors whose href, aria-label, or title reference the
            values above are strong candidates.

            Rules:
              - Must be an <a> element somewhere inside the row HTML (the row root
                itself may be the <a>).
              - The href must be a navigational URL:
                  * NOT '#' or fragment-only
                  * NOT 'javascript:', 'mailto:', 'tel:'
                  * NOT ending in an image extension (.png .jpg .jpeg .gif .webp
                    .svg .ico .avif), with or without query string
              - The <a>'s content must NOT be only an <img>, <svg>, or <picture>
                (icon / thumbnail nav).
              - Empty stretched-link <a> elements — no inner content but with
                href and aria-label or title — ARE valid picks, often the
                preferred one.
              - Reject share, favourite, preview, save, and category-badge
                anchors.

            Selector rules:
              - One own identifier on the <a> element. Prefer a single class,
                then data-testid, then another data-*, then the tag, then nth.
              - If you fall back to nth, pair it with a selector that describes
                the *kind* of element (a class shared with peers, or the tag),
                not the specific instance.

            Row HTML:
            ```
            $rowHtml
            ```$feedbackBlock

            Return JSON only:
              { "detailLink": { "selector": "...", "nth": null } }
            If no anchor qualifies:
              { "detailLink": null }
        """.trimIndent()

        return runCatching {
            llm.json(instructions, Response::class.java)?.detailLink?.toDetailLinkSelector()
        }.onFailure { log.warn("LLM call failed: {}", it.message) }.getOrNull()
    }

    private fun verify(rowRoot: Element, dl: DetailLinkSelector): Verdict {
        val matches = runCatching { rowRoot.select(dl.selector) }.getOrNull()
            ?: return Verdict(false, "selector '${dl.selector}' is invalid CSS")
        val candidates = matches.toList()
        if (candidates.isEmpty()) {
            return Verdict(false, "selector '${dl.selector}' matched 0 elements")
        }
        val element = when (val nth = dl.nth) {
            null -> {
                if (candidates.size > 1) {
                    return Verdict(false, "selector '${dl.selector}' matched ${candidates.size} elements; narrow it or set nth")
                }
                candidates[0]
            }
            else -> candidates.getOrNull(nth)
                ?: return Verdict(false, "nth=$nth out of range; selector matched ${candidates.size} elements")
        }
        if (element.tagName().lowercase() != "a") {
            return Verdict(false, "target is <${element.tagName()}>, not <a>")
        }
        val href = element.attr("href")
        if (href.isBlank()) return Verdict(false, "<a> has empty href")
        val lower = href.lowercase()
        if (BAD_HREF_PREFIXES.any { lower.startsWith(it) }) {
            return Verdict(false, "href '$href' is not a navigational URL")
        }
        val pathOnly = lower.substringBefore('?').substringBefore('#')
        if (IMAGE_EXTENSIONS.any { pathOnly.endsWith(it) }) {
            return Verdict(false, "href '$href' points at an image asset")
        }
        val children = element.children()
        val onlyImageContent = element.text().isBlank() &&
            children.isNotEmpty() &&
            children.all { it.tagName().lowercase() in IMAGE_LIKE_TAGS }
        if (onlyImageContent) {
            return Verdict(false, "<a> wraps only an image/icon (image link), not a navigational text anchor")
        }
        return Verdict(true, null)
    }

    private fun quoted(s: String): String = "\"${s.replace("\"", "\\\"")}\""

    private data class Verdict(val valid: Boolean, val reason: String?)

    private data class Response(
        @JsonProperty("detailLink") val detailLink: RawDetailLink?,
    )

    private data class RawDetailLink(
        @JsonProperty("selector") val selector: String,
        @JsonProperty("nth") val nth: Int? = null,
    ) {
        fun toDetailLinkSelector() = DetailLinkSelector(selector, nth)
    }

    private companion object {
        const val MAX_RETRIES = 2
        val BAD_HREF_PREFIXES = listOf("#", "javascript:", "mailto:", "tel:")
        val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif")
        val IMAGE_LIKE_TAGS = setOf("img", "svg", "picture")
    }
}
