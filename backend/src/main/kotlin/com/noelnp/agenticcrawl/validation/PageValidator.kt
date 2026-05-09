package com.noelnp.agenticcrawl.validation

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service
import org.springframework.util.MimeTypeUtils

@Service
class PageValidator(chatClientBuilder: ChatClient.Builder) {

    private val chatClient = chatClientBuilder.build()
    private val log = LoggerFactory.getLogger(javaClass)

    fun validate(description: String, screenshot: ByteArray): ValidationOutcome {
        log.debug("calling validator descriptionLen={} bytes={}", description.length, screenshot.size)
        val instructions = """
            You are validating whether a web page contains the content a user is asking about.

            User request:
            ---
            $description
            ---

            Examine the screenshot of the page and decide one verdict:
              - PRESENT: the requested content is clearly visible on this page.
              - PARTIAL: the page is related (same site, same topic) but does not clearly
                         show the requested content yet — e.g. it is a landing page that
                         likely needs navigation, or filters need to be applied.
              - ABSENT:  the page does not contain the requested content.

            Respond with the verdict and a single short sentence of reasoning grounded
            in what is actually visible on the page (not assumptions about the site).
        """.trimIndent()

        val raw = chatClient.prompt()
            .user { spec ->
                spec.text(instructions)
                    .media(MimeTypeUtils.IMAGE_PNG, ByteArrayResource(screenshot))
            }
            .call()
            .entity(RawValidation::class.java)
            ?: error("empty response from chat model")

        val outcome = ValidationOutcome(verdict = raw.verdict, reasoning = raw.reasoning.trim())
        log.debug("validator returned verdict={}", outcome.verdict)
        return outcome
    }

    private data class RawValidation(
        @JsonProperty("verdict") val verdict: ValidationVerdict,
        @JsonProperty("reasoning") val reasoning: String,
    )
}

data class ValidationOutcome(
    val verdict: ValidationVerdict,
    val reasoning: String,
)
