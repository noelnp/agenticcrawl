package com.noelnp.agenticcrawl.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.noelnp.agenticcrawl.analysis.ExtractionExample
import com.noelnp.agenticcrawl.analysis.PageAnalyzer
import com.noelnp.agenticcrawl.analysis.ValidationVerdict
import com.noelnp.agenticcrawl.browser.BrowserService
import com.noelnp.agenticcrawl.browser.BrowserSessionManager
import com.noelnp.agenticcrawl.browser.LiveSession
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
    private val sessionManager: BrowserSessionManager,
    private val pageAnalyzer: PageAnalyzer,
    private val jobExecutor: ExecutorService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(description: String, url: String): Job {
        val saved = jobRepository.save(Job(description = description, url = url))
        val id = saved.id ?: error("job id was null after save")
        log.info("created job url={} descriptionPreview='{}'", url, description.take(80))
        jobExecutor.submit { withMdc(id) { runAnalysis(id) } }
        return saved
    }

    fun get(id: UUID): Job =
        jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }

    fun confirm(id: UUID): Job {
        val job = get(id)
        if (job.status != JobStatus.AWAITING_CONFIRMATION) {
            throw InvalidJobStateException(id, job.status, JobStatus.AWAITING_CONFIRMATION)
        }
        val session = sessionManager.take(id)
            ?: throw SessionUnavailableException(id)
        val updated = update(id) { it.status = JobStatus.RUNNING }
        log.info("status -> RUNNING (confirmed)")
        jobExecutor.submit { withMdc(id) { runLocator(id, session) } }
        return updated
    }

    private fun withMdc(id: UUID, block: () -> Unit) {
        MDC.put("jobId", id.toString())
        try {
            block()
        } finally {
            MDC.remove("jobId")
        }
    }

    private fun runAnalysis(id: UUID) {
        var session: LiveSession? = null
        try {
            val job = update(id) { it.status = JobStatus.RUNNING }
            log.info("status -> RUNNING url={}", job.url)

            log.debug("opening browser session")
            session = browserService.openSession(job.url)

            log.debug("starting capture")
            val capture = session.capture()
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
                session.close()
                session = null
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
                it.status = JobStatus.AWAITING_CONFIRMATION
            }
            sessionManager.register(id, session, CONFIRMATION_TTL_SECONDS) {
                onSessionExpired(id)
            }
            session = null
            log.info("status -> AWAITING_CONFIRMATION (ttl={}s)", CONFIRMATION_TTL_SECONDS)
        } catch (e: Exception) {
            log.error("analysis failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            session?.let { runCatching { it.close() } }
        }
    }

    private fun runLocator(id: UUID, session: LiveSession) {
        try {
            val fieldsJson = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }
                .exampleFieldsJson
            val groundedValues = parseGroundedValues(fieldsJson)
            log.debug("running locator over {} grounded values", groundedValues.size)

            val containerHtml = session.findContainerHtml(groundedValues)
            if (containerHtml.isNullOrBlank()) {
                log.warn("locator returned no container — saving job without containerHtml")
            } else {
                log.info("locator captured containerHtml chars={}", containerHtml.length)
            }

            update(id) {
                it.containerHtml = containerHtml
                it.status = JobStatus.SUCCEEDED
            }
            log.info("status -> SUCCEEDED")
        } catch (e: Exception) {
            log.error("locator failed: {}", e.message, e)
            markFailed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { session.close() }
        }
    }

    private fun onSessionExpired(id: UUID) {
        withMdc(id) {
            try {
                val job = get(id)
                if (job.status != JobStatus.AWAITING_CONFIRMATION) {
                    log.debug("expiry no-op (status={})", job.status)
                    return@withMdc
                }
                update(id) {
                    it.status = JobStatus.EXPIRED
                    it.errorMessage = "Confirmation timeout"
                }
                log.info("status -> EXPIRED (no confirmation within {}s)", CONFIRMATION_TTL_SECONDS)
            } catch (e: Exception) {
                log.error("failed to mark job EXPIRED", e)
            }
        }
    }

    private fun parseGroundedValues(fieldsJson: String?): List<String> {
        if (fieldsJson.isNullOrBlank()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val map = objectMapper.readValue(fieldsJson, LinkedHashMap::class.java) as Map<String, String>
        return map.values.filter { it.isNotBlank() }.toList()
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
        const val CONFIRMATION_TTL_SECONDS = 180L
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

class InvalidJobStateException(id: UUID, actual: JobStatus, expected: JobStatus) :
    RuntimeException("Job $id is in state $actual but expected $expected")

class SessionUnavailableException(id: UUID) :
    RuntimeException("No live session available for job $id (it may have expired)")
