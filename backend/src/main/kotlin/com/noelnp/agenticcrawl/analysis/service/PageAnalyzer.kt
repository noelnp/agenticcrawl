package com.noelnp.agenticcrawl.analysis.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.noelnp.agenticcrawl.analysis.llm.LlmClient
import com.noelnp.agenticcrawl.analysis.model.PageAnalysis
import com.noelnp.agenticcrawl.analysis.model.Target
import com.noelnp.agenticcrawl.analysis.model.TargetField
import com.noelnp.agenticcrawl.analysis.model.TargetType
import com.noelnp.agenticcrawl.analysis.model.ValidationVerdict
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

    /**
     * Verification-mode analysis for follow-up layers (after we've navigated or
     * clicked during recon). Unlike [analyze], this does not try to discover
     * any prominent repeating pattern on the page — it asks "is the user's
     * specific request visible here?" and prefers PARTIAL over making up a
     * target when something is structurally similar but semantically different.
     */
    fun verifyRequest(description: String, screenshot: ByteArray): PageAnalysis {
        log.debug("calling verifier descriptionLen={} bytes={}", description.length, screenshot.size)

        val instructions = """
            You are verifying whether information requested by a user is visible on
            this screenshot of a web page. The page was reached during automated
            reconnaissance — we arrived here from a previous page in the same flow.

            User request:
            ---
            $description
            ---

            Decide a verdict for what THIS screenshot offers:

              PRESENT — the requested information is clearly visible. Identify ONLY
                        the fields/values that directly satisfy the request. If the
                        info is a repeating list of items the user asked for, emit
                        type=MULTI with ONE row's fields. If a single value,
                        type=SINGLE.

              PARTIAL — the page is related to the user's request (same site, same
                        topic, same item) but the specific information they asked
                        for is NOT yet visible on this screenshot. It may live
                        behind a tab, a button, an accordion, or further down the
                        page — but it is not on screen now.

              ABSENT  — the page does not relate to the user's request.

            CRITICAL VERIFICATION RULES:

            - Do NOT seize on the most prominent repeating pattern on the page just
              because it looks structurally like a list. Only emit fields when the
              values they hold are *exactly* the kind of information the user
              asked for.

            - Examples of the conceptual mismatch to avoid:
                * User asked for "match statistics" → you see a list of goal
                  events. Goal events are a different concept from statistics.
                  Return PARTIAL.
                * User asked for "product specifications" → you see a list of
                  related products / recommendations. Different concept.
                  Return PARTIAL.
                * User asked for "user reviews" → you see news article comments,
                  or seller responses. Different concept. Return PARTIAL.

            - Prefer PARTIAL with clear reasoning over PRESENT with shaky fields.
              PARTIAL gives the orchestrator a chance to drill deeper (click a
              tab, follow a link); PRESENT-with-wrong-fields derails the flow.

            - When PRESENT, the fields you emit must answer the user's request
              directly. A field that's merely "interesting" or "related" but
              doesn't answer the request is not appropriate — leave it out.

            HOW TO READ REPEATING STRUCTURES (type=MULTI):

            The repeating unit is whatever element appears multiple times on the
            page, similar to its neighbour instances. Identify ONE example
            instance and emit row fields from it. Two row shapes you might see:

              (a) Fixed-column rows — every row has the same conceptual columns
                  whose meaning is the same across rows (e.g. a list where each
                  row is "thing, attribute1, attribute2"). Use semantic camelCase
                  field names that match the column meaning, consistent across
                  what every row would also provide.

              (b) Label-as-data rows — each row's own *label text* varies and
                  itself identifies what the row is about; the row also carries
                  one or more values that belong to that label. Common shapes:
                  key-value entries, spec rows, settings rows, comparison rows
                  with one or two value sides. Here the label IS one of the row
                  fields — emit it. Use GENERIC field names that describe
                  position, not meaning: `label` for the varying-text element,
                  and `value` for a single value, or `home`/`away` (or
                  `valueA`/`valueB`, whichever orientation matches what you
                  see) for two values flanking a label.

                  Do not invent semantic names from one row's label text — the
                  label is data, not a column header, so different rows would
                  carry different labels under the same `label` field.

            ROW-FIELD DISCIPLINE (applies to every MULTI emission):

            - A value qualifies as a row field only if it CHANGES between this
              row and its immediate neighbour rows. Look at the rows above and
              below: if both share the same value, that value belongs to a
              section header or page chrome, not to this row — EXCLUDE it.

            - Row fields must come from inside ONE instance of the repeating
              unit. Do NOT mix in values from the page header, breadcrumbs,
              page title, score/summary banner, tab bar, or any section-level
              chrome that lives outside the repeating block.

            - Each visible value is its OWN field. If two distinct values appear
              side-by-side (e.g. a left value and a right value flanking a
              label, before/after columns, two comparison sides), emit them as
              TWO separate fields. NEVER combine two cell values into one field
              with a visual separator — no `"A — B"`, `"A vs B"`, `"A / B"`,
              `"A : B"` aggregations. The DOM stores them as separate text
              nodes; combined strings exist only in the rendered layout.

            VALUE EXTRACTION:
              - Only return text you can clearly read in the screenshot.
              - Do not invent or guess. Read the actual pixels.
              - Copy each value EXACTLY as the single cell renders it — do not
                concatenate with adjacent cells' values, do not add separators
                that aren't part of the cell's own text.
              - Copy text exactly as displayed (capitalisation, accents,
                punctuation, whitespace).
              - For purely numeric values, emit only digits and decimal/thousands
                separators. Drop currency symbols, units, decorative typography.
              - Do not infer numeric values from charts, star bars, progress
                meters. Text only.
              - For MULTI, pick ONE specific visible row instance; do not
                aggregate across rows.

            Return:

              1. verdict: PRESENT | PARTIAL | ABSENT.
              2. reasoning: one short sentence grounded in what is visible.
                 If PARTIAL, name what IS visible vs what is missing.
              3. target: object (null when verdict is PARTIAL or ABSENT).
                 Shape:
                   { type: "SINGLE"|"MULTI", fields: [{name, text}] }
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
            "verifier returned verdict={} targetType={} fieldCount={}",
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
