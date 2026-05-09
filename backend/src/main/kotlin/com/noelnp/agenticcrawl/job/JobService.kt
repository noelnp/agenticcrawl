package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.ExtractionExample
import com.noelnp.agenticcrawl.analysis.PageAnalyzer
import com.noelnp.agenticcrawl.analysis.ValidationVerdict
import com.noelnp.agenticcrawl.browser.BrowserService
import com.noelnp.agenticcrawl.browser.PageCapture
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ExecutorService

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val browserService: BrowserService,
    private val pageAnalyzer: PageAnalyzer,
    private val jobExecutor: ExecutorService,
    private val objectMapper: ObjectMapper,
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
        val capture = browserService.capture(job.url)
        update(id) { it.screenshot = capture.screenshot }
        log.info("capture complete bytes={} visibleTextChars={}", capture.screenshot.size, capture.visibleText.length)

        log.debug("starting analysis")
        val analysis = pageAnalyzer.analyze(job.description, capture.screenshot)
        update(id) {
            it.validationVerdict = analysis.verdict
            it.validationReasoning = analysis.reasoning
        }
        log.info("analysis verdict={} reasoning='{}'", analysis.verdict, analysis.reasoning)

        if (analysis.verdict == ValidationVerdict.ABSENT) {
            update(id) { it.status = JobStatus.SUCCEEDED }
            log.info("status -> SUCCEEDED (verdict ABSENT — skipping example)")
            return
        }

        val example = analysis.example
            ?: throw MissingExampleException(analysis.verdict)

        log.info("example containerType='{}'", example.containerType)
        example.fields.forEach { (name, value) ->
            log.info("  field {}='{}'", name, value)
        }

        val groundedness = checkGroundedness(example, capture)
        log.info(
            "groundedness {}/{} matched required={} matched={} unmatched={}",
            groundedness.matched,
            groundedness.total,
            groundedness.required,
            groundedness.matchedFieldNames,
            groundedness.unmatchedFieldNames,
        )
        if (!groundedness.passes) {
            throw HallucinatedExampleException(groundedness.matched, groundedness.total)
        }

        update(id) {
            it.exampleContainerType = example.containerType
            it.exampleFieldsJson = objectMapper.writeValueAsString(example.fields)
            it.status = JobStatus.SUCCEEDED
        }
        log.info("status -> SUCCEEDED")
    }

    private data class GroundednessResult(
        val matched: Int,
        val total: Int,
        val required: Int,
        val matchedFieldNames: List<String>,
        val unmatchedFieldNames: List<String>,
    ) {
        val passes: Boolean get() = matched >= required
    }

    private fun checkGroundedness(example: ExtractionExample, capture: PageCapture): GroundednessResult {
        val haystack = capture.visibleText
        val matched = mutableListOf<String>()
        val unmatched = mutableListOf<String>()
        example.fields.forEach { (name, value) ->
            if (value.isNotBlank() && haystack.contains(value, ignoreCase = true)) matched += name
            else unmatched += name
        }
        val total = example.fields.size
        val required = maxOf(GROUNDEDNESS_MIN_MATCHES, (total + 1) / 2)
        return GroundednessResult(
            matched = matched.size,
            total = total,
            required = required,
            matchedFieldNames = matched,
            unmatchedFieldNames = unmatched,
        )
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

    private companion object {
        const val GROUNDEDNESS_MIN_MATCHES = 2
    }
}

class JobNotFoundException(id: UUID) : RuntimeException("Job not found: $id")

class ScreenshotNotAvailableException(id: UUID) : RuntimeException("Screenshot not yet available for job $id")

class MissingExampleException(verdict: ValidationVerdict) :
    RuntimeException("Analyzer returned verdict $verdict but did not produce an example")

class HallucinatedExampleException(matched: Int, total: Int) :
    RuntimeException(
        "Example appears hallucinated: only $matched of $total field values were found on the page",
    )
