package com.noelnp.agenticcrawl.codegen.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.codegen.model.ExtractionPlan
import com.noelnp.agenticcrawl.job.domain.Job
import com.noelnp.agenticcrawl.job.domain.JobStatus
import com.noelnp.agenticcrawl.job.service.JobMutator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ExecutorService

/**
 * Public surface for "run / download the scraper" actions on a finished job.
 *
 * Two responsibilities:
 *  - [ensureRendered] returns the cached `.main.kts` source, rendering and
 *    persisting it on first call. The rendered script is the downloadable
 *    artefact runnable elsewhere with the Kotlin CLI.
 *  - [startRun] invokes the in-process [PlanExecutor] asynchronously and
 *    persists status + result on the job. The Kotlin CLI is not required.
 *
 * Only one in-flight run per job at a time. Attempts to start another while
 * [SCRIPT_STATUS_RUNNING] are rejected with [InvalidScriptStateException]
 * rather than queued — the result column is overwritten on each run, and
 * concurrent runs would clobber the artefact.
 */
@Service
class ScriptRunner(
    private val jobMutator: JobMutator,
    private val renderer: KotlinScriptRenderer,
    private val executor: PlanExecutor,
    private val objectMapper: ObjectMapper,
    private val jobExecutorService: ExecutorService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun ensureRendered(jobId: UUID): String {
        val job = jobMutator.load(jobId)
        val existing = job.generatedScript
        if (!existing.isNullOrBlank()) return existing
        val plan = decodePlan(job)
            ?: throw NoPlanException("Job $jobId has no extraction plan to render. Recon must complete first.")
        val rendered = renderer.render(plan)
        jobMutator.mutate(jobId) { it.generatedScript = rendered }
        log.info("rendered script for job {} ({} chars)", jobId, rendered.length)
        return rendered
    }

    fun startRun(jobId: UUID) {
        val job = jobMutator.load(jobId)
        if (job.status != JobStatus.SUCCEEDED) {
            throw InvalidScriptStateException(
                "Cannot run script: job is in status ${job.status} (need SUCCEEDED).",
            )
        }
        if (job.extractionPlanJson.isNullOrBlank()) {
            throw NoPlanException("Job $jobId has no extraction plan; nothing to run.")
        }
        if (job.scriptStatus == SCRIPT_STATUS_RUNNING) {
            throw InvalidScriptStateException("A run is already in progress for job $jobId.")
        }

        // Cache the rendered source so /download-script serves the equivalent
        // script of the run that just executed.
        ensureRendered(jobId)

        val plan = decodePlan(jobMutator.load(jobId))
            ?: throw NoPlanException("Job $jobId plan could not be decoded after re-load.")

        jobMutator.mutate(jobId) {
            it.scriptStatus = SCRIPT_STATUS_RUNNING
            it.scriptError = null
            it.scriptResultJson = null
        }
        jobExecutorService.submit { withMdc(jobId) { executeAndPersist(jobId, plan) } }
    }

    private fun executeAndPersist(jobId: UUID, plan: ExtractionPlan) {
        try {
            val result = executor.execute(plan)
            val json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)
            jobMutator.mutate(jobId) {
                it.scriptStatus = SCRIPT_STATUS_SUCCEEDED
                it.scriptResultJson = json
                it.scriptError = null
            }
            log.info("script run succeeded job={} resultChars={}", jobId, json.length)
        } catch (e: Exception) {
            log.error("script run failed for job {}: {}", jobId, e.message, e)
            jobMutator.mutate(jobId) {
                it.scriptStatus = SCRIPT_STATUS_FAILED
                it.scriptError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun decodePlan(job: Job): ExtractionPlan? {
        val json = job.extractionPlanJson?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { objectMapper.readValue(json, ExtractionPlan::class.java) }
            .onFailure { log.warn("could not decode extraction plan: {}", it.message) }
            .getOrNull()
    }

    private fun withMdc(jobId: UUID, block: () -> Unit) {
        MDC.put("jobId", jobId.toString())
        try {
            block()
        } finally {
            MDC.remove("jobId")
        }
    }

    companion object {
        const val SCRIPT_STATUS_RUNNING = "RUNNING"
        const val SCRIPT_STATUS_SUCCEEDED = "SUCCEEDED"
        const val SCRIPT_STATUS_FAILED = "FAILED"
    }
}

class NoPlanException(message: String) : RuntimeException(message)
class InvalidScriptStateException(message: String) : RuntimeException(message)
