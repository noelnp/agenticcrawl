package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.job.PlanAction
import com.noelnp.agenticcrawl.job.ReconLayer
import com.noelnp.agenticcrawl.job.ReconLayerKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class Planner(
    private val llm: LlmClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun decide(
        userDescription: String,
        layers: List<ReconLayer>,
        availableActions: Set<PlanAction>,
    ): PlanDecision? {
        require(availableActions.isNotEmpty()) { "planner needs at least one available action" }

        val layerSummary = summarizeLayers(layers)
        val actionList = availableActions.joinToString("\n") { describeAction(it) }
        val schemaUnion = availableActions.joinToString(" | ") { "\"${it.name}\"" }

        val instructions = """
            You are the planner of a small agent that recons web pages to discover
            scrapable structure. The runtime scraper is plain Playwright, not LLM
            driven — your only job is to decide what additional reconnaissance is
            needed (if any) before handing off to script generation.

            USER REQUEST:
            $userDescription

            RECON LAYERS SO FAR:
            $layerSummary

            AVAILABLE ACTIONS:
            $actionList

            Decision rules:
              - FINISH when every distinct kind of information the user asked for is
                visible (verdict PRESENT or PARTIAL with fields) on some recon'd
                layer. The same selectors apply to every row at runtime, so one
                example per kind is enough.
              - NAVIGATE_VIA_DETAIL_LINK only when (a) the listing layer has a
                detailLink AVAILABLE, AND (b) the user asked for information that
                is not visible on any layer so far. The most common case: user
                asked for per-item detail (stats, full description, specs) and the
                listing only shows summary info.
              - CLICK_TO_REVEAL when (a) we are already on a detail-style page
                (a non-LISTING layer exists), AND (b) the user's missing info
                looks like it lives behind a tab or button on that page (e.g.
                stats, lineups, reviews, specifications). Picking this hands off
                to a separate finder that identifies the exact label to click —
                you do NOT pick the label yourself.
              - If neither action would reach the missing info, pick FINISH and
                note the gap in reasoning.

            Return JSON only:
              { "action": $schemaUnion,
                "reasoning": "one short sentence grounded in the layers above" }
        """.trimIndent()

        return runCatching {
            llm.json(instructions, RawDecision::class.java)?.let { raw ->
                if (raw.action !in availableActions) {
                    log.warn("planner returned out-of-set action {}", raw.action)
                    null
                } else {
                    PlanDecision(raw.action, raw.reasoning.trim())
                }
            }
        }.onFailure { log.warn("planner LLM call failed: {}", it.message) }.getOrNull()
    }

    private fun summarizeLayers(layers: List<ReconLayer>): String {
        if (layers.isEmpty()) return "(none — recon has not started)"
        return layers.joinToString("\n\n") { describeLayer(it) }
    }

    private fun describeLayer(layer: ReconLayer): String {
        val target = parseTarget(layer.targetJson)
        val hasDetailLink = parseHasDetailLink(layer.extractedStructureJson)

        val verdictLine = layer.validationVerdict?.name ?: "UNKNOWN"
        val reasoning = layer.validationReasoning?.trim().orEmpty()

        val fieldLine = target?.fields?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { "${it.name}=${quote(it.text)}" }
            ?: "(no fields)"

        val detailLine = when (layer.layerKind) {
            ReconLayerKind.LISTING -> when {
                hasDetailLink == true -> "  detailLink: AVAILABLE"
                hasDetailLink == false -> "  detailLink: not found on this layer"
                else -> "  detailLink: not extracted yet"
            }
            else -> null
        }

        return buildString {
            append("Layer ${layer.layerIndex} [${layer.layerKind}] @ ${layer.atUrl}\n")
            append("  verdict: $verdictLine\n")
            if (reasoning.isNotEmpty()) append("  reasoning: $reasoning\n")
            append("  fields: $fieldLine")
            if (detailLine != null) append("\n").append(detailLine)
        }
    }

    private fun parseTarget(targetJson: String?): Target? {
        if (targetJson.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue(targetJson, Target::class.java) }
            .onFailure { log.warn("planner could not parse target JSON: {}", it.message) }
            .getOrNull()
    }

    private fun parseHasDetailLink(structureJson: String?): Boolean? {
        if (structureJson.isNullOrBlank()) return null
        return runCatching {
            objectMapper.readValue(structureJson, ExtractedStructure::class.java).detailLink != null
        }.getOrNull()
    }

    private fun describeAction(action: PlanAction): String = when (action) {
        PlanAction.NAVIGATE_VIA_DETAIL_LINK ->
            "- NAVIGATE_VIA_DETAIL_LINK: follow the listing's detailLink and recon one example detail page."
        PlanAction.CLICK_TO_REVEAL ->
            "- CLICK_TO_REVEAL: click a visible UI control (tab, button, link) on the current page to reveal content that isn't shown yet. Pick this when the missing info looks like it lives behind a tab on the current page (e.g. a 'Stats' or 'Lineups' tab)."
        PlanAction.FINISH ->
            "- FINISH: stop reconnaissance; existing layers cover the user's request."
    }

    private fun quote(s: String): String = "\"${s.replace("\"", "\\\"").take(80)}\""

    private data class RawDecision(
        @JsonProperty("action") val action: PlanAction,
        @JsonProperty("reasoning") val reasoning: String,
    )
}
