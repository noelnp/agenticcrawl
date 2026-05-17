package com.noelnp.agenticcrawl.codegen.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.model.ExtractedStructure
import com.noelnp.agenticcrawl.codegen.model.ExtractionPlan
import com.noelnp.agenticcrawl.codegen.model.ExtractionStep
import com.noelnp.agenticcrawl.codegen.model.OutputSpec
import com.noelnp.agenticcrawl.job.domain.Job
import com.noelnp.agenticcrawl.job.domain.PlanAction
import com.noelnp.agenticcrawl.job.domain.PlanOutcome
import com.noelnp.agenticcrawl.job.domain.PlanStep
import com.noelnp.agenticcrawl.job.domain.ReconLayer
import com.noelnp.agenticcrawl.job.domain.ReconLayerKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Walks a completed [Job]'s recon layers + plan steps and materialises a
 * deterministic [ExtractionPlan]. Pure read-side logic — does not mutate the
 * job.
 *
 * Two output shapes:
 *  - Listing-only: when no follow-up layer carries an [ExtractedStructure],
 *    emits a flat `[Navigate, DismissConsent, WaitForSelector, ExtractRows]`.
 *  - Nested: when at least one follow-up layer has structure, the listing
 *    becomes the outer iterator of a [ExtractionStep.ForEachRow] and the
 *    final non-listing layer's structure becomes the inner extraction. The
 *    trajectory recorded as [PlanStep]s in between is replayed as
 *    `ResolveAndNavigate` / `Click` steps inside `perRowSteps`.
 *
 * Returns null when the listing layer has no structure. Plan steps whose
 * outcome was not SUCCESS are skipped so failed trajectories cannot leak into
 * codegen.
 */
