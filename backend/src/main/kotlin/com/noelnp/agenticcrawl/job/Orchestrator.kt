package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.ClickTargetFinder
import com.noelnp.agenticcrawl.analysis.ExtractedStructure
import com.noelnp.agenticcrawl.analysis.PageAnalyzer
import com.noelnp.agenticcrawl.analysis.Planner
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
        val analysis = pageAnalyzer.analyze(job.description, capture.screenshot)
        log.info(
            "follow-up analysis verdict={} fieldCount={}",
            analysis.verdict, analysis.target?.fields?.size ?: 0,
        )

        val landedAt = session.currentUrl()
        val newIndex = jobService.appendLayer(
            id = jobId,
            atUrl = landedAt,
            layerKind = ReconLayerKind.FOLLOWED_DETAIL_LINK,
            screenshot = capture.screenshot,
            analysis = analysis,
        )

        jobService.recordPlanStep(
            jobId,
            action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
            reasoning = reasoning,
            outcome = PlanOutcome.SUCCESS,
            detail = "layer $newIndex captured at $landedAt (verdict=${analysis.verdict})",
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

        val target = clickTargetFinder.find(
            intent = reasoning,
            userDescription = job.description,
            screenshot = screenshot,
        )
        if (target == null) {
            return failStep(
                jobId,
                action = PlanAction.CLICK_TO_REVEAL,
                reasoning = reasoning,
                detail = "ClickTargetFinder could not identify a label to click",
                jobFailMessage = "no clickable element identified for intent",
            )
        }
        log.info("orchestrator clicking label '{}'", target.label)

        val click = session.clickByText(target.label)
        if (!click.clicked) {
            return failStep(
                jobId,
                action = PlanAction.CLICK_TO_REVEAL,
                reasoning = reasoning,
                detail = "clickByText('${target.label}') did not click an element",
                jobFailMessage = "click failed for label '${target.label}'",
                actionData = mapOf(
                    "label" to target.label,
                    "previousUrl" to click.previousUrl,
                    "currentUrl" to click.currentUrl,
                    "urlChanged" to false,
                ),
            )
        }
        if (!click.urlChanged) {
            log.info(
                "click '{}' succeeded with no URL change — capturing anyway, the reveal may be inline (accordion / DOM swap)",
                target.label,
            )
        }

        val capture = session.capture()
        val analysis = pageAnalyzer.analyze(job.description, capture.screenshot)
        log.info(
            "post-click analysis verdict={} fieldCount={}",
            analysis.verdict, analysis.target?.fields?.size ?: 0,
        )

        val landedAt = session.currentUrl()
        val newIndex = jobService.appendLayer(
            id = jobId,
            atUrl = landedAt,
            layerKind = ReconLayerKind.REVEALED_BY_CLICK,
            screenshot = capture.screenshot,
            analysis = analysis,
        )

        jobService.recordPlanStep(
            jobId,
            action = PlanAction.CLICK_TO_REVEAL,
            reasoning = reasoning,
            outcome = PlanOutcome.SUCCESS,
            detail = "clicked '${target.label}' → layer $newIndex at $landedAt (verdict=${analysis.verdict}, urlChanged=${click.urlChanged})",
            actionDataJson = jsonOrNull(
                mapOf(
                    "label" to target.label,
                    "previousUrl" to click.previousUrl,
                    "currentUrl" to landedAt,
                    "urlChanged" to click.urlChanged,
                ),
            ),
        )
        return PlanOutcome.SUCCESS
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
    }
}
