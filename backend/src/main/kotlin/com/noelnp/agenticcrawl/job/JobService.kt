package com.noelnp.agenticcrawl.job

import com.noelnp.agenticcrawl.browser.BrowserService
import com.noelnp.agenticcrawl.validation.PageValidator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ExecutorService

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val browserService: BrowserService,
    private val pageValidator: PageValidator,
    private val jobExecutor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(description: String, url: String): Job {
        val saved = jobRepository.save(Job(description = description, url = url))
        val id = saved.id ?: error("job id was null after save")
        log.info("created job url={} descriptionPreview='{}'", url, description.take(80))
        jobExecutor.submit { runSafely(id) }
        return saved
    }

    fun get(id: UUID): Job =
        jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }

    private fun runSafely(id: UUID) {
        MDC.put("jobId", id.toString())
        try {
            run(id)
        } catch (e: Exception) {
            log.error("pipeline failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            MDC.remove("jobId")
        }
    }

    private fun run(id: UUID) {
        val job = update(id) {
            it.status = JobStatus.RUNNING
        }
        log.info("status -> RUNNING url={}", job.url)

        log.debug("starting capture")
        val screenshot = browserService.captureScreenshot(job.url)
        update(id) { it.screenshot = screenshot }
        log.info("capture complete bytes={}", screenshot.size)

        log.debug("starting validation")
        val outcome = pageValidator.validate(job.description, screenshot)
        update(id) {
            it.validationVerdict = outcome.verdict
            it.validationReasoning = outcome.reasoning
            it.status = JobStatus.SUCCEEDED
        }
        log.info("status -> SUCCEEDED verdict={} reasoning='{}'", outcome.verdict, outcome.reasoning)
    }

    private fun markFailed(id: UUID, message: String) {
        runCatching {
            update(id) {
                it.status = JobStatus.FAILED
                it.errorMessage = message
            }
            log.warn("status -> FAILED message='{}'", message)
        }.onFailure { log.error("could not mark job failed", it) }
    }

    private fun update(id: UUID, mutator: (Job) -> Unit): Job {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
        mutator(job)
        return jobRepository.save(job)
    }
}

class JobNotFoundException(id: UUID) : RuntimeException("Job not found: $id")

class ScreenshotNotAvailableException(id: UUID) : RuntimeException("Screenshot not yet available for job $id")
