package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.ClickTargetFinder
import com.noelnp.agenticcrawl.analysis.ExtractedStructure
import com.noelnp.agenticcrawl.analysis.PageAnalysis
import com.noelnp.agenticcrawl.analysis.PageAnalyzer
import com.noelnp.agenticcrawl.analysis.Planner
import com.noelnp.agenticcrawl.analysis.Target
import com.noelnp.agenticcrawl.analysis.ValidationVerdict
import com.noelnp.agenticcrawl.browser.LiveSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class Orchestrator(
    private val jobService: JobService,
    private val planner: Planner,
    private val pageAnalyzer: PageAnalyzer,
    private val clickTargetFinder: ClickTargetFinder,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runPlannerLoop(jobId: UUID, session: LiveSession) {
        try {
            log.info("planner loop start (max steps={})", MAX_PLAN_STEPS)
            for (iteration in 0 until MAX_PLAN_STEPS) {
                val job = jobService.get(jobId)
                val description = job.description
                val layers = job.layers.toList()
                val available = availableActions(job)

                val decision = planner.decide(description, layers, available)
                if (decision == null) {
                    jobService.recordPlanStep(
                        jobId,
                        action = PlanAction.FINISH,
                        reasoning = "planner LLM call failed",
                        outcome = PlanOutcome.FAILED,
                    )
                    jobService.markFailed(jobId, "planner returned no decision")
                    return
                }
                log.info(
                    "planner iteration {} -> action={} reasoning='{}'",
                    iteration + 1, decision.action, decision.reasoning,
                )

                when (decision.action) {
                    PlanAction.FINISH -> {
                        jobService.recordPlanStep(
                            jobId,
                            action = decision.action,
                            reasoning = decision.reasoning,
                            outcome = PlanOutcome.SUCCESS,
                            actionDataJson = jsonOrNull(mapOf<String, Any?>()),
                        )
                        jobService.markGoalSatisfied(jobId)
                        return
                    }
                    PlanAction.NAVIGATE_VIA_DETAIL_LINK -> {
                        val outcome = navigateViaDetailLink(jobId, session, job, decision.reasoning)
                        if (outcome == PlanOutcome.FAILED) return
                    }
                    PlanAction.CLICK_TO_REVEAL -> {
                        val outcome = clickToReveal(jobId, session, job, decision.reasoning)
                        if (outcome == PlanOutcome.FAILED) return
                    }
                }
            }
            jobService.markFailed(
                jobId,
                "planner exceeded MAX_PLAN_STEPS=$MAX_PLAN_STEPS without picking FINISH",
            )
        } catch (e: Exception) {
            log.error("planner loop crashed: {}", e.message, e)
            jobService.markFailed(jobId, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { session.close() }
        }
    }

    private fun navigateViaDetailLink(
        jobId: UUID,
        session: LiveSession,
        job: Job,
        reasoning: String,
    ): PlanOutcome {
        val listing = job.layers.firstOrNull { it.layerKind == ReconLayerKind.LISTING }
        val structure = listing?.extractedStructureJson?.let { parseStructure(it) }
        val detailLink = structure?.detailLink
        if (detailLink == null) {
            return failStep(
                jobId,
                action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
                reasoning = reasoning,
                detail = "listing layer has no detailLink to navigate",
                jobFailMessage = "planner picked NAVIGATE but no detailLink on listing",
            )
        }

        val href = session.resolveDetailLinkHref(detailLink)
        if (href.isNullOrBlank()) {
            return failStep(
                jobId,
                action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
                reasoning = reasoning,
                detail = "could not resolve a concrete href for detailLink '${detailLink.selector}'",
                jobFailMessage = "detailLink href unresolved",
            )
        }

        log.info("orchestrator navigating to {}", href)
        val ok = session.navigateTo(href)
        if (!ok) {
            return failStep(
                jobId,
                action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
                reasoning = reasoning,
                detail = "navigateTo($href) failed",
                jobFailMessage = "navigation failed: $href",
                actionData = mapOf(
                    "detailLinkSelector" to detailLink.selector,
                    "detailLinkNth" to detailLink.nth,
                    "resolvedHref" to href,
                ),
            )
        }

        val capture = session.capture()
        val analysis = pageAnalyzer.verifyRequest(job.description, capture.screenshot)
        log.info(
            "follow-up analysis verdict={} fieldCount={}",
            analysis.verdict, analysis.target?.fields?.size ?: 0,
        )

        val mapping = mapIfPresent(session, capture.visibleText, analysis)
        val landedAt = session.currentUrl()
        val newIndex = jobService.appendLayer(
            id = jobId,
            atUrl = landedAt,
            layerKind = ReconLayerKind.FOLLOWED_DETAIL_LINK,
            screenshot = capture.screenshot,
            analysis = analysis,
            containerHtml = mapping?.containerHtml,
            structureJson = mapping?.structureJson,
        )

        jobService.recordPlanStep(
            jobId,
            action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
            reasoning = reasoning,
            outcome = PlanOutcome.SUCCESS,
            detail = layerDetail(newIndex, landedAt, analysis, mapping?.structure),
            actionDataJson = jsonOrNull(
                mapOf(
                    "detailLinkSelector" to detailLink.selector,
                    "detailLinkNth" to detailLink.nth,
                    "resolvedHref" to href,
                    "landedAtUrl" to landedAt,
                ),
            ),
        )
        return PlanOutcome.SUCCESS
    }

    private fun clickToReveal(
        jobId: UUID,
        session: LiveSession,
        job: Job,
        reasoning: String,
    ): PlanOutcome {
        // Use the most recent non-LISTING layer's screenshot as the current page context.
        val currentLayer = job.layers.lastOrNull { it.layerKind != ReconLayerKind.LISTING }
        val screenshot = currentLayer?.screenshot
        if (screenshot == null) {
            return failStep(
                jobId,
                action = PlanAction.CLICK_TO_REVEAL,
                reasoning = reasoning,
                detail = "no recent non-listing layer screenshot available for click-target lookup",
                jobFailMessage = "click without prior follow-up layer",
            )
        }

        val candidates = session.collectClickableCandidates()
        if (candidates.isBlank()) {
            return failStep(
                jobId,
                action = PlanAction.CLICK_TO_REVEAL,
                reasoning = reasoning,
                detail = "no visible clickable elements found on the current page",
                jobFailMessage = "click without any visible candidates",
            )
        }

        val target = clickTargetFinder.find(
            intent = reasoning,
            userDescription = job.description,
            screenshot = screenshot,
            candidates = candidates,
        )
        if (target == null) {
            return failStep(
                jobId,
                action = PlanAction.CLICK_TO_REVEAL,
                reasoning = reasoning,
                detail = "ClickTargetFinder could not identify an element matching the intent",
                jobFailMessage = "no clickable element identified for intent",
            )
        }
        log.info(
            "orchestrator clicking selector='{}' text={} nth={}",
            target.selector, target.text, target.nth,
        )

        val click = session.clickElement(target)
        if (!click.clicked) {
            return failStep(
                jobId,
                action = PlanAction.CLICK_TO_REVEAL,
                reasoning = reasoning,
                detail = "clickElement(selector='${target.selector}', text=${target.text}, nth=${target.nth}) did not click an element",
                jobFailMessage = "click failed for selector '${target.selector}'",
                actionData = mapOf(
                    "selector" to target.selector,
                    "text" to target.text,
                    "nth" to target.nth,
                    "previousUrl" to click.previousUrl,
                    "currentUrl" to click.currentUrl,
                    "urlChanged" to false,
                ),
            )
        }
        if (!click.urlChanged) {
            log.info(
                "click selector='{}' succeeded with no URL change — capturing anyway, the reveal may be inline",
                target.selector,
            )
        }

        val capture = session.capture()
        val analysis = pageAnalyzer.verifyRequest(job.description, capture.screenshot)
        log.info(
            "post-click analysis verdict={} fieldCount={}",
            analysis.verdict, analysis.target?.fields?.size ?: 0,
        )

        val mapping = mapIfPresent(session, capture.visibleText, analysis)
        val landedAt = session.currentUrl()
        val newIndex = jobService.appendLayer(
            id = jobId,
            atUrl = landedAt,
            layerKind = ReconLayerKind.REVEALED_BY_CLICK,
            screenshot = capture.screenshot,
            analysis = analysis,
            containerHtml = mapping?.containerHtml,
            structureJson = mapping?.structureJson,
        )

        jobService.recordPlanStep(
            jobId,
            action = PlanAction.CLICK_TO_REVEAL,
            reasoning = reasoning,
            outcome = PlanOutcome.SUCCESS,
            detail = "clicked selector='${target.selector}' → " +
                layerDetail(newIndex, landedAt, analysis, mapping?.structure) +
                " urlChanged=${click.urlChanged}",
            actionDataJson = jsonOrNull(
                mapOf(
                    "selector" to target.selector,
                    "text" to target.text,
                    "nth" to target.nth,
                    "previousUrl" to click.previousUrl,
                    "currentUrl" to landedAt,
                    "urlChanged" to click.urlChanged,
                ),
            ),
        )
        return PlanOutcome.SUCCESS
    }

    private fun mapIfPresent(
        session: LiveSession,
        visibleText: String,
        analysis: PageAnalysis,
    ): JobService.MappingResult? {
        if (analysis.verdict != ValidationVerdict.PRESENT) return null
        val raw = analysis.target ?: return null

        // Defence against verifier hallucinations: if a field's value isn't
        // literally on the page (e.g. an aggregated "A — B" string the LLM
        // synthesised from the rendered layout), no selector can return it.
        // Drop those fields before mapping; if too few survive, skip mapping.
        val grounded = jobService.groundFields(raw.fields, visibleText)
        val dropped = raw.fields.size - grounded.size
        if (dropped > 0) {
            log.warn(
                "dropping {} of {} verifier fields with no literal match on page: {}",
                dropped, raw.fields.size,
                raw.fields.filterNot { grounded.contains(it) }.joinToString { "${it.name}='${it.text.take(40)}'" },
            )
        }
        if (grounded.size < MIN_GROUNDED_FIELDS) {
            log.warn(
                "only {} grounded field(s) remain after dropping ungrounded values — skipping structure mapping for this layer",
                grounded.size,
            )
            return null
        }

        val groundedTarget = Target(type = raw.type, fields = grounded)
        return runCatching {
            jobService.mapTargetToStructure(session, groundedTarget, includeDetailLink = false)
        }.onFailure { log.warn("follow-up structure mapping failed: {}", it.message) }.getOrNull()
    }

    private fun layerDetail(
        layerIndex: Int,
        landedAt: String,
        analysis: PageAnalysis,
        structure: ExtractedStructure?,
    ): String {
        val structurePart = when {
            structure != null -> ", rowSelector='${structure.rowSelector}', fields=${structure.fields.size}"
            analysis.verdict == ValidationVerdict.PRESENT -> ", no selectors mapped"
            else -> ""
        }
        return "layer $layerIndex at $landedAt (verdict=${analysis.verdict}$structurePart)"
    }

    private fun failStep(
        jobId: UUID,
        action: PlanAction,
        reasoning: String,
        detail: String,
        jobFailMessage: String,
        actionData: Map<String, Any?>? = null,
    ): PlanOutcome {
        jobService.recordPlanStep(
            jobId,
            action = action,
            reasoning = reasoning,
            outcome = PlanOutcome.FAILED,
            detail = detail,
            actionDataJson = actionData?.let { jsonOrNull(it) },
        )
        jobService.markFailed(jobId, jobFailMessage)
        return PlanOutcome.FAILED
    }

    private fun availableActions(job: Job): Set<PlanAction> {
        val actions = mutableSetOf(PlanAction.FINISH)
        val listing = job.layers.firstOrNull { it.layerKind == ReconLayerKind.LISTING }
        val hasDetailLink = listing?.extractedStructureJson?.let { hasDetailLink(it) } == true
        val alreadyFollowed = job.layers.any { it.layerKind == ReconLayerKind.FOLLOWED_DETAIL_LINK }
        val hasNonListingLayer = job.layers.any { it.layerKind != ReconLayerKind.LISTING }
        if (hasDetailLink && !alreadyFollowed) {
            actions += PlanAction.NAVIGATE_VIA_DETAIL_LINK
        }
        if (hasNonListingLayer) {
            actions += PlanAction.CLICK_TO_REVEAL
        }
        return actions
    }

    private fun parseStructure(structureJson: String): ExtractedStructure? =
        runCatching { objectMapper.readValue(structureJson, ExtractedStructure::class.java) }
            .getOrNull()

    private fun hasDetailLink(structureJson: String): Boolean =
        parseStructure(structureJson)?.detailLink != null

    private fun jsonOrNull(payload: Map<String, Any?>): String? =
        runCatching { objectMapper.writeValueAsString(payload) }.getOrNull()

    private companion object {
        const val MAX_PLAN_STEPS = 4

        // A row needs at least this many literally-grounded fields for selector
        // mapping to have any chance of locking onto a meaningful container.
        // Below this we'd be feeding noise into findRowContainerHtml.
        const val MIN_GROUNDED_FIELDS = 2
    }
}