@Service
class PlanAssembler(
    private val objectMapper: ObjectMapper,
    private val limitExtractor: LimitExtractor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun assemble(job: Job): ExtractionPlan? {
        val listing = job.layers.firstOrNull { it.layerKind == ReconLayerKind.LISTING }
            ?: run {
                log.warn("cannot assemble plan: no LISTING layer")
                return null
            }
        val listingStructure = parseStructure(listing.extractedStructureJson)
            ?: run {
                log.warn("cannot assemble plan: listing layer has no structure")
                return null
            }

        val followUpLayers = job.layers
            .filter { it.layerKind != ReconLayerKind.LISTING }
            .filter { parseStructure(it.extractedStructureJson) != null }
            .sortedBy { it.layerIndex }
        val successfulSteps = job.planSteps
            .filter { it.outcome == PlanOutcome.SUCCESS && it.action != PlanAction.FINISH }
            .sortedBy { it.stepIndex }

        val outerLimit = limitExtractor.extract(job.description)

        return if (followUpLayers.isEmpty()) {
            buildListingOnlyPlan(job, listing, listingStructure, outerLimit)
        } else {
            buildNestedPlan(job, listing, listingStructure, followUpLayers, successfulSteps, outerLimit)
        }
    }

    private fun buildListingOnlyPlan(
        job: Job,
        listing: ReconLayer,
        listingStructure: ExtractedStructure,
        outerLimit: Int?,
    ): ExtractionPlan {
        val extractDescription = buildString {
            append("Extract ${describeFields(listingStructure)} from each row.")
            if (outerLimit != null) append(" Stops after $outerLimit row(s).")
        }
        val steps = mutableListOf<ExtractionStep>(
            ExtractionStep.Navigate(
                url = job.url,
                description = "Open the listing page.",
            ),
            ExtractionStep.DismissConsent(),
            ExtractionStep.WaitForSelector(
                selector = listingStructure.rowSelector,
                description = "Wait until listing rows are rendered.",
            ),
            ExtractionStep.ExtractRows(
                rowSelector = listingStructure.rowSelector,
                fields = listingStructure.fields,
                attachAs = ROOT_ITEMS_KEY,
                description = extractDescription,
                limit = outerLimit,
            ),
        )
        return ExtractionPlan(
            targetUrl = job.url,
            userRequest = job.description,
            description = planDescription(
                base = "Scrape the listing on this page; no per-row detail navigation needed.",
                outerLimit = outerLimit,
            ),
            output = OutputSpec(rootKey = ROOT_ITEMS_KEY),
            steps = steps,
        )
    }

    private fun buildNestedPlan(
        job: Job,
        listing: ReconLayer,
        listingStructure: ExtractedStructure,
        followUpLayers: List<ReconLayer>,
        successfulSteps: List<PlanStep>,
        outerLimit: Int?,
    ): ExtractionPlan {
        val innerLayer = followUpLayers.last()
        val innerStructure = parseStructure(innerLayer.extractedStructureJson)
            ?: error("follow-up layer ${innerLayer.layerIndex} lost its structure between filter and use")

        val perRowSteps = mutableListOf<ExtractionStep>()
        for (step in successfulSteps) {
            when (step.action) {
                PlanAction.NAVIGATE_VIA_DETAIL_LINK -> {
                    val detailLink = listingStructure.detailLink
                    if (detailLink == null) {
                        log.warn(
                            "planSteps contain NAVIGATE_VIA_DETAIL_LINK but listing has no detailLink — skipping step",
                        )
                        continue
                    }
                    perRowSteps += ExtractionStep.ResolveAndNavigate(
                        detailLinkSelector = detailLink.selector,
                        nth = detailLink.nth,
                        description = augmentReasoning(
                            base = "Follow the row's detail link to the per-item page.",
                            reasoning = step.reasoning,
                        ),
                    )
                    perRowSteps += ExtractionStep.DismissConsent()
                }
                PlanAction.CLICK_TO_REVEAL -> {
                    val click = parseClickStep(step.actionDataJson)
                    if (click == null) {
                        log.warn(
                            "CLICK_TO_REVEAL step {} missing selector in actionData — skipping",
                            step.stepIndex,
                        )
                        continue
                    }
                    val label = click.text?.let { "the '$it' control" } ?: "the indicated control"
                    perRowSteps += ExtractionStep.Click(
                        selector = click.selector,
                        text = click.text,
                        nth = click.nth,
                        description = augmentReasoning(
                            base = "Click $label to reveal the requested content.",
                            reasoning = step.reasoning,
                        ),
                    )
                }
                PlanAction.FINISH -> { /* filtered above, unreachable */ }
            }
        }

        perRowSteps += ExtractionStep.WaitForSelector(
            selector = innerStructure.rowSelector,
            description = "Wait until the revealed content renders before extracting.",
        )
        perRowSteps += ExtractionStep.ExtractRows(
            rowSelector = innerStructure.rowSelector,
            fields = innerStructure.fields,
            attachAs = NESTED_DETAILS_KEY,
            description = "Extract ${describeFields(innerStructure)} from each nested row.",
        )

        val foreachDescription = buildString {
            append("For each row in the listing: extract its summary fields, then run the per-row trajectory to capture nested details.")
            if (outerLimit != null) append(" Stops after $outerLimit row(s).")
        }
        val foreach = ExtractionStep.ForEachRow(
            rowSelector = listingStructure.rowSelector,
            fields = listingStructure.fields,
            attachAs = ROOT_ITEMS_KEY,
            perRowSteps = perRowSteps,
            description = foreachDescription,
            limit = outerLimit,
        )

        val steps = listOf(
            ExtractionStep.Navigate(
                url = job.url,
                description = "Open the listing page.",
            ),
            ExtractionStep.DismissConsent(),
            ExtractionStep.WaitForSelector(
                selector = listingStructure.rowSelector,
                description = "Wait until listing rows are rendered.",
            ),
            foreach,
        )

        return ExtractionPlan(
            targetUrl = job.url,
            userRequest = job.description,
            description = planDescription(
                base = "Scrape the listing and, for each row, follow the recon-discovered trajectory to capture nested per-item details.",
                outerLimit = outerLimit,
            ),
            output = OutputSpec(rootKey = ROOT_ITEMS_KEY),
            steps = steps,
        )
    }

    private fun planDescription(base: String, outerLimit: Int?): String =
        if (outerLimit != null) "$base Limited to the first $outerLimit item(s)." else base

    private fun parseStructure(json: String?): ExtractedStructure? {
        if (json.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue(json, ExtractedStructure::class.java) }
            .onFailure { log.warn("could not parse layer structure JSON: {}", it.message) }
            .getOrNull()
    }

    private fun parseClickStep(actionDataJson: String?): ClickActionData? {
        if (actionDataJson.isNullOrBlank()) return null
        return runCatching {
            val node: JsonNode = objectMapper.readTree(actionDataJson)
            val selector = node.get("selector")?.asText()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            ClickActionData(
                selector = selector,
                text = node.get("text")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() },
                nth = node.get("nth")?.takeIf { !it.isNull }?.asInt(),
            )
        }.getOrNull()
    }

    private fun describeFields(structure: ExtractedStructure): String =
        structure.fields.joinToString(", ") { it.name }

    private fun augmentReasoning(base: String, reasoning: String?): String {
        val trimmed = reasoning?.trim().orEmpty()
        return if (trimmed.isEmpty()) base else "$base ($trimmed)"
    }

    private data class ClickActionData(
        val selector: String,
        val text: String?,
        val nth: Int?,
    )

    private companion object {
        const val ROOT_ITEMS_KEY = "items"
        const val NESTED_DETAILS_KEY = "details"
    }
}
