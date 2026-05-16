package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ClickTargetFinder(private val llm: LlmClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Given the planner's intent (e.g. "click the Stats tab to see match statistics")
     * and a screenshot of the current page, identify the visible label of the element
     * to click. Returns null if nothing obvious can be picked.
     */
    fun find(intent: String, userDescription: String, screenshot: ByteArray): ClickTarget? {
        val instructions = """
            You look at a screenshot of a web page and pick the visible text label of the
            ONE clickable UI element (button, tab, link) that, when clicked, will reveal
            the information described below.

            User's overall request:
            $userDescription

            What we are trying to reveal on this page (from the planner):
            $intent

            Rules:
              - Return the visible text of the control as it appears on the page, exactly
                — same capitalisation, same spelling, no surrounding whitespace.
              - Prefer tab labels, button labels, or short link text (e.g. "Stats",
                "Statistics", "Lineups"). Avoid long descriptive paragraphs.
              - If the element does not have visible text but is clearly an icon-only
                control, return null — we cannot find it by text in that case.
              - If no element on this screenshot would reveal the requested info,
                return null.

            Return JSON only:
              { "label": "the exact visible text" }
            or
              { "label": null }
        """.trimIndent()

        return runCatching {
            val raw = llm.jsonWithImage(instructions, screenshot, RawTarget::class.java)
            raw?.label?.trim()?.takeIf { it.isNotEmpty() }?.let { ClickTarget(label = it) }
        }.onFailure { log.warn("ClickTargetFinder LLM call failed: {}", it.message) }
            .getOrNull()
    }

    private data class RawTarget(
        @JsonProperty("label") val label: String?,
    )
}

data class ClickTarget(
    val label: String,
)
