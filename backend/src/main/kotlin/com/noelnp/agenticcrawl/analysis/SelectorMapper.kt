package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Service

@Service
class SelectorMapper(chatClientBuilder: ChatClient.Builder) {

    private val chatClient = chatClientBuilder.build()
    private val log = LoggerFactory.getLogger(javaClass)

    private val deterministicOptions: OpenAiChatOptions =
        OpenAiChatOptions.builder().temperature(0.0).build()

    fun map(rowHtml: String, fields: List<TargetField>, type: TargetType): ExtractedStructure? {
        if (rowHtml.isBlank() || fields.isEmpty()) return null
        val rowRoot = parseRowRoot(rowHtml) ?: run {
            log.warn("could not parse row HTML")
            return null
        }

        var feedback: String? = null
        var lastAttempt: ExtractedStructure? = null
        for (attempt in 0..MAX_RETRIES) {
            val structure = callLlm(rowHtml, fields, type, feedback)
            if (structure == null) {
                log.warn("LLM returned no structure on attempt {}", attempt + 1)
                break
            }
            lastAttempt = structure
            val verification = verify(rowRoot, fields, structure)
            if (verification.allValid) {
                log.info("selectors verified on attempt {} rowSelector='{}'", attempt + 1, structure.rowSelector)
                return structure
            }
            log.warn("selectors failed verification (attempt {}): {}", attempt + 1, verification.summary())
            feedback = verification.feedbackPrompt()
        }
        log.warn("returning last LLM attempt without successful verification")
        return lastAttempt
    }

    private fun callLlm(
        rowHtml: String,
        fields: List<TargetField>,
        type: TargetType,
        feedback: String?,
    ): ExtractedStructure? {
        val fieldList = fields.joinToString("\n") { "- ${it.name} = ${quoted(it.text)}" }
        val rowIntent = when (type) {
            TargetType.MULTI -> "This row is one of many similar rows on the page. The rowSelector must be a class or data-* attribute on the row root that would also match the other similar rows (so a scraper can iterate). Avoid per-instance identifiers like auto-generated IDs."
            TargetType.SINGLE -> "This is a single value on the page (not part of a repeating list). The rowSelector still matches the captured element root, but no iteration is implied."
        }
        val feedbackBlock = feedback?.let { "\n\nPrevious attempt had problems:\n$it\n\nFix these and try again." } ?: ""

        val instructions = """
            You map field names to CSS selectors from one row of captured HTML. The values
            are already known; produce selectors that point at them so a scraper can extract
            the same fields from this and peer rows.

            $rowIntent

            RULES (apply to every field independently):

            1. Find the LEAF element — the one whose own text or attribute equals the value.
               The selector MUST live on that element. Never point at a parent and read
               descendant text, even if a parent has a nicer-looking class.

            2. Use exactly ONE identifier on the leaf. No compounds (`.foo.bar`, `tag.foo`),
               no descendant combinators (`.parent .child`).

            3. Try identifiers in this order; stop at the first that uniquely matches the
               leaf among the row's descendants:

                 a. A single own class — `.foo`
                    (try every class on the leaf; short/hashed/unusual names are valid)
                 b. The leaf's data-testid — `[data-testid='x']`
                 c. Another own data-* — `[data-card='x']`
                    (avoid transient flags: data-state, data-live, data-pressed)
                 d. The leaf's tag — `tag` (only when it has no classes or data-*)
                 e. nth fallback — use ONLY when nothing in (a)–(d) is unique. Emit `nth`
                    as a 0-based DOM-order index. The selector you pair with nth must
                    describe the *kind* of element, not the specific instance — so the
                    nth index stays stable as the row's state changes. Choose, in order:
                      i.  The leaf's data-testid, if it has one — USE IT. It almost
                          always names the element type and is the most stable anchor.
                      ii. Otherwise, a class that is also present on the OTHER elements
                          your nth indexes through. The selector should match the
                          target leaf AND its structural peers; if it doesn't, your
                          nth indices can shift unpredictably.
                      iii. Never pick a class that appears only on the target leaf and
                          not on its structural peers — that class is typically
                          conditional styling (think: it's there because of the row's
                          current state, not because of the element's role) and will
                          drop off other rows.

            4. Evaluate each field independently. Sibling fields with similar structure can
               land on different rungs — don't force symmetry, and don't pick nth just
               because a sibling needed it.

            For the row root, emit rowSelector — one own identifier of the row root that
            matches ONLY the root in this captured HTML (no descendants) and would also
            match peer rows on the page. Avoid per-instance IDs and classes reused on
            descendants.

            Row HTML:
            ```
            $rowHtml
            ```

            Fields:
            $fieldList$feedbackBlock

            Return JSON only:
            {
              "rowSelector": "...",
              "fields": [
                { "name": "...", "selector": "...", "source": { "from": "TEXT" } },
                { "name": "...", "selector": "...", "source": { "from": "ATTRIBUTE", "name": "alt" } },
                { "name": "...", "selector": "...", "source": { "from": "TEXT" }, "nth": 1 }
              ]
            }
        """.trimIndent()

        return runCatching {
            chatClient.prompt()
                .options(deterministicOptions)
                .user(instructions)
                .call()
                .entity(RawStructure::class.java)
                ?.toExtractedStructure()
        }.onFailure { log.warn("LLM call failed: {}", it.message) }.getOrNull()
    }

