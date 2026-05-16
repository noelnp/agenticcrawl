package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PageAnalyzer(private val llm: LlmClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun analyze(description: String, screenshot: ByteArray): PageAnalysis {
        log.debug("calling analyzer descriptionLen={} bytes={}", description.length, screenshot.size)

        val instructions = """
            You are inspecting a screenshot of a web page. The user has described
            something they want from this page. Your job is to (a) decide whether
            it is reachable from this page and (b) describe ONE concrete target
            you can see, in a structured form.

            User request:
            ---
            $description
            ---

            Step 1 — verdict, one of:
              - PRESENT: the requested target is clearly visible on this page.
              - PARTIAL: the page is related (same site, same topic) but the
                         target is not yet on screen — e.g. a landing page that
                         needs navigation, or filters need to be applied.
              - ABSENT:  the page does not contain the target.

            Step 2 — if PRESENT or PARTIAL, produce ONE concrete `target` from
            something you can actually see on the page. Pick the type that
            matches the user's intent:

              type=SINGLE
                One unique value on the page. Example: a current price, a stock
                count, a headline. Emit one field with `name` (short camelCase
                key derived from intent + what you see, e.g. "price") and
                `text` (the visible text, copied exactly).

              type=MULTI
                A repeating row structure (a table, a list of cards, search
                results). Pick ONE visible row instance. Emit 2–8 fields read
                from that single row — `name` is a short camelCase key
                (teamHome, teamAway, scoreHome, scoreAway), `text` is the
                visible text copied exactly. Names must be consistent with
                what other rows would also provide.

                Row-field test: a value qualifies as a row field only if it
                would CHANGE for the row immediately above or below the one
                you picked. Look at the neighbouring rows in the screenshot —
                if both share the same value, that value is NOT row-level; it
                belongs to a section/header above the rows. EXCLUDE it.

                Common exclusions (NOT row fields, even when visually near a
                row):
                  - Section / group / category headers shared by many rows
                    (league names, store categories, date dividers like
                    "Today")
                  - Column or table headers
                  - Page-wide filters, tabs, breadcrumbs, sort indicators
                  - Pagination text ("Page 1 of 12", "Showing 1–20")

            CRITICAL RULES — read carefully:
            - Only return text you can clearly read in the screenshot.
            - Do not invent, guess, or infer text based on what you would
              *expect* a page of this type to contain. Read the actual pixels.
            - Copy text exactly as displayed (capitalisation, accents,
              special characters, punctuation, whitespace).
            - For purely numeric values (prices, ratings, counts, scores,
              quantities), emit only the digits and decimal/thousands
              separators. Drop currency symbols, unit suffixes, and
              decorative typography.
            - Do not infer numeric values from visual elements like star
              bars, progress meters, filled-icon indicators, or charts.
              Only emit a numeric value when the digits are rendered as
              text on the page.
            - If you cannot clearly read a field, omit it rather than guessing.
            - For MULTI: pick ONE specific visible row. Do not aggregate
              across rows. Field names must be in camelCase.

            Return:

            1. verdict: PRESENT | PARTIAL | ABSENT.
            2. reasoning: one short sentence grounded in what is visible.
            3. target: object as described above (null if ABSENT). The object
               has shape:
                 { type: "SINGLE"|"MULTI", fields: [{name, text}] }

            Examples (illustrative — do not copy):
              SINGLE:
                { type: "SINGLE",
                  fields: [{ name: "price", text: "1 803,58 kr" }] }
              MULTI:
                { type: "MULTI",
                  fields: [
                    { name: "teamHome",  text: "Liverpool" },
                    { name: "teamAway",  text: "Chelsea" },
                    { name: "scoreHome", text: "2" },
                    { name: "scoreAway", text: "1" }
                  ] }
        """.trimIndent()

        val raw = llm.jsonWithImage(instructions, screenshot, RawAnalysis::class.java)
            ?: error("empty response from chat model")

        val target = raw.target?.toTarget()
        val analysis = PageAnalysis(
            verdict = raw.verdict,
            reasoning = raw.reasoning.trim(),
            target = target,
        )
        log.debug(
            "analyzer returned verdict={} targetType={} fieldCount={}",
            analysis.verdict,
            target?.type,
            target?.fields?.size ?: 0,
        )
        return analysis
    }

    private fun RawTarget.toTarget(): Target? {
        val t = type ?: return null
        val cleaned = fields.orEmpty()
            .asSequence()
            .map { TargetField(it.name.trim(), it.text.trim()) }
            .filter { it.name.isNotEmpty() && it.text.isNotEmpty() }
            .take(MAX_FIELDS)
            .toList()
        return if (cleaned.isEmpty()) null else Target(type = t, fields = cleaned)
    }

    private data class RawAnalysis(
        @JsonProperty("verdict") val verdict: ValidationVerdict,
        @JsonProperty("reasoning") val reasoning: String,
        @JsonProperty("target") val target: RawTarget?,
    )

    private data class RawTarget(
        @JsonProperty("type") val type: TargetType?,
        @JsonProperty("fields") val fields: List<RawField>?,
    )

    private data class RawField(
        @JsonProperty("name") val name: String,
        @JsonProperty("text") val text: String,
    )

    private companion object {
        const val MAX_FIELDS = 8
    }
}
