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
            content they want to extract from this page.

            User request:
            ---
            $description
            ---

            Decide one of three verdicts:
              - PRESENT: the requested content is clearly visible on this page.
              - PARTIAL: the page is related (same site, same topic) but does not
                         clearly show the requested content yet — e.g. it is a
                         landing page that needs navigation, or filters need to
                         be applied.
              - ABSENT:  the page does not contain the requested content.

            If verdict is PRESENT or PARTIAL, also produce ONE concrete example by
            extracting values from a single specific instance you can see on the page.

            CRITICAL RULES — read carefully:
            - Only return text/values you can clearly read in the screenshot.
            - Do not invent, guess, or infer text based on what you would *expect*
              a page of this type to contain. Read the actual pixels.
            - Pick ONE specific visible instance and extract its values. Do not
              aggregate, summarise, or generalise across instances.
            - Copy values exactly as displayed (capitalisation, accents, special
              characters, punctuation, whitespace).
            - If you cannot clearly read a field in the chosen instance, omit
              that field rather than guessing.

            Return:

            1. verdict: PRESENT | PARTIAL | ABSENT.

            2. reasoning: one short sentence grounded in what is visible on the page.

            3. example: only when verdict is PRESENT or PARTIAL; null otherwise.
                 - containerType: short label for the kind of structure (e.g.
                   "job listing card", "football match row", "product listing
                   tile", "news article preview").
                 - fields: list of {name, value} pairs read from one specific
                   visible instance. Use lower-case names. Provide between
                   3 and 8 fields. List the most important fields first.

            Examples of valid example output (illustrative — do not copy):
              {
                containerType: "football match row",
                fields: [
                  {name: "home team",   value: "Liverpool"},
                  {name: "away team",   value: "Chelsea"},
                  {name: "home score",  value: "2"},
                  {name: "away score",  value: "1"},
                  {name: "kickoff",     value: "67'"}
                ]
              }
              {
                containerType: "job listing card",
                fields: [
                  {name: "title",      value: "SOS-sjuksköterska"},
                  {name: "company",    value: "SOS Alarm Sverige AB"},
                  {name: "location",   value: "Stockholm"},
                  {name: "deadline",   value: "5/5 - 31/5"}
                ]
              }
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

        val example = raw.example?.toExample()
        val analysis = PageAnalysis(
            verdict = raw.verdict,
            reasoning = raw.reasoning.trim(),
            example = example,
        )
        log.debug(
            "analyzer returned verdict={} containerType={} fieldCount={}",
            analysis.verdict,
            example?.containerType,
            example?.fields?.size ?: 0,
        )
        return analysis
    }

    private fun RawExample.toExample(): ExtractionExample {
        val cleaned: List<Pair<String, String>> = fields
            .asSequence()
            .map { it.name.trim().lowercase() to it.value.trim() }
            .filter { (name, value) -> name.isNotEmpty() && value.isNotEmpty() }
            .take(MAX_FIELDS)
            .toList()
        return ExtractionExample(
            containerType = containerType.trim(),
            fields = LinkedHashMap<String, String>().also { map ->
                cleaned.forEach { (name, value) -> map.putIfAbsent(name, value) }
            },
        )
    }

    private data class RawAnalysis(
        @JsonProperty("verdict") val verdict: ValidationVerdict,
        @JsonProperty("reasoning") val reasoning: String,
        @JsonProperty("example") val example: RawExample?,
    )

    private data class RawExample(
        @JsonProperty("containerType") val containerType: String,
        @JsonProperty("fields") val fields: List<RawField>,
    )

    private data class RawField(
        @JsonProperty("name") val name: String,
        @JsonProperty("value") val value: String,
    )

    private companion object {
        const val MAX_FIELDS = 8
    }
}
