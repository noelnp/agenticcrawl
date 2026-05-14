package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service
import org.springframework.util.MimeTypeUtils

@Service
class PageAnalyzer(chatClientBuilder: ChatClient.Builder) {

    private val chatClient = chatClientBuilder.build()
    private val log = LoggerFactory.getLogger(javaClass)

    private val deterministicOptions: OpenAiChatOptions =
        OpenAiChatOptions.builder().temperature(0.0).build()

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
            something you can actually see on the page. Pick the kind that
            matches the user's intent:

              kind=DATA, type=SINGLE
                One unique value on the page. Example: a current price, a stock
                count, a headline. Emit one field with `name` (short camelCase
                key derived from intent + what you see, e.g. "price") and
                `text` (the visible text, copied exactly).

              kind=DATA, type=MULTI
                A repeating row structure (a table, a list of cards, search
                results). Pick ONE visible row instance. Emit 2–8 fields read
                from that single row — `name` is a short camelCase key
                (teamHome, teamAway, scoreHome, scoreAway), `text` is the
                visible text copied exactly. Names must be consistent with
                what other rows would also provide.

              kind=ACTION
                An interactive element the user needs to operate. Emit `verb`
                (CLICK for buttons/links, FILL for inputs/textareas/search
                boxes) and `text` (for CLICK the visible label e.g. "Logga
                in"; for FILL the text to type).

            CRITICAL RULES — read carefully:
            - Only return text you can clearly read in the screenshot.
            - Do not invent, guess, or infer text based on what you would
              *expect* a page of this type to contain. Read the actual pixels.
            - Copy text exactly as displayed (capitalisation, accents,
              special characters, punctuation, whitespace).
            - If you cannot clearly read a field, omit it rather than guessing.
            - For MULTI: pick ONE specific visible row. Do not aggregate
              across rows. Field names must be in camelCase.

            Return:

            1. verdict: PRESENT | PARTIAL | ABSENT.
            2. reasoning: one short sentence grounded in what is visible.
            3. target: object as described above (null if ABSENT). The object
               has shape:
                 { kind: "DATA",   type: "SINGLE"|"MULTI", fields: [{name, text}] }
                 { kind: "ACTION", verb: "CLICK"|"FILL",    text: "..." }
               Unused fields for the chosen kind should be null/omitted.

            Examples (illustrative — do not copy):
              SINGLE:
                { kind: "DATA", type: "SINGLE",
                  fields: [{ name: "price", text: "1 803,58 kr" }] }
              MULTI:
                { kind: "DATA", type: "MULTI",
                  fields: [
                    { name: "teamHome",  text: "Liverpool" },
                    { name: "teamAway",  text: "Chelsea" },
                    { name: "scoreHome", text: "2" },
                    { name: "scoreAway", text: "1" }
                  ] }
              ACTION:
                { kind: "ACTION", verb: "CLICK", text: "Logga in" }
        """.trimIndent()

        val raw = chatClient.prompt()
            .options(deterministicOptions)
            .user { spec ->
                spec.text(instructions)
                    .media(MimeTypeUtils.IMAGE_PNG, ByteArrayResource(screenshot))
            }
            .call()
            .entity(RawAnalysis::class.java)
            ?: error("empty response from chat model")

        val target = raw.target?.toTarget()
        val analysis = PageAnalysis(
            verdict = raw.verdict,
            reasoning = raw.reasoning.trim(),
            target = target,
        )
        log.debug(
            "analyzer returned verdict={} targetKind={} targetType={} fieldCount={}",
            analysis.verdict,
            target?.let { it::class.simpleName },
            (target as? Target.Data)?.type,
            (target as? Target.Data)?.fields?.size ?: 0,
        )
        return analysis
    }

    private fun RawTarget.toTarget(): Target? {
        return when (kind) {
            TargetKind.DATA -> {
                val t = type ?: return null
                val cleaned = fields.orEmpty()
                    .asSequence()
                    .map { TargetField(it.name.trim(), it.text.trim()) }
                    .filter { it.name.isNotEmpty() && it.text.isNotEmpty() }
                    .take(MAX_FIELDS)
                    .toList()
                if (cleaned.isEmpty()) null else Target.Data(type = t, fields = cleaned)
            }
            TargetKind.ACTION -> {
                val v = verb ?: return null
                val t = text?.trim().orEmpty()
                if (t.isEmpty()) null else Target.Action(verb = v, text = t)
            }
        }
    }

    private enum class TargetKind { DATA, ACTION }

    private data class RawAnalysis(
        @JsonProperty("verdict") val verdict: ValidationVerdict,
        @JsonProperty("reasoning") val reasoning: String,
        @JsonProperty("target") val target: RawTarget?,
    )

    private data class RawTarget(
        @JsonProperty("kind") val kind: TargetKind,
        @JsonProperty("type") val type: TargetType?,
        @JsonProperty("fields") val fields: List<RawField>?,
        @JsonProperty("verb") val verb: ActionType?,
        @JsonProperty("text") val text: String?,
    )

    private data class RawField(
        @JsonProperty("name") val name: String,
        @JsonProperty("text") val text: String,
    )

    private companion object {
        const val MAX_FIELDS = 8
    }
}