    private fun verify(
        rowRoot: Element,
        fields: List<TargetField>,
        structure: ExtractedStructure,
    ): VerificationResult {
        val expectedByName = fields.associate { it.name to normalize(it.text) }
        val rowMatches = runCatching { rowRoot.select(structure.rowSelector) }.getOrNull().orEmpty()
        val rowValid = rowMatches.size == 1 && rowMatches[0] === rowRoot
        val rowReason = when {
            rowMatches.isEmpty() ->
                "rowSelector '${structure.rowSelector}' does not match the row root"
            rowMatches.size > 1 ->
                "rowSelector '${structure.rowSelector}' matched ${rowMatches.size} elements in the row HTML; it must match only the row root and not any descendant — pick a class/attribute that's exclusive to the root"
            rowMatches[0] !== rowRoot ->
                "rowSelector '${structure.rowSelector}' matched an element other than the row root"
            else -> null
        }

        val fieldVerdicts = structure.fields.associate { fs ->
            fs.name to verifyField(rowRoot, fs, expectedByName[fs.name])
        }
        return VerificationResult(rowValid, rowReason, fieldVerdicts)
    }

    private fun verifyField(
        rowRoot: Element,
        fs: FieldSelector,
        expected: String?,
    ): FieldVerdict {
        if (expected == null) return FieldVerdict(false, "field name not in target")
        val matches = runCatching { rowRoot.select(fs.selector) }.getOrNull()
            ?: return FieldVerdict(false, "selector '${fs.selector}' is invalid CSS")
        val descendants = matches.asSequence().filter { it !== rowRoot }.toList()
        if (descendants.isEmpty()) {
            return FieldVerdict(false, "selector '${fs.selector}' matched 0 elements within the row")
        }
        val element = when (val nth = fs.nth) {
            null -> {
                if (descendants.size > 1) {
                    return FieldVerdict(
                        false,
                        "selector '${fs.selector}' matched ${descendants.size} elements; either narrow it to match 1, or set nth to disambiguate (matches found in order: ${describeMatches(descendants)})",
                    )
                }
                descendants[0]
            }
            else -> {
                if (nth < 0 || nth >= descendants.size) {
                    return FieldVerdict(
                        false,
                        "nth=$nth is out of range; selector '${fs.selector}' matched ${descendants.size} elements (valid nth: 0..${descendants.size - 1})",
                    )
                }
                descendants[nth]
            }
        }
        val actual = when (val src = fs.source) {
            is ValueSource.Text -> element.text()
            is ValueSource.Attribute -> element.attr(src.name)
        }
        val actualNorm = normalize(actual)
        return if (actualNorm == expected || actualNorm.contains(expected, ignoreCase = false) || expected.contains(actualNorm, ignoreCase = false)) {
            FieldVerdict(true, null)
        } else {
            FieldVerdict(
                false,
                "selector '${fs.selector}'${fs.nth?.let { " nth=$it" }.orEmpty()} returned ${quoted(actual.take(120))}; expected ${quoted(expected)}",
            )
        }
    }

    private fun describeMatches(elements: List<Element>): String =
        elements.mapIndexed { i, el -> "[$i] <${el.tagName()}> ${quoted(el.text().take(40))}" }
            .joinToString(", ")

    private fun parseRowRoot(rowHtml: String): Element? =
        Jsoup.parseBodyFragment(rowHtml).body().firstElementChild()

    private fun normalize(s: String): String = s.replace(WHITESPACE_REGEX, " ").trim()

    private fun quoted(s: String): String = "\"${s.replace("\"", "\\\"")}\""

    private data class VerificationResult(
        val rowSelectorValid: Boolean,
        val rowSelectorReason: String?,
        val fieldVerdicts: Map<String, FieldVerdict>,
    ) {
        val allValid: Boolean
            get() = rowSelectorValid && fieldVerdicts.values.all { it.valid }

        fun summary(): String {
            val parts = mutableListOf<String>()
            if (!rowSelectorValid) parts += rowSelectorReason ?: "rowSelector invalid"
            fieldVerdicts.forEach { (name, v) -> if (!v.valid) parts += "[$name] ${v.reason}" }
            return parts.joinToString("; ")
        }

        fun feedbackPrompt(): String = buildString {
            if (!rowSelectorValid) append("- rowSelector: ${rowSelectorReason}\n")
            fieldVerdicts.forEach { (name, v) ->
                if (!v.valid) append("- field $name: ${v.reason}\n")
            }
        }.trimEnd()
    }

    private data class FieldVerdict(val valid: Boolean, val reason: String?)

    private data class RawStructure(
        @JsonProperty("rowSelector") val rowSelector: String,
        @JsonProperty("fields") val fields: List<RawField>,
    ) {
        fun toExtractedStructure() = ExtractedStructure(
            rowSelector = rowSelector,
            fields = fields.map { FieldSelector(it.name, it.selector, it.source.toValueSource(), it.nth) },
        )
    }

    private data class RawField(
        @JsonProperty("name") val name: String,
        @JsonProperty("selector") val selector: String,
        @JsonProperty("source") val source: RawSource,
        @JsonProperty("nth") val nth: Int? = null,
    )

    private data class RawSource(
        @JsonProperty("from") val from: String,
        @JsonProperty("name") val name: String?,
    ) {
        fun toValueSource(): ValueSource = when (from.uppercase()) {
            "TEXT" -> ValueSource.Text
            "ATTRIBUTE" -> ValueSource.Attribute(name ?: error("ATTRIBUTE source missing 'name'"))
            else -> error("unknown source.from: $from")
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        val WHITESPACE_REGEX = Regex("[\\p{Zs}\\s]+")
    }
}
