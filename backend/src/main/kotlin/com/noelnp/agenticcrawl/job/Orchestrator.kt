package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
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
                        )
                        jobService.markGoalSatisfied(jobId)
                        return
                    }
                    PlanAction.NAVIGATE_VIA_DETAIL_LINK -> {
                        val outcome = navigateViaDetailLink(jobId, session, job, decision.reasoning)
                        if (outcome == PlanOutcome.FAILED) return
                        // success → continue loop, planner re-evaluates with the new layer
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
        val structureJson = listing?.extractedStructureJson
        val structure = structureJson?.let {
            runCatching { objectMapper.readValue(it, ExtractedStructure::class.java) }.getOrNull()
        }
        val detailLink = structure?.detailLink
        if (detailLink == null) {
            jobService.recordPlanStep(
                jobId,
                action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
                reasoning = reasoning,
                outcome = PlanOutcome.FAILED,
                detail = "listing layer has no detailLink to navigate",
            )
            jobService.markFailed(jobId, "planner picked NAVIGATE but no detailLink on listing")
            return PlanOutcome.FAILED
        }

        val href = session.resolveDetailLinkHref(detailLink)
        if (href.isNullOrBlank()) {
            jobService.recordPlanStep(
                jobId,
                action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
                reasoning = reasoning,
                outcome = PlanOutcome.FAILED,
                detail = "could not resolve a concrete href for detailLink '${detailLink.selector}'",
            )
            jobService.markFailed(jobId, "detailLink href unresolved")
            return PlanOutcome.FAILED
        }

        log.info("orchestrator navigating to {}", href)
        val ok = session.navigateTo(href)
        if (!ok) {
            jobService.recordPlanStep(
                jobId,
                action = PlanAction.NAVIGATE_VIA_DETAIL_LINK,
                reasoning = reasoning,
                outcome = PlanOutcome.FAILED,
                detail = "navigateTo($href) failed",
            )
            jobService.markFailed(jobId, "navigation failed: $href")
            return PlanOutcome.FAILED
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
        )
        return PlanOutcome.SUCCESS
    }

    private fun availableActions(job: Job): Set<PlanAction> {
        val actions = mutableSetOf(PlanAction.FINISH)
        val listing = job.layers.firstOrNull { it.layerKind == ReconLayerKind.LISTING }
        val hasDetailLink = listing?.extractedStructureJson?.let { hasDetailLink(it) } == true
        val alreadyFollowed = job.layers.any { it.layerKind == ReconLayerKind.FOLLOWED_DETAIL_LINK }
        if (hasDetailLink && !alreadyFollowed) {
            actions += PlanAction.NAVIGATE_VIA_DETAIL_LINK
        }
        return actions
    }

    private fun hasDetailLink(structureJson: String): Boolean =
        runCatching {
            objectMapper.readValue(structureJson, ExtractedStructure::class.java).detailLink != null
        }.getOrDefault(false)

    private companion object {
        const val MAX_PLAN_STEPS = 4
    }
}
