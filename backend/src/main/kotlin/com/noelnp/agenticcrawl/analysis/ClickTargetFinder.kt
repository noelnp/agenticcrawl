package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ClickTargetFinder(private val llm: LlmClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Given the planner's intent, the user's overall request, a screenshot of
     * the current page, and a compact summary of visible clickable elements on
     * that page, identify the ONE element to click. Returns a [ClickTarget]
     * describing the element with a CSS selector and optional text/nth
     * disambiguation, or null if nothing matches.
     */
    fun find(
        intent: String,
        userDescription: String,
        screenshot: ByteArray,
        candidates: String,
    ): ClickTarget? {
        if (candidates.isBlank()) {
            log.debug("no candidates provided to ClickTargetFinder")
            return null
        }

        val instructions = """
            You are picking ONE element on a web page to click. The click should
            reveal information the user has been asking for that is not yet on
            screen — typically by switching tabs, expanding a section, or opening
            a detail view.

            User's overall request:
            $userDescription

            What we are trying to reveal (from the planner):
            $intent

            Visible interactive elements on the current page (each line is one
            element, with its tag, ARIA role, key attributes, and visible text):
            ---
            $candidates
            ---

            How to pick:
              - Find the element whose role + text/aria-label best matches the
                intent above. Use the screenshot to confirm position and label
                styling (some pages render text via CSS uppercase/lowercase —
                the DOM text shown above is the authoritative form).
              - Build a CSS selector that uniquely identifies that element. Prefer
                stable identifiers in this priority:
                  1. data-testid combined with role or another stable attribute.
                  2. A single semantic class.
                  3. tag + role (e.g. button[role='tab']).
                Avoid generated/hashed class names that look auto-generated
                (random-looking suffixes) unless they are the only option.
              - If the selector matches multiple elements in the DOM, also
                return `text` — the visible inner text of the chosen element,
                used to filter the matches.
              - If the selector + text combination still matches multiple, set
                `nth` (0-based) to pick the right one.

            Return JSON only:
              { "selector": "...", "text": "...", "nth": null }
            Examples of selectors (illustrative, do not copy verbatim):
              { "selector": "button[role='tab']", "text": "Specifications", "nth": null }
              { "selector": "[data-testid='details-toggle']", "text": null, "nth": null }
              { "selector": "a[role='link']", "text": "View more", "nth": 0 }

            If nothing in the candidate list plausibly matches the intent,
            return:
              { "selector": null }
        """.trimIndent()

        return runCatching {
            val raw = llm.jsonWithImage(instructions, screenshot, RawTarget::class.java)
            raw?.let { r ->
                val sel = r.selector?.trim()?.takeIf { it.isNotEmpty() } ?: return@let null
                ClickTarget(
                    selector = sel,
                    text = r.text?.trim()?.takeIf { it.isNotEmpty() },
                    nth = r.nth,
                )
            }
        }.onFailure { log.warn("ClickTargetFinder LLM call failed: {}", it.message) }
            .getOrNull()
    }

    private data class RawTarget(
        @JsonProperty("selector") val selector: String?,
        @JsonProperty("text") val text: String? = null,
        @JsonProperty("nth") val nth: Int? = null,
    )
}

data class ClickTarget(
    val selector: String,
    val text: String? = null,
    val nth: Int? = null,
)
