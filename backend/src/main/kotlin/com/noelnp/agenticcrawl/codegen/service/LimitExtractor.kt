package com.noelnp.agenticcrawl.codegen.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.noelnp.agenticcrawl.analysis.llm.LlmClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Parses an optional upper-bound row count out of a natural-language user
 * description. Returns null when the description does not specify a limit.
 *
 * Robust to phrasing variations the LLM handles naturally — `"top 5"`,
 * `"first ten"`, `"only 3 of them"`, `"limit to 20"` — and avoids the
 * false-positives a regex would produce on unrelated numbers like
 * `"matches with score 5"` or `"team 7 vs team 5"`.
 */
@Service
class LimitExtractor(private val llm: LlmClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun extract(userDescription: String): Int? {
        if (userDescription.isBlank()) return null

        val prompt = """
            You are reading a user request for a web scraper. Determine whether the
            user specified an UPPER BOUND on how many top-level items to collect.

            User request:
            ---
            $userDescription
            ---

            A limit is the maximum number of distinct top-level items the user wants
            in the result set. Phrasing examples that imply a limit:
              - "top 5 matches"
              - "first 10 products"
              - "just three reviews"
              - "limit to 20"
              - "only the first 8"
              - "no more than 15"

            NOT a limit (return null in these cases):
              - Numbers that name an attribute or filter, not a count
                (e.g. "matches with score 5", "products under \$50", "team 7 vs team 5")
              - Numbers describing nested sub-items per top-level item
                (e.g. "all 8 stats per match" is per-item, not a top-level cap)
              - References to time, year, version, ranking value, etc.

            Return JSON only, with this shape:
              { "limit": <integer> }      -- when a clear upper bound was specified
              { "limit": null }           -- when no upper bound was specified

            The integer must be positive. If the user wrote a number as a word
            ("five", "ten"), normalise it to an integer.
        """.trimIndent()

        val parsed = runCatching { llm.json(prompt, RawLimit::class.java) }
            .onFailure { log.warn("limit extraction call failed: {}", it.message) }
            .getOrNull()

        val limit = parsed?.limit
        return when {
            limit == null -> {
                log.debug("no row-count limit in user description")
                null
            }
            limit <= 0 -> {
                log.warn("limit extractor returned non-positive value {} — ignoring", limit)
                null
            }
            limit > MAX_REASONABLE_LIMIT -> {
                log.warn("limit {} above sanity cap {} — ignoring", limit, MAX_REASONABLE_LIMIT)
                null
            }
            else -> {
                log.info("user requested a top-level limit of {}", limit)
                limit
            }
        }
    }

    private data class RawLimit(@JsonProperty("limit") val limit: Int?)

    private companion object {
        const val MAX_REASONABLE_LIMIT = 10_000
    }
}
